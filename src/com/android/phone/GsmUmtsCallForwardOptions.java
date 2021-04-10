package com.android.phone;

import android.app.ActionBar;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MenuItem;
import android.app.Dialog;

import android.content.Context;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import java.util.ArrayList;
import com.android.phone.settings.ActivityContainer;
import com.android.phone.R;

public class GsmUmtsCallForwardOptions extends TimeConsumingPreferenceActivity
        implements CallForwardTimeEditPreFragement.Listener {
    private static final String LOG_TAG = "GsmUmtsCallForwardOptions";

    private static final String NUM_PROJECTION[] = {
        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    private static final String BUTTON_CFU_KEY   = "button_cfu_key";
    private static final String BUTTON_CFB_KEY   = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";

    private static final String KEY_TOGGLE = "toggle";
    private static final String KEY_STATUS = "status";
    private static final String KEY_NUMBER = "number";
    private static final String KEY_ENABLE = "enable";
    private static final String KEY_REASON = "reason";
    private static final String KEY_SERVICECLASS = "serviceclass";

    private CallForwardEditPreference mButtonCFU;
    private CallForwardEditPreference mButtonCFB;
    private CallForwardEditPreference mButtonCFNRy;
    private CallForwardEditPreference mButtonCFNRc;

    private final ArrayList<CallForwardEditPreference> mPreferences =
            new ArrayList<CallForwardEditPreference> ();
    private int mInitIndex= 0;

    private boolean mFirstResume;
    private Bundle mIcicle;
    private Phone mPhone;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private boolean mReplaceInvalidCFNumbers;
    private boolean mCallForwardByUssd;

    /* UNISOC: add for feature 1071416 durationforward @{ */
    private static final int ALL_CALL_FORWARD_INDEX = 0;
    private static final String PREF_PREFIX_TIME = "phonecalltimeforward_";
    private static final String BUTTON_CFT_KEY = "button_cft_key";
    private Preference mButtonCFT;
    SharedPreferences mPrefs;
    private boolean mSupportDurationForward;
    TimeConsumingPreferenceListener tcpListener;
    private static final String CFT_STATUS_ACTIVE = "1";
    private boolean mIsFDNOn;
    private ImsManager mImsMgr;
    private Context mContext;
    /* @} */
    private static final String PROGRESS_DIALOG_SHOWING = "progress_dialog_showing";
    //UNISOC: add for bug1013340
    private Dialog mProgressDialog;
    private static final int BUSY_READING_DIALOG = 100;
    private static final int BUSY_SAVING_DIALOG = 200;
    /* UNISOC: add for feature 1072988 */
    private static final int DEFAUT_CONFIG_SERVICE_VALUE = -1;
    private int mConfigServiceClass = DEFAUT_CONFIG_SERVICE_VALUE;
    private boolean mIsSupportAllCfu = true;

    // UNISOC: modify by BUG 916869
    private ActivityContainer mActivityContainer;
    //UNISOC: add for bug1145309
    private boolean mCheckAllCfStatus = false;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.callforward_options);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_forwarding_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        initParent(mPhone);

        if(mPhone == null){
            Log.d(LOG_TAG, "call finish()!");
            finish();
            return;
        }
        PersistableBundle b = null;
        if (mSubscriptionInfoHelper.hasSubId()) {
            b = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    mSubscriptionInfoHelper.getSubId());
        } else {
            b = PhoneGlobals.getInstance().getCarrierConfig();
        }
        if (b != null) {
            mReplaceInvalidCFNumbers = b.getBoolean(
                    CarrierConfigManager.KEY_CALL_FORWARDING_MAP_NON_NUMBER_TO_VOICEMAIL_BOOL);
            mCallForwardByUssd = b.getBoolean(
                    CarrierConfigManager.KEY_USE_CALL_FORWARDING_USSD_BOOL);
            mConfigServiceClass = b.getInt(CarrierConfigManagerEx.KEY_CONFIG_IMS_CALLFORWARD_SERVICECLASS, -1);
            mIsSupportAllCfu =b.getBoolean(
                    CarrierConfigManagerEx.KEY_CONFIG_SUPPORT_QUERY_ALL_CF,true);
            //UNISOC: add for bug1145309
            mCheckAllCfStatus = b.getBoolean(
                    CarrierConfigManagerEx.KEY_CHECK_ALL_CF_AFTER_UPDATE_CF);
        }

        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonCFU = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFU_KEY);
        mButtonCFB = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFB_KEY);
        mButtonCFNRy = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRY_KEY);
        mButtonCFNRc = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRC_KEY);

        mButtonCFU.setParentActivity(this, mButtonCFU.reason);
        mButtonCFB.setParentActivity(this, mButtonCFB.reason);
        mButtonCFNRy.setParentActivity(this, mButtonCFNRy.reason);
        mButtonCFNRc.setParentActivity(this, mButtonCFNRc.reason);

        mPreferences.add(mButtonCFU);
        mPreferences.add(mButtonCFB);
        mPreferences.add(mButtonCFNRy);
        mPreferences.add(mButtonCFNRc);
        //UNISOC: add for bug1145309,1183643
        for (CallForwardEditPreference pref : mPreferences) {
            pref.setCheckAllCfStatus(mCheckAllCfStatus);
            if (icicle != null && icicle.getParcelable(pref.getKey()) != null) {
                Bundle bundle = icicle.getParcelable(pref.getKey());
                pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
            }
        }

        /* UNISOC: add for feature bug1071416 durationforward @{ */
        mContext = this.getApplicationContext();
        mImsMgr = new ImsManager(this.getApplicationContext(), mPhone.getPhoneId());
        mSupportDurationForward = mImsMgr.isVolteEnabledByPlatform()
                && getResources().getBoolean(R.bool.config_show_durationforward);/*@}*/

        mButtonCFT = prefSet.findPreference(BUTTON_CFT_KEY);
        mPrefs = getSharedPreferences(PREF_PREFIX_TIME + mPhone.getSubId(), Context.MODE_PRIVATE);
        if (mSupportDurationForward) {
            CallForwardTimeEditPreFragement.addListener(this);
            tcpListener = this;
        } else {
            if (mButtonCFT != null) {
                prefSet.removePreference(mButtonCFT);
            }
        }/* @} */


        /* UNISOC: add for feature 1072988 @{*/
        if (mConfigServiceClass != DEFAUT_CONFIG_SERVICE_VALUE) {
            for (CallForwardEditPreference pref : mPreferences) {
                pref.setConfigServiceClass(mConfigServiceClass);
            }
        }/*@}*/

        if (mCallForwardByUssd) {
            //the call forwarding ussd command's behavior is similar to the call forwarding when
            //unanswered,so only display the call forwarding when unanswered item.
            prefSet.removePreference(mButtonCFU);
            prefSet.removePreference(mButtonCFB);
            prefSet.removePreference(mButtonCFNRc);
            mPreferences.remove(mButtonCFU);
            mPreferences.remove(mButtonCFB);
            mPreferences.remove(mButtonCFNRc);
            mButtonCFNRy.setDependency(null);
        }

        // we wait to do the initialization until onResume so that the
        // TimeConsumingPreferenceActivity dialog can display as it
        // relies on onResume / onPause to maintain its foreground state.

        mFirstResume = true;
        mIcicle = icicle;

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        /* UNISOC: modify by BUG 916869 @{ */
        mActivityContainer = ActivityContainer.getInstance();
        mActivityContainer.setApplication(getApplication());
        mActivityContainer.addActivity(this, mPhone.getPhoneId());
        /* @} */
        // UNISOC: Add for Bug#1125621
        mContext.registerReceiver(mServiceStateChangeReceiver
                , new IntentFilter(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFirstResume) {
            if (mIcicle == null) {
                Log.d(LOG_TAG, "start to init mConfigServiceClass: "+mConfigServiceClass +" mIsSupportAllCfu: "+mIsSupportAllCfu);
                if (mPhone != null && mPhone.isImsRegistered()
                        && mConfigServiceClass == DEFAUT_CONFIG_SERVICE_VALUE && mIsSupportAllCfu) {
                    //UNISOC: porting for bug1071416
                    mPhone.getCallForwardingOption(CommandsInterface.CF_REASON_ALL,
                            mHandler.obtainMessage(MyHandler.MESSAGE_GET_ALL_CF,
                                    // unused in this case
                                    CommandsInterface.CF_ACTION_DISABLE,
                                    MyHandler.MESSAGE_GET_ALL_CF, null));
                    onStarted(mButtonCFU, true);
                }else {
                    CallForwardEditPreference pref = mPreferences.get(mInitIndex);
                    pref.init(this, mPhone, mReplaceInvalidCFNumbers, mCallForwardByUssd);
                    pref.startCallForwardOptionsQuery();
                }

            } else {
                mInitIndex = mPreferences.size();
                Log.d(LOG_TAG, "start to init onResume: mInitIndex: "+mInitIndex);
                //UNISOC: add for bug1013340
                boolean needQuery = false;
                if(mIcicle.getBoolean(PROGRESS_DIALOG_SHOWING)){
                    removeDialog(BUSY_SAVING_DIALOG);
                    needQuery = true;
                    Log.d(LOG_TAG, "start to init onResume: needQuery: "+needQuery);
                }

                for (CallForwardEditPreference pref : mPreferences) {
                    Bundle bundle = mIcicle.getParcelable(pref.getKey());
                    /** Orig
                     *Unisoc:Add for unisoc 1183643
                     * pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
                     */
                    pref.setEnabled(bundle.getBoolean(KEY_ENABLE));
                    CallForwardInfo cf = new CallForwardInfo();
                    cf.number = bundle.getString(KEY_NUMBER);
                    cf.status = bundle.getInt(KEY_STATUS);
                    cf.reason = bundle.getInt(KEY_REASON);
                    cf.serviceClass = bundle.getInt(KEY_SERVICECLASS);
                    pref.init(this, mPhone, mReplaceInvalidCFNumbers, mCallForwardByUssd);
                    //UNISOC: add for bug1013340
                    if(needQuery){
                        pref.startCallForwardOptionsQuery();
                    }else{
                        pref.restoreCallForwardInfo(cf);
                    }
                }
            }
            mFirstResume = false;
            mIcicle = null;
        }
        // UNISOC: add for feature 1071416 durationforward
        updateCFTSummaryText();
        // UNISOC: add for bug 1083462
        mSubscriptionInfoHelper.addOnSubscriptionsChangedListener();
    }

    /* UNISOC: add for bug 1083462 @{ */
    @Override
    public void onPause() {
        super.onPause();
        mSubscriptionInfoHelper.removeOnSubscriptionsChangedListener();
    }
    /* @} */

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        for (CallForwardEditPreference pref : mPreferences) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_TOGGLE, pref.isToggled());
            bundle.putBoolean(KEY_ENABLE, pref.isEnabled());
            if (pref.callForwardInfo != null) {
                bundle.putString(KEY_NUMBER, pref.callForwardInfo.number);
                bundle.putInt(KEY_STATUS, pref.callForwardInfo.status);
                /* UNISOC: add for FEATURE_ALL_QUERY_CALLFORWARD @{ */
                bundle.putInt(KEY_REASON, pref.callForwardInfo.reason);
                bundle.putInt(KEY_SERVICECLASS, pref.callForwardInfo.serviceClass);
                /* @} */
            }
            outState.putParcelable(pref.getKey(), bundle);
        }
        //UNISOC: add for bug1013340
        outState.putBoolean(PROGRESS_DIALOG_SHOWING,
                mProgressDialog != null && mProgressDialog.isShowing());
    }
    //UNISOC: add for bug1013340
    @Override
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        super.onPrepareDialog(id, dialog, args);
        if (id == BUSY_READING_DIALOG || id == BUSY_SAVING_DIALOG) {
            // For onSaveInstanceState, treat the SAVING dialog as the same as the READING. As
            // the result, if the activity is recreated while waiting for SAVING, it starts reading
            // all the newest data.
            mProgressDialog = dialog;
        }
    }

    /* UNISOC: add for FEATURE_ALL_QUERY_CALLFOR @{ */
    protected void onDestroy() {
        super.onDestroy();
        /* UNISOC: add for feature 1071416 durationforward @{ */
        if (mSupportDurationForward) {
            CallForwardTimeEditPreFragement.removeListener(this);
        }
        /* @} */

        // UNISOC: add for bug1145309
        if (mCheckAllCfStatus && mPhone != null) {
            mPhone.getCallForwardingOption(CommandsInterface.CF_REASON_ALL,
                    null);
        }

        /* UNISOC: modify by BUG 916869 @{ */
        if (mActivityContainer != null) {
            mActivityContainer.removeActivity(this);
        }
        /* @} */
        // UNISOC: Add for Bug#1125621
        if (mServiceStateChangeReceiver != null) {
            mContext.unregisterReceiver(mServiceStateChangeReceiver);
            mServiceStateChangeReceiver = null;
        }
    }
    /* @} */

    @Override
    public void onFinished(Preference preference, boolean reading) {
        Log.d(LOG_TAG, "onFinished, preference=" + preference + ", isFinishing()=" + isFinishing()+ " mInitIndex: "+mInitIndex);
        if (mInitIndex < mPreferences.size() - 1 && !isFinishing()) {
            mInitIndex++;
            CallForwardEditPreference pref = mPreferences.get(mInitIndex);
            pref.init(this, mPhone, mReplaceInvalidCFNumbers, mCallForwardByUssd);
            pref.startCallForwardOptionsQuery();
        } else if (mInitIndex == mPreferences.size() - 1 && !isFinishing()) {
            /* UNISOC: add for feature 1071416 durationforward @{ */
            if (mSupportDurationForward) {
                mInitIndex++;
                mPreferences.get(ALL_CALL_FORWARD_INDEX).initCallTimeForward();
            }
            /* @} */
        }
        super.onFinished(preference, reading);
    }

    /* UNISOC: add for feature 1071416 durationforward @{ */
    @Override
    public void onError(Preference preference, int error) {

        Log.d(LOG_TAG, "onError, preference=" + preference.getKey() + ", error=" + error);
        CallForwardEditPreference pref = null;
        if (preference instanceof CallForwardEditPreference) {
            pref = (CallForwardEditPreference)preference;
            if (pref != null) {
                pref.setEnabled(false);
            }
        }
        Log.d(LOG_TAG, "mInitIndex =" + mInitIndex);
        if (pref != null) {
            super.onError(preference,error);
        }

        /* UNISOC: add for feature bug1071416 durationforward @{ */
        if (error == FDN_CHECK_FAILURE) {
            mIsFDNOn = true;
        } else {
            mIsFDNOn = false;
        }
        if (mInitIndex == mPreferences.size() - 1) {
            refreshCFTButton();
        }
        /* @} */
    }

    @Deprecated
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
                                         Preference preference) {
        if (preference == mButtonCFT) {
            final Intent intent = new Intent();
            intent.setClassName("com.android.phone",
                    "com.android.phone.CallForwardTimeEditPreference");
            intent.putExtra("phone_id", String.valueOf(mPhone.getPhoneId()));
            startActivity(intent);
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }
    /* @} */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(LOG_TAG, "onActivityResult: done");
        if (resultCode != RESULT_OK) {
            Log.d(LOG_TAG, "onActivityResult: contact picker result not OK.");
            return;
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(data.getData(),
                NUM_PROJECTION, null, null, null);
            if ((cursor == null) || (!cursor.moveToFirst())) {
                Log.d(LOG_TAG, "onActivityResult: bad contact data, no results found.");
                return;
            }

            switch (requestCode) {
                case CommandsInterface.CF_REASON_UNCONDITIONAL:
                    mButtonCFU.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_BUSY:
                    mButtonCFB.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NO_REPLY:
                    mButtonCFNRy.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NOT_REACHABLE:
                    mButtonCFNRc.onPickActivityResult(cursor.getString(0));
                    break;
                default:
                    // TODO: may need exception here.
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            GsmUmtsAllCallForwardOptions.goUpToTopLevelSetting(this, mSubscriptionInfoHelper);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*UNISOC: porting for bug1071416 @{*/
    private MyHandler mHandler = new MyHandler();

    private class MyHandler extends Handler {
        static final int MESSAGE_GET_ALL_CF = 0;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_ALL_CF:
                    handleGetAllCFResponse(msg);
                    break;
            }
        }
    };

    private void handleGetAllCFResponse(Message msg) {
        Log.d(LOG_TAG, "handleGetAllCFResponse query all callforward, query cfu.");
        boolean querySuccess = false;
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar != null) {
            if ((ar.exception != null || ar.userObj instanceof Throwable)) {
                Log.d(LOG_TAG, "handleGetAllCFResponse query failed ar: " + ar);
            } else {
                CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
                if (cfInfoArray != null && cfInfoArray.length > 0) {
                    querySuccess = true;

                    mInitIndex = mPreferences.size() - 1;
                    int length = cfInfoArray.length;

                    for (int i = 0; i < length && i < mPreferences.size(); i++) {
                        Log.d(LOG_TAG,
                                "handleGetCFAllResponse, cfInfoArray[" + i + "]=" + cfInfoArray[i]);
                        CallForwardEditPreference pref = mPreferences.get(i);

                        if (pref != null) {
                            if (pref == mButtonCFU) {
                                mPhone.setVoiceCallForwardingFlag(
                                        1, (cfInfoArray[i].status == 1), cfInfoArray[i].number);
                            }
                            cfInfoArray[i].reason = pref.reason;
                            pref.init(this, mPhone, mReplaceInvalidCFNumbers, mCallForwardByUssd);
                            pref.restoreCallForwardInfo(cfInfoArray[i]);
                            pref.setEnabled(true);
                        }
                    }
                    GsmUmtsCallForwardOptions.this.onFinished(
                            mPreferences.get(ALL_CALL_FORWARD_INDEX), true);
                }
            }
        }

        if (!querySuccess) {
            // UNISOC: Modify for bug 1101737
            GsmUmtsCallForwardOptions.super.onFinished(
                    mPreferences.get(ALL_CALL_FORWARD_INDEX), true);
            CallForwardEditPreference pref = mPreferences.get(mInitIndex);
            pref.init(this, mPhone, mReplaceInvalidCFNumbers, mCallForwardByUssd);
            pref.startCallForwardOptionsQuery();
        }
    }/*@}*/


    /* UNISOC: add for feature 1071416 durationforward @{ */
    public void refreshCFTButton() {
        if (mButtonCFT != null && mSupportDurationForward) {
            if ((mButtonCFU.isToggled()
                    && !CFT_STATUS_ACTIVE.equals(
                    mPrefs.getString(PREF_PREFIX_TIME + "status_" + mPhone.getSubId(), "")))
                    || mIsFDNOn) {
                mButtonCFT.setEnabled(false);
            } else {
                mButtonCFT.setEnabled(true);
            }
        }
    }

    private void updateCFTSummaryText() {
        if (mSupportDurationForward) {
            CharSequence summary;
            if (CFT_STATUS_ACTIVE.equals(mPrefs.getString(PREF_PREFIX_TIME
                    + "status_" + mPhone.getSubId(), ""))) {
                summary = mPrefs
                        .getString(PREF_PREFIX_TIME + "num_" + mPhone.getSubId(), "");
            } else {
                summary = mContext.getText(R.string.sum_cft_disabled);
            }
            mButtonCFT.setSummary(summary);
        }
    }

    @Override
    public void onEnableStatus(Preference preference, int status) {
        if (mSupportDurationForward) {
            refreshCFTButton();
            if (CFT_STATUS_ACTIVE.equals(
                    mPrefs.getString(PREF_PREFIX_TIME + "status_" + mPhone.getSubId(), ""))) {
                mButtonCFU.setEnabled(false);
            }
            updateCFTSummaryText();
        }
        // UNISOC: Add for Bug#1125621
        updatePrefCategoryEnabled(preference);
    }

    @Override
    public void onCallForawrdTimeStateChanged(String number) {
        mInitIndex = 0;
        mPreferences.get(mInitIndex).init(this,mPhone, mReplaceInvalidCFNumbers,false);
        updateCFTSummaryText();
        //UNISOC: add for bug1171209, Fix other call forword still clickable when enable DurationForward
        CallForwardEditPreference pref = mPreferences.get(mInitIndex);
        pref.startCallForwardOptionsQuery();
    }
    /* @} */

    /* UNISOC: Add for Bug#1125621 @{ */
    private boolean hasService(ServiceState srvState) {
        if (srvState != null) {
            switch (srvState.getVoiceRegState()) {
                case ServiceState.STATE_POWER_OFF:
                    return false;
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    return srvState.getDataRegState() == ServiceState.STATE_IN_SERVICE;
                default:
                    return true;
            }
        } else {
            return false;
        }
    }

    private void updatePrefCategoryEnabled(Preference preference) {
        if (preference == mButtonCFU) {
            if (mButtonCFU.isToggled()) {
                mButtonCFB.setEnabled(false);
                mButtonCFNRc.setEnabled(false);
                mButtonCFNRy.setEnabled(false);
            } else {
                mButtonCFB.setEnabled(true);
                mButtonCFNRc.setEnabled(true);
                mButtonCFNRy.setEnabled(true);
            }
        }
    }

    private BroadcastReceiver mServiceStateChangeReceiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPhone != null) {
                ServiceState srvState = TelephonyManager.from(context).getServiceStateForSubscriber(mPhone.getSubId());
                Log.d(LOG_TAG, "mServiceStateChangeReceiver: hasService(srvState) = " + hasService(srvState));
                if (!hasService(srvState)) {
                    mButtonCFU.setEnabled(false);
                    mButtonCFB.setEnabled(false);
                    mButtonCFNRc.setEnabled(false);
                    mButtonCFNRy.setEnabled(false);
                    if (mButtonCFT != null && mSupportDurationForward) {
                        mButtonCFT.setEnabled(false);
                    }
                } else {
                    mButtonCFU.setEnabled(true);
                    onEnableStatus(mButtonCFU, 0);
                }
            }
        }
    };
    /* @} */
}

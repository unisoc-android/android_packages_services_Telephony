package com.android.phone;

import android.app.ActionBar;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.Preference;
import android.util.Log;
import android.view.MenuItem;
import android.preference.PreferenceScreen;
import android.os.RemoteException;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import java.util.ArrayList;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;
import com.android.phone.settings.ActivityContainer;

/**
 * UNISOC: porting for bug1071416
 */
public class GsmUmtsVideoCallForwardOptions extends TimeConsumingPreferenceActivity{

    private static final String LOG_TAG = "GsmUmtsVideoCallForwardOptions";
    private static final boolean DBG = true;

    private static final String NUM_PROJECTION[] = {
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    private static final String BUTTON_VCFU_KEY   = "button_vcfu_key";
    private static final String BUTTON_VCFB_KEY   = "button_vcfb_key";
    private static final String BUTTON_VCFNRY_KEY = "button_vcfnry_key";
    private static final String BUTTON_VCFNRC_KEY = "button_vcfnrc_key";

    private static final String KEY_TOGGLE = "toggle";
    private static final String KEY_STATUS = "status";
    private static final String KEY_NUMBER = "number";
    private static final String KEY_ENABLE = "enable";

    private CallForwardEditPreference mButtonVCFU;
    private CallForwardEditPreference mButtonVCFB;
    private CallForwardEditPreference mButtonVCFNRy;
    private CallForwardEditPreference mButtonVCFNRc;

    private final ArrayList<CallForwardEditPreference> mPreferences =
            new ArrayList<CallForwardEditPreference> ();
    private int mInitIndex= 0;
    private boolean mFirstResume;
    private Bundle mIcicle;
    private boolean mReplaceInvalidCFNumbers;

    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private Phone mPhone;
    private int mPhoneId;
    private ImsManager mImsMgr;

    private boolean mIsVolteEnable;
    private boolean mIsImsListenerRegistered;
    private IImsServiceEx mIImsServiceEx;
    private boolean mIsFDNOn;
    private Context mContext;
    // SPRD: add for bug788564
    private boolean mHasInit = false;
    // UNISOC: modify by BUG 916869
    private ActivityContainer mActivityContainer;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        /* SPRD: add for callforward time @{ */
        mContext = this.getApplicationContext();
        addPreferencesFromResource(R.xml.video_callforward_options);
        /* @} */

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_forwarding_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        /* SPRD: add for bug694673 @{ */
        if (mPhone == null) {
            finish();
            return;
        }
        /* @} */
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
        }
        // SPRD: add for callforward time
        mContext = this.getApplicationContext();
        mPhoneId = mPhone.getPhoneId();
        mImsMgr = new ImsManager(mContext, mPhone.getPhoneId());

        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonVCFU = (CallForwardEditPreference) prefSet.findPreference(BUTTON_VCFU_KEY);
        mButtonVCFB = (CallForwardEditPreference) prefSet.findPreference(BUTTON_VCFB_KEY);
        mButtonVCFNRy = (CallForwardEditPreference) prefSet.findPreference(BUTTON_VCFNRY_KEY);
        mButtonVCFNRc = (CallForwardEditPreference) prefSet.findPreference(BUTTON_VCFNRC_KEY);

        mButtonVCFU.setParentActivity(this, mButtonVCFU.reason);
        mButtonVCFB.setParentActivity(this, mButtonVCFB.reason);
        mButtonVCFNRy.setParentActivity(this, mButtonVCFNRy.reason);
        mButtonVCFNRc.setParentActivity(this, mButtonVCFNRc.reason);

        mPreferences.add(mButtonVCFU);
        mPreferences.add(mButtonVCFB);
        mPreferences.add(mButtonVCFNRy);
        mPreferences.add(mButtonVCFNRc);

        // we wait to do the initialization until onResume so that the
        // TimeConsumingPreferenceActivity dialog can display as it
        // relies on onResume / onPause to maintain its foreground state.

        mFirstResume = true;
        mIcicle = icicle;

        // SPRD: modify for bug788564
        tryRegisterImsListener();

        /* UNISOC: modify by BUG 916869 @{ */
        mActivityContainer = ActivityContainer.getInstance();
        if (mActivityContainer != null) {
            mActivityContainer.setApplication(getApplication());
            mActivityContainer.addActivity(this, mPhone.getPhoneId());
        } /* @} */

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mFirstResume) {
            if (mIcicle == null) {
                /* SPRD: modify for bug788564 @{ */
                if (DBG) {
                    Log.d(LOG_TAG, "start to init mIsVolteEnable : " + mIsVolteEnable);
                }
                if (mPhone.isImsRegistered()) {
                    mHasInit = true;
                    CallForwardEditPreference pref = mPreferences.get(mInitIndex);
                    pref.init(this, mPhone, mReplaceInvalidCFNumbers, false);
                    pref.startCallForwardOptionsQuery();
                }
                /* @} */
            } else {
                mInitIndex = mPreferences.size();
                for (CallForwardEditPreference pref : mPreferences) {
                    Bundle bundle = mIcicle.getParcelable(pref.getKey());
                    pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
                    pref.setEnabled(bundle.getBoolean(KEY_ENABLE));
                    CallForwardInfo cf = new CallForwardInfo();
                    cf.number = bundle.getString(KEY_NUMBER);
                    cf.status = bundle.getInt(KEY_STATUS);
                    pref.init(this, mPhone, mReplaceInvalidCFNumbers, false);
                    pref.restoreCallForwardInfo(cf);
                }
            }
            mFirstResume = false;
            mIcicle = null;
        }
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
            }
            outState.putParcelable(pref.getKey(), bundle);
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mInitIndex < mPreferences.size()-1 && !isFinishing()) {
            mInitIndex++;
            CallForwardEditPreference pref = mPreferences.get(mInitIndex);
            pref.init(this, mPhone, mReplaceInvalidCFNumbers, false);
            pref.startCallForwardOptionsQuery();
        }
        super.onFinished(preference, reading);
    }

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
                    mButtonVCFU.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_BUSY:
                    mButtonVCFB.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NO_REPLY:
                    mButtonVCFNRy.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NOT_REACHABLE:
                    mButtonVCFNRc.onPickActivityResult(cursor.getString(0));
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
            // SPRD: modify for bug544979
            GsmUmtsAllCallForwardOptions.goUpToTopLevelSetting(this, mSubscriptionInfoHelper);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    /* SPRD: add for bug544979 @{ */
    @Override
    public void onBackPressed() {
        GsmUmtsAllCallForwardOptions.goUpToTopLevelSetting(this, mSubscriptionInfoHelper);
        super.onBackPressed();
    }
    /* @} */
        /* SPRD: add for callforward time @{ */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        /* UNISOC: modify by BUG 916869 @{ */
        if (mActivityContainer != null) {
            mActivityContainer.removeActivity(this);
        } /* @} */
        unRegisterImsListener();
    }

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

        if (error == FDN_CHECK_FAILURE) {
            mIsFDNOn = true;
        } else {
            mIsFDNOn = false;
        }
    }
    public void updateButtonStatus() {
        if (!mIsVolteEnable || mIsFDNOn) {
            mButtonVCFU.setEnabled(false);
            mButtonVCFB.setEnabled(false);
            mButtonVCFNRc.setEnabled(false);
            mButtonVCFNRy.setEnabled(false);
        } else {
            /* SPRD: add for bug788564 @{ */
            Log.d(LOG_TAG, "mHasInit : " + mHasInit + " mFirstResume : " + mFirstResume);
            if (!mFirstResume && !mHasInit && mPreferences != null && mPhone != null) {
                mInitIndex = 0;
                mPreferences.get(mInitIndex).init(this, mPhone, false, false);
                //add for unisoc 1229995
                mPreferences.get(mInitIndex).startCallForwardOptionsQuery();
                mHasInit = true;
            } else { /* @} */
                //add for unisoc 1229995
                updateVCFEnabled();
            }
        }
    }

    private synchronized void tryRegisterImsListener() {
        if (mImsMgr.isVolteEnabledByPlatform()) {
            mIImsServiceEx = ImsManagerEx.getIImsServiceEx();
            if (mIImsServiceEx != null) {
                try {
                    if (!mIsImsListenerRegistered) {
                        mIsImsListenerRegistered = true;
                        mIImsServiceEx.registerforImsRegisterStateChanged(mImsUtListenerExBinder);
                    }
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "regiseterforImsException", e);
                }
            }
        }
    }

    private synchronized void unRegisterImsListener() {
        if (mImsMgr.isVolteEnabledByPlatform()) {
            try {
                if (mIsImsListenerRegistered) {
                    mIsImsListenerRegistered = false;
                    mIImsServiceEx.unregisterforImsRegisterStateChanged(mImsUtListenerExBinder);
                }
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "unRegisterImsListener", e);
            }
        }
    }

    /* SPRD: modify for bug791625 @{ */
    private final IImsRegisterListener.Stub mImsUtListenerExBinder
            = new IImsRegisterListener.Stub() {
        @Override
        public void imsRegisterStateChange(boolean isRegistered) {
            boolean isRegisteredById = ImsManagerEx.isImsRegisteredForPhone(mPhone.getPhoneId());
            Log.d(LOG_TAG, "isRegistered : " + isRegistered
                    + " isRegisteredById : " + isRegisteredById);
            if (mIsVolteEnable != isRegisteredById) {
                mIsVolteEnable = isRegisteredById;
                updateButtonStatus();
            }
        }
    };/* @} */

    /* UNISOC: modify for bug1214541 @{ */
    private void updateVCFEnabled() {
        //add for unisoc 1229995
        mButtonVCFU.setEnabled(true);
        if (mButtonVCFU.isToggled()) {
            mButtonVCFB.setEnabled(false);
            mButtonVCFNRc.setEnabled(false);
            mButtonVCFNRy.setEnabled(false);
        } else {
            mButtonVCFB.setEnabled(true);
            mButtonVCFNRc.setEnabled(true);
            mButtonVCFNRy.setEnabled(true);
        }
    }
    /* @} */
}

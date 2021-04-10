package com.android.phone;

import android.app.Activity;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.ServiceState;
import android.util.Log;
import android.preference.PreferenceScreen;
import android.content.Intent;
import android.content.Context;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.MenuItem;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;
import android.os.RemoteException;


import com.android.internal.telephony.Phone;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.ImsManager;
import com.android.phone.settings.ActivityContainer;
/**
 * UNISOC: porting for bug1071416
 */
public class GsmUmtsAllCallForwardOptions extends PreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsAllCallForwardOptions";
    private static final boolean DBG = true;

    private static final String AUDIO_CALL_FORWARDING_KEY = "audio_call_forwarding_key";
    private static final String VIDEO_CALL_FORWARDING_KEY = "video_call_forwarding_key";
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private Context mContext;

    /* SPRD: modify by BUG 850080 @{ */
    private ServiceState mServiceState = null;
    private Preference mCallForwardingPref;
    private Preference mVideoCallForwardingPref;
    private ImsManager mImsManager;
    private Phone mPhone;
    private boolean mIsVideoCallForwardSupport;
    private IImsServiceEx mIImsServiceEx;
    private boolean mIsImsListenerRegistered;
    /* @} */
    //UNISOC: modify by BUG 681546
    private ActivityContainer mActivityContainer;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.gsm_umts_all_call_forward_options);

        // UNISOC: add for bug1075491
        boolean isStopped = getIntent().getBooleanExtra(PhoneUtils.IS_STOPPED_ACTIVITY_FLAG, false);
        if (isStopped) {
            moveTaskToBack(true);
        }

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.labelCF);
        mPhone = mSubscriptionInfoHelper.getPhone();
        mContext = this;
        // SPRD: add by Bug 939312
        mContext.registerReceiver(mServiceStateChangeReciver
                , new IntentFilter(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED));
        if (mPhone == null) {
            Log.d(LOG_TAG, "call finish()");
            finish();
        } else {
            mImsManager = new ImsManager(mContext, mSubscriptionInfoHelper.getPhone().getPhoneId());
            init(getPreferenceScreen(), mSubscriptionInfoHelper);
            tryRegisterImsListener();// UNISOC: modify by BUG 951133, 960801
            /* UNISOC: modify by BUG 681546 @{ */
            mActivityContainer = ActivityContainer.getInstance();
            mActivityContainer.setApplication(getApplication());
            mActivityContainer.addActivity(this, mPhone.getPhoneId());
            /* @} */
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    public void init(PreferenceScreen prefScreen, SubscriptionInfoHelper subInfoHelper) {
        mCallForwardingPref = prefScreen.findPreference(AUDIO_CALL_FORWARDING_KEY);
        Intent callForwardingIntent = subInfoHelper.getIntent(GsmUmtsCallForwardOptions.class);
        mCallForwardingPref.setIntent(callForwardingIntent);
        mVideoCallForwardingPref = prefScreen.findPreference(VIDEO_CALL_FORWARDING_KEY);
        mVideoCallForwardingPref
                .setIntent(subInfoHelper.getIntent(GsmUmtsVideoCallForwardOptions.class));
        mCallForwardingPref.setEnabled(false);
        mVideoCallForwardingPref.setEnabled(false);

        PersistableBundle b = null;
        if (mSubscriptionInfoHelper.hasSubId()) {
            b = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    mSubscriptionInfoHelper.getSubId());
        } else {
            b = PhoneGlobals.getInstance().getCarrierConfig();
        }
        if(b != null){
            mIsVideoCallForwardSupport = !b.getBoolean(
                    CarrierConfigManagerEx.KEY_HIDE_VIDEO_CALL_FORWARD, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateImsManager(mPhone);

        PreferenceScreen preferenceScreen = getPreferenceScreen();

        /* UNISOC: modify for FEATURE bug889200 951133 @{ */
        mIsVideoCallForwardSupport = mImsManager.isVolteEnabledByPlatform()
                && mImsManager.isVtEnabledByPlatform()
                && mContext.getResources().getBoolean(R.bool.config_video_callforward_support)
                && mIsVideoCallForwardSupport;

        Log.d(LOG_TAG, "mIsVideoCallForwardSupport " + mIsVideoCallForwardSupport);
        if (!mIsVideoCallForwardSupport) {
            preferenceScreen.removePreference(mVideoCallForwardingPref);
        }
        updateEnableStatus();
    }

    public static void goUpToTopLevelSetting(
            Activity activity, SubscriptionInfoHelper subscriptionInfoHelper) {
        Intent intent = subscriptionInfoHelper.getIntent(GsmUmtsAllCallForwardOptions.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unRegisterImsListener();// UNISOC: modify by BUG 951133
        if (mServiceStateChangeReciver != null) {
            mContext.unregisterReceiver(mServiceStateChangeReciver);
            mServiceStateChangeReciver = null;
        }

        /* UNISOC: modify by BUG 681546 @{ */
        if (mActivityContainer != null) {
            mActivityContainer.removeActivity(this);
        }
        /* @} */
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
                                         Preference preference) {
        if (preference != null && (AUDIO_CALL_FORWARDING_KEY.equals(preference.getKey())
                || VIDEO_CALL_FORWARDING_KEY.equals(preference.getKey()))) {
            if (isAirplaneModeOn(getBaseContext())
                    && !(mPhone.isWifiCallingEnabled() && isSupportSSOnVowifiWithAirPlane())) {
                log("the phone airplane mode is opened");
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(getResources().getString(
                        R.string.turn_off_airplane_for_ss))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return true;
            }
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    private boolean isAirplaneModeOn(Context context) {
        if (context == null) {
            return true;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    private boolean isSupportSSOnVowifiWithAirPlane() {

        PersistableBundle b = null;
        if (mSubscriptionInfoHelper.hasSubId()) {
            b = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    mSubscriptionInfoHelper.getSubId());
        } else {
            b = PhoneGlobals.getInstance().getCarrierConfig();
        }

        if (b != null) {
            boolean bRet = b
                    .getBoolean(CarrierConfigManagerEx.KEY_SUPPORT_SS_OVER_VOWIFI_WITH_AIRPLANE);
            log("isSupportSSOnVowifiWithAirPlane " + bRet);

            return bRet;
        }
        return false;
    }

    private void updateImsManager(Phone phone) {
        log("updateImsManager :: phone.getContext()=" + phone.getContext()
                + " phone.getPhoneId()=" + phone.getPhoneId());
        mImsManager = ImsManager.getInstance(phone.getContext(), phone.getPhoneId());
        if (mImsManager == null) {
            log("updateImsManager :: Could not get ImsManager instance!");
        } else {
            log("updateImsManager :: mImsMgr=" + mImsManager);
        }
    }


    private BroadcastReceiver mServiceStateChangeReciver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateEnableStatus();
        }
    };

    private void updateEnableStatus() {

        boolean isVoiceCfEnabled = false;
        boolean isVideoCfEnabled = false;
        if (mPhone != null) {
            mServiceState = mPhone.getServiceState();

            if (mPhone.isImsRegistered()) {
                isVoiceCfEnabled = true;
                isVideoCfEnabled = true;
            } else if (mServiceState.getState() == ServiceState.STATE_IN_SERVICE
                    || mServiceState.getDataRegState() == ServiceState.STATE_IN_SERVICE) {
                isVoiceCfEnabled = true;
            }

            log("updateEnableStatus isVoiceCfEnabled: " + isVoiceCfEnabled + " isVideoCfEnabled: " + isVideoCfEnabled);
            mCallForwardingPref.setEnabled(isVoiceCfEnabled);
            if (mIsVideoCallForwardSupport) {
                mVideoCallForwardingPref.setEnabled(isVideoCfEnabled);
            }
        }
    }

    /* UNISOC: modify by BUG 951133 @{ */
    private synchronized void tryRegisterImsListener() {
        // UNISOC: modify by Bug 960801
        if (mImsManager != null && mImsManager.isVolteEnabledByPlatform()) {
            mIImsServiceEx = ImsManagerEx.getIImsServiceEx();
            if (mIImsServiceEx != null) {
                try {
                    if (!mIsImsListenerRegistered) {
                        mIsImsListenerRegistered = true;
                        log("tryRegisterImsListener to ims");
                        mIImsServiceEx.registerforImsRegisterStateChanged(mImsUtListenerExBinder);
                    }
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "regiseterforImsException", e);
                }
            }
        }
    }

    private synchronized void unRegisterImsListener() {
        // UNISOC: modify by Bug 960801
        if (mImsManager != null && mImsManager.isVolteEnabledByPlatform()) {
            try {
                if (mIsImsListenerRegistered) {
                    mIsImsListenerRegistered = false;
                    log("unRegisterImsListener to ims");
                    mIImsServiceEx.unregisterforImsRegisterStateChanged(mImsUtListenerExBinder);
                }
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "unRegisterImsListener", e);
            }
        }
    }

    private final IImsRegisterListener.Stub mImsUtListenerExBinder
            = new IImsRegisterListener.Stub() {
        @Override
        public void imsRegisterStateChange(boolean isRegistered) {
            if (mSubscriptionInfoHelper != null && mSubscriptionInfoHelper.getPhone() != null) {
                boolean isRegisteredById = ImsManagerEx.isImsRegisteredForPhone(mSubscriptionInfoHelper.getPhone().getPhoneId());
                Log.d(LOG_TAG, "isRegistered : " + isRegistered
                        + " isRegisteredById : " + isRegisteredById);

                log("imsRegisterStateChange updateEnableStatus again");
                updateEnableStatus();
            }
        }
    };
   /* @} */
}

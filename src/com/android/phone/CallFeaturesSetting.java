/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.BroadcastReceiver;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.os.Handler;
import android.os.Message;
import android.database.ContentObserver;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.feature.ImsFeature;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.services.telephony.sip.SipUtil;
import com.android.sprd.telephony.RadioInteractorCore;
import com.android.sprd.telephony.RadioInteractorFactory;
import com.android.phone.settings.PhoneAccountSettingsFragment;
import com.android.phone.settings.SuppServicesUiUtil;
import com.android.phone.settings.VoicemailSettingsActivity;
import com.android.phone.settings.fdn.FdnSetting;
import com.android.phone.settings.ActivityContainer;
import android.provider.Settings;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;
import android.os.RemoteException;

import java.util.List;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "Call settings" hierarchy available from the Phone
 * app; the settings here let you control various features related to phone calls (including
 * voicemail settings, the "Respond via SMS" feature, and others.)  It's used only on
 * voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "Mobile network settings" screen under the main Settings app,
 * See {@link com.android.settings.network.telephony.MobileNetworkActivity}.
 */
public class CallFeaturesSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    // TODO: Consider moving these strings to strings.xml, so that they are not duplicated here and
    // in the layout files. These strings need to be treated carefully; if the setting is
    // persistent, they are used as the key to store shared preferences and the name should not be
    // changed unless the settings are also migrated.
    private static final String VOICEMAIL_SETTING_SCREEN_PREF_KEY = "button_voicemail_category_key";
    private static final String BUTTON_FDN_KEY   = "button_fdn_key";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";
    private static final String BUTTON_GSM_UMTS_OPTIONS = "button_gsm_more_expand_key";
    private static final String BUTTON_CDMA_OPTIONS = "button_cdma_more_expand_key";
    // UNISOC: modify for CDMA
    private static final String BUTTON_CDMA_CW_OPTIONS = "button_cdma_cw_key";

    private static final String PHONE_ACCOUNT_SETTINGS_KEY =
            "phone_account_settings_preference_screen";

    private static final String ENABLE_VIDEO_CALLING_KEY = "button_enable_video_calling";

    private Phone mPhone;
    private ImsManager mImsMgr;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private TelecomManager mTelecomManager;

    private SwitchPreference mButtonAutoRetry;
    private PreferenceScreen mVoicemailSettingsScreen;
    private SwitchPreference mEnableVideoCalling;
    private Preference mButtonWifiCalling;
    // UNISOC: add for bug916869
    private ActivityContainer mActivityContainer;

    // UNISOC: modify for bug911559
    private AlertDialog mVideoCallingDialog;

    ProgressDialog mProgress = null;
    private static final int GET_RAT_CAP_RESULT = 0;

    private static final int EVENT_GET_CALL_FORWARDING_DONE = 1;
    private static final int EVENT_GET_FAIL_REASON_DONE = 2;

    private final int VARIANT_TITLE_VOLTE = 0;
    private final int VARIANT_TITLE_ADVANCED_CALL = 1;
    private final int VARIANT_TITLE_4G_CALLING = 2;

    // UNISOC: add for bug1075491
    private boolean mIsStopped = false;
    private String mSyncVolte = null;
    private CharSequence[] mVariantTitles;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonAutoRetry) {
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        } else if (preference == preferenceScreen.findPreference(
                GsmUmtsCallOptions.CALL_FORWARDING_KEY)) {
            return doSsOverUtPrecautions(preference);
        } else if (preference == preferenceScreen.findPreference(
                GsmUmtsCallOptions.CALL_BARRING_KEY)) {
            return doSsOverUtPrecautions(preference);
        } else if (preference == preferenceScreen.findPreference(
                GsmUmtsCallOptions.ADDITIONAL_GSM_SETTINGS_KEY)) {
            //UNISOC: porting for  bug1068581 bug1149158
            return doSsOverUtPrecautions(preference);
        }
        return false;
    }

    private boolean doSsOverUtPrecautions(Preference preference) {

        /*UNISOC: porting for bug1069096 bug1068581 @{*/
        if (mPhone != null && !doSsOverAirplaneMode()) {
            return true;
        } else if (mPhone == null
                || (!mPhone.isImsRegistered() && !mPhone.isUtEnabled())) {
            return false;
        }
        /*@}*/

        PersistableBundle b = null;
        if (mSubscriptionInfoHelper.hasSubId()) {
            b = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    mSubscriptionInfoHelper.getSubId());
        } else {
            b = PhoneGlobals.getInstance().getCarrierConfig();
        }

        String configKey;
        if (preference.getKey().equals(GsmUmtsCallOptions.CALL_FORWARDING_KEY)) {
            configKey = CarrierConfigManager.KEY_CALL_FORWARDING_OVER_UT_WARNING_BOOL;
        } else if (preference.getKey().equals(GsmUmtsCallOptions.CALL_BARRING_KEY)) {
            configKey = CarrierConfigManager.KEY_CALL_BARRING_OVER_UT_WARNING_BOOL;
        } else {
            configKey = CarrierConfigManager.KEY_CALLER_ID_OVER_UT_WARNING_BOOL;
        }
        if (b != null && b.getBoolean(configKey)
                && mPhone != null
                && SuppServicesUiUtil.isSsOverUtPrecautions(this, mPhone)) {
            SuppServicesUiUtil.showBlockingSuppServicesDialog(this, mPhone,
                    preference.getKey(), this).show();
            return true;
        }

        if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA
                && preference.getKey().equals(GsmUmtsCallOptions.CALL_FORWARDING_KEY)) {
            queryCallForwardFirstByUt();
            return true;
        }
        return false;
    }

    /*UNISOC: porting for  bug1068581 @{*/
    boolean doSsOverAirplaneMode() {
        if (isAirplaneModeOn() && !isSupportSSOnVowifiEnable()) {
            log("the phone airplane mode is opened");
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(getResources().getString(
                    R.string.turn_off_airplane_for_ss))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return false;
        }
        return true;
    }

    private boolean isSupportSSOnVowifiEnable() {
        boolean isWifiEnbale = mPhone.isWifiCallingEnabled();

        PersistableBundle b = null;
        if (mSubscriptionInfoHelper.hasSubId()) {
            b = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    mSubscriptionInfoHelper.getSubId());
        } else {
            b = PhoneGlobals.getInstance().getCarrierConfig();
        }

        if (b != null && isWifiEnbale) {
            if (DBG) {
                log("isSupportSSOnVowifiEnable " + b.getBoolean(CarrierConfigManagerEx.KEY_SUPPORT_SS_OVER_VOWIFI_WITH_AIRPLANE));
            }
            return b.getBoolean(CarrierConfigManagerEx.KEY_SUPPORT_SS_OVER_VOWIFI_WITH_AIRPLANE);
        }
        return false;
    }

    public boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }/*@}*/

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (DBG) log("onPreferenceChange: \"" + preference + "\" changed to \"" + objValue + "\"");

        if (preference == mEnableVideoCalling) {
            if (mImsMgr.isEnhanced4gLteModeSettingEnabledByUser()) {
                mImsMgr.setVtSetting((boolean) objValue);
            } else {
                /* UNISOC: modify for bug911559 @{ */
                if (mVideoCallingDialog != null && mVideoCallingDialog.isShowing()) {
                    return false;
                }
                DialogInterface.OnClickListener networkSettingsClickListener =
                        new Dialog.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent intent = new Intent(Intent.ACTION_MAIN);
                                ComponentName mobileNetworkSettingsComponent = new ComponentName(
                                        getString(R.string.mobile_network_settings_package),
                                        getString(R.string.mobile_network_settings_class));
                                intent.setComponent(mobileNetworkSettingsComponent);
                                // UNISOC: add for bug1112380
                                if (mPhone != null) {
                                    intent.putExtra(Settings.EXTRA_SUB_ID, mPhone.getSubId());
                                }
                                startActivity(intent);
                            }
                        };
               mVideoCallingDialog = new AlertDialog.Builder(this)
                        .setMessage(mSyncVolte)
                        .setNeutralButton(getResources().getString(
                                R.string.enable_video_calling_dialog_settings),
                                networkSettingsClickListener)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
               mVideoCallingDialog.show();
               return false;
            }
        }

        // Always let the preference setting proceed.
        return true;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("onCreate: Intent is " + getIntent());

        // Make sure we are running as an admin user.
        if (!UserManager.get(this).isAdminUser()) {
            Toast.makeText(this, R.string.call_settings_admin_user_only,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        /* UNISOC: add for bug923038 @{ */
        if (mPhone == null) {
            finish();
        } else {
            mTelecomManager = TelecomManager.from(this);
            /* UNISOC: modify by BUG 916869 @{ */
            mActivityContainer = ActivityContainer.getInstance();
            mActivityContainer.setApplication(getApplication());
            mActivityContainer.addActivity(this, mSubscriptionInfoHelper.getPhone().getPhoneId());
            /* @} */
        }
        /* @} */
    }

    private void updateImsManager(Phone phone) {
        log("updateImsManager :: phone.getContext()=" + phone.getContext()
                + " phone.getPhoneId()=" + phone.getPhoneId());
        mImsMgr = ImsManager.getInstance(phone.getContext(), phone.getPhoneId());
        if (mImsMgr == null) {
            log("updateImsManager :: Could not get ImsManager instance!");
        } else {
            log("updateImsManager :: mImsMgr=" + mImsMgr);
        }
    }

    private void listenPhoneState(boolean listen) {
        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, listen
                ? PhoneStateListener.LISTEN_CALL_STATE : PhoneStateListener.LISTEN_NONE);
    }

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DBG) log("PhoneStateListener onCallStateChanged: state is " + state);
            // Use TelecomManager#getCallStete instead of 'state' parameter because it needs
            // to check the current state of all phone calls.
            boolean isCallStateIdle =
                    mTelecomManager.getCallState() == TelephonyManager.CALL_STATE_IDLE;
            if (mEnableVideoCalling != null) {
                mEnableVideoCalling.setEnabled(isCallStateIdle);
            }
            //UNISOC:fix for bug 1083551,only data cards are available for WiFi Calling
            if (mButtonWifiCalling != null) {
                mButtonWifiCalling.setEnabled(isCallStateIdle && mPhone.getSubId() == SubscriptionManager.getDefaultDataSubscriptionId());
            }
        }
    };

    private final ProvisioningManager.Callback mProvisioningCallback =
            new ProvisioningManager.Callback() {
        @Override
        public void onProvisioningIntChanged(int item, int value) {
            if (item == ImsConfig.ConfigConstants.VOICE_OVER_WIFI_SETTING_ENABLED
                    || item == ImsConfig.ConfigConstants.VLT_SETTING_ENABLED
                    || item == ImsConfig.ConfigConstants.LVC_SETTING_ENABLED) {
                updateVtWfc();
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        listenPhoneState(false);
        // UNISOC: Add for Bug 1073231 control WFC showing via OMA request
        if (mPhone.getContext() != null) {
            mPhone.getContext().getContentResolver().unregisterContentObserver(mWfcEnableObserver);
            mPhone.getContext().getContentResolver().unregisterContentObserver(mWFCShownObserver);
        }

        // Remove callback for provisioning changes.
        try {
            if (mImsMgr != null) {
                mImsMgr.getConfigInterface().removeConfigCallback(
                        mProvisioningCallback.getBinder());
            }
        } catch (ImsException e) {
            Log.w(LOG_TAG, "onPause: Unable to remove callback for provisioning changes");
        }
        // UNISOC: add for bug 1082937
        mSubscriptionInfoHelper.removeOnSubscriptionsChangedListener();
    }

    @Override
    protected void onResume() {
        super.onResume();

        /* UNISOC: modify by BUG 916869 @{ */
        if (mPhone != null) {
            updateImsManager(mPhone);
        }
        /* @} */
        listenPhoneState(true);
        PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            preferenceScreen.removeAll();
        }

        addPreferencesFromResource(R.xml.call_feature_setting);

        TelephonyManager telephonyManager = getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mPhone.getSubId());

        // Note: The PhoneAccountSettingsActivity accessible via the
        // android.telecom.action.CHANGE_PHONE_ACCOUNTS intent is accessible directly from
        // the AOSP Dialer settings page on multi-sim devices.
        // Where a device does NOT make the PhoneAccountSettingsActivity directly accessible from
        // its Dialer app, this check must be modified in the device's AOSP branch to ensure that
        // the PhoneAccountSettingsActivity is always accessible.
        if (telephonyManager.isMultiSimEnabled()) {
            Preference phoneAccountSettingsPreference = findPreference(PHONE_ACCOUNT_SETTINGS_KEY);
            getPreferenceScreen().removePreference(phoneAccountSettingsPreference);
        }

        PreferenceScreen prefSet = getPreferenceScreen();
        mVoicemailSettingsScreen =
                (PreferenceScreen) findPreference(VOICEMAIL_SETTING_SCREEN_PREF_KEY);
        mVoicemailSettingsScreen.setIntent(mSubscriptionInfoHelper.getIntent(
                VoicemailSettingsActivity.class));

        maybeHideVoicemailSettings();

        mButtonAutoRetry = (SwitchPreference) findPreference(BUTTON_RETRY_KEY);

        mEnableVideoCalling = (SwitchPreference) findPreference(ENABLE_VIDEO_CALLING_KEY);
        mButtonWifiCalling = findPreference(getResources().getString(
                R.string.wifi_calling_settings_key));

        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());

        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_AUTO_RETRY_ENABLED_BOOL)) {
            mButtonAutoRetry.setOnPreferenceChangeListener(this);
            int autoretry = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        } else {
            prefSet.removePreference(mButtonAutoRetry);
            mButtonAutoRetry = null;
        }

        Preference cdmaOptions = prefSet.findPreference(BUTTON_CDMA_OPTIONS);
        Preference gsmOptions = prefSet.findPreference(BUTTON_GSM_UMTS_OPTIONS);
        Preference fdnButton = prefSet.findPreference(BUTTON_FDN_KEY);
        // UNISOC: modify for CDMA
        Preference cdmaCwButton = prefSet.findPreference(BUTTON_CDMA_CW_OPTIONS);
        fdnButton.setIntent(mSubscriptionInfoHelper.getIntent(FdnSetting.class));
        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_PHONE_BOOL)) {
            cdmaOptions.setIntent(mSubscriptionInfoHelper.getIntent(CdmaCallOptions.class));
            gsmOptions.setIntent(mSubscriptionInfoHelper.getIntent(GsmUmtsCallOptions.class));
        } else {
            prefSet.removePreference(cdmaOptions);
            prefSet.removePreference(gsmOptions);

            int phoneType = mPhone.getPhoneType();
            if (carrierConfig.getBoolean(CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)) {
                prefSet.removePreference(fdnButton);
            } else {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    prefSet.removePreference(fdnButton);
                    /* UNISOC: FEATURE_CDMA_CALL_FOR @{ */
                    addPreferencesFromResource(R.xml.gsm_umts_call_options);
                    GsmUmtsCallOptions.init(prefSet, mSubscriptionInfoHelper);
                    cdmaCwButton.setIntent(mSubscriptionInfoHelper.getIntent(CdmaCallWaitingSetting.class));
                    /* @} */
                    /* UNISOC: @{
                    if (!carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
                        addPreferencesFromResource(R.xml.cdma_call_privacy);
                    }
                    @}*/
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (mPhone.getIccCard() == null || !mPhone.getIccCard().getIccFdnAvailable()) {
                        prefSet.removePreference(fdnButton);
                    }
                    if (carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_ADDITIONAL_CALL_SETTING_BOOL)) {
                        addPreferencesFromResource(R.xml.gsm_umts_call_options);
                        GsmUmtsCallOptions.init(prefSet, mSubscriptionInfoHelper);
                    }
                    // UNISOC: modify for CDMA
                    prefSet.removePreference(cdmaCwButton);
                } else {
                    throw new IllegalStateException("Unexpected phone type: " + phoneType);
                }
            }
        }
        /* UNISOC: add for bug1073231 @{ */
        if (mPhone.getContext() != null) {
            mPhone.getContext().getContentResolver().registerContentObserver(
                    getNotifyContentUri(SubscriptionManager.WFC_ENABLED_CONTENT_URI, true, mPhone.getSubId()),
                    true, mWfcEnableObserver);
            mPhone.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(OmaHelper.OMA_WFC_ENABLE + mPhone.getSubId()), true,
                    mWFCShownObserver);
            /* UNISOC:fix for bug 1141115{ */
            mVariantTitles = mPhone.getContext().getResources()
                    .getTextArray(R.array.enhanced_4g_lte_mode_title_variant);
            mSyncVolte = String.format(getResources().getString(R.string.enable_video_calling_dialog_auto_msg), getVolteTitle());
            log("[mSyncVolte]: " + mSyncVolte);
            /* @} */
        }
        /* @} */
        updateVtWfc();

        // Register callback for provisioning changes.
        try {
            if (mImsMgr != null) {
                mImsMgr.getConfigInterface().addConfigCallback(mProvisioningCallback);
            }
        } catch (ImsException e) {
            Log.w(LOG_TAG, "onResume: Unable to register callback for provisioning changes.");
        }
        // UNISOC: add for bug 1082937
        mSubscriptionInfoHelper.addOnSubscriptionsChangedListener();
    }
    /* UNISOC: modify by BUG 1145273 @{ */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
       public void onReceive(Context context, Intent intent) {
           if (intent.getAction().equals(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
               updateVtWfc();
           }
       }
    };
    /* @} */
    /* UNISOC: modify by BUG 916869 @{ */
    protected void onDestroy() {
        super.onDestroy();
        if (mActivityContainer != null) {
            mActivityContainer.removeActivity(this);
        }

        if (mProgress != null) {
            mProgress.dismiss();
        }
    }
    /* @} */

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(
                TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        // UNISOC: Bug 1145273
        mPhone.getContext().registerReceiver(mReceiver, filter);
        mIsStopped = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        // UNISOC: Bug 1145273
        mPhone.getContext().unregisterReceiver(mReceiver);
        mIsStopped = true;
    }

    private void updateVtWfc() {
        PreferenceScreen prefSet = getPreferenceScreen();
        TelephonyManager telephonyManager = getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mPhone.getSubId());
        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
        boolean editableWfcRoamingMode = true;
        boolean useWfcHomeModeForRoaming = false;
        if (carrierConfig != null) {
            editableWfcRoamingMode = carrierConfig.getBoolean(
                    CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL);
            useWfcHomeModeForRoaming = carrierConfig.getBoolean(
                    CarrierConfigManager.KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL,
                    false);
        }
        if (mImsMgr.isVtEnabledByPlatform() && mImsMgr.isVtProvisionedOnDevice()
                && (carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS)
                || mPhone.getDataEnabledSettings().isDataEnabled())) {
            boolean currentValue =
                    mImsMgr.isEnhanced4gLteModeSettingEnabledByUser()
                    ? mImsMgr.isVtEnabledByUser() : false;
            mEnableVideoCalling.setChecked(currentValue);
            mEnableVideoCalling.setOnPreferenceChangeListener(this);
            prefSet.addPreference(mEnableVideoCalling);
        } else {
            prefSet.removePreference(mEnableVideoCalling);
        }

        final PhoneAccountHandle simCallManager = mTelecomManager.getSimCallManagerForSubscription(
                mPhone.getSubId());
        if (simCallManager != null) {
            Intent intent = PhoneAccountSettingsFragment.buildPhoneAccountConfigureIntent(
                    this, simCallManager);
            if (intent != null) {
                PackageManager pm = mPhone.getContext().getPackageManager();
                List<ResolveInfo> resolutions = pm.queryIntentActivities(intent, 0);
                if (!resolutions.isEmpty()) {
                    mButtonWifiCalling.setTitle(resolutions.get(0).loadLabel(pm));
                    mButtonWifiCalling.setSummary(null);
                    mButtonWifiCalling.setIntent(intent);
                    prefSet.addPreference(mButtonWifiCalling);
                } else {
                    prefSet.removePreference(mButtonWifiCalling);
                }
            } else {
                prefSet.removePreference(mButtonWifiCalling);
            }
        } else if (!mImsMgr.isWfcEnabledByPlatform() || !mImsMgr.isWfcProvisionedOnDevice()
                    || (!isShowWfcByDefault() && !isOmaWfcEnable ())) {
                prefSet.removePreference(mButtonWifiCalling);
        } else {
            String title = SubscriptionManager.getResourcesForSubId(mPhone.getContext(),
                    mPhone.getSubId()).getString(R.string.wifi_calling);
            mButtonWifiCalling.setTitle(title);

            int resId = com.android.internal.R.string.wifi_calling_off_summary;
            if (mImsMgr.isWfcEnabledByUser()) {
                //UNISOC:fix for bug 854291
                boolean showWifiCallingPreference = true;
                if(carrierConfig != null) {
                    showWifiCallingPreference = carrierConfig.getBoolean(
                                CarrierConfigManagerEx.KEY_SUPPORT_SHOW_WIFI_CALLING_PREFERENCE);
                }
                if(showWifiCallingPreference) {
                    boolean isRoaming = telephonyManager.isNetworkRoaming();
                    boolean wfcRoamingEnabled = editableWfcRoamingMode && !useWfcHomeModeForRoaming;
                    int wfcMode = mImsMgr.getWfcMode(isRoaming && wfcRoamingEnabled);
                    switch (wfcMode) {
                        case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                            resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                            break;
                        case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                            resId = com.android.internal.R.string.wfc_mode_cellular_preferred_summary;
                            break;
                        case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                            resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                            break;
                        default:
                            if (DBG) log("Unexpected WFC mode value: " + wfcMode);
                    }
                } else {
                    resId = com.android.internal.R.string.wifi_calling_on_summary;
                }
            }
            mButtonWifiCalling.setSummary(resId);
            Intent intent = mButtonWifiCalling.getIntent();
            if (intent != null) {
                intent.putExtra(Settings.EXTRA_SUB_ID, mPhone.getSubId());
            }
            prefSet.addPreference(mButtonWifiCalling);
        }

        try {
            if (mImsMgr.getImsServiceState() != ImsFeature.STATE_READY) {
                log("Feature state not ready so remove vt and wfc settings for "
                        + " phone =" + mPhone.getPhoneId());
                prefSet.removePreference(mButtonWifiCalling);
                prefSet.removePreference(mEnableVideoCalling);
            }
        } catch (ImsException ex) {
            log("Exception when trying to get ImsServiceStatus: " + ex);
            prefSet.removePreference(mButtonWifiCalling);
            prefSet.removePreference(mEnableVideoCalling);
        }
        boolean isCallStateIdle =
                mTelecomManager.getCallState() == TelephonyManager.CALL_STATE_IDLE;
        if (mButtonWifiCalling != null) {
            mButtonWifiCalling.setEnabled(isCallStateIdle && mPhone.getSubId() == SubscriptionManager.getDefaultDataSubscriptionId());
        }
    }

    /*
     * UNISOC: Add for Bug 1073231 control WFC showing via OMA request
     */
    private boolean isShowWfcByDefault() {
        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());

        if (carrierConfig != null) {
            return carrierConfig.getBoolean(
                    CarrierConfigManagerEx.KEY_DEFAULT_SHOW_WIFI_CALL);
        }

        return true;
    }

    private boolean isOmaWfcEnable() {
        return ((Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                OmaHelper.OMA_WFC_ENABLE + mPhone.getSubId(), 0)) == 1 ? true : false);
    }

    /**
     * Hides the top level voicemail settings entry point if the default dialer contains a
     * particular manifest metadata key. This is required when the default dialer wants to display
     * its own version of voicemail settings.
     */
    private void maybeHideVoicemailSettings() {
        String defaultDialer = getSystemService(TelecomManager.class).getDefaultDialerPackage();
        if (defaultDialer == null) {
            return;
        }
        try {
            Bundle metadata = getPackageManager()
                    .getApplicationInfo(defaultDialer, PackageManager.GET_META_DATA).metaData;
            if (metadata == null) {
                return;
            }
            if (!metadata
                    .getBoolean(TelephonyManager.METADATA_HIDE_VOICEMAIL_SETTINGS_MENU, false)) {
                if (DBG) {
                    log("maybeHideVoicemailSettings(): not disabled by default dialer");
                }
                return;
            }
            getPreferenceScreen().removePreference(mVoicemailSettingsScreen);
            if (DBG) {
                log("maybeHideVoicemailSettings(): disabled by default dialer");
            }
        } catch (NameNotFoundException e) {
            // do nothing
            if (DBG) {
                log("maybeHideVoicemailSettings(): not controlled by default dialer");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent newIntent) {
        setIntent(newIntent);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(
            Activity activity, SubscriptionInfoHelper subscriptionInfoHelper) {
        Intent intent = subscriptionInfoHelper.getIntent(CallFeaturesSetting.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }

    private ContentObserver mWfcEnableObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (mPhone.getContext() == null) {
                return;
            }
            updateVtWfc();
        }
    };

    private ContentObserver mWFCShownObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (mPhone.getContext() == null) {
                return;
            }
            updateVtWfc();
        }
    };

    private Uri getNotifyContentUri(Uri uri, boolean usingSubId, int subId) {
        return (usingSubId) ? Uri.withAppendedPath(uri, "" + subId) : uri;
    }

    // UNISOC: porting for bug1024577
    public void queryCallForwardFirstByUt() {
        log("queryCallForwardFirstByUt");
        if (mProgress != null) {
            mProgress.dismiss();
        }
        mProgress = new ProgressDialog(this);
        mProgress.setMessage(getResources().getString(R.string.sum_loading_settings));
        mProgress.setCanceledOnTouchOutside(false);
        mProgress.setOnCancelListener(new OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                log("user cancelled.");
                finish();
            }
        });
        mProgress.show();

        if (mPhone != null) {
            mPhone.getCallForwardingOption(CommandsInterface.CF_REASON_UNCONDITIONAL,
                    mHandler.obtainMessage(EVENT_GET_CALL_FORWARDING_DONE));
        } else {
            log("queryCallForwardFirstByUt imsPhone == null");
            startCdmaCallForwardActivity();
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case EVENT_GET_CALL_FORWARDING_DONE:
                    if (isFinishing() || isDestroyed()) {
                        log("EVENT_GET_CALL_FORWARDING_DONE, activity is destroyed, break.");
                        break;
                    }

                    if (ar.exception != null) {
                        RadioInteractorCore radioInteractor = getRadioInteractor();
                        if (radioInteractor != null) {
                            radioInteractor.getSpecialRatcap(obtainMessage(EVENT_GET_FAIL_REASON_DONE),
                                    GET_RAT_CAP_RESULT);
                        }
                    } else {
                        if (mProgress != null) {
                            mProgress.dismiss();
                        }
                        startGsmCallForwardActivity();
                    }
                    break;
                case EVENT_GET_FAIL_REASON_DONE:
                    if (isFinishing() || isDestroyed()) {
                        log("EVENT_GET_FAIL_REASON_DONE, activity is destroyed, break.");
                        break;
                    }

                    if (mProgress != null) {
                        mProgress.dismiss();
                    }

                    if (ar.exception == null && ar.result != null) {
                        int[] responseArray = (int[]) ar.result;
                        log("EVENT_GET_FAIL_REASON_DONE response = " + responseArray[0]);
                        if (responseArray[0] == 1) { // ut is not supported, use cs call for call forward options.
                            startCdmaCallForwardActivity();
                        } else {
                            startGsmCallForwardActivity();
                        }
                    } else {
                        log("EVENT_GET_FAIL_REASON_DONE ar.exception: " + ar.exception);
                        startCdmaCallForwardActivity();
                    }
                    break;
                default:
                    break;
            }

        }
    };

    private RadioInteractorCore getRadioInteractor(){
        if(RadioInteractorFactory.getInstance() != null) {
            return RadioInteractorFactory.getInstance().getRadioInteractorCore(mPhone.getPhoneId());
        }
        return null;
    }

    private void startCdmaCallForwardActivity() {
        Intent intent = mSubscriptionInfoHelper.getIntent(CdmaCallForwardOptions.class);
        intent.putExtra(PhoneUtils.IS_STOPPED_ACTIVITY_FLAG, mIsStopped);
        startActivity(intent);
    }

    private void startGsmCallForwardActivity() {
        Intent intent = mSubscriptionInfoHelper.getIntent(GsmUmtsAllCallForwardOptions.class);
        intent.putExtra(PhoneUtils.IS_STOPPED_ACTIVITY_FLAG, mIsStopped);
        startActivity(intent);
    }
    /* UNISOC:fix for bug 1141115{ */
    private String getVolteTitle(){
        PersistableBundle b =
            PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
        if (b != null) {
            boolean show4GForLTE = b.getBoolean(
                CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL);
            int variant4glteTitleIndex = b.getInt(
                CarrierConfigManager.KEY_ENHANCED_4G_LTE_TITLE_VARIANT_INT);
            if (variant4glteTitleIndex != VARIANT_TITLE_ADVANCED_CALL) {
                variant4glteTitleIndex = show4GForLTE ? VARIANT_TITLE_4G_CALLING : VARIANT_TITLE_VOLTE;
            }
            return mVariantTitles[variant4glteTitleIndex].toString();
        }
        return null;
    }
    /* @} */
}

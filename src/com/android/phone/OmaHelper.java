package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.ImsManager;
import com.android.phone.R;

/*
 * UNISOC: Add for Bug 843666 controll WFC showing via OMA request @{
 */
public class OmaHelper {
    public static final String TAG = OmaHelper.class.getSimpleName();
    private static final String VOWIFI_DATA = "vowifiData";
    private static final String EPDG_FQDN_KEY = "epdg fqdn";
    private static final String VOWIFI_SIZE = "vowifi_size";
    private static final String OMA_WIFI_CALLING_ACTION = "com.andorid.VowifiDataConfig";
    public static final String OMA_WFC_ENABLE = "oma.wfc.enable";
    private static final String VOWIFI_SUBID = "subid";
    private static OmaHelper mInstance;
    private Editor mEditor;
    private SharedPreferences mPreference;
    private PhoneGlobals mApplication;

    public static OmaHelper getInstance() {
        if (mInstance == null) {
            mInstance = new OmaHelper();
        }
        return mInstance;
    }

    private OmaHelper() {
        mApplication = PhoneGlobals.getInstance();
        mPreference = mApplication.getApplicationContext().getSharedPreferences(TAG,
                mApplication.getApplicationContext().MODE_PRIVATE
                        | mApplication.getApplicationContext().MODE_MULTI_PROCESS);
        mEditor = mPreference.edit();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(OMA_WIFI_CALLING_ACTION);
        mApplication.registerReceiver(mOmaRreciver, intentFilter);
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private final BroadcastReceiver mOmaRreciver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "OmaReceiver start ---- intent: " + intent);
            if (intent != null) {
                if (OMA_WIFI_CALLING_ACTION.equals(intent.getAction())) {
                    int subId = intent.getIntExtra(VOWIFI_SUBID,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                    int def = -1;
                    int size = intent.getIntExtra(VOWIFI_SIZE, def);
                    Log.d(TAG, "vowifi's size = " + size);
                    boolean isShown = false;
                    if (size > 0 && SubscriptionManager.isValidSubscriptionId(subId)) {
                        for (int i = 1; i <= size; i++) {
                            log("vowifi i = " + i);
                            Bundle bundles = intent.getBundleExtra(VOWIFI_DATA + i);
                            if (bundles != null) {
                                log("vowifi bundels: " + bundles.toString());
                                String vowifi = bundles.getString(EPDG_FQDN_KEY, "");
                                String operatorKey = getStringCarrierConfig(mApplication, subId,
                                        CarrierConfigManagerEx.KEY_OPERATOR_STRING_SHOW_WIFI_CALL);
                                if (!TextUtils.isEmpty(vowifi)
                                        && TextUtils.equals(operatorKey, vowifi)) {
                                    isShown = true;
                                    break;
                                }
                            }
                        }
                    }
                    int defVal = 0;
                    int showVal = 1;
                    int wfcOmaEabledPrev = Settings.Global.getInt(
                            mApplication.getContentResolver(), OMA_WFC_ENABLE + subId, defVal);
                    log("isShown = " + isShown + ", prev = " + wfcOmaEabledPrev
                            + " subId = " + subId);
                    if (wfcOmaEabledPrev != (isShown ? showVal : defVal)) {
                        Settings.Global.putInt(mApplication.getContentResolver(),
                                OMA_WFC_ENABLE + subId, isShown ? showVal : defVal);
                        int defaultMode = getIntCarrierConfig(mApplication, subId,
                                CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT);
                        log("defaultMode = " + defaultMode);
                        ImsManager.setWfcSetting(mApplication, false);
                        ImsManager.setWfcMode(mApplication, defaultMode);
                    }
                }
            }
        }
    };

    private int getIntCarrierConfig(Context context, int subId, String key) {
        CarrierConfigManager configManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = null;
        if (configManager != null) {
            b = configManager.getConfigForSubId(subId);
        }
        if (b != null) {
            return b.getInt(key);
        } else {
            log("use default config");
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getInt(key);
        }
    }

    private String getStringCarrierConfig(Context context, int subId, String key) {
        CarrierConfigManager configManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = null;
        if (configManager != null) {
            b = configManager.getConfigForSubId(subId);
        }
        if (b != null) {
            return b.getString(key);
        } else {
            log("use default config");
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getString(key);
        }
    }
}
/* @} */

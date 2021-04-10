package com.android.phone;

import android.app.AppOpsManager;
import android.content.SharedPreferences;

import android.os.AsyncResult;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellLocation;
import android.telephony.LocationAccessPolicy;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.GsmCdmaPhoneEx;
import com.android.internal.telephony.ITelephonyEx;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.RadioController;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.SimEnabledController;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.uicc.ExtraIccRecords;
import com.android.internal.telephony.uicc.ExtraIccRecordsController;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccCardApplication;

/**
 * Implementation of the ITelephonyEx interface.
 */
public class PhoneInterfaceManagerEx extends ITelephonyEx.Stub {
    private static final String LOG_TAG = "PhoneInterfaceManagerEx";
    private static final boolean DBG = true;

    private static final String PREF_CARRIERS_ALPHATAG_PREFIX = "carrier_alphtag_";
    private static final String PREF_CARRIERS_SUBSCRIBER_PREFIX = "carrier_subscriber_";
    private static final String PREF_CARRIERS_NUMBER_PREFIX = "carrier_number_";
    private PhoneGlobalsEx mApp;
    /** The singleton instance. */
    private static PhoneInterfaceManagerEx sInstance;
    /**
     * Initialize the singleton PhoneInterfaceManagerEx instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static PhoneInterfaceManagerEx init(PhoneGlobalsEx app, Phone phone) {
        synchronized (PhoneInterfaceManager.class) {
            if (sInstance == null) {
                sInstance = new PhoneInterfaceManagerEx(app, phone);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private PhoneInterfaceManagerEx(PhoneGlobalsEx app, Phone phone) {
        mApp = app;
        publish();
    }

    public Bundle getCellLocationForPhone(int phoneId,String callingPackage) {
        mApp.getSystemService(AppOpsManager.class)
        .checkPackage(Binder.getCallingUid(), callingPackage);

        LocationAccessPolicy.LocationPermissionResult locationResult =
                LocationAccessPolicy.checkLocationPermission(mApp,
                        new LocationAccessPolicy.LocationPermissionQuery.Builder()
                                .setCallingPackage(callingPackage)
                                .setCallingPid(Binder.getCallingPid())
                                .setCallingUid(Binder.getCallingUid())
                                .setMethod("getCellLocationForPhone")
                                .setMinSdkVersionForFine(Build.VERSION_CODES.Q)
                                .build());
        switch (locationResult) {
        case DENIED_HARD:
            throw new SecurityException("Not allowed to access cell location");
        case DENIED_SOFT:
            return new Bundle();
            }

        final long identity = Binder.clearCallingIdentity();
        try {
            if (DBG) log("getCellLocation: is active user");
            Bundle data = new Bundle();
            Phone phone = PhoneFactory.getPhone(phoneId);
            CellLocation cl = phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ? new CdmaCellLocation()
                    : new GsmCellLocation();
            ServiceStateTracker sst = phone.getServiceStateTracker();
            if (sst != null) {
                if (DBG) log("getCellLocation from SST");
                cl = sst.getCellLocation();
            }
            cl.fillInNotifierBundle(data);
            return data;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private String getIccId(int subId) {
        final Phone phone = getPhone(subId);
        IccRecords card = phone == null ? null : phone.getIccRecords();
        if (card == null) {
            if (DBG) log("getIccId: No UICC");
            return null;
        }
        String iccId = card.getFullIccId();
        if (TextUtils.isEmpty(iccId)) {
            if (DBG) log("getIccId: ICC ID is null or empty.");
            return null;
        }
        return iccId;
    }

    @Override
    public boolean setLine1NumberForDisplayForSubscriberEx(int subId, String alphaTag,
            String number) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final String iccId = getIccId(subId);
            final Phone phone = getPhone(subId);
            if (phone == null) {
                return false;
            }
            final String subscriberId = phone.getSubscriberId();

            if (TextUtils.isEmpty(iccId)) {
                return false;
            }

            SharedPreferences telephonySharedPreferences = PreferenceManager.getDefaultSharedPreferences(mApp);
            final SharedPreferences.Editor editor = telephonySharedPreferences.edit();

            final String alphaTagPrefKey = PREF_CARRIERS_ALPHATAG_PREFIX + iccId;
            if (alphaTag == null) {
                editor.remove(alphaTagPrefKey);
            } else {
                editor.putString(alphaTagPrefKey, alphaTag);
            }

            // Record both the line number and IMSI for this ICCID, since we need to
            // track all merged IMSIs based on line number
            final String numberPrefKey = PREF_CARRIERS_NUMBER_PREFIX + iccId;
            final String subscriberPrefKey = PREF_CARRIERS_SUBSCRIBER_PREFIX + iccId;
            if (number == null) {
                editor.remove(numberPrefKey);
                editor.remove(subscriberPrefKey);
            } else {
                editor.putString(numberPrefKey, number);
                editor.putString(subscriberPrefKey, subscriberId);
            }

            editor.commit();
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean isDataDisconnected(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if ( phone != null){
            return phone.getDcTracker(AccessNetworkConstants.TRANSPORT_TYPE_WWAN).isDisconnected();
        }
        return false;
    }

    @Override
    public boolean areAllDataDisconnected(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            return phone.areAllDataDisconnected();
        }
        return false;
    }

    @Override
   public void setInternalDataEnabled(int phoneId, boolean enabled){
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            phone.getDataEnabledSettings().setInternalDataEnabled(enabled);
        }
   }

    private void publish() {
        if (DBG) log("publish: " + this);

        ServiceManager.addService("phone_ex", this);
    }

    public int getMaxRafSupported() {
        return ProxyController.getInstance().getMaxRafSupported();
    }

    public boolean isUsimCard(int phoneId) {
        if (DBG) log("isUsimCard: ");
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            if (DBG) log("Ignore invalid phoneId: " + phoneId);
            return false;
        }
        UiccCardApplication application =
                UiccController.getInstance().getUiccCardApplication(phoneId, UiccController.APP_FAM_3GPP);
        if (application != null) {
            return  application.getType() == AppType.APPTYPE_USIM;
        }
        return false;
    }

    public void setSimEnabled(int phoneId, final boolean turnOn) {
        enforceModifyPermission();
        TelephonyManager.setTelephonyProperty(phoneId, "persist.radio.sim_enabled",
                turnOn ? "1" : "0");
        log("setSimEnabled[" + phoneId + "]= " + turnOn);
        SimEnabledController.getInstance().setSimEnabled(phoneId, turnOn);
    }

    /**
     * UNISOC: add for bug1072750, AndroidQ porting for USIM/SIM phonebook
     */
    private Phone getPhone(int subId) {
        return PhoneFactory.getPhone(SubscriptionController.getInstance().getPhoneId(subId));
    }

    /**
     * @return true if a IccFdn enabled
     */
    public boolean getIccFdnEnabledForSubscriber(int subId) {
        return getPhone(subId).getIccCard().getIccFdnEnabled();
    }
    /* @} */

    public String getPnnHomeName(int subId, String callingPackage) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mApp, subId, callingPackage, "getPnnHomeName")) {
            return null;
        }
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            Phone phone = PhoneFactory.getPhone(phoneId);
            IccRecords iccRecords = phone.getIccRecords();
            if (iccRecords != null && iccRecords instanceof SIMRecords) {
                return ((SIMRecords) iccRecords).getPnnHomeName();
            }
        }
        return null;
    }

    /**
     * Unisoc: Support SimLock @{
     */
    public int[] supplySimLock(int phoneId, boolean isLock, String password, int type){
        log("supplySimLock, isLock:" + isLock + ",password:" + password + ",type:" + type);
        enforceModifyPermission();
        GsmCdmaPhoneEx phone = (GsmCdmaPhoneEx)PhoneFactory.getPhone(phoneId);
        final UnlockSimLockThread unlockSimLockThread = new UnlockSimLockThread(phone);
        unlockSimLockThread.start();
        return unlockSimLockThread.unlockSimLock(isLock, password, type);
    }

    private static class UnlockSimLockThread extends Thread {
        private final GsmCdmaPhoneEx mPhone;
        private boolean mDone = false;
        private int mResult = PhoneConstants.PIN_GENERAL_FAILURE;
        private int mRemainTimes = 0;

        private Handler mHandler;

        // For async handler to identify request type
        private static final int SUPPLY_SIMLOCK_COMPLETE = 100;

        public UnlockSimLockThread(GsmCdmaPhoneEx phone) {
            mPhone = phone;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (UnlockSimLockThread.this) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        switch (msg.what) {
                        case SUPPLY_SIMLOCK_COMPLETE:
                            log("SUPPLY_SIMLOCK_COMPLETE");
                            synchronized (UnlockSimLockThread.this) {
                                mRemainTimes = msg.arg1;
                                if (ar.exception != null) {
                                    if (ar.exception instanceof CommandException &&
                                            ((CommandException)(ar.exception)).getCommandError()
                                            == CommandException.Error.PASSWORD_INCORRECT) {
                                        mResult = PhoneConstants.PIN_PASSWORD_INCORRECT;
                                    } else {
                                        mResult = PhoneConstants.PIN_GENERAL_FAILURE;
                                    }
                                } else {
                                    mResult = PhoneConstants.PIN_RESULT_SUCCESS;
                                }
                                mDone = true;
                                UnlockSimLockThread.this.notifyAll();
                            }
                            break;
                        }
                    }
                };
                UnlockSimLockThread.this.notifyAll();
            }
            Looper.loop();
        }

        synchronized int[] unlockSimLock(boolean isLock, String password, int type) {

            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Message callback = Message.obtain(mHandler, SUPPLY_SIMLOCK_COMPLETE, type);
            mPhone.supplySimLock(isLock, password, callback);

            while (!mDone) {
                try {
                    log("wait for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            log("done");
            int[] resultArray = new int[2];
            resultArray[0] = mResult;
            resultArray[1] = mRemainTimes;
            log("result: " + resultArray[0] + ", remainTimes = " + resultArray[1]);
            return resultArray;
        }
    }
    /** @} */

    public void setRadioPower(int phoneId, boolean onOff) {
        enforceModifyPermission();
        RadioController radioController = RadioController.getInstance();
        radioController.setRadioPower(phoneId, onOff, null);
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, "[PhoneIntfMgrEx] " + msg);
    }

    public int getHomeExceptService(int phoneId, String callingPackage) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone == null) {
            return -1;
        }

        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
              mApp, phone.getSubId(), callingPackage, "getHomeExceptService")) {
            throw new SecurityException("Requires READ_PHONE_STATE permission.");
        }

        ExtraIccRecordsController exIccRecordsController = ExtraIccRecordsController.getInstance();
        if (exIccRecordsController == null) {
            return -1;
        }
        ExtraIccRecords exIccRecords = ExtraIccRecordsController.getInstance()
                .getExtraIccRecords(phoneId);
        if (exIccRecords == null) {
            return -1;
        }
        return exIccRecords.getHomeExceptService();
    }

    public int getRomingExceptService(int phoneId, String callingPackage) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone == null) {
            return -1;
        }

        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mApp, phone.getSubId(), callingPackage, "getRomingExceptService")) {
            throw new SecurityException("Requires READ_PHONE_STATE permission.");
        }

        ExtraIccRecordsController exIccRecordsController = ExtraIccRecordsController.getInstance();
        if (exIccRecordsController == null) {
            return -1;
        }
        ExtraIccRecords exIccRecords = ExtraIccRecordsController.getInstance()
                .getExtraIccRecords(phoneId);
        if (exIccRecords == null) {
            return -1;
        }
        return exIccRecords.getRomingExceptService();
    }

    /*UNISOC: BUG1131047 for test usim @{ */
    public boolean isTestUsim(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone == null) {
            return false;
        }
        IccRecords iccRecords = phone.getIccRecords();
        if (iccRecords == null) {
            return false;
        }
        if (iccRecords.isTestUsim()) {
            log("is test usim");
            return true;
        }
        return false;
    }
     /*UNISOC: @} */
}

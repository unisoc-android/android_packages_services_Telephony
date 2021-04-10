
package com.android.telephony;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.provider.Telephony;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.RadioAccessFamily;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.ImsManager;
import com.android.ims.internal.ImsManagerEx;
import com.android.phone.R;
import com.android.internal.telephony.IDmykTelephony;
import com.android.internal.telephony.PhoneConstantConversions;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.UiccController;
import com.android.phone.PhoneGlobalsEx;
import com.dmyk.android.telephony.DmykAbsTelephonyManager;

import java.util.ArrayList;
import java.util.List;

public class DmykTelephonyManager extends IDmykTelephony.Stub {
    static final String TAG = "DmykMgr";

    private static final int APPTYPE_UNKNOWN = -1;
    private static final int APPTYPE_ICC = 1;
    private static final int APPTYPE_UICC = 2;
    private static final int PHONE_ID_ZERO = 0;
    private static final int PHONE_ID_ONE = 1;

    private static final String APN_URI = "content://telephony/carriers/";
    private static final String MOBILE_NETWORK_V2 = "settings_mobile_network_v2";

    private static DmykTelephonyManager sInstance;

    private Context mContext;
    private Handler mHandler;
    private Handler mTorchHandler;
    private PhoneGlobalsEx mApp;

    /** Watches for changes to the APN db. */
    private ApnChangeObserver mApnObserver;
    private ContentObserver mVoLTESettingObserver0;
    private ContentObserver mVoLTESettingObserver1;

    private int mPhoneCount;
    private boolean mRegisterVolteSwitchChanged0 = false;
    private boolean mRegisterVolteSwitchChanged1 = false;

    private String mCameraId = null;
    private int mFlashlightState = DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;

    private static final int CMD_GET_CELL_LOCATION = 1001;
    private static final int EVENT_GET_CELL_LOCATION_DONE = 1002;
    private static final int CMD_GET_ALL_CELL_INFO = 1003;
    private static final int EVENT_GET_ALL_CELL_INFO_DONE = 1004;

    public static DmykTelephonyManager init(PhoneGlobalsEx app, Context context) {
        synchronized (DmykTelephonyManager.class) {
            if (sInstance == null) {
                sInstance = new DmykTelephonyManager(app, context);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance: " + sInstance);
            }
            return sInstance;
        }
    }

    private DmykTelephonyManager(PhoneGlobalsEx app, Context context) {
        mApp = app;
        mContext = context;

        mPhoneCount = getPhoneCount();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DmykAbsTelephonyManager.ACTION_VOLTE_STATE_SETTING);
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        context.registerReceiver(mReceiver, intentFilter);
        mHandler = new DmykHandler(mContext.getMainLooper());
        mApnObserver = new ApnChangeObserver();
        mVoLTESettingObserver0 = new ContentObserver(new Handler()) {
            public void onChange(boolean selfChange, Uri uri) {
                Log.d(TAG, "mVoLTESettingObserver0 uri = " + uri.toString());
                onVoLTESettingChange(PHONE_ID_ZERO);
            }
        };
        mVoLTESettingObserver1 = new ContentObserver(new Handler()) {
            public void onChange(boolean selfChange, Uri uri) {
                Log.d(TAG, "mVoLTESettingObserver1 uri = " + uri.toString());
                onVoLTESettingChange(PHONE_ID_ONE);
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, mApnObserver);
        mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                DmykAbsTelephonyManager.VOLTE_DMYK_STATE_0), true, mVoLTESettingObserver0);
        mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                DmykAbsTelephonyManager.VOLTE_DMYK_STATE_1), true, mVoLTESettingObserver1);
        publish();
        Log.d(TAG, "register receiver and contentResolver");
    }

    private void publish() {
        ServiceManager.addService("phone_dmyk", this);
    }

    public int getDataState(int phoneId) {
        Log.d(TAG, "getDataState phoneId: " + phoneId);
        Phone phone = getPhone(getSubId(phoneId));
        if (phone != null) {
            return PhoneConstantConversions.convertDataState(
                    phone.getDataConnectionState());
        } else {
            return PhoneConstantConversions.convertDataState(
                    PhoneConstants.DataState.DISCONNECTED);
        }
    }

    public boolean isInternationalNetworkRoaming(int phoneId) {
        Log.d(TAG, "isInternationalNetworkRoaming phoneId: " + phoneId);
        int subId = getSubId(phoneId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return false;
        }
        Phone phone = getPhone(subId);
        if (phone != null && phone.getServiceState().getVoiceRoamingType()
                == ServiceState.ROAMING_TYPE_INTERNATIONAL) {
            return true;
        }
        return false;
    }

    public int getVolteState(int phoneId) {
        int volteState = DmykAbsTelephonyManager.VOLTE_STATE_UNKNOWN;
        boolean volteEnable = isVolteEnabledByPlatform();

        Log.d(TAG, "getVoLTEState() - volteEnable: " + volteEnable
                + " phoneId: " + phoneId);
        if (phoneId == PHONE_ID_ZERO) {
            volteState = android.provider.Settings.System.getInt(
                    mContext.getContentResolver(),
                    DmykAbsTelephonyManager.VOLTE_DMYK_STATE_0, DmykAbsTelephonyManager.VOLTE_STATE_OFF);
        } else if (phoneId == PHONE_ID_ONE) {
            volteState = android.provider.Settings.System.getInt(
                    mContext.getContentResolver(),
                    DmykAbsTelephonyManager.VOLTE_DMYK_STATE_1, DmykAbsTelephonyManager.VOLTE_STATE_OFF);
        }

        Log.d(TAG, "getVoLTEState() - volteState: " + volteState);
        return volteState;
    }

    public Uri getAPNContentUri(int phoneId) {
        Log.d(TAG, "getAPNContentUri phoneId: " + phoneId);
        int preferedId = -1;
        String mccmnc = TelephonyManager.getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
        Log.d(TAG, " mccmnc: " + mccmnc);
        if (mccmnc.isEmpty()) {
            return null;
        }
        int subId = getSubId(phoneId);
        Uri uri = Uri.parse(APN_URI);
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            final String orderBy = "_id";
            final String where = "numeric=\""
                    + mccmnc
                    + "\" AND NOT (type='ia' AND (apn=\"\" OR apn IS NULL))";

            Cursor cursor = mContext.getContentResolver().query(Uri.parse(
                    APN_URI + "preferapn/subId/" + subId), new String[] {
                    "_id"}, where, null,orderBy);
            if (cursor != null) {
                if (cursor.getCount() > 0 && cursor.moveToFirst()) {
                    preferedId = cursor.getInt(0);
                    Log.d(TAG, "preferedId: " + preferedId + ", sub id: " + subId);
                    if (preferedId != -1) {
                        uri =  Uri.parse(APN_URI + preferedId);
                    }
                }
                cursor.close();
            }
        }
        return uri;
    }

    public int getSubId(int phoneId) {
        SubscriptionManager subManager = SubscriptionManager.from(mContext);
        List<SubscriptionInfo> availableSubInfoList = subManager.getActiveSubscriptionInfoList();
        if (availableSubInfoList == null) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        for(int i = 0 ;i < availableSubInfoList.size();i++){
            if(availableSubInfoList.get(i).getSimSlotIndex() == phoneId){
                int subId = availableSubInfoList.get(i).getSubscriptionId();
                Log.d(TAG, "getSubId subId = " + subId);
                return subId;
            }
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }


    public int getCellId(int phoneId) {
        Log.d(TAG, "getCellId phoneId: " + phoneId);
        int cellId = -1;
        int simState = SubscriptionManager.getSimStateForSlotIndex(phoneId);
        if (simState != TelephonyManager.SIM_STATE_ABSENT &&
                simState != TelephonyManager.SIM_STATE_UNKNOWN) {
            cellId = getCellIdForDM(phoneId);
        }
        Log.d(TAG, " getCellId cellId: " + cellId);
        return cellId;
    }

    public void setVolteState(boolean enabled, int phoneId) {
        Log.d(TAG, "setVolteState phoneId: " + phoneId + " enabled: " + enabled);
        setEnhanced4gLteModeSetting(enabled, phoneId);
    }

    public int queryLteCtccSimType(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone == null) {
            return APPTYPE_UNKNOWN;
        }
        switch (phone.getCurrentUiccAppType()) {
            case APPTYPE_SIM:
            case APPTYPE_RUIM:
                return APPTYPE_ICC;
            case APPTYPE_USIM:
            case APPTYPE_CSIM:
            case APPTYPE_ISIM:
                return APPTYPE_UICC;
            default:
                break;
        }
        return APPTYPE_UNKNOWN;
    }

    public boolean isServiceStateInService(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone == null) {
            return false;
        }
        ServiceState ss = phone.getServiceState();
        if (ss != null && (ss.getVoiceRegState() == ServiceState.STATE_IN_SERVICE ||
                ss.getDataRegState() == ServiceState.STATE_IN_SERVICE)) {
            Log.d(TAG, " isServiceStateInService: true");
            return true;
        }
        return false;
    }

    public int getSwitchState(int switchId) {
        int switchState = DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        switch (switchId) {
            case DmykAbsTelephonyManager.SWITCH_WIFI:
                switchState = getWifiState();
                break;
            case DmykAbsTelephonyManager.SWITCH_GPRS:
                switchState = getGprsState();
                break;
            case DmykAbsTelephonyManager.SWITCH_BLUETOOTH:
                switchState = getBlueToothState();
                break;
            case DmykAbsTelephonyManager.SWITCH_GPS:
                switchState = getGpsState();
                break;
            case DmykAbsTelephonyManager.SWITCH_SHOCK:
                switchState = getShockState();
                break;
            case DmykAbsTelephonyManager.SWITCH_SILENT:
                switchState = getSilentState();
                break;
            case DmykAbsTelephonyManager.SWITCH_HOT_SPOT:
                switchState = getHotspotState();
                break;
            case DmykAbsTelephonyManager.SWITCH_FLYING:
                switchState = getFlyingState();
                break;
            case DmykAbsTelephonyManager.SWITCH_FLASH_LIGHT:
                switchState = getFlashlightState();
                break;
            case DmykAbsTelephonyManager.SWITCH_SCREEN:
                switchState = getScreenState();
                break;
            case DmykAbsTelephonyManager.SWITCH_SCREEN_ROTATE:
                switchState = getScreenRotateState();
                break;
            case DmykAbsTelephonyManager.SWITCH_LTE:
                switchState = getLteState();
                break;
            case DmykAbsTelephonyManager.SWITCH_AUTO_BRIGHT:
                switchState = getAutoBrightState();
                break;
        }
        Log.d(TAG, "getSwitchState switchState: " + switchState);
        return switchState;
    }

    /**
     * Get IMSI from CsimRecords
     * @param phoneId
     * @return CDMA IMSI
     * @throws SecurityException if the caller does not have the required permission
     */
    public String getCdmaImsi(int phoneId) {
        try{
            mApp.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
            UiccController uiccController = UiccController.getInstance();
            IccRecords cRecords = uiccController.getIccRecords(phoneId,
                    UiccController.APP_FAM_3GPP2);
            return cRecords.getIMSI();
        } catch (NullPointerException e) {
            Log.e(TAG, "NPE getCdmaImsi.");
            return null;
        }
    }

    /**
     * UNISOC Add for DM to read MEID from CSIM
     * @throws SecurityException if the caller does not have the required permission
     */
    public String readMeidFromCsim(int phoneId) {
        try {
            // throws SecurityException if the caller does not have the required permission
            mApp.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE, null);
            IccRecords r = PhoneFactory.getPhone(phoneId).getUiccCard().getUiccProfile()
                    .getApplication(UiccController.APP_FAM_3GPP2).getIccRecords();
            if (r instanceof RuimRecords) {
                return ((RuimRecords) r).getMeid();
            }
            Log.e(TAG, "Error in readMeidFromCsim.");
        } catch (NullPointerException e) {
            Log.e(TAG, "NullPointerException in readMeidFromCsim.");
        }
        return null;
    }

    /**
     * UNISOC Add for DM to write MEID to CSIM
     * @throws SecurityException if the caller does not have the required permission
     */
    public void writeMeidToCsim(int phoneId, String meid) {
        try {
            // throws SecurityException if the caller does not have the required permission
            mApp.enforceCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE, null);
            // An MEID is 56 bits long (14 hex digits).
            if (meid != null && meid.length() == 14) {
                String csimAid = PhoneFactory.getPhone(phoneId).getUiccCard().getUiccProfile()
                        .getApplication(UiccController.APP_FAM_3GPP2).getAid();
                // UNISOC Bug1181653. See C.S0065 section 5.2.24:
                // EF_ESNME highest-order byte is stored in byte 8 so revert MEID before writing
                String revMeid = "";
                for (int i = 13; i > 0; i = i -2) {
                    revMeid += "" + meid.charAt(i-1) + meid.charAt(i);
                }
                PhoneFactory.getPhone(phoneId).mCi.iccIOForApp(0xDE, 0, "3F007FFF", 0, 0, 7, revMeid
                        , null, csimAid, null);
            } else {
                Log.e(TAG, "Invalid MEID: " + meid);
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "NullPointerException in writeMeidToCsim.");
        }
    }

    private int getWifiState() {
        int wifiState = DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        WifiManager wifiManager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
        if (null == wifiManager) {
            return wifiState;  //SWITCH_STATE_UNKNOWN by default
        }

        int state = wifiManager.getWifiState();

        if (wifiManager.WIFI_STATE_ENABLED == state) {
            wifiState = DmykAbsTelephonyManager.SWITCH_STATE_ON;
        } else if (wifiManager.WIFI_STATE_UNKNOWN != state) {
            wifiState = DmykAbsTelephonyManager.SWITCH_STATE_OFF;
        }
        Log.d(TAG, "getWifiState wifiState = " + wifiState);
        return wifiState;
    }

    private int getGprsState() {
        Phone phone = PhoneFactory.getPhone(getMasterPhoneId());
        if (phone == null) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        boolean retVal = phone.isUserDataEnabled();
        Log.d(TAG, "getGprsState retVal : " + retVal);
        return retVal ? DmykAbsTelephonyManager.SWITCH_STATE_ON :
                DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getBlueToothState() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (null == bluetoothAdapter) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        return bluetoothAdapter.isEnabled() ? DmykAbsTelephonyManager.SWITCH_STATE_ON :
                DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getGpsState() {
        LocationManager locationManager = (LocationManager) mContext.getSystemService(mContext.LOCATION_SERVICE);
        if (null == locationManager) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getShockState() {
        AudioManager audioManager = (AudioManager)mContext.getSystemService(mContext.AUDIO_SERVICE);
        if (null == audioManager) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        return (audioManager.RINGER_MODE_VIBRATE == audioManager.getRingerMode()) ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getSilentState() {
        AudioManager audioManager = (AudioManager)mContext.getSystemService(mContext.AUDIO_SERVICE);
        if (null == audioManager) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        return (audioManager.RINGER_MODE_SILENT == audioManager.getRingerMode()) ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getHotspotState() {
        WifiManager wifiManager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
        if (null == wifiManager) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        return wifiManager.isWifiApEnabled() ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getFlyingState() {
        return (Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0) ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getFlashlightState() {
        mFlashlightState = DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        Log.d(TAG, "getFlashlightState Enter");
        CameraManager cameraManager = (CameraManager)mContext.getSystemService(Context.CAMERA_SERVICE);
        if (null == cameraManager) {
            Log.d(TAG, "getFlashlightState null == cameraManager");
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        tryInitCamera(cameraManager);
        Log.d(TAG, "getFlashlightState Before sleep 50ms");
        SystemClock.sleep(50);

        Log.d(TAG, "getFlashlightState mFlashlightState: " + mFlashlightState);
        cameraManager.unregisterTorchCallback(mTorchCallback);

        return mFlashlightState;
    }

    private final CameraManager.TorchCallback mTorchCallback =
            new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeUnavailable(String cameraId) {
                }

                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    Log.d(TAG, "cameraId: " + cameraId);
                    if (TextUtils.equals(cameraId, mCameraId)) {
                        mFlashlightState = enabled ? DmykAbsTelephonyManager.SWITCH_STATE_ON :
                                DmykAbsTelephonyManager.SWITCH_STATE_OFF;
                        Log.d(TAG, "mFlashlightState: " + mFlashlightState);
                    }
                }
    };

    private void tryInitCamera(CameraManager cameraManager) {
        try {
            mCameraId = getCameraId(cameraManager);
            Log.d(TAG, "tryInitCamera mCameraId: " + mCameraId);
        } catch (Throwable e) {
            Log.e(TAG, "Couldn't initialize.", e);
            return;
        }

        if (mCameraId != null) {
            ensureHandler();
            Log.d(TAG, "registerTorchCallback");
            cameraManager.registerTorchCallback(mTorchCallback, mTorchHandler);
            Log.d(TAG, "registerTorchCallback after");
        }
    }

    private String getCameraId(CameraManager cameraManager) throws CameraAccessException {
        String[] ids = cameraManager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null && flashAvailable
                    && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private void ensureHandler() {
        Log.d(TAG, "ensureHandler Enter");
        if (null == mTorchHandler) {
            Log.d(TAG, "ensureHandler mTorchHandler null");
            HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            mTorchHandler = new Handler(thread.getLooper());
        }
    }

    private int getScreenState() {
        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        if (null == powerManager) {
            return DmykAbsTelephonyManager.SWITCH_STATE_UNKNOWN;
        }

        return powerManager.isScreenOn() ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getScreenRotateState() {
        return (Settings.System.getInt(mContext.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1) ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getLteState() {
        int raf = RadioAccessFamily.RAF_UNKNOWN;
        raf = ProxyController.getInstance().getRadioAccessFamily(getMasterPhoneId());
        Log.d(TAG, "getLTEState raf = " + raf);
        boolean lteEnable = (raf & RadioAccessFamily.RAF_LTE) != 0;
        return lteEnable ? DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getAutoBrightState() {
        return (Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) ?
                DmykAbsTelephonyManager.SWITCH_STATE_ON : DmykAbsTelephonyManager.SWITCH_STATE_OFF;
    }

    private int getPhoneCount() {
        return TelephonyManager.getDefault().getPhoneCount();
    }

    private int getMasterPhoneId() {
        return SubscriptionManager.getPhoneId(
                SubscriptionManager.getDefaultDataSubscriptionId());
    }

    // returns phone associated with the subId.
    private Phone getPhone(int subId) {
        return PhoneFactory.getPhone(SubscriptionController.getInstance().getPhoneId(subId));
    }

    private WorkSource getWorkSource(int uid) {
        String packageName = mContext.getPackageManager().getNameForUid(uid);
        return new WorkSource(uid, packageName);
    }

    /**
     * UNISOC Add for DM to get CellID
     * Return:
     * GSM - Cell ID
     * CDMA - BaseStation ID
     * CDMA+LTE - LTE Cell ID
     */
    private int getCellIdForDM(int phoneId) {
        int cellId = -1;
        WorkSource workSource = getWorkSource(Binder.getCallingUid());
        Phone phone = PhoneFactory.getPhone(phoneId);
        int subId = getSubId(phoneId);
        try {
            Log.d(TAG, "getCellIdForDM phoneId: " + phoneId);
            if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
                // GSM, simply return cell id
                GsmCellLocation gsmCell = (GsmCellLocation) sendRequest(CMD_GET_CELL_LOCATION, null,
                        subId, phone, workSource);
                cellId = gsmCell.getCid();

            } else if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                if (phone.getServiceState().getDataNetworkType() == TelephonyManager.NETWORK_TYPE_LTE) {
                    // CDMA + LTE, return LTE cell id
                    List<CellInfo> cellInfoList = (List<CellInfo>) sendRequest(
                            CMD_GET_ALL_CELL_INFO, null, subId, phone, workSource);
                    for (CellInfo c: cellInfoList) {
                        if (c.isRegistered() && (c instanceof CellInfoLte)) {
                            cellId = ((CellInfoLte) c).getCellIdentity().getCi();
                            return cellId;
                        }
                    }
                } else {
                    // PS register on CDMA, return BaseStationId as cell id
                    CdmaCellLocation cdmaCell = (CdmaCellLocation) sendRequest(CMD_GET_CELL_LOCATION,
                            null, subId, phone, workSource);
                    cellId = cdmaCell.getBaseStationId();
                }
            }
        } catch (NullPointerException e) {
            Log.d(TAG, "NPE getCellIdForDM.");
        }
        return cellId;
    }

    /**
     * Posts the specified command to be executed on the main thread,
     * waits for the request to complete, and returns the result.
     * @see #sendRequestAsync
     */
    private Object sendRequest(
            int command, Object argument, Integer subId, Phone phone, WorkSource workSource) {
        if (Looper.myLooper() == mHandler.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread.");
        }

        MainThreadRequest request = null;
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && phone != null) {
            request = new MainThreadRequest(argument, phone, subId, workSource);
        } else if (phone != null) {
            request = new MainThreadRequest(argument, phone, workSource);
        } else {
            request = new MainThreadRequest(argument, subId, workSource);
        }

        Message msg = mHandler.obtainMessage(command, request);
        msg.sendToTarget();

        // Wait for the request to complete
        synchronized (request) {
            while (request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is complete
                }
            }
        }
        return request.result;
    }

    /**
     * A request object for use with {@link ＤmykHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The argument to use for the request */
        public Object argument;
        /** The result of the request that is run on the main thread */
        public Object result;
        // The subscriber id that this request applies to. Defaults to
        // SubscriptionManager.INVALID_SUBSCRIPTION_ID
        public Integer subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        // In cases where subId is unavailable, the caller needs to specify the phone.
        public Phone phone;

        public WorkSource workSource;

        public MainThreadRequest(Object argument) {
            this.argument = argument;
        }

        MainThreadRequest(Object argument, Phone phone, WorkSource workSource) {
            this.argument = argument;
            if (phone != null) {
                this.phone = phone;
            }
            this.workSource = workSource;
        }

        MainThreadRequest(Object argument, Integer subId, WorkSource workSource) {
            this.argument = argument;
            if (subId != null) {
                this.subId = subId;
            }
            this.workSource = workSource;
        }

        MainThreadRequest(Object argument, Phone phone, Integer subId, WorkSource workSource) {
            this.argument = argument;
            if (phone != null) {
                this.phone = phone;
            }
            if (subId != null) {
                this.subId = subId;
            }
            this.workSource = workSource;
        }
    }

    private ImsManager getImsManager() {
        return ImsManager.getInstance(mApp, getMasterPhoneId());
    }
    /**
     * @param valid phoneId
     * @return ImsManager instance. Must check null before use.
     */
    private ImsManager getImsManagerByPhoneId(int phoneId) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return ImsManager.getInstance(mApp, phoneId);
        } else {
            return null;
        }
    }

    private boolean isEnhanced4gLteModeSettingEnabledByUser(int phoneId) {
        ImsManager imsManager = getImsManagerByPhoneId(phoneId);
        if (imsManager != null) {
            return imsManager.isEnhanced4gLteModeSettingEnabledByUser();
        } else {
            return false;
        }
    }

    private void setEnhanced4gLteModeSetting(boolean enabled, int phoneId) {
        ImsManager imsManager = getImsManagerByPhoneId(phoneId);
        if (imsManager != null) {
            imsManager.setEnhanced4gLteModeSetting(enabled);
        }
    }

    private boolean isVolteEnabledByPlatform() {
        ImsManager imsManager = getImsManager();
        return imsManager.isVolteEnabledByPlatform();
    }
    /**
     * Put value in Settings when VoLTE state changed .
     */
    private void putVoLteState(int phoneId) {
        boolean enhanced4gLteMode = isEnhanced4gLteModeSettingEnabledByUser(phoneId);
        Log.d(TAG, "putVoLTEState: enhanced4gLteMode: " + enhanced4gLteMode);
        if (phoneId == PHONE_ID_ZERO) {
            android.provider.Settings.System.putInt(
                    mContext.getContentResolver(),
                    DmykAbsTelephonyManager.VOLTE_DMYK_STATE_0,
                    enhanced4gLteMode ? DmykAbsTelephonyManager.VOLTE_STATE_ON
                            : DmykAbsTelephonyManager.VOLTE_STATE_OFF);
        } else {
            android.provider.Settings.System.putInt(
                    mContext.getContentResolver(),
                    DmykAbsTelephonyManager.VOLTE_DMYK_STATE_1,
                    enhanced4gLteMode ? DmykAbsTelephonyManager.VOLTE_STATE_ON
                            : DmykAbsTelephonyManager.VOLTE_STATE_OFF);
        }
    }

    private void onVoLTESettingChange(int phoneId) {
        Log.d(TAG, "onVoLTESettingChange phoneId: " + phoneId);
        Intent intent = new Intent(DmykAbsTelephonyManager.ACTION_VOLTE_STATE_CHANGE);
        intent.putExtra(DmykAbsTelephonyManager.EXTRA_SIM_PHONEID, phoneId);
        intent.setPackage(DmykAbsTelephonyManager.PACKAGE_NAME);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mContext.sendBroadcast(intent);
        Log.d(TAG, "sendBroadcast VOLTE_STATE_CHANGE");
    }

    private Uri getNotifyContentUri(Uri uri, boolean usingSubId, int subId) {
        return (usingSubId) ? Uri.withAppendedPath(uri, "" + subId) : uri;
    }

    private ContentObserver mEnhancedLTEObserver0 = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            Log.d(TAG, "onVoLTESettingChangeByUser0");
            putVoLteState(PHONE_ID_ZERO);
        }
    };

    private ContentObserver mEnhancedLTEObserver1 = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            Log.d(TAG, "onVoLTESettingChangeByUser1");
            putVoLteState(PHONE_ID_ONE);
        }
    };

    private void rigisterVolteSwitchChanged() {
        if (!mRegisterVolteSwitchChanged0) {
            int subId0 = getSubId(PHONE_ID_ZERO);
            if (subId0 != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.d(TAG, "rigisterVolteSwitchChanged0");
                mContext.getContentResolver().registerContentObserver(getNotifyContentUri(
                        SubscriptionManager.ADVANCED_CALLING_ENABLED_CONTENT_URI, true, subId0),
                        true, mEnhancedLTEObserver0);
                mRegisterVolteSwitchChanged0 = true;
            }
        }
        if (!mRegisterVolteSwitchChanged1) {
            int subId1 = getSubId(PHONE_ID_ONE);
            if (subId1 != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.d(TAG, "rigisterVolteSwitchChanged1");
                mContext.getContentResolver().registerContentObserver(getNotifyContentUri(
                        SubscriptionManager.ADVANCED_CALLING_ENABLED_CONTENT_URI, true, subId1),
                        true, mEnhancedLTEObserver1);
                mRegisterVolteSwitchChanged1 = true;
            }
        }
    }

    private class DmykHandler extends Handler{
        public DmykHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage = " + msg);
            MainThreadRequest request;
            Message onCompleted;
            AsyncResult ar;
            switch (msg.what) {
                    case CMD_GET_CELL_LOCATION:
                        request = (MainThreadRequest) msg.obj;
                        WorkSource ws = (WorkSource) request.workSource;
                        Phone phone = request.phone;
                        phone.getCellLocation(ws, obtainMessage(EVENT_GET_CELL_LOCATION_DONE, request));
                        break;
                    case EVENT_GET_CELL_LOCATION_DONE:
                        ar = (AsyncResult) msg.obj;
                        request = (MainThreadRequest) ar.userObj;
                        if (ar.exception == null) {
                            request.result = ar.result;
                        } else {
                            phone = request.phone;
                            request.result = (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA)
                                    ? new CdmaCellLocation() : new GsmCellLocation();
                        }

                        synchronized (request) {
                            request.notifyAll();
                        }
                        break;
                    case CMD_GET_ALL_CELL_INFO:
                        request = (MainThreadRequest) msg.obj;
                        onCompleted = obtainMessage(EVENT_GET_ALL_CELL_INFO_DONE, request);
                        request.phone.requestCellInfoUpdate(request.workSource, onCompleted);
                        break;
                    case EVENT_GET_ALL_CELL_INFO_DONE:
                        ar = (AsyncResult) msg.obj;
                        request = (MainThreadRequest) ar.userObj;
                        // If a timeout occurs, the response will be null
                        request.result = (ar.exception == null && ar.result != null)
                                ? ar.result : new ArrayList<CellInfo>();
                        synchronized (request) {
                            request.notifyAll();
                        }
                        break;
                    default:
                        Log.w(TAG, "DmykHandler: unexpected message code: " + msg.what);
                        break;
            }
        }
    }

    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mHandler);
        }

        public void onChange(boolean selfChange) {
            Uri uri =  Uri.parse(APN_URI);
            int phoneId = SubscriptionManager.from(mContext).getDefaultDataPhoneId();
            if (SubscriptionManager.isValidPhoneId(phoneId)) {
                uri = getAPNContentUri(phoneId);
                Log.d(TAG, "uri " + uri );
            }
            Intent intent = new Intent(DmykAbsTelephonyManager.ACTION_APN_STATE_CHANGE);
            intent.setPackage(DmykAbsTelephonyManager.PACKAGE_NAME);
            intent.setData(uri);
            mContext.sendBroadcast(intent);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "receive action: " + action);

            if (DmykAbsTelephonyManager.ACTION_VOLTE_STATE_SETTING.equals(action)) {
                int phoneId = intent.getIntExtra(DmykAbsTelephonyManager.EXTRA_SIM_PHONEID, -1);
                Log.d(TAG, " phoneId: " + phoneId);
                Intent volteStateSetting = new Intent(Intent.ACTION_MAIN);
                ComponentName mobileNetworkSettingsComponent = new ComponentName(
                    context.getString(R.string.mobile_network_settings_package),
                    context.getString(R.string.mobile_network_settings_class));
                volteStateSetting.setComponent(mobileNetworkSettingsComponent);
                volteStateSetting.putExtra(Settings.EXTRA_SUB_ID, getSubId(phoneId));
                volteStateSetting.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(volteStateSetting);
            } else if (TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
                int[] enh4glteMode = new int[mPhoneCount];
                int masterState = DmykAbsTelephonyManager.VOLTE_STATE_UNKNOWN;
                int masterPhoneId = getMasterPhoneId();
                Log.d(TAG, "default masterPhoneId: " + masterPhoneId);
                if (masterPhoneId == -1) {
                    return;
                }
                for (int i = 0; i < mPhoneCount; i++) {
                    enh4glteMode[i] = isEnhanced4gLteModeSettingEnabledByUser(i) ? 1 : 0;
                    if (masterPhoneId == i) {
                        masterState = enh4glteMode[i];
                    }
                }
                Log.d(TAG, " masterState: " + masterState);

                for (int i = 0; i < mPhoneCount; i++) {
                    if (getVolteState(i) != enh4glteMode[i]) {
                        putVoLteState(i);
                    }
                }

            } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                Intent conn = new Intent(DmykAbsTelephonyManager.ACTION_CONNECTIVITY_CHANGE);
                conn.setPackage(DmykAbsTelephonyManager.PACKAGE_NAME);
                mContext.sendBroadcast(conn);
            } else if (TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED.equals(action)) {
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1);
                Log.d(TAG, "ACTION_DEFAULT_SUBSCRIPTION_CHANGED subId: " + subId);

                SubscriptionManager subManager = SubscriptionManager.from(mContext);
                List<SubscriptionInfo> subInfoList = subManager.getActiveSubscriptionInfoList();
                if (subId == -1 || subInfoList == null || subInfoList.size() < 1) {
                    return;
                }

                rigisterVolteSwitchChanged();
            }
        }
    };
}

package com.android.phone;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ServiceState;
import android.util.Log;
import android.os.SystemProperties;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import com.android.ims.ImsManager;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.GsmCdmaPhoneEx;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SubscriptionController;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.telephony.CarrierConfigManager;
import android.content.BroadcastReceiver;
import android.telephony.CarrierConfigManagerEx;
import com.android.internal.telephony.TelephonyIntents;
import com.android.ims.internal.ImsCallForwardInfoEx;
import android.telephony.PhoneStateListener;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import android.util.ArrayMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class CallForwardHelper {
    private static final String TAG = CallForwardHelper.class.getSimpleName();
    private static CallForwardHelper mInstance;

    //query mode
    private static final int CFU_MODE_QUERY_ONLY_ONCE = 0;
    private static final int CFU_MODE_ALWAYS_QUERY = 1;
    private static final int CFU_MODE_ALWAYS_NOT_QUERY = 2;

    //query status
    private static final int CFU_INIT_NOT_QUERY_SUCCESS = 0;
    private static final int CFU_INIT_QUERY_SUCCESS = 1;
    private static final int CFU_QUERY_SUCCESS = 2;

    private static final int CFI_VOICE_INDEX = 0;
    private static final int CFI_VIDEO_INDEX = 1;

    private static final int EVENT_FORWARDING_GET_COMPLETED = 100;
    private static final int EVENT_ROOTNODE_QUERY_COMPLETED = 101;

    private static final String REFRESH_VIDEO_CF_NOTIFICATION_ACTION =
            "android.intent.action.REFRESH_VIDEO_CF_NOTIFICATION";
    /* SPRD: add for bug653709 @{ */
    private final static String DISSMISS_VIDEO_CF_NOTIFICATION_ACTION =
            "android.intent.action.DISMISS_VIDEO_CF_NOTIFICATION";

    private static final String KEY_CFU_VOICE_VALUE = "cfu_voice_value";
    private static final String KEY_CFU_VIDEO_VALUE = "cfu_video_value";
    private static final String KEY_ICC_ID = "cfu_icc_id";
    private final static String VIDEO_CF_SUB_ID = "video_cf_flag_with_subid";
    private static final String VIDEO_CF_STATUS = "video_cf_status";

    private static final int CALL_FORWARD_NOTIFICATION = 4;
    private static final int NOTIFICATION_ID_VIDEO_CF = 7;

    private static final int[] sCallfwdIcon = new int[]{
            R.drawable.stat_sys_phone_call_forward_sub1_ex,
            R.drawable.stat_sys_phone_call_forward_sub2_ex,
    };
    private static final int[] sVideoCallfwdIcon = new int[]{
            R.drawable.stat_sys_phone_video_call_forward_sub1_ex,
            R.drawable.stat_sys_phone_video_call_forward_sub2_ex,
    };

    private UserManager mUserManager;
    private NotificationManager mNotificationManager;
    private PhoneGlobalsEx mApplication;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private boolean mIsImsListenerRegistered;
    private IImsServiceEx mIImsServiceEx;
    private Map<Integer, CallForwardPhoneStateListener> mPhoneStateListeners =
            new ArrayMap<Integer, CallForwardPhoneStateListener>();
    private Map<Integer, CallForwardHelperInfo> mCallForwardHelperInfoMap =
            new ArrayMap<Integer, CallForwardHelperInfo>();
    private List<CallForwardHelperInfo> mPendingCfuQuery = new ArrayList<CallForwardHelperInfo>();

    private SharedPreferences mPreference;
    private Editor mEditor;
    /* UNISOC: add for Bug 1095038 @{ */
    private NetworkStateReceiver mNetworkStateReceiver;
    //private RadioInteractor mRi;
    private ConnectivityManager mConnMgr;
    /* @} */
    private static final int EVENT_IMS_CAPABILITIES_CHANGED = 102;
    private boolean mIsImsSupported = true;
    static class CallForwardHelperInfo {
        int mPhoneId;
        int mSubId;
        String mIccID;
        boolean[] mCfiStatus = {false, false};//voice,video
        boolean mNeedQuery = false;
        boolean mShowVideoStatus = false;
        int mHasQuerySuccess = CFU_INIT_NOT_QUERY_SUCCESS;
        boolean mImsRegister = false;
        boolean mHasQueryRootNode = false;
        boolean mHasUtRequired = false;

        public CallForwardHelperInfo(int subId, int phoneId, String iccid) {

            mSubId = subId;
            mPhoneId = phoneId;
            mIccID = iccid;
        }

        public void updateNeedQueryStatus(boolean isNeedQuery) {
            mNeedQuery = isNeedQuery;
            mHasQuerySuccess =
                    isNeedQuery ? CFU_INIT_NOT_QUERY_SUCCESS : CFU_INIT_QUERY_SUCCESS;
        }

        public void updateCfiStatus(int cfiIdex, boolean value) {
            mCfiStatus[cfiIdex] = value;
        }

        public boolean getQueryRootNodeStatus() {
            return mHasQueryRootNode;
        }

        public void setQueryRootNodeStatus(boolean value) {
            mHasQueryRootNode = value;
        }

        @Override
        public String toString() {
            return "[CallForwardHelperInfo: ] mPhoneId = " + mPhoneId
                    + " mSubId = " + mSubId + " mIccID = " + mIccID
                    + " voiceStatus = " + mCfiStatus[0]
                    + " videoStatus = " + mCfiStatus[1]
                    + " mNeedQuery = " + mNeedQuery
                    + " mShowVideoStatus = " + mShowVideoStatus
                    + " mHasQuerySuccess = " + mHasQuerySuccess
                    + " mImsRegister = " + mImsRegister
                    + " mHasQueryRootNode = " + mHasQueryRootNode;
        }

    };

    public static synchronized CallForwardHelper getInstance() {
        if (mInstance == null) {
            mInstance = new CallForwardHelper();
        }
        return mInstance;
    }

    private CallForwardHelper() {
        mApplication = PhoneGlobalsEx.getInstance();
        mSubscriptionManager = (SubscriptionManager) mApplication.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mNotificationManager = (NotificationManager) mApplication.getSystemService(Context.NOTIFICATION_SERVICE);
        mTelephonyManager = (TelephonyManager) mApplication.getSystemService(Context.TELEPHONY_SERVICE);

        mPreference = mApplication.getApplicationContext().getSharedPreferences(TAG,
                mApplication.getApplicationContext().MODE_PRIVATE);
        mEditor = mPreference.edit();

        IntentFilter intentFilter = new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intentFilter.addAction(REFRESH_VIDEO_CF_NOTIFICATION_ACTION);
        intentFilter.addAction(DISSMISS_VIDEO_CF_NOTIFICATION_ACTION);
        mApplication.registerReceiver(mReceiver, intentFilter);

        mSubscriptionManager.addOnSubscriptionsChangedListener(
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        updatePhoneStateListeners();
                    }
                });

        /* UNISOC: add for Bug 1095038 @{ */
        if(mApplication.getApplicationContext()
                .getResources().getBoolean(R.bool.config_ut_need_query_root_node)) {
            mConnMgr = (ConnectivityManager) mApplication
                    .getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            mNetworkStateReceiver = new NetworkStateReceiver();
        }
        /* @} */
        //add  for unisoc 1214571
        mIsImsSupported = mApplication.getApplicationContext().getResources().getBoolean(
                com.android.internal.R.bool.config_device_wfc_ims_available) ||
                SystemProperties.getBoolean("persist.vendor.sys.volte.enable", false);
    }

    public void updatePhoneStateListeners() {
        List<SubscriptionInfo> subInfos = mSubscriptionManager.getActiveSubscriptionInfoList();
        log("updatePhoneStateListeners subscriptions: " + subInfos);
        Iterator<Map.Entry<Integer, CallForwardPhoneStateListener>> entries = mPhoneStateListeners
                .entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, CallForwardPhoneStateListener> entry = entries.next();
            int subId = entry.getKey();
            if (subInfos == null || !containsSubId(subInfos, subId)) {
                log("updatePhoneStateListeners: Hide the outstanding notifications.");
                // Hide the outstanding notifications.
                updateCfi(subId, false, false, false);
                updateCfi(subId, false, true, false);

                // Listening to LISTEN_NONE removes the listener.
                mTelephonyManager.createForSubscriptionId(subId).listen(entry.getValue(),
                        PhoneStateListener.LISTEN_NONE);
                CallForwardHelperInfo cfi  = mCallForwardHelperInfoMap.get(subId);
                if(cfi != null) {
                    int phoneId =cfi.mPhoneId;
                    if (mIsImsSupported && PhoneFactory.getPhone(phoneId) != null &&
                            PhoneFactory.getPhone(phoneId).getServiceStateTracker() != null) {
                        PhoneFactory.getPhone(phoneId).getServiceStateTracker()
                                .unregisterForImsCapabilityChanged(mHandler);
                    }
                }
                mCallForwardHelperInfoMap.remove(subId);
                entries.remove();
            } else {
                log("updatePhoneStateListeners: update CF notifications.");
                CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(subId);
                if (cfi != null) {
                    updateCfi(cfi, true);
                }
            }
        }
        if (subInfos == null) {
            return;
        }

        // Register new phone listeners for active subscriptions.
        for (int i = 0; i < subInfos.size(); i++) {
            int subId = subInfos.get(i).getSubscriptionId();
            if (!mPhoneStateListeners.containsKey(subId)) {
                int phoneId = SubscriptionManager.getPhoneId(subId);

                CallForwardHelperInfo cfiHelperInfo = new CallForwardHelperInfo(subId, phoneId,
                        subInfos.get(i).getIccId());
                initCfiInfo(cfiHelperInfo);
                // set up mapping between sudId and CallForwardHelperInfo
                mCallForwardHelperInfoMap.put(subId, cfiHelperInfo);

                CallForwardPhoneStateListener listener = new CallForwardPhoneStateListener();
                mTelephonyManager.createForSubscriptionId(subId).listen(listener,
                        PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR
                                | PhoneStateListener.LISTEN_SERVICE_STATE);

                mPhoneStateListeners.put(subId, listener);
                if (mIsImsSupported && SubscriptionManager.isValidPhoneId(phoneId) &&
                        PhoneFactory.getPhone(phoneId) != null &&
                        PhoneFactory.getPhone(phoneId).getServiceStateTracker() != null) {
                    PhoneFactory.getPhone(phoneId).getServiceStateTracker()
                            .registerForImsCapabilityChanged(mHandler,
                                    EVENT_IMS_CAPABILITIES_CHANGED, subId);
                }
                log("updatePhoneStateListeners add listener: " + listener + " subId: " + subId);
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("onReceive action: " + action + " intent: " + intent);
            if (TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
                for (Iterator<Map.Entry<Integer, CallForwardHelperInfo>> it = mCallForwardHelperInfoMap.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Integer, CallForwardHelperInfo> item = it.next();
                    CallForwardHelperInfo cfi = item.getValue();
                    if (cfi.mNeedQuery) {
                        cfi.mNeedQuery = !queryAllCfu(cfi);
                    }
                }
            } else if (DISSMISS_VIDEO_CF_NOTIFICATION_ACTION.equals(action) ||
                    REFRESH_VIDEO_CF_NOTIFICATION_ACTION.equals(action)) {

                int subId = intent.getIntExtra(VIDEO_CF_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(subId);
                cfi.mCfiStatus[CFI_VIDEO_INDEX] = intent.getBooleanExtra(VIDEO_CF_STATUS, false);
                updateCfi(cfi, CFI_VIDEO_INDEX, false);
                saveCfiStatus(cfi);
            } else if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(action)) {
                int CONFIG_CHANGED_SUB = CarrierConfigManagerEx.CONFIG_SUBINFO;
                if (intent.getIntExtra(CarrierConfigManagerEx.CARRIER_CONFIG_CHANGED_TYPE, -1) == CONFIG_CHANGED_SUB) {
                    int phoneId = intent.getIntExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, -1);
                    int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                    if(SubscriptionController.getInstance() != null){
                        subId = SubscriptionController.getInstance().getSubIdUsingPhoneId(phoneId);
                    }

                    boolean isHideVideoCF = false;
                    CarrierConfigManager configManager = (CarrierConfigManager) mApplication.getApplicationContext().getSystemService(
                            mApplication.getApplicationContext().CARRIER_CONFIG_SERVICE);
                    if (configManager != null && configManager.getConfigForSubId(subId) != null) {
                        isHideVideoCF = configManager.getConfigForSubId(subId).getBoolean(
                                CarrierConfigManagerEx.KEY_HIDE_VIDEO_CALL_FORWARD);
                    }

                    CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(subId);
                    log("ACTION_CARRIER_CONFIG_CHANGED cfi: " + cfi + " isHideVideoCF: " + isHideVideoCF);
                    ImsManager imsManager = ImsManager.getInstance(mApplication, phoneId);
                    if (cfi != null && imsManager != null) {
                        cfi.mShowVideoStatus = imsManager.isVolteEnabledByPlatform()
                                && imsManager.isVtEnabledByPlatform()
                                && mApplication.getResources().getBoolean(R.bool.config_video_callforward_support)
                                && !isHideVideoCF;
                        if (cfi.mShowVideoStatus) {
                            updateCfi(cfi, CFI_VIDEO_INDEX, false);
                        } else {
                            updateCfi(subId, false, true, false);
                        }
                    }
                }
            }
        }
    };

    private class CallForwardPhoneStateListener extends PhoneStateListener {
        public CallForwardPhoneStateListener() {
            super();
        }

        @Override
        public void onCallForwardingIndicatorChanged(boolean cfi) {
            log("onCallForwardingIndicatorChanged(): " + this.mSubId + " " + cfi + " this: " + this);

            CallForwardHelperInfo callForwardHelperInfo = mCallForwardHelperInfoMap.get(mSubId);
            if (callForwardHelperInfo != null) {
                callForwardHelperInfo.updateCfiStatus(CFI_VOICE_INDEX, cfi);
                saveCfiStatus(callForwardHelperInfo);
                updateCfi(callForwardHelperInfo, CFI_VOICE_INDEX,false);
            }
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {

            log("onServiceStateChanged(), state: "
                    + serviceState.getState() + " DataRegState: " + serviceState.getDataRegState());
            if (serviceState.getState() == ServiceState.STATE_IN_SERVICE
                    || serviceState.getDataRegState() == ServiceState.STATE_IN_SERVICE) {

                CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(this.mSubId);
                if (cfi != null && cfi.mNeedQuery) {
                    cfi.mNeedQuery = !queryAllCfu(cfi);
                }
//                if(!mIsImsListenerRegistered){
//                    tryRegisterImsListener();
//                }
            }
        }
    };

    private boolean containsSubId(List<SubscriptionInfo> subInfos, int subId) {
        if (subInfos == null) {
            return false;
        }

        for (int i = 0; i < subInfos.size(); i++) {
            if (subInfos.get(i).getSubscriptionId() == subId) {
                return true;
            }
        }
        return false;
    }

    void initCfiInfo(CallForwardHelperInfo callForwardHelperInfo) {
        int queryMode = SystemProperties.getInt("persist.sys.callforwarding", CFU_MODE_QUERY_ONLY_ONCE);
        boolean isSavedIccid = checkIccId(callForwardHelperInfo.mIccID, callForwardHelperInfo.mPhoneId);

        if (isSavedIccid) {
            callForwardHelperInfo.updateCfiStatus(CFI_VOICE_INDEX, mPreference.getBoolean(KEY_CFU_VOICE_VALUE + callForwardHelperInfo.mPhoneId, false));
            callForwardHelperInfo.updateCfiStatus(CFI_VIDEO_INDEX, mPreference.getBoolean(KEY_CFU_VIDEO_VALUE + callForwardHelperInfo.mPhoneId, false));
        }
        if (queryMode == CFU_MODE_QUERY_ONLY_ONCE) {
            callForwardHelperInfo.updateNeedQueryStatus(!isSavedIccid);//not query when isSaveIccid is true
        } else if (queryMode == CFU_MODE_ALWAYS_QUERY) {
            callForwardHelperInfo.updateNeedQueryStatus(true);
        } else {
            callForwardHelperInfo.updateNeedQueryStatus(false);
        }
        saveIccId(callForwardHelperInfo);
        log("initCfiInfo queryMode: " + queryMode + " cfi: "+callForwardHelperInfo);
    }

    private boolean queryAllCfu(CallForwardHelperInfo cfi) {

        GsmCdmaPhoneEx gsmCdmaPhoneEx = (GsmCdmaPhoneEx) PhoneFactory.getPhone(cfi.mPhoneId);
        if (gsmCdmaPhoneEx == null) {
            return false;
        }

        /* UNISOC: add for Bug 1095038 @{ */
        if(mApplication.getApplicationContext()
                .getResources().getBoolean(R.bool.config_ut_need_query_root_node)) {
            int defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (!SubscriptionManager.isValidSubscriptionId(defaultDataSubId)) {
                Log.d(TAG, "queryAllCfu: Invalid defaultDataSubId!!!");
                return false;
            }

            CallForwardHelperInfo defaultDataSubIdCfi = mCallForwardHelperInfoMap.get(defaultDataSubId);
            log("queryAllCfu(): defaultDataSubId = " + defaultDataSubId
                    + ", mTelephonyManager.isDataEnabled() = " + mTelephonyManager.isDataEnabled()
                    + ", defaultDataSubIdCfi = " + defaultDataSubIdCfi);
            if (mTelephonyManager.isDataEnabled()
                    && defaultDataSubIdCfi != null && !defaultDataSubIdCfi.getQueryRootNodeStatus()) {
                synchronized (mPendingCfuQuery) {
                    return mPendingCfuQuery.add(cfi);
                }
            }
        }
        /* @} */

        boolean isInService = (gsmCdmaPhoneEx.getServiceState().getState() == ServiceState.STATE_IN_SERVICE ||
                gsmCdmaPhoneEx.getServiceState().getDataRegState() == ServiceState.STATE_IN_SERVICE);
        boolean isSetDefaultData = SubscriptionManager.isValidSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId());
        //add  for unisoc 1214571 if imsService will startup,
        // wait until imsAvailable to perform call forward query.
        boolean canQueryCfu = mIsImsSupported ? cfi.mHasUtRequired : true;
        log("queryAllCfu: isInService: " + isInService + " isSetDefaultData: " +
                isSetDefaultData + "cfi: " + cfi + "canQueryCfu:" + canQueryCfu);
        if (isInService && isSetDefaultData && canQueryCfu) {
            synchronized (mPendingCfuQuery) {
                if (mPendingCfuQuery.isEmpty()) {
                    mPendingCfuQuery.add(cfi);
                    doQuery(cfi);
                    return true;
                } else {
                    return mPendingCfuQuery.add(cfi);
                }
            }
        }
        return false;
    }

    private void doQuery(CallForwardHelperInfo cfi) {
        log("doQuery: subId: " + cfi.mSubId);
        GsmCdmaPhoneEx gsmCdmaPhoneEx = (GsmCdmaPhoneEx) PhoneFactory.getPhone(cfi.mPhoneId);

        if (gsmCdmaPhoneEx != null) {
            if (gsmCdmaPhoneEx.isImsAvailable()) {
                gsmCdmaPhoneEx.getCallForwardingOption(
                        CommandsInterface.CF_REASON_UNCONDITIONAL,
                        CommandsInterface.SERVICE_CLASS_VOICE | CommandsInterface.SERVICE_CLASS_DATA,//3:voice and video
                        null,
                        mHandler.obtainMessage(EVENT_FORWARDING_GET_COMPLETED, cfi.mSubId,
                                CommandsInterface.SERVICE_CLASS_VOICE | CommandsInterface.SERVICE_CLASS_DATA));
            } else {
                gsmCdmaPhoneEx.getCallForwardingOption(
                        CommandsInterface.CF_REASON_UNCONDITIONAL,
                        mHandler.obtainMessage(EVENT_FORWARDING_GET_COMPLETED, cfi.mSubId,
                                CommandsInterface.SERVICE_CLASS_VOICE));
            }
        }
    }

    private void doPendingQuery() {
        synchronized (mPendingCfuQuery) {
            Iterator<CallForwardHelperInfo> it = mPendingCfuQuery.iterator();
            if (it.hasNext()) {
                CallForwardHelperInfo cfi = it.next();
                doQuery(cfi);
            }
        }
    }

    void updateCfi(CallForwardHelperInfo callForwardHelperInfo, boolean isRefresh) {
        if (callForwardHelperInfo.mHasQuerySuccess != CFU_INIT_NOT_QUERY_SUCCESS) {
            updateCfi(callForwardHelperInfo, CFI_VOICE_INDEX, isRefresh);
            if (callForwardHelperInfo.mShowVideoStatus) {
                updateCfi(callForwardHelperInfo, CFI_VIDEO_INDEX, isRefresh);
            }
        }
    }

    void updateCfi(CallForwardHelperInfo callForwardHelperInfo, int CfiTypeIndex, boolean isRefresh) {
        if (callForwardHelperInfo.mHasQuerySuccess != CFU_INIT_NOT_QUERY_SUCCESS
                && CfiTypeIndex >= 0 && CfiTypeIndex < 2) {
            updateCfi(callForwardHelperInfo.mSubId, callForwardHelperInfo.mCfiStatus[CfiTypeIndex],
                    CfiTypeIndex == CFI_VIDEO_INDEX, isRefresh);
        }
    }

    void updateCfi(int subId, boolean visible, boolean isVideo, boolean isRefresh) {
        log("updateCfi(): " + visible + " , isVideo " + isVideo + " , for subId " + subId);
        if (visible) {
            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                log("Found null subscription info for: " + subId);
                return;
            }

            /* UNISOC: bug 918615 @{ */
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (phoneId == SubscriptionManager.INVALID_PHONE_INDEX) {
                log("phonid == -1 return ");
                return;
            }
            /* @} */
            /* SPRD: add for bug510093 @{ */
            boolean isMultiSimEnabled = mTelephonyManager.isMultiSimEnabled();
            int iconId;
            String notificationTitle;
            String notificationText = mApplication.getString(isVideo ? R.string.sum_vcfu_enabled_indicator : R.string.sum_cfu_enabled_indicator);
            if (isMultiSimEnabled) {
                if (isVideo) {
                    iconId = sVideoCallfwdIcon[phoneId];
                    notificationTitle = subInfo.getDisplayName().toString();
                } else {
                    iconId = sCallfwdIcon[phoneId];
                    notificationTitle = subInfo.getDisplayName().toString();
                }
            } else {
                if (isVideo) {
                    iconId = R.drawable.stat_sys_phone_video_call_forward;
                    notificationTitle = mApplication.getString(R.string.labelVideoCF);
                } else {
                    iconId = R.drawable.stat_sys_phone_call_forward;
                    notificationTitle = mApplication.getString(R.string.labelCF);
                }
            }/* @} */

            String groupId = String.valueOf(phoneId);
            Notification.Builder builder = new Notification.Builder(mApplication)
                    .setSmallIcon(iconId)
                    .setColor(subInfo.getIconTint())
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setChannel(NotificationChannelController.CHANNEL_ID_CALL_FORWARD)
                    .setGroup(groupId)//UNISOC: add for bug960847
                    .setOnlyAlertOnce(isRefresh);

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setClassName("com.android.phone", "com.android.phone.CallFeaturesSetting");
            SubscriptionInfoHelper.addExtrasToIntent(
                    intent, mSubscriptionManager.getActiveSubscriptionInfo(subId));
            // SPRD: add for bug736290
            builder.setContentIntent(PendingIntent.getActivity(mApplication, subId /* requestCode */,
                    intent, PendingIntent.FLAG_UPDATE_CURRENT));
            mNotificationManager.notifyAsUser(
                    Integer.toString(subId) /* tag */,
                    isVideo ? NOTIFICATION_ID_VIDEO_CF : CALL_FORWARD_NOTIFICATION,
                    builder.build(),
                    UserHandle.ALL);
        } else {
            if (isVideo) {
                mNotificationManager.cancelAsUser(
                        Integer.toString(subId) /* tag */,
                        NOTIFICATION_ID_VIDEO_CF,
                        UserHandle.ALL);
            } else {
                mNotificationManager.cancelAsUser(
                        Integer.toString(subId) /* tag */,
                        CALL_FORWARD_NOTIFICATION,
                        UserHandle.ALL);
            }
        }
    }

    boolean checkIccId(String iccId, int phoneId) {
        String savedIccId = mPreference.getString(KEY_ICC_ID + phoneId, null);
        log("checkIccId: iccid: " + iccId + " savedIccId: " + savedIccId);

        if (savedIccId == null || !iccId.equalsIgnoreCase(savedIccId)) {
            return false;//iccid is not saved,need query
        } else {
            return true;//iccid is saved, not need query
        }
    }

    void saveCfiStatus(CallForwardHelperInfo cfiHelperInfo) {
        log("saveCfiStatus: " + cfiHelperInfo);
        mEditor.putBoolean(KEY_CFU_VOICE_VALUE + cfiHelperInfo.mPhoneId, cfiHelperInfo.mCfiStatus[CFI_VOICE_INDEX]);
        mEditor.putBoolean(KEY_CFU_VIDEO_VALUE + cfiHelperInfo.mPhoneId, cfiHelperInfo.mCfiStatus[CFI_VIDEO_INDEX]);
        mEditor.apply();
    }

    void saveIccId(CallForwardHelperInfo cfiHelperInfo) {
        log("saveIccId: cfi " + cfiHelperInfo);
        mEditor.putString(KEY_ICC_ID + cfiHelperInfo.mPhoneId, cfiHelperInfo.mIccID);
        mEditor.apply();
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            log("handleMessage: msg = " + msg);
            switch (msg.what) {

                case EVENT_FORWARDING_GET_COMPLETED:
                    handleGetCFResponse(msg);
                    doPendingQuery();
                    break;
                case EVENT_ROOTNODE_QUERY_COMPLETED:
                    CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(msg.arg1);
                    synchronized (mPendingCfuQuery) {
                        if (cfi != null && mPendingCfuQuery.contains(cfi)) {
                            doQuery(cfi);
                        } else {
                            doPendingQuery();
                        }
                    }
                    break;
                case EVENT_IMS_CAPABILITIES_CHANGED:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    int subId = ar != null ? (Integer)(ar.userObj) :
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                    if (SubscriptionManager.isValidSubscriptionId(subId)) {
                        CallForwardHelperInfo cfinfo= mCallForwardHelperInfoMap.get(subId);
                        if (cfinfo != null && cfinfo.mNeedQuery) {
                            cfinfo.mHasUtRequired = true;
                            cfinfo.mNeedQuery = !queryAllCfu(cfinfo);
                        }

                    }
                    break;
            }
        }
    };

    private void handleGetCFResponse(Message msg) {
        int subId = msg.arg1;
        int serviceClass = msg.arg2;
        CallForwardHelperInfo cfi = null;

        synchronized (mPendingCfuQuery) {
            //UNISOC:modify by bug1137589
            for (CallForwardHelperInfo temp : mPendingCfuQuery) {
                if (temp.mSubId == subId) {
                    cfi = temp;
                    break;
                }
            }
            if (cfi == null) {
                log("handleGetCFResponse cfi is null subId: " + subId);
                return;
            }

            AsyncResult ar = (AsyncResult) msg.obj;
            if (ar.exception == null) {

                if (ar.userObj instanceof Throwable) {
                    log("handleGetCFResponse Throwable: ar.userObj " + ar.userObj);
                } else {
                    if (serviceClass == CommandsInterface.SERVICE_CLASS_VOICE) {
                        CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
                        if (cfInfoArray.length != 0) {
                            for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                                log("handleGetCFResponse, cfInfoArray[" + i + "]="
                                        + cfInfoArray[i]);
                                CallForwardInfo info = cfInfoArray[i];
                                cfi.mCfiStatus[CFI_VOICE_INDEX] = (info.status == 1);
                            }
                        }
                    } else if (serviceClass ==
                            (CommandsInterface.SERVICE_CLASS_VOICE | CommandsInterface.SERVICE_CLASS_DATA)) {
                        //voice + video callforward
                        ImsCallForwardInfoEx cfInfoArray[] = (ImsCallForwardInfoEx[]) ar.result;
                        if (cfInfoArray.length != 0) {
                            for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                                log("handleGetCFResponseEx, cfInfoArray[" + i + "]="
                                        + cfInfoArray[i]);
                                if (isServiceClassVoice(cfInfoArray[i].mServiceClass)) {
                                    cfi.mCfiStatus[CFI_VOICE_INDEX] = (cfInfoArray[i].mStatus == 1);
                                } else {
                                    cfi.mCfiStatus[CFI_VIDEO_INDEX] = (cfInfoArray[i].mStatus == 1);
                                }
                            }
                        }
                    }
                }
            }
            //remove pendingquery after return query result
            mPendingCfuQuery.remove(cfi);
            cfi.mHasQuerySuccess = CFU_QUERY_SUCCESS;
            log("handleGetCFResponse cfi: " + cfi);
            updateCfi(cfi, false);
        }
    }

    private boolean isServiceClassVoice(int serviceClass) {
        return (serviceClass & CommandsInterface.SERVICE_CLASS_VOICE) != 0;
    }

    /*private synchronized void tryRegisterImsListener() {*/
    /*    if (mApplication.getApplicationContext() != null*/
    /*            && ImsManager.isVolteEnabledByPlatform(mApplication)) {*/
    /*        mIImsServiceEx = ImsManagerEx.getIImsServiceEx();*/
    /*        if (mIImsServiceEx != null) {*/
    /*            try {*/
    /*                mIsImsListenerRegistered = true;*/
    /*                mIImsServiceEx.registerforImsRegisterStateChanged(mImsRegisterListener);*/
    /*            } catch (RemoteException e) {*/
    /*                log("regiseterforImsException: " + e);*/
    /*            }*/
    /*        }*/
    /*    }*/
    /*}*/

    /*private final IImsRegisterListener.Stub mImsRegisterListener = new IImsRegisterListener.Stub() {*/
    /*    @Override*/
    /*    public void imsRegisterStateChange(boolean isRegistered) {*/
    /*        log("imsRegisterStateChange: isRegistered: " + isRegistered);*/
    /*        for (Phone phone : PhoneFactory.getPhones()) {*/
    /*            int phoneId = phone.getPhoneId();*/
    /*            int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;*/
    /*            if (SubscriptionController.getInstance() != null) {*/
    /*                subId = SubscriptionController.getInstance().getSubIdUsingPhoneId(phoneId);*/
    /*            }*/
    /*            CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(subId);*/
    /*            boolean imsRegisterForPhone = ImsManagerEx.isImsRegisteredForPhone(phoneId);*/
    /*            log("imsRegisterStateChange: cfi: " + cfi + "  subId: " + subId + " imsRegisterForPhone: " + imsRegisterForPhone);*/
/*
*/

    /*            if (cfi != null) {*/
    /*                if (cfi.mImsRegister != imsRegisterForPhone && cfi.mCfiStatus[CFI_VIDEO_INDEX]) {*/
    /*                    if (imsRegisterForPhone) {*/
    /*                        if (cfi.mShowVideoStatus) {*/
    /*                            updateCfi(cfi, CFI_VIDEO_INDEX, false);*/
    /*                        }*/
    /*                    } else {*/
    /*                        updateCfi(cfi.mSubId, false, true, false);//dismiss video callforwardicon*/
    /*                    }*/
    /*                }*/
    /*                cfi.mImsRegister = imsRegisterForPhone;*/
    /*            }*/
    /*        }*/
    /*    }*/
    /*};*/

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    /* UNISOC: add for Bug 1095038 @{ */
    class NetworkStateReceiver extends BroadcastReceiver {
        public NetworkStateReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION); // "android.net.conn.CONNECTIVITY_CHANGE"
            mApplication.getApplicationContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.d(TAG, "onReceive get CONNECTIVITY_ACTION.");
                NetworkInfo info = (NetworkInfo)intent.getExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                if (info != null && info.getState() == NetworkInfo.State.CONNECTED
                        && info.getType() == ConnectivityManager.TYPE_MOBILE) {

                    int defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                    if (!SubscriptionManager.isValidSubscriptionId(defaultDataSubId)) {
                        Log.d(TAG, "Invalid defaultDataSubId!!!");
                        return;
                    }

                    CallForwardHelperInfo cfi = mCallForwardHelperInfoMap.get(defaultDataSubId);
                    if (cfi != null) {
                        GsmCdmaPhoneEx gsmCdmaPhoneEx = (GsmCdmaPhoneEx) PhoneFactory.getPhone(cfi.mPhoneId);
                        if (gsmCdmaPhoneEx != null
                                && !cfi.getQueryRootNodeStatus()
                                && cfi.mHasQuerySuccess != CFU_QUERY_SUCCESS
                                && mTelephonyManager.isDataEnabled()) {
                            gsmCdmaPhoneEx.queryRootNode(SubscriptionManager.getPhoneId(defaultDataSubId),
                                    mHandler.obtainMessage(EVENT_ROOTNODE_QUERY_COMPLETED, defaultDataSubId, 0));
                            cfi.setQueryRootNodeStatus(true);
                        }
                    }
                }
            }
        }
    }
    /* @} */
}

package com.android.phone.settings.fdn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.*;
import android.telephony.*;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.R;
import com.android.phone.PhoneGlobalsEx;
import android.telephony.TelephonyManager;
import android.provider.Settings;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

/**
 * Show FDN notification helper
 * SPRD: add for bug650920
 */
public class FdnHelper {
    private static final String TAG = FdnHelper.class.getSimpleName();
    private static FdnHelper sInstance;

    private PhoneGlobalsEx mApplication;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;
    private UserManager mUserManager;
    private NotificationManager mNotificationManager;
    private ArrayMap<Integer, PhoneStateListener> mPhoneStateListeners =
            new ArrayMap<Integer, PhoneStateListener>();
    private HashMap<Integer,Boolean> mFirstQuery = new HashMap<Integer,Boolean>();
    private int[] mServiceState;

    private static final String ACTION_FDN_STATUS_CHANGED =
            "android.callsettings.action.FDN_STATUS_CHANGED";
    // Extra on intent containing the id of a subscription.
    private static final String SUB_ID_EXTRA =
            "com.android.phone.settings.SubscriptionInfoHelper.SubscriptionId";
    // Extra on intent containing the label of a subscription.
    private static final String SUB_LABEL_EXTRA =
            "com.android.phone.settings.SubscriptionInfoHelper.SubscriptionLabel";
    private static final String INTENT_EXTRA_SUB_ID = "subid";
    private static final String MSG_FDN_ENABLED = "fdn_enabled";
    private static final String MSG_FDN_SUB_ID = "icc_subId";
    private static final int NO_SUB_ID = -1;
    static final int FDN_NOTIFICATION = 1;
    private static final int[] sFdnIcon = new int[] {
        R.drawable.stat_sys_phone_fdn_sub1,
        R.drawable.stat_sys_phone_fdn_sub2,
    };
    private static final String FDN_PACKAGE_NAME = "com.android.phone";
    private static final String FDN_CLASS_NAME =
            "com.android.phone.settings.fdn.FdnSetting";
    // SPRD: add for bug768028
    private static final String CHANNEL_ID_FDN = "fdn";
    /* UNISOC: modify for bug905004 @{ */
    private SparseArray<String> mLastSimDisplayName = new SparseArray<String>();
    private SparseArray<Integer> mLastSimIconTint = new SparseArray<Integer>();
    private SparseArray<Boolean> mIsSimFdnEnabled = new SparseArray<Boolean>();
    private static final int DEFAULT_ICON_COLOR = -1;
    /* @} */
    // UNISOC: add for bug906354
    private boolean mIsLocaleChanged = false;


    public static FdnHelper getInstance() {
        if (sInstance == null) {
            sInstance = new FdnHelper();
        }
        return sInstance;
    }

    private FdnHelper() {
        mApplication = PhoneGlobalsEx.getInstance();
        mTelephonyManager =
                (TelephonyManager) mApplication.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = SubscriptionManager.from(mApplication);
        mUserManager = (UserManager) mApplication.getSystemService(Context.USER_SERVICE);
        mNotificationManager =
                (NotificationManager) mApplication.getSystemService(Context.NOTIFICATION_SERVICE);
        int phoneCount = mTelephonyManager.getPhoneCount();
        mServiceState = new int[phoneCount];
        for (int i = 0; i < phoneCount; i++) {
            mServiceState[i] = ServiceState.STATE_POWER_OFF;
        }

        mSubscriptionManager.addOnSubscriptionsChangedListener(
                new OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        updatePhoneStateListeners();
                        // UNISOC: add for bug905004
                        refreshFdnNotification(false);
                    }
                });
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_FDN_STATUS_CHANGED);
        // UNISOC: add for bug905004
        intentFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mApplication.registerReceiver(new FdnUpdateReceiver(), intentFilter);
    }

    protected void updatePhoneStateListeners() {
        List<SubscriptionInfo> subscriptions = SubscriptionManager.from(
                mApplication).getActiveSubscriptionInfoList();

        Iterator<Entry<Integer, PhoneStateListener>> itr = mPhoneStateListeners.entrySet().iterator();

        /* UNISOC: add for bug903860 @{ */
        while (itr.hasNext()) {
            Entry<Integer, PhoneStateListener> entry = itr.next();
            int subId = entry.getKey();
            if (subscriptions == null || !containsSubId(subscriptions, subId)) {
                log(" set Listening to LISTEN_NONE and removes the listener.");
                mFirstQuery.put(subId, true);
                displayFdnIcon(false, subId);
                // Listening to LISTEN_NONE removes the listener.
                TelephonyManager telephonyManager =
                        TelephonyManager.from(mApplication).createForSubscriptionId(subId);
                telephonyManager.listen(
                        mPhoneStateListeners.get(subId), PhoneStateListener.LISTEN_NONE);
                itr.remove();
            }
        }
        /* @} */
        if (subscriptions == null) {
            subscriptions = Collections.emptyList();
        }

        // Register new phone listeners for active subscriptions.
        for (int i = 0; i < subscriptions.size(); i++) {
            int subId = subscriptions.get(i).getSubscriptionId();
            if (!mPhoneStateListeners.containsKey(subId)) {
                log("register listener for sub[" + subId + "]");
                PhoneStateListener listener = getPhoneStateListener(subId);
                if (listener != null) {
                    TelephonyManager telephonyManager =
                            TelephonyManager.from(mApplication).createForSubscriptionId(subId);
                    telephonyManager.listen(listener,
                        PhoneStateListener.LISTEN_SERVICE_STATE);
                    mPhoneStateListeners.put(subId, listener);
                }
            }
        }
    }

    private PhoneStateListener getPhoneStateListener(int subId) {
        /* UNISOC: add for Bug 987817 @{ */
        SubscriptionInfo subInfo = SubscriptionManager.from(mApplication)
                .getActiveSubscriptionInfo(subId);
        if (subInfo == null) {
            log("getPhoneStateListener: found null subscription info for: " + subId);
            return null;
        }
        final int phoneId = subInfo.getSimSlotIndex();
        /* @} */
        log("getPhoneStateListener for phone[" + phoneId + "]" + ", [subId: " + subId + "]");
        PhoneStateListener phoneStateListener = new PhoneStateListener() {
            @Override
            public void onServiceStateChanged(ServiceState serviceState) {
                int subId = -1;
                Phone phone = PhoneFactory.getPhone(phoneId);
                if (phone == null || TextUtils.isEmpty(getCurrentIccId(phoneId))) {
                    return;
                }
                subId = phone.getSubId();
                mServiceState[phoneId] = serviceState.getState();
                log("(phoneId" + phoneId + ") onServiceStateChanged(), state: " +
                        serviceState.getState());
                switch (serviceState.getState()) {
                    case ServiceState.STATE_IN_SERVICE:
                        /* UNISOC: add for bug903860 @{ */
                        boolean firstQuery = mFirstQuery.get(subId) == null ?
                               true : mFirstQuery.get(subId);
                        log("(subId" + subId + ") firstQuery =" + firstQuery);
                        if (firstQuery) {
                            //getIccFdnEnable(subId);
                            getIccFdnEnable(phone, subId);
                            mFirstQuery.put(subId, false);
                        }
                        break;
                    case ServiceState.STATE_OUT_OF_SERVICE:
                        // UNISOC: add for bug925681
                        break;
                    case ServiceState.STATE_POWER_OFF:
                        displayFdnIcon(false, subId);
                        mFirstQuery.put(subId, true);
                        /* @} */
                    default:
                        break;
                }
            }
        };
        return phoneStateListener;
    }

    private class FdnUpdateReceiver extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // UNISOC: add for bug1165388
            if (ACTION_FDN_STATUS_CHANGED.equals(action) && !isAirplaneModeOn(context)) {
                int subId = intent.getIntExtra(INTENT_EXTRA_SUB_ID, NO_SUB_ID);
                int phoneId = mSubscriptionManager.getPhoneId(subId);
                Phone phone = PhoneFactory.getPhone(phoneId);
                getIccFdnEnable(phone, subId);
                //getIccFdnEnable(subId);
            /* UNISOC: add for bug905004 @{ */
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                refreshFdnNotification(true);
            }
            /* @} */
        }
    }

    /* UNISOC: add for bug905004 & 906354 @{ */
    private void refreshFdnNotification(boolean isLocaleChanged) {
        List<SubscriptionInfo> subscriptions = SubscriptionManager.from(
                mApplication).getActiveSubscriptionInfoList();
        if (subscriptions == null) {
            subscriptions = Collections.emptyList();
        }
        for (int i = 0; i < subscriptions.size(); i++) {
            int subId = subscriptions.get(i).getSubscriptionId();
            if (mIsSimFdnEnabled.get(subId, false)) {
                String simDisplayName = subscriptions.get(i).getDisplayName().toString();
                String lastSimDisplayName = mLastSimDisplayName.get(subId, "");
                int simIconTint = subscriptions.get(i).getIconTint();
                int lastSimIconTint = mLastSimIconTint.get(subId, DEFAULT_ICON_COLOR);
                Log.d(TAG, "isLocaleChanged " + isLocaleChanged
                        + "; " + lastSimDisplayName + " > " + simDisplayName
                        + "; " + lastSimIconTint + " > " + simIconTint);
                if (!TextUtils.equals(simDisplayName, lastSimDisplayName)
                        || lastSimIconTint != simIconTint || isLocaleChanged) {
                    mIsLocaleChanged = isLocaleChanged;
                    displayFdnIcon(true, subId);
                    mIsLocaleChanged = false;
                }
            }
        }
    }
    /* @} */

    final Handler mHandler = new Handler(Looper.getMainLooper()){

        public void handleMessage(Message msg) {
            if ((msg != null) && (msg.getData() != null)) {
                boolean fdnEnbaled = msg.getData().getBoolean(MSG_FDN_ENABLED, false);
                int subId = msg.getData().getInt(MSG_FDN_SUB_ID, NO_SUB_ID);;
                log("Handler fdnEnbaled = " + fdnEnbaled + ", subId = " + subId);
                displayFdnIcon(fdnEnbaled, subId);
            }
        }

    };

    private void getIccFdnEnable(Phone phone, int id) {
        if (phone == null) {
            return;
        }
        final int subId = id;
        Thread mThread = new Thread() {
            public void run() {
                boolean iccFdnEnable = phone.getIccCard().getIccFdnEnabled();
                Message msg = new Message();
                Bundle bundle = new Bundle();
                bundle.putBoolean(MSG_FDN_ENABLED, iccFdnEnable);
                bundle.putInt(MSG_FDN_SUB_ID, subId);
                msg.setData(bundle);
                mHandler.sendMessage(msg);
            }
        };
        mHandler.post(mThread);
    }

    protected void displayFdnIcon(boolean visible, int subId) {
        log("displayFdnIcon(),subId = " + subId + ", visible = " + visible);
        updateFdnVisibility(visible, subId);
    }

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

    private String getCurrentIccId(int phoneId) {
        String iccId = null;
        Phone phone = PhoneFactory.getPhone(phoneId);
        if ((phone != null) && (mApplication != null)) {
            SubscriptionInfo subInfo = SubscriptionManager.from(mApplication)
                    .getActiveSubscriptionInfo(phone.getSubId());
            if (subInfo != null) {
                iccId = subInfo.getIccId();
            }
        }
        return iccId;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    public void updateFdnVisibility(boolean visible, int subId) {
        if (visible) {
            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                log("Found null subscription info for: " + subId);
                return;
            }
            /* UNISOC: add for bug905004 @{ */
            mLastSimDisplayName.put(subId, subInfo.getDisplayName().toString());
            mLastSimIconTint.put(subId, subInfo.getIconTint());
            mIsSimFdnEnabled.put(subId, true);
            /* @} */
            boolean isMultiSimEnabled = mTelephonyManager.isMultiSimEnabled();
            int iconId;
            if (isMultiSimEnabled) {
                int phoneId = SubscriptionManager.getPhoneId(subId);
                iconId = sFdnIcon[phoneId];
            } else {
                iconId = R.drawable.stat_sys_phone_fdn;
            }

            String notificationTitle;
            if (TextUtils.isEmpty(subInfo.getDisplayName())) {
                notificationTitle = mApplication.getString(R.string.label_fdn);
            } else {
                notificationTitle = subInfo.getDisplayName().toString();
            }

            /* UNISOC: add for bug906354 @{ */
            if (mIsLocaleChanged) {
                mNotificationManager.deleteNotificationChannel(CHANNEL_ID_FDN);
            }
            /* @} */
            /* SPRD: add for bug768028 @{ */
            NotificationChannel fdnChannel
                    = mNotificationManager.getNotificationChannel(CHANNEL_ID_FDN);
            if (fdnChannel == null) {
                Log.d(TAG, "channel is null, create it");
                fdnChannel = new NotificationChannel(CHANNEL_ID_FDN,
                        mApplication.getString(R.string.notification_channel_fdn),
                        NotificationManager.IMPORTANCE_LOW);
                mNotificationManager.createNotificationChannel(fdnChannel);
            }
            Notification.Builder builder = new Notification.Builder(mApplication)
                    .setSmallIcon(iconId)
                    .setColor(subInfo.getIconTint())
                    .setContentTitle(notificationTitle)
                    .setContentText(mApplication.getString(R.string.fdn_enabled))
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setChannel(CHANNEL_ID_FDN);
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setClassName(FDN_PACKAGE_NAME, FDN_CLASS_NAME);
            addExtrasToIntent(
                    intent, mSubscriptionManager.getActiveSubscriptionInfo(subId));
            // UNISOC: modify for bug905004
            PendingIntent contentIntent =
                    PendingIntent.getActivity(mApplication, subId, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
            builder.setContentIntent(contentIntent);
            mNotificationManager.notifyAsUser(
                    Integer.toString(subId),
                    FDN_NOTIFICATION,
                    builder.build(),
                    UserHandle.ALL);
            /* @} */
        } else {
            /* UNISOC: add for bug905004 @{ */
            mLastSimDisplayName.put(subId, "");
            mLastSimIconTint.put(subId, DEFAULT_ICON_COLOR);
            mIsSimFdnEnabled.put(subId, false);
            /* @} */
            mNotificationManager.cancelAsUser(
                    Integer.toString(subId),
                    FDN_NOTIFICATION,
                    UserHandle.ALL);
        }
    }

    private void addExtrasToIntent(Intent intent, SubscriptionInfo subscription) {
        if (subscription == null) {
            return;
        }

        intent.putExtra(SUB_ID_EXTRA, subscription.getSubscriptionId());
        intent.putExtra(SUB_LABEL_EXTRA, subscription.getDisplayName().toString());
    }

    private boolean isAirplaneModeOn(Context context) {
        if (context == null) {
            return true;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }
}

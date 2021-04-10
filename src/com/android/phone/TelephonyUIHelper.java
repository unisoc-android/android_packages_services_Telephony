package com.android.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManagerEx;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Helper to support fast shutdown */
public class TelephonyUIHelper {
    private static final String TAG = "TelephonyUIHelper";
    private static final boolean DBG = true;

    /* UNISOC: Bug 693469 Reliance block notification. @{ */
    static final int BLOCKED_NUMBER_CALL_NOTIFICATION = 7;
    private TelephonyManager mTelephonyMgr;
    private boolean mIsCallFireWallInstalled;
    private UserManager mUserManager;
    private NotificationManager mNotificationManager;
    private static final String EXTRA_BLOCK_NUMBER = "block_phone_number";
    private static final String ACTION_INCOMING_BLOCK_NUMBER =
            "action_incoming_block_number";
    private static final String ACTION_BLOCK_NUMBER_ACTIVITY =
            "com.sprd.blacklist.action";
    private static final String BLOCK_NUMBER_PACKAGE =
            "com.sprd.firewall";
    /* @} */

    private static TelephonyUIHelper sInstance;
    private Context mContext;

    private static final String CHANNEL_ID_BLOCK_CALL = "block_call";
    private PhoneGlobalsEx mApplication;

    public TelephonyUIHelper(Context context) {
        mContext = context;
        mApplication = PhoneGlobalsEx.getInstance();
        /* UNISOC: Bug 693469 Reliance block notification. @{ */
        mTelephonyMgr = TelephonyManager.from(mContext);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mIsCallFireWallInstalled = mTelephonyMgr != null &&
                mTelephonyMgr.isCallFireWallInstalled();
        if (mContext.getResources()
                .getBoolean(R.bool.config_block_number_notification)) {
            IntentFilter blockedCallFilter = new IntentFilter();
            blockedCallFilter.addAction(ACTION_INCOMING_BLOCK_NUMBER);
            mContext.registerReceiver(mBlockedCallFilter, blockedCallFilter);
        }
        /* @} */
    }

    public static void init(Context context) {
        if (sInstance == null) {
            sInstance = new TelephonyUIHelper(context);
        } else {
            Log.d(TAG, "TelephonyUIHelper.init() called multiple times");
        }
    }

    /* UNISOC: Bug 693469 Reliance block notification. @{ */
    private BroadcastReceiver mBlockedCallFilter = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Incoming Number:"
                    + intent.getStringExtra(EXTRA_BLOCK_NUMBER));
            if (!TextUtils.isEmpty(intent.getStringExtra(EXTRA_BLOCK_NUMBER))) {
                String incoming = intent.getStringExtra(EXTRA_BLOCK_NUMBER);
                if (mContext.getResources()
                        .getBoolean(R.bool.config_block_number_notification)) {
                    updateBlockedCallNotification(incoming, true);
                }
            }
        }
    };

    public void updateBlockedCallNotification(String incoming, boolean visible) {
        if (DBG) {
            Log.d(TAG, "updateBlockedCall(): " + visible + " Number: " + incoming);
        }
        if (visible) {
            int iconId = R.drawable.firewall;
            String notificationTitle = mContext.getString(R.string.blocked_call_notification);

            Intent intent = new Intent(ACTION_BLOCK_NUMBER_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationChannel blockedCallChannel
                    = mNotificationManager.getNotificationChannel(CHANNEL_ID_BLOCK_CALL);
            if (blockedCallChannel == null) {
                Log.d(TAG, "block Call Channel is null, create it");
                blockedCallChannel = new NotificationChannel(CHANNEL_ID_BLOCK_CALL,
                        mApplication.getString(R.string.blocked_call_notification),
                        NotificationManager.IMPORTANCE_LOW);
                mNotificationManager.createNotificationChannel(blockedCallChannel);
            }
            Notification.Builder builder = new Notification.Builder(mContext)
                    .setSmallIcon(iconId)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setContentTitle(notificationTitle)
                    .setContentText(incoming)
                    .setShowWhen(false)
                    .setOngoing(false)
                    .setContentIntent(mIsCallFireWallInstalled ? contentIntent : null)
                    .setChannel(CHANNEL_ID_BLOCK_CALL);

            if (mUserManager != null) {
                if (mUserManager.isSystemUser()) {
                    mNotificationManager.notify(BLOCKED_NUMBER_CALL_NOTIFICATION,
                            builder.build());
                }
            }
        } else {
            mNotificationManager.cancel(BLOCKED_NUMBER_CALL_NOTIFICATION);
        }
    }
    /* @} */
}

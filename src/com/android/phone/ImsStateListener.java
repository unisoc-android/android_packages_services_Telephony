package com.android.phone;

import com.android.ims.ImsManager;
import com.android.ims.internal.IImsRegisterListener;
import com.android.ims.internal.ImsManagerEx;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.ims.internal.IImsServiceEx;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;

/**
 * UNISOC: add for Bug1118484
 * listen IMS state, and do something.
 */
public class ImsStateListener {
    public static final String LOG_TAG = "ImsStateListener";
    public Context mContext;
    public Phone mPhone;
    private IImsServiceEx mIImsServiceEx;
    private static final int MSG_NOSERVICE_NOTIFY      = 1;
    private static final int EVENT_SERVICE_STATE_CHANGED = 2;
    private static final int TIME_NOSERVICE_NOTIFY_MILLIS = 2 * 60 * 1000;
    public boolean mHasNotified = false;
    private boolean mIsImsListenerRegistered = false;
    AlertDialog mAlertDialog = null;

    public ImsStateListener(Context context, Phone phone) {
        mContext = context;
        mPhone = phone;
        }

    public static void ImsStateListenerCreator(Context context, Phone[] phones) {
        for (Phone phone : phones) {
            new ImsStateListener(context, phone).init();
        }
    }
    public void init() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(ImsManager.ACTION_IMS_SERVICE_UP);
        mContext.registerReceiver(mReceiver, intentFilter);
        mPhone.registerForServiceStateChanged(mHandler, EVENT_SERVICE_STATE_CHANGED, null);
    }
    private synchronized void registerImsListener(){
        log("registerImsListener");
        mIImsServiceEx = ImsManagerEx.getIImsServiceEx();
        if(mIImsServiceEx != null){
            try{
                mIsImsListenerRegistered = true;
                mIImsServiceEx.registerforImsRegisterStateChanged(mImsRegisterListener);
            }catch(RemoteException e){
                e.printStackTrace();
            }
        }
    }

    private final IImsRegisterListener.Stub mImsRegisterListener = new IImsRegisterListener.Stub() {
        @Override
        public void imsRegisterStateChange(boolean isRegistered) throws RemoteException {
            log("imsRegisterStateChange: isRegistered " + isRegistered);
            imsNoServiceNotification();
        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ((TelephonyIntents.ACTION_SIM_STATE_CHANGED).equals(action)) {
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                    int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                            SubscriptionManager.INVALID_PHONE_INDEX);
                    log("ACTION_SIM_STATE_CHANGED, sim card absent.phoneid = " + phoneId );
                    if (phoneId == mPhone.getPhoneId()) {
                        mHasNotified = false;
                    }
                }
            } else if ((ImsManager.ACTION_IMS_SERVICE_UP).equals(action)) {
                log("ACTION_IMS_SERVICE_UP");
                if (!mIsImsListenerRegistered) {
                    registerImsListener();
                }
            }
        }
    };

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_NOSERVICE_NOTIFY: {
                    log("MSG_NOSERVICE_NOTIFY, mHasNotified = " + mHasNotified);
                    if (mHasNotified) {
                        break; // notify only once
                    }
                    mHasNotified = true;
                    popupAlertDialog();
                    break;
                }
                case EVENT_SERVICE_STATE_CHANGED: {
                    log("ACTION_SERVICE_STATE_CHANGED");
                    imsNoServiceNotification();
                }
                default:
                    break;
            }
        }
    };

    private void imsNoServiceNotification() {
        ImsManager imsManager = ImsManager.getInstance(mContext,mPhone.getPhoneId());
        TelephonyManager tm = TelephonyManager.from(mContext);
        boolean isVoLTERegistered = ImsManagerEx.isVoLTERegisteredForPhone(mPhone.getPhoneId());

        if (PhoneUtils.isCtCard(mContext, mPhone.getPhoneId())
                && mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM
                && imsManager.isEnhanced4gLteModeSettingEnabledByUser()
                && !isVoLTERegistered
                && tm.getServiceStateForSubscriber(mPhone.getSubId()).getVoiceRegState() != ServiceState.STATE_IN_SERVICE
                && tm.getServiceStateForSubscriber(mPhone.getSubId()).getDataRegState() == ServiceState.STATE_IN_SERVICE) {
            if (!mHasNotified && !mHandler.hasMessages(MSG_NOSERVICE_NOTIFY)) {
                // CTCC requirements:
                // If VoLTE service is not available for 2 mins, notify user to disable VoLTE.
                mHandler.sendEmptyMessageDelayed(MSG_NOSERVICE_NOTIFY, TIME_NOSERVICE_NOTIFY_MILLIS);
                log("imsNoServiceNotification: send MSG_NOSERVICE_NOTIFY delay.");
            }
        } else if (mHandler.hasMessages(MSG_NOSERVICE_NOTIFY)) {
            log("remove MSG_NOSERVICE_NOTIFY");
            mHandler.removeMessages(MSG_NOSERVICE_NOTIFY);
        }
    }

    private void popupAlertDialog() {
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(mContext.getApplicationContext());
        builder.setMessage(R.string.enhanced_4g_lte_unuseable);
        builder.setCancelable(true);
        builder.setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        //ToDo close VoLTE button...
                        ImsManager imsManager = ImsManager.getInstance(mContext, mPhone.getPhoneId());
                        log("VoLTE is no service more then 2 mins, close VoLTE button by user.");
                        imsManager.setEnhanced4gLteModeSetting(false);

                        if (mAlertDialog != null) {
                            mAlertDialog.dismiss();
                            mAlertDialog = null;
                        }
                    }
                });
        builder.setNegativeButton(android.R.string.cancel,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (mAlertDialog != null) {
                            mAlertDialog.dismiss();
                            mAlertDialog = null;
                        }
                    }
                });
        mAlertDialog = builder.create();
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        mAlertDialog.show();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[" + mPhone.getPhoneId() + "] " + msg);
    }
}
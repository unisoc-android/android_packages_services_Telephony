package com.android.phone;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.sprd.telephony.RadioInteractor;
import com.android.sprd.telephony.RadioInteractorCallbackListener;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.phone.PhoneDisplayMessage;
import com.android.phone.R;

public class SuppServiceConsumer extends Handler {
    private static final String TAG = "SuppServiceConsumer";
    private static final int MO_CALL = 0;
    private static final int MT_CALL = 1;

    protected static final int EVENT_SSN = 1;
    protected static final int MSG_SUPP_SERVICE_FAILED = 2;
    private static final int INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE = 3;

    // Time to display the message from the underlying phone layers.
    private static final int SHOW_MESSAGE_NOTIFICATION_TIME = 3000; // msec

    private Context mContext;
    private int mPhoneId;
    private static SuppServiceConsumer mInstance;
    /* UNISOC: Explicit Transfer Call @{ */
    RadioInteractor mRadioInteractor;
    RadioInteractorCallbackListener mRadioInteractorCallbackListener;
    /* @} */

    // TODO Register this handler to gsmphone.
    private SuppServiceConsumer(Context context, int phoneId) {
        mContext = context;
        /* UNISOC: Explicit Transfer Call @{ */
        mPhoneId = phoneId;
        mRadioInteractor = new RadioInteractor(mContext);
        registerForSuppServiceFailed(mPhoneId);
        /* @} */
    }

    public static SuppServiceConsumer getInstance(Context context, Phone phone) {
        if (mInstance == null) {
            mInstance = new SuppServiceConsumer(context, phone.getPhoneId());
        }
        Log.d(TAG, "getInstance");
        phone.registerForSuppServiceNotification(mInstance, EVENT_SSN, null);
        // it can not handle the old one still or the activity will be not
        // release
        phone.registerForSuppServiceFailed(mInstance, MSG_SUPP_SERVICE_FAILED, null);

        mInstance.setContext(context);
        return mInstance;
    }

    private void setContext(Context context) {
        mContext = context;
    }

    private enum SuppService {
        UNKNOWN, SWITCH, SEPARATE, TRANSFER, CONFERENCE, REJECT, HANGUP, RESUME, HOLD;
    }

    @Override
    public void handleMessage(Message msg) {
        // TODO Auto-generated method stub
        AsyncResult ar;
        Log.d(TAG, "handleMessage msg.what = " + msg.what);
        switch (msg.what) {
        case EVENT_SSN:
            ar = (AsyncResult)msg.obj;
            CharSequence cs = null;
            SuppServiceNotification not = (SuppServiceNotification) ar.result;
            if (not.notificationType == MO_CALL) {
                switch(not.code) {
                    case SuppServiceNotification.CODE_1_UNCONDITIONAL_CF_ACTIVE:
                    cs = mContext.getString(R.string.ActiveUnconCf);
                    break;
                    case SuppServiceNotification.CODE_1_SOME_CF_ACTIVE:
                    cs = mContext.getString(R.string.ActiveConCf);
                    break;
                    case SuppServiceNotification.CODE_1_CALL_FORWARDED:
                    cs = mContext.getString(R.string.CallForwarded);
                    break;
                    case SuppServiceNotification.CODE_1_CALL_IS_WAITING:
                    cs = mContext.getString(R.string.CallWaiting);
                    break;
                    case SuppServiceNotification.CODE_1_OUTGOING_CALLS_BARRED:
                    cs = mContext.getString(R.string.OutCallBarred);
                    break;
                    case SuppServiceNotification.CODE_1_INCOMING_CALLS_BARRED:
                    cs = mContext.getString(R.string.InCallBarred);
                    break;
                    //case SuppServiceNotification.MO_CODE_CLIR_SUPPRESSION_REJECTED:
                    //cs = mContext.getText(com.android.internal.R.string.ClirRejected);
                    //break;
                }
            } else if (not.notificationType == MT_CALL) {
                switch(not.code) {
                    case SuppServiceNotification.CODE_2_FORWARDED_CALL:
                    cs = mContext.getString(R.string.ForwardedCall);
                    break;
                    /* case SuppServiceNotification.MT_CODE_CUG_CALL:
                    cs = mContext.getText(com.android.internal.R.string.CugCall);
                    break;*/
                    //Fix Bug 4182 phone_01
                    case SuppServiceNotification.CODE_2_CALL_ON_HOLD:
                    cs = mContext.getString(R.string.CallHold);
                    break;
                    case SuppServiceNotification.CODE_2_CALL_RETRIEVED:
                    cs = mContext.getString(R.string.CallRetrieved);
                    break;
                    case SuppServiceNotification.CODE_2_MULTI_PARTY_CALL:
                    cs = mContext.getText(R.string.MultiCall);
                    break;
                    /*case SuppServiceNotification.MT_CODE_ON_HOLD_CALL_RELEASED:
                    cs = mContext.getText(R.string.HoldCallReleased);
                    break;
                    case SuppServiceNotification.MT_CODE_CALL_CONNECTING_ECT:
                    cs = mContext.getText(R.string.ConnectingEct);
                    break;
                    case SuppServiceNotification.MT_CODE_CALL_CONNECTED_ECT:
                    cs = mContext.getText(R.string.ConnectedEct);
                    break;*/
                    case SuppServiceNotification.CODE_2_ADDITIONAL_CALL_FORWARDED:
                    cs = mContext.getString(R.string.IncomingCallForwarded);
                    break;
                }
            }
            if (cs!=null) {
                makeText(mContext, cs, Toast.LENGTH_LONG);
            }
            break;
        case MSG_SUPP_SERVICE_FAILED:
            if (mContext != null) {
                AsyncResult r = (AsyncResult) msg.obj;
                Phone.SuppService service = (Phone.SuppService) r.result;
                int val = service.ordinal();
                onSuppServiceFailed(val);
            }
            break;
        case INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE:
            PhoneDisplayMessage.dismissMessage();
            break;
        }
        super.handleMessage(msg);
    }

    private void onSuppServiceFailed(int service) {
        Log.d(TAG,"onSuppServiceFailed service: " + service);
        SuppService  result = SuppService.values()[service];
        int errorMessageResId;
        Log.d(TAG,"onSuppServiceFailed service: result " + result);

        switch (result) {
            case CONFERENCE:
            case RESUME:
            case HOLD:
            case SEPARATE:
            case TRANSFER:
                //has aready show in CallNotifier.java onSuppServiceFailed
                return;

            case SWITCH:
                // Attempt to switch foreground and background/incoming calls failed
                // ("Failed to switch or hold calls")
                if (!hasBackgroundCall()) {
                    errorMessageResId = R.string.incall_error_supp_service_hold;
                } else {
                    errorMessageResId = R.string.incall_error_supp_service_switch;
                }
                break;

            case REJECT:
                // Attempt to reject an incoming call failed
                // ("Call rejection failed")
                errorMessageResId = R.string.incall_error_supp_service_reject;
                break;

            case HANGUP:
                // Attempt to release a call failed ("Failed to release call(s)")
                errorMessageResId = R.string.incall_error_supp_service_hangup;
                break;

            case UNKNOWN:
            default:
                // Attempt to use a service we don't recognize or support
                // ("Unsupported service" or "Selected service failed")
                errorMessageResId = R.string.incall_error_supp_service_unknown;
                break;
        }

        final String msg = mContext.getString(errorMessageResId);
        PhoneDisplayMessage.displayErrorMessage(mContext, msg);
        // start a timer that kills the dialog
        sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                SHOW_MESSAGE_NOTIFICATION_TIME);
    }

    private boolean hasBackgroundCall() {
        for (Phone phone : PhoneFactory.getPhones()) {
            if (phone.getBackgroundCall() != null && !phone.getBackgroundCall().isIdle()) {
                return true;
            }
        }
        return false;
    }

    /* UNISOC: Explicit Transfer Call @{ */
    private void registerForSuppServiceFailed(int phoneId) {
        mRadioInteractorCallbackListener = new RadioInteractorCallbackListener(phoneId) {
            @Override
            public void onSuppServiceFailedEvent(int code) {
                onSuppServiceFailed(code);
            }
        };
        mRadioInteractor.listen(mRadioInteractorCallbackListener,
                RadioInteractorCallbackListener.LISTEN_SUPP_SERVICE_FAILED_EVENT,
                false);
    }
    /* @} */

    /**
     * Add method to show toast at LockScreen.
     */
    public void makeText(Context context, CharSequence text, int duration) {
        Toast mToast = Toast.makeText(context, text, duration);
        mToast.getWindowParams().flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        mToast.show();
    }

  /* @} */
}

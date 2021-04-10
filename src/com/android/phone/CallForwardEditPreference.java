package com.android.phone;

import static com.android.phone.TimeConsumingPreferenceActivity.EXCEPTION_ERROR;
import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.BidiFormatter;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.content.Intent;

import com.android.ims.internal.ImsCallForwardInfoEx;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.GsmCdmaPhoneEx;
import android.content.SharedPreferences;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.uicc.IccRecords;

import java.util.HashMap;

public class CallForwardEditPreference extends EditPhoneNumberPreference {
    private static final String LOG_TAG = "CallForwardEditPreference";

    private static final String SRC_TAGS[]       = {"{0}"};
    private CharSequence mSummaryOnTemplate;
    /**
     * Remembers which button was clicked by a user. If no button is clicked yet, this should have
     * {@link DialogInterface#BUTTON_NEGATIVE}, meaning "cancel".
     *
     * TODO: consider removing this variable and having getButtonClicked() in
     * EditPhoneNumberPreference instead.
     */
    private int mButtonClicked;
    private int mServiceClass;
    private MyHandler mHandler = new MyHandler();
    int reason;
    private Phone mPhone;
    private GsmCdmaPhoneEx mGsmCdmaPhoneEx;
    CallForwardInfo callForwardInfo;
    private TimeConsumingPreferenceListener mTcpListener;
    // Should we replace CF queries containing an invalid number with "Voicemail"
    private boolean mReplaceInvalidCFNumber = false;
    private boolean mCallForwardByUssd = false;
    private CarrierXmlParser mCarrierXmlParser;
    private int mPreviousCommand = MyHandler.MESSAGE_GET_CF;
    private Object mCommandException;
    private CarrierXmlParser.SsEntry.SSAction mSsAction =
            CarrierXmlParser.SsEntry.SSAction.UNKNOWN;
    private int mAction;
    private HashMap<String, String> mCfInfo;

    public ImsCallForwardInfoEx mImsCallForwardInfoEx;
    Context mContext;
    private int mSubId = 0;
    private static final String REFRESH_VIDEO_CF_NOTIFICATION_ACTION =
            "android.intent.action.REFRESH_VIDEO_CF_NOTIFICATION";
    private static final String VIDEO_CF_SUB_ID = "video_cf_flag_with_subid";
    private static final String VIDEO_CF_STATUS = "video_cf_status";
    /* UNISOC: add for feature bug1071416 durationforward @{ */
    private SharedPreferences mTimePrefs;
    private static final String PREF_PREFIX_TIME = "phonecalltimeforward_";
    /* @} */
    private boolean mUseConfigServiceClass = false;
    /* UNISOC: add for bug905164 @{ */
    private Button mUpdateButton;
    private Button mConfirmButton;
    /* @} */
    //UNISOC: add for bug1145309
    private boolean mCheckAllCfStatus = false;

    public CallForwardEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mSummaryOnTemplate = this.getSummaryOn();

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CallForwardEditPreference, 0, R.style.EditPhoneNumberPreference);
        mServiceClass = a.getInt(R.styleable.CallForwardEditPreference_serviceClass,
                CommandsInterface.SERVICE_CLASS_VOICE);
        reason = a.getInt(R.styleable.CallForwardEditPreference_reason,
                CommandsInterface.CF_REASON_UNCONDITIONAL);
        a.recycle();

        Log.d(LOG_TAG, "mServiceClass=" + mServiceClass + ", reason=" + reason);
    }

    public CallForwardEditPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, Phone phone,
            boolean replaceInvalidCFNumber, boolean callForwardByUssd) {
        mPhone = phone;
        mGsmCdmaPhoneEx = (GsmCdmaPhoneEx) phone;
        mSubId = mPhone.getSubId();
        // UNISOC: add for feature bug1071416 durationforward
        mTimePrefs = mContext.getSharedPreferences(PREF_PREFIX_TIME + mSubId, mContext.MODE_PRIVATE);
        mTcpListener = listener;
        mReplaceInvalidCFNumber = replaceInvalidCFNumber;
        mCallForwardByUssd = callForwardByUssd;
        Log.d(LOG_TAG,
                "init :mReplaceInvalidCFNumber " + mReplaceInvalidCFNumber + ", mCallForwardByUssd "
                        + mCallForwardByUssd);
        if (mCallForwardByUssd) {
            mCfInfo = new HashMap<String, String>();
            TelephonyManager telephonyManager = new TelephonyManager(getContext(),
                    phone.getSubId());
            mCarrierXmlParser = new CarrierXmlParser(getContext(),
                    telephonyManager.getSimCarrierId());
        }
    }

    void restoreCallForwardInfo(CallForwardInfo cf) {
        handleCallForwardResult(cf);
        updateSummaryText();
    }

    @Override
    protected void onBindDialogView(View view) {
        // default the button clicked to be the cancel button.
        mButtonClicked = DialogInterface.BUTTON_NEGATIVE;
        super.onBindDialogView(view);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        super.onClick(dialog, which);
        mButtonClicked = which;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        Log.d(LOG_TAG, "mButtonClicked=" + mButtonClicked + ", positiveResult=" + positiveResult);
        // Ignore this event if the user clicked the cancel button, or if the dialog is dismissed
        // without any button being pressed (back button press or click event outside the dialog).
        if (this.mButtonClicked != DialogInterface.BUTTON_NEGATIVE) {
            int action = (isToggled() || (mButtonClicked == DialogInterface.BUTTON_POSITIVE)) ?
                    CommandsInterface.CF_ACTION_REGISTRATION :
                    CommandsInterface.CF_ACTION_DISABLE;
            int time = 0;
            if (reason == CommandsInterface.CF_REASON_NO_REPLY) {
                PersistableBundle carrierConfig = PhoneGlobals.getInstance()
                        .getCarrierConfigForSubId(mPhone.getSubId());
                if (carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_SUPPORT_NO_REPLY_TIMER_FOR_CFNRY_BOOL, true)) {
                    time = 20;
                }
            }
            String number = getPhoneNumber();

            Log.d(LOG_TAG, "callForwardInfo=" + callForwardInfo);
            Log.d(LOG_TAG, "mImsCallForwardInfoEx=" + mImsCallForwardInfoEx);

            if (!needToSetToNetwork(action, number)) {
                // no change, do nothing
                Log.d(LOG_TAG, "no change, do nothing");
            } else {
                // set to network
                Log.d(LOG_TAG, "reason=" + reason + ", action=" + action
                        + ", number=" + number + ", mServiceClass=" + mServiceClass);

                //UNISOC: add for bug1139943
                if (action == CommandsInterface.CF_ACTION_DISABLE) {
                    if (mServiceClass != CommandsInterface.SERVICE_CLASS_VOICE) {
                        // video
                        if (mImsCallForwardInfoEx != null
                                && !number.equals(mImsCallForwardInfoEx.mNumber)) {
                            number = mImsCallForwardInfoEx.mNumber;
                        }
                    } else {// audio
                        if (callForwardInfo != null && !number.equals(callForwardInfo.number)) {
                            number = callForwardInfo.number;
                        }
                    }
                }

                // Display no forwarding number while we're waiting for
                // confirmation
                setSummaryOn("");
                if (!mCallForwardByUssd) {
                    // the interface of Phone.setCallForwardingOption has error:
                    // should be action, reason...
                    /*  UNISOC: add for FEATURE_VIDEO_CALL_FOR & feature 888845, 948130 @{ */
                    if (mServiceClass != CommandsInterface.SERVICE_CLASS_VOICE && mGsmCdmaPhoneEx != null) {
                        mGsmCdmaPhoneEx.setCallForwardingOption(action,
                                reason,
                                mServiceClass,
                                number,
                                time,
                                null,
                                mHandler.obtainMessage(MyHandler.MESSAGE_SET_VCF,
                                        action,
                                        MyHandler.MESSAGE_SET_VCF));
                    } else {
                        mPhone.setCallForwardingOption(action,
                                reason,
                                number,
                                time,
                                mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF,
                                        action,
                                        MyHandler.MESSAGE_SET_CF));
                    }
                } else {
                    if (action == CommandsInterface.CF_ACTION_REGISTRATION) {
                        mCfInfo.put(CarrierXmlParser.TAG_ENTRY_NUMBER, number);
                        mCfInfo.put(CarrierXmlParser.TAG_ENTRY_TIME, Integer.toString(time));
                    } else {
                        mCfInfo.clear();
                    }
                    mHandler.sendMessage(mHandler.obtainMessage(mHandler.MESSAGE_SET_CF_USSD,
                            action, MyHandler.MESSAGE_SET_CF));
                }
                if (mTcpListener != null) {
                    mTcpListener.onStarted(this, false);
                }
            }
        }
    }

    //UNISOC: add for bug1139943
    private boolean needToSetToNetwork(int action, String number) {
        boolean ret = true;
        if (action == CommandsInterface.CF_ACTION_REGISTRATION) {
            if (mServiceClass != CommandsInterface.SERVICE_CLASS_VOICE) {
                if (mImsCallForwardInfoEx != null
                        && mImsCallForwardInfoEx.mStatus == CommandsInterface.CF_ACTION_ENABLE
                        && number.equals(mImsCallForwardInfoEx.mNumber)) {
                    ret = false;
                }
            } else {
                if (callForwardInfo != null
                        && callForwardInfo.status == CommandsInterface.CF_ACTION_ENABLE
                        && number.equals(callForwardInfo.number)) {
                    ret = false;
                }
            }
        }
        Log.d(LOG_TAG, "needToSetToNetwork: ret: " + ret);
        return ret;
    }

    private void handleCallForwardResult(CallForwardInfo cf) {
        callForwardInfo = cf;
        Log.d(LOG_TAG, "handleGetCFResponse done, callForwardInfo=" + callForwardInfo);
        // In some cases, the network can send call forwarding URIs for voicemail that violate the
        // 3gpp spec. This can cause us to receive "numbers" that are sequences of letters. In this
        // case, we must detect these series of characters and replace them with "Voicemail".
        // PhoneNumberUtils#formatNumber returns null if the number is not valid.
        if (mReplaceInvalidCFNumber && (PhoneNumberUtils.formatNumber(callForwardInfo.number,
                getCurrentCountryIso()) == null)) {
            callForwardInfo.number = getContext().getString(R.string.voicemail);
            Log.i(LOG_TAG, "handleGetCFResponse: Overridding CF number");
        }

        setToggled(callForwardInfo.status == 1);
        boolean displayVoicemailNumber = false;
        if (TextUtils.isEmpty(callForwardInfo.number)) {
            PersistableBundle carrierConfig =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
            if (carrierConfig != null) {
                displayVoicemailNumber = carrierConfig.getBoolean(CarrierConfigManager
                        .KEY_DISPLAY_VOICEMAIL_NUMBER_AS_DEFAULT_CALL_FORWARDING_NUMBER_BOOL);
                Log.d(LOG_TAG, "display voicemail number as default");
            }
        }
        String voicemailNumber = mPhone.getVoiceMailNumber();
        setPhoneNumber(displayVoicemailNumber ? voicemailNumber : callForwardInfo.number);
        //UNISOC: Add for Bug#1125621
        mTcpListener.onEnableStatus(CallForwardEditPreference.this, 0);
    }

    /**
     * Starts the Call Forwarding Option query to the network and calls
     * {@link TimeConsumingPreferenceListener#onStarted}. Will call
     * {@link TimeConsumingPreferenceListener#onFinished} when finished, or
     * {@link TimeConsumingPreferenceListener#onError} if an error has occurred.
     */
    void startCallForwardOptionsQuery() {
        if (!mCallForwardByUssd) {
            /*  UNISOC: add for FEATURE_VIDEO_CALL_FOR  @{ */
            if (mServiceClass != CommandsInterface.SERVICE_CLASS_VOICE) {
                mGsmCdmaPhoneEx.getCallForwardingOption(
                        reason,
                        mServiceClass,
                        null,
                        mHandler.obtainMessage(MyHandler.MESSAGE_GET_VCF,
                                // unused in this case
                                CommandsInterface.CF_ACTION_DISABLE,
                                MyHandler.MESSAGE_GET_VCF, null));

            } else {
                mPhone.getCallForwardingOption(reason,
                        mHandler.obtainMessage(MyHandler.MESSAGE_GET_CF,
                                // unused in this case
                                CommandsInterface.CF_ACTION_DISABLE,
                                MyHandler.MESSAGE_GET_CF, null));
            }/* @} */
        } else {
            mHandler.sendMessage(mHandler.obtainMessage(mHandler.MESSAGE_GET_CF_USSD,
                    // unused in this case
                    CommandsInterface.CF_ACTION_DISABLE, MyHandler.MESSAGE_GET_CF, null));
        }
        if (mTcpListener != null) {
            mTcpListener.onStarted(this, true);
        }
    }

    private void updateSummaryText() {
        if (isToggled()) {
            final String number = getRawPhoneNumber();
            if (number != null && number.length() > 0) {
                // Wrap the number to preserve presentation in RTL languages.
                String wrappedNumber = BidiFormatter.getInstance().unicodeWrap(
                        number, TextDirectionHeuristics.LTR);
                String values[] = { wrappedNumber };
                String summaryOn = String.valueOf(
                        TextUtils.replace(mSummaryOnTemplate, SRC_TAGS, values));
                int start = summaryOn.indexOf(wrappedNumber);

                SpannableString spannableSummaryOn = new SpannableString(summaryOn);
                PhoneNumberUtils.addTtsSpan(spannableSummaryOn,
                        start, start + wrappedNumber.length());
                setSummaryOn(spannableSummaryOn);
            } else {
                setSummaryOn(getContext().getString(R.string.sum_cfu_enabled_no_number));
            }
        }

    }

    /**
     * @return The ISO 3166-1 two letters country code of the country the user is in based on the
     *      network location.
     */
    private String getCurrentCountryIso() {
        final TelephonyManager telephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            return "";
        }
        return telephonyManager.getNetworkCountryIso().toUpperCase();
    }

    // Message protocol:
    // what: get vs. set
    // arg1: action -- register vs. disable
    // arg2: get vs. set for the preceding request
    private class MyHandler extends Handler {
        static final int MESSAGE_GET_CF = 0;
        static final int MESSAGE_SET_CF = 1;
        static final int MESSAGE_GET_CF_USSD = 2;
        static final int MESSAGE_SET_CF_USSD = 3;
        static final int MESSAGE_GET_VCF = 4;
        static final int MESSAGE_SET_VCF = 5;

        TelephonyManager.UssdResponseCallback mUssdCallback =
                new TelephonyManager.UssdResponseCallback() {
                    @Override
                    public void onReceiveUssdResponse(final TelephonyManager telephonyManager,
                            String request, CharSequence response) {
                        if (mSsAction == CarrierXmlParser.SsEntry.SSAction.UNKNOWN) {
                            return;
                        }

                        HashMap<String, String> analysisResult = mCarrierXmlParser.getFeature(
                                CarrierXmlParser.FEATURE_CALL_FORWARDING)
                                .getResponseSet(mSsAction,
                                        response.toString());

                        Throwable throwableException = null;
                        if (analysisResult.get(CarrierXmlParser.TAG_RESPONSE_STATUS_ERROR)
                                != null) {
                            throwableException = new CommandException(
                                    CommandException.Error.GENERIC_FAILURE);
                        }

                        Object obj = null;
                        if (mSsAction == CarrierXmlParser.SsEntry.SSAction.QUERY) {
                            obj = makeCallForwardInfo(analysisResult);
                        }

                        sendCfMessage(obj, throwableException);
                    }

                    @Override
                    public void onReceiveUssdResponseFailed(final TelephonyManager telephonyManager,
                            String request, int failureCode) {
                        Log.d(LOG_TAG, "receive the ussd result failed");
                        Throwable throwableException = new CommandException(
                                CommandException.Error.GENERIC_FAILURE);
                        sendCfMessage(null, throwableException);
                    }
                };

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_CF:
                    handleGetCFResponse(msg);
                    break;
                case MESSAGE_SET_CF:
                    handleSetCFResponse(msg);
                    break;
                case MESSAGE_GET_CF_USSD:
                    prepareUssdCommand(msg, CarrierXmlParser.SsEntry.SSAction.QUERY);
                    break;
                case MESSAGE_SET_CF_USSD:
                    prepareUssdCommand(msg, CarrierXmlParser.SsEntry.SSAction.UNKNOWN);
                    break;
                case MESSAGE_GET_VCF:
                    handleGetVCFResponse(msg);
                    break;
                case MESSAGE_SET_VCF:
                    handleSetVCFResponse(msg);
                    break;
            }
        }

        private void handleGetCFResponse(Message msg) {
            Log.d(LOG_TAG, "handleGetCFResponse: done");

            mTcpListener.onFinished(CallForwardEditPreference.this,
                    (msg.arg2 != MESSAGE_SET_CF && msg.arg2 != MESSAGE_SET_VCF));

            AsyncResult ar = (AsyncResult) msg.obj;

            callForwardInfo = null;
            if (ar.exception != null) {
                Log.d(LOG_TAG, "handleGetCFResponse: ar.exception=" + ar.exception);
                if (ar.exception instanceof CommandException) {
                    mTcpListener.onException(CallForwardEditPreference.this,
                            (CommandException) ar.exception);
                } else {
                    // Most likely an ImsException and we can't handle it the same way as
                    // a CommandException. The best we can do is to handle the exception
                    // the same way as mTcpListener.onException() does when it is not of type
                    // FDN_CHECK_FAILURE.
                    mTcpListener.onError(CallForwardEditPreference.this, EXCEPTION_ERROR);
                }
            } else {
                if (ar.userObj instanceof Throwable) {
                    mTcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                }

                CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;


                if (cfInfoArray.length == 0) {
                    Log.d(LOG_TAG, "handleGetCFResponse: cfInfoArray.length==0");
                    setEnabled(false);
                    mTcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                } else {
                    for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                        Log.d(LOG_TAG, "handleGetCFResponse, cfInfoArray[" + i + "]="
                                + cfInfoArray[i]);
                        if ((mServiceClass & cfInfoArray[i].serviceClass) != 0) {
                            // corresponding class

                            CallForwardInfo info = cfInfoArray[i];
                            //UNISOC: add for bug1145309process all callforward result after update callforwrd status
                            if (mCheckAllCfStatus && cfInfoArray.length != 1) {
                                if (i != reason) {
                                    //only handle result of setting callforward option,ignore other callforward option result.
                                    continue;
                                } else {
                                    //the reason of query result is CommandsInterface.CF_REASON_ALL
                                    //so need to set correct reason for setting option
                                    info.reason = i;
                                }
                            }
                            handleCallForwardResult(info);


                            // Show an alert if we got a success response but
                            // with unexpected values.
                            // Currently only handle the fail-to-disable case
                            // since we haven't observed fail-to-enable.
                            if (msg.arg2 == MESSAGE_SET_CF &&
                                    msg.arg1 == CommandsInterface.CF_ACTION_DISABLE &&
                                    info.status == 1) {
                                // Skip showing error dialog since some operators return
                                // active status even if disable call forward succeeded.
                                // And they don't like the error dialog.
                                if (isSkipCFFailToDisableDialog()) {
                                    Log.d(LOG_TAG, "Skipped Callforwarding fail-to-disable dialog");
                                    continue;
                                }
                                CharSequence s;
                                switch (reason) {
                                    case CommandsInterface.CF_REASON_BUSY:
                                        s = getContext().getText(R.string.disable_cfb_forbidden);
                                        break;
                                    case CommandsInterface.CF_REASON_NO_REPLY:
                                        s = getContext().getText(R.string.disable_cfnry_forbidden);
                                        break;
                                    default: // not reachable
                                        s = getContext().getText(R.string.disable_cfnrc_forbidden);
                                }
                                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                builder.setNeutralButton(R.string.close_dialog, null);
                                builder.setTitle(getContext().getText(R.string.error_updating_title));
                                builder.setMessage(s);
                                builder.setCancelable(true);
                                builder.create().show();
                            }
                        }
                    }
                }
            }

            // Now whether or not we got a new number, reset our enabled
            // summary text since it may have been replaced by an empty
            // placeholder.
            updateSummaryText();
        }

        /*UNISOC: add for FEATURE_VIDEO_CALL_FOR @{*/
        private void handleGetVCFResponse(Message msg) {
            Log.d(LOG_TAG, "handleGetVCFResponse: done");

            mTcpListener.onFinished(CallForwardEditPreference.this, (msg.arg2 != MESSAGE_SET_VCF));

            AsyncResult ar = (AsyncResult) msg.obj;

            mImsCallForwardInfoEx = null;
            if (ar.exception != null) {
                Log.d(LOG_TAG, "handleGetVCFResponse: ar.exception=" + ar.exception);
                if (ar.exception instanceof CommandException) {
                    mTcpListener.onException(CallForwardEditPreference.this,
                            (CommandException) ar.exception);
                } else {
                    // Most likely an ImsException and we can't handle it the same way as
                    // a CommandException. The best we can do is to handle the exception
                    // the same way as mTcpListener.onException() does when it is not of type
                    // FDN_CHECK_FAILURE.
                    mTcpListener.onError(CallForwardEditPreference.this, EXCEPTION_ERROR);
                }
            } else {
                if (ar.userObj instanceof Throwable) {
                    mTcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                }
                //UNISOC: add for FEATURE_VIDEO_CALL_FOR
                ImsCallForwardInfoEx cfInfoArray[] = (ImsCallForwardInfoEx[]) ar.result;

                if (cfInfoArray.length == 0) {
                    Log.d(LOG_TAG, "handleGetVCFResponse: cfInfoArray.length==0");
                    setEnabled(false);
                    mTcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                } else {
                    for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                        Log.d(LOG_TAG, "handleGetVCFResponse, cfInfoArray[" + i + "]="
                                + cfInfoArray[i]);
                        if (checkServiceClassSupport(cfInfoArray[i].mServiceClass)) {
                            // corresponding class

                            ImsCallForwardInfoEx info = cfInfoArray[i];
                            ////UNISOC: add for bug1145309 process all callforward result after update callforwrd status
                            if (mCheckAllCfStatus && cfInfoArray.length != 1) {
                                if (i != reason) {
                                    //only handle result of setting callforward option,ignore other callforward option result.
                                    continue;
                                } else {
                                    //the reason of query result is CommandsInterface.CF_REASON_ALL
                                    //so need to set correct reason for setting option
                                    info.mCondition = i;
                                }
                            }
                            handleCallForwardVResult(info);

                            // Show an alert if we got a success response but
                            // with unexpected values.
                            // Currently only handle the fail-to-disable case
                            // since we haven't observed fail-to-enable.
                            if (msg.arg2 == MESSAGE_SET_CF &&
                                    msg.arg1 == CommandsInterface.CF_ACTION_DISABLE &&
                                    info.mStatus == 1) {
                                // Skip showing error dialog since some operators return
                                // active status even if disable call forward succeeded.
                                // And they don't like the error dialog.
                                if (isSkipCFFailToDisableDialog()) {
                                    Log.d(LOG_TAG, "Skipped Callforwarding fail-to-disable dialog");
                                    continue;
                                }
                                CharSequence s;
                                switch (reason) {
                                    case CommandsInterface.CF_REASON_BUSY:
                                        s = getContext().getText(R.string.disable_cfb_forbidden);
                                        break;
                                    case CommandsInterface.CF_REASON_NO_REPLY:
                                        s = getContext().getText(R.string.disable_cfnry_forbidden);
                                        break;
                                    default: // not reachable
                                        s = getContext().getText(R.string.disable_cfnrc_forbidden);
                                }
                                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                builder.setNeutralButton(R.string.close_dialog, null);
                                builder.setTitle(getContext().getText(R.string.error_updating_title));
                                builder.setMessage(s);
                                builder.setCancelable(true);
                                builder.create().show();
                            }
                        }
                    }
                }
            }

            // Now whether or not we got a new number, reset our enabled
            // summary text since it may have been replaced by an empty
            // placeholder.
            updateSummaryText();
        }

        private void handleSetVCFResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            if (ar.exception != null) {
                Log.d(LOG_TAG, "handleSetVCFResponse: ar.exception=" + ar.exception);
                // setEnabled(false);
            }
            int tempReason = reason;
            if (mCheckAllCfStatus) {
                tempReason = CommandsInterface.CF_REASON_ALL;
            }
            Log.d(LOG_TAG, "handleSetVCFResponse: re get");
            if (!mCallForwardByUssd && mGsmCdmaPhoneEx != null) {
                mGsmCdmaPhoneEx.getCallForwardingOption(
                        tempReason,
                        mServiceClass,
                        null,
                        mHandler.obtainMessage(MyHandler.MESSAGE_GET_VCF,
                                // unused in this case
                                CommandsInterface.CF_ACTION_DISABLE,
                                MyHandler.MESSAGE_SET_VCF, null));
            }
        }/*@}*/

        private void handleSetCFResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            if (ar.exception != null) {
                Log.d(LOG_TAG, "handleSetCFResponse: ar.exception=" + ar.exception);
                // setEnabled(false);
            }
            int tempReason = reason;
            if (mCheckAllCfStatus) {
                tempReason = CommandsInterface.CF_REASON_ALL;
            }
            Log.d(LOG_TAG, "handleSetCFResponse: re get");
            if (!mCallForwardByUssd) {
                mPhone.getCallForwardingOption(tempReason,
                        obtainMessage(MESSAGE_GET_CF, msg.arg1, MESSAGE_SET_CF, ar.exception));
            } else {
                mHandler.sendMessage(mHandler.obtainMessage(mHandler.MESSAGE_GET_CF_USSD,
                        msg.arg1, MyHandler.MESSAGE_SET_CF, ar.exception));
            }
        }

        private void prepareUssdCommand(Message msg,
                CarrierXmlParser.SsEntry.SSAction inputSsAction) {
            mAction = msg.arg1;
            mPreviousCommand = msg.arg2;
            mCommandException = msg.obj;
            mSsAction = inputSsAction;

            if (mSsAction != CarrierXmlParser.SsEntry.SSAction.QUERY) {
                if (mAction == CommandsInterface.CF_ACTION_REGISTRATION) {
                    mSsAction = CarrierXmlParser.SsEntry.SSAction.UPDATE_ACTIVATE;
                } else {
                    mSsAction = CarrierXmlParser.SsEntry.SSAction.UPDATE_DEACTIVATE;
                }
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendUssdCommand(mUssdCallback, mSsAction, mCfInfo.isEmpty() ? null : mCfInfo);
                }
            }).start();
        }

        private void sendUssdCommand(TelephonyManager.UssdResponseCallback inputCallback,
                CarrierXmlParser.SsEntry.SSAction inputAction,
                HashMap<String, String> inputCfInfo) {
            String newUssdCommand = mCarrierXmlParser.getFeature(
                    CarrierXmlParser.FEATURE_CALL_FORWARDING)
                    .makeCommand(inputAction, inputCfInfo);
            TelephonyManager telephonyManager =
                    (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            telephonyManager.sendUssdRequest(newUssdCommand, inputCallback, mHandler);
        }

        private Message makeGetCfMessage(int inputMsgWhat, int inputMsgArg2, Object inputMsgObj) {
            return mHandler.obtainMessage(inputMsgWhat,
                    mAction,
                    inputMsgArg2,
                    inputMsgObj);
        }

        private Message makeSetCfMessage(int inputMsgWhat, int inputMsgArg2) {
            return mHandler.obtainMessage(inputMsgWhat,
                    mAction,
                    inputMsgArg2);
        }

        private void sendCfMessage(Object inputArObj, Throwable inputThrowableException) {
            Message message;
            if (mSsAction == CarrierXmlParser.SsEntry.SSAction.UNKNOWN) {
                return;
            }
            if (mSsAction == CarrierXmlParser.SsEntry.SSAction.QUERY) {
                message = makeGetCfMessage(MyHandler.MESSAGE_GET_CF, mPreviousCommand,
                        mCommandException);
            } else {
                message = makeSetCfMessage(MyHandler.MESSAGE_SET_CF, MyHandler.MESSAGE_SET_CF);
            }
            AsyncResult.forMessage(message, inputArObj, inputThrowableException);
            message.sendToTarget();
        }

        private CallForwardInfo[] makeCallForwardInfo(HashMap<String, String> inputInfo) {
            int tmpStatus = 0;
            String tmpNumberStr = "";
            int tmpTime = 0;
            if (inputInfo != null && inputInfo.size() != 0) {
                String tmpStatusStr = inputInfo.get(CarrierXmlParser.TAG_RESPONSE_STATUS);

                String tmpTimeStr = inputInfo.get(CarrierXmlParser.TAG_RESPONSE_TIME);
                if (!TextUtils.isEmpty(tmpStatusStr)) {
                    if (tmpStatusStr.equals(
                            CarrierXmlParser.TAG_COMMAND_RESULT_DEFINITION_ACTIVATE)) {
                        tmpStatus = 1;
                    } else if (tmpStatusStr.equals(
                            CarrierXmlParser.TAG_COMMAND_RESULT_DEFINITION_DEACTIVATE)
                            || tmpStatusStr.equals(
                            CarrierXmlParser.TAG_COMMAND_RESULT_DEFINITION_UNREGISTER)) {
                        tmpStatus = 0;
                    }
                }

                tmpNumberStr = inputInfo.get(CarrierXmlParser.TAG_RESPONSE_NUMBER);
                if (!TextUtils.isEmpty(tmpTimeStr)) {
                    tmpTime = Integer.valueOf(inputInfo.get(CarrierXmlParser.TAG_RESPONSE_TIME));
                }
            }

            CallForwardInfo[] newCallForwardInfo = new CallForwardInfo[1];
            newCallForwardInfo[0] = new CallForwardInfo();
            newCallForwardInfo[0].status = tmpStatus;
            newCallForwardInfo[0].reason = reason;
            newCallForwardInfo[0].serviceClass = mServiceClass;
            newCallForwardInfo[0].number = tmpNumberStr;
            newCallForwardInfo[0].timeSeconds = tmpTime;
            return newCallForwardInfo;
        }
    }

    /*
     * Get the config of whether skip showing CF fail-to-disable dialog
     * from carrier config manager.
     *
     * @return boolean value of the config
     */
    private boolean isSkipCFFailToDisableDialog() {
        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
        if (carrierConfig != null) {
            return carrierConfig.getBoolean(
                    CarrierConfigManager.KEY_SKIP_CF_FAIL_TO_DISABLE_DIALOG_BOOL);
        } else {
            // by default we should not skip
            return false;
        }
    }

    /*  UNISOC: add for FEATURE_VIDEO_CALL_FOR  @{ */
    public void handleCallForwardVResult(ImsCallForwardInfoEx cf) {
        mImsCallForwardInfoEx = cf;

        /* UNISOC: add for feature 1072988 @{ */
        if (mUseConfigServiceClass) {

            Log.d(LOG_TAG, "special operator handleCallForwardVResult done");
            if (mReplaceInvalidCFNumber && (PhoneNumberUtils.formatNumber(mImsCallForwardInfoEx.mNumber,
                    getCurrentCountryIso()) == null)) {
                mImsCallForwardInfoEx.mNumber = getContext().getString(R.string.voicemail);
                Log.i(LOG_TAG, "handleGetCFResponse: Overridding CF number");
            }
            setToggled(mImsCallForwardInfoEx.mStatus == 1);
            boolean displayVoicemailNumber = false;
            if (TextUtils.isEmpty(mImsCallForwardInfoEx.mNumber)) {
                PersistableBundle carrierConfig =
                        PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());
                if (carrierConfig != null) {
                    displayVoicemailNumber = carrierConfig.getBoolean(CarrierConfigManager
                            .KEY_DISPLAY_VOICEMAIL_NUMBER_AS_DEFAULT_CALL_FORWARDING_NUMBER_BOOL);
                    Log.d(LOG_TAG, "display voicemail number as default");
                }
            }
            String voicemailNumber = mPhone.getVoiceMailNumber();
            setPhoneNumber(displayVoicemailNumber ? voicemailNumber : mImsCallForwardInfoEx.mNumber);
            return;
        }
        /* @} */

         /* UNISOC: modify for feature bug1071416 durationforward @{ */
        String iccId = getIccId(mSubId);

        if (TextUtils.isEmpty(mImsCallForwardInfoEx.mRuleset)) {
            //video call forward
            if (isVideoCallServiceClass(mImsCallForwardInfoEx.mServiceClass)) {
                setToggled(mImsCallForwardInfoEx.mStatus == 1);
                setPhoneNumber(mImsCallForwardInfoEx.mNumber);
            }
        } else {
            //durationforward
            if ((mImsCallForwardInfoEx.mServiceClass
                    & CommandsInterface.SERVICE_CLASS_VOICE) != 0) {
                String number = mImsCallForwardInfoEx.mNumber;
                String numberToSave = null;
                if (number != null && PhoneNumberUtils.isUriNumber(number)) {
                    numberToSave = number;
                    saveStringPrefs(PREF_PREFIX_TIME + "num_" + mSubId, numberToSave, mTimePrefs);
                } else {
                    numberToSave = PhoneNumberUtils.stripSeparators(number);
                    saveStringPrefs(PREF_PREFIX_TIME + "num_" + mSubId, numberToSave, mTimePrefs);
                }
                saveStringPrefs(PREF_PREFIX_TIME + "ruleset_" + mSubId,
                        mImsCallForwardInfoEx.mRuleset, mTimePrefs);
                saveStringPrefs(PREF_PREFIX_TIME + "status_" + mSubId,
                        String.valueOf(mImsCallForwardInfoEx.mStatus), mTimePrefs);
                CallForwardTimeUtils.getInstance(mContext).writeStatus(
                        iccId, mImsCallForwardInfoEx.mStatus);
                /* @} */
                setToggled(true);
                setPhoneNumber(mImsCallForwardInfoEx.mNumber);
                mTcpListener.onEnableStatus(CallForwardEditPreference.this, 0);
            }
        }
        /* @} */

        if (mImsCallForwardInfoEx.mCondition == CommandsInterface.CF_REASON_UNCONDITIONAL
                && isVideoCallServiceClass(mImsCallForwardInfoEx.mServiceClass)) {
            boolean videoCfStatus = (mImsCallForwardInfoEx.mStatus == 1);
            Intent intent = new Intent(REFRESH_VIDEO_CF_NOTIFICATION_ACTION);
            intent.putExtra(VIDEO_CF_STATUS, videoCfStatus);
            intent.putExtra(VIDEO_CF_SUB_ID, mPhone.getSubId());
            mContext.sendBroadcast(intent);

            Log.d(LOG_TAG, "refresh notification for video cf subid : "
                    + mPhone.getSubId() + "; enable : " + videoCfStatus);
        }
    }/* @} */

    /* UNISOC: add for feature 913471 @{ */
    public boolean checkServiceClassSupport(int sc) {
        return (sc & CommandsInterface.SERVICE_CLASS_DATA) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_DATA_SYNC) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_DATA_ASYNC) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_PACKET) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_PAD) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_VOICE) != 0;
    }
        /* @} */


    /* UNISOC: add for feature 925949 @{ */
    public boolean isVideoCallServiceClass(int sc) {
        return (sc & CommandsInterface.SERVICE_CLASS_DATA) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_DATA_SYNC) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_DATA_ASYNC) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_PACKET) != 0
                || (sc & CommandsInterface.SERVICE_CLASS_PAD) != 0;
    }
        /* @} */

    /* UNISOC: add for feature 1072988 @{*/
    public void setConfigServiceClass(int serviceClass) {
        Log.d(LOG_TAG, "setConfigServiceClass: " + serviceClass);
        mServiceClass = serviceClass;
        mUseConfigServiceClass = true;
    }/*@}*/

    /* UNISOC: add for feature bug1071416 durationforward @{ */
    void initCallTimeForward() {
        if (mGsmCdmaPhoneEx != null) {
            mGsmCdmaPhoneEx.getCallForwardingOption(
                    CommandsInterface.CF_REASON_UNCONDITIONAL,
                    CommandsInterface.SERVICE_CLASS_VOICE,
                    null,
                    mHandler.obtainMessage(MyHandler.MESSAGE_GET_VCF,
                            // unused in this case
                            CommandsInterface.CF_ACTION_DISABLE,
                            MyHandler.MESSAGE_GET_VCF, null));
        }
        if (mTcpListener != null) {
            mTcpListener.onStarted(this, true);
        }
    }

    void saveStringPrefs(String key, String value, SharedPreferences prefs) {
        Log.w(LOG_TAG, "saveStringPrefs(" + key + ", " + value + ")");
        try {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(key, value);
            editor.apply();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Exception happen.");
        }
    }
    private String getIccId(int subId) {
        String iccId = "";
        if (mPhone != null) {
            IccCard iccCard = mPhone.getIccCard();
            if (iccCard != null) {
                IccRecords iccRecords = iccCard.getIccRecords();
                if (iccRecords != null) {
                    iccId = iccRecords.getIccId();
                }
            }
        }
        return iccId;
    }
    /* @} */
    /* UNISOC: add for bug905164 @{ */
    @Override
    protected void showDialog(Bundle state) {
        super.showDialog(state);
        mUpdateButton = ((AlertDialog) getDialog()).getButton(DialogInterface.BUTTON1);
        mConfirmButton = ((AlertDialog) getDialog()).getButton(DialogInterface.BUTTON3);
        EditText editText = getEditText();
        if (editText != null) {
            if (TextUtils.isEmpty(editText.getText().toString())) {
                updateButtonState(false);
            }
            /* UNISOC: add for bug1086452 @{ */
            editText.setTextDirection(View.TEXT_DIRECTION_LTR);
            editText.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            /* @} */
            // UNISOC: add for bug967617
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
            editText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence charSequence, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(
                        CharSequence charSequence, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    String number = editable.toString().trim();
                    if (TextUtils.isEmpty(number)) {
                        updateButtonState(false);
                    } else {
                        updateButtonState(true);
                    }
                }
            });
        }
    }

    private void updateButtonState(boolean enabled) {
        if (mUpdateButton != null && mUpdateButton.isShown()) {
            mUpdateButton.setEnabled(enabled);
        } else if (mConfirmButton != null && mConfirmButton.isShown()) {
            mConfirmButton.setEnabled(enabled);
        }
    }
    /* @} */

    //UNISOC: add for bug1145309
    public void setCheckAllCfStatus(boolean value) {
        mCheckAllCfStatus = value;
    }

}

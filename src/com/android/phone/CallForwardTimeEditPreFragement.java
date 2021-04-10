package com.android.phone;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TimePicker;
import android.widget.Toast;

import com.android.phone.EditPhoneNumberPreference;
import com.android.phone.R;
import com.android.ims.internal.ImsCallForwardInfoEx;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmCdmaPhoneEx;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.uicc.IccRecords;

import com.google.android.collect.Sets;
import com.google.common.base.Preconditions;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;

public class CallForwardTimeEditPreFragement extends PreferenceFragment
        implements TimePickerDialog.OnTimeSetListener,
        EditPhoneNumberPreference.OnDialogClosedListener {

    private static final String LOG_TAG = CallForwardTimeEditPreFragement.class.getSimpleName();
    private static final String SRC_TAGS[] = {"{0}"};
    private static final String ACTION_CALLFORWARD_TIME_START =
            "com.android.phone.ACTION_CALLFORWARD_TIME_START";
    private static final String ACTION_CALLFORWARD_TIME_STOP =
            "com.android.phone.ACTION_CALLFORWARD_TIME_STOP";
    private static final String ICC_ID_KEY = "icc_id_key";
    private static final String IS_TIME_EDIT_DIALOG_SHOWING = "is_time_edit_dialog_showing";
    private static final String TIME_EDIT_PHONE_NUMBER = "time_edit_phone_number";
    private static final String CURRENT_TIME_MINUTE = "current_time_minute";
    private static final String CURRENT_TIME_HOUR = "current_time_hour";
    private static final String IS_TIME_PICKER_DIALOG_SHOWING="is_time_picker_dialog_showing";

    private Preference mStartTimePref;
    private Preference mStopTimePref;
    private EditPhoneNumberPreference mNumberEditPref;

    private boolean mIsStopTimeSetting;
    private static final int STARTTIME_SET = 1 << 0;
    private static final int STOPTIME_SET = 1 << 1;
    private static final int NUMBER_SET = 1 << 2;
    private int mSetNumber = 0;
    private int mSubId = 0;
    private int mStatus = 0;//UNISOC: add for bug1169146
    String mIccId;
    Context mContext;
    private int mPhoneId = 0;
    private static final boolean DBG = true;
    Phone mPhone;
    private GsmCdmaPhoneEx mGsmCdmaPhoneEx;
    private static final int TIME_ZERO = 0;
    private MyHandler mHandler = new MyHandler();
    String mStartTime;
    String mStopTime;
    String timeRule;
    private static final Set<Listener> mListeners = Sets.newArraySet();
    SharedPreferences mPrefs;
    static final String PREF_PREFIX_TIME = "phonecalltimeforward_";
    private TimePickerDialog mTimePickerDialog = null;
    private TimePicker mTimePicker;
    private int mCurrentHour;
    private int mCurrentMinute;
    private static final String START_TIME_FLAG = "start_time_flag";
    private static final String STOP_TIME_FLAG = "stop_time_flag";
    private static final String PHONE_ID = "phone_id";
    private PreferenceScreen mPreferenceScreen;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mContext = getContext();
        if (mContext == null) {
            return;
        }
        Bundle argument = this.getArguments();
        if (argument != null) {
            mPhoneId = argument.getInt(PHONE_ID);
        }
        mPhone = PhoneFactory.getPhone(mPhoneId);
        mGsmCdmaPhoneEx = (GsmCdmaPhoneEx) PhoneFactory.getPhone(mPhoneId);
        mSubId = mPhone.getSubId();
        mIccId = getIccId(mSubId);
        mPrefs = mContext.getSharedPreferences(PREF_PREFIX_TIME + mSubId, Context.MODE_PRIVATE);

        addPreferencesFromResource(R.xml.callforward_time_setting_ex);
        mPreferenceScreen = getPreferenceScreen();
        mStartTimePref = findPreference("start_time");
        mStopTimePref = findPreference("stop_time");
        mNumberEditPref = (EditPhoneNumberPreference) findPreference("cft_number_key");
        mNumberEditPref.setDialogOnClosedListener(this);

        String timeString = mPrefs.getString(PREF_PREFIX_TIME + "ruleset_" + mSubId, "");
        if (!TextUtils.isEmpty(timeString)) {
            try {
                mStartTime = timeString.substring(4, 9);
                mStopTime = timeString.substring(10, 15);
                updateTimeSummary(mStartTime, true);
                updateTimeSummary(mStopTime, false);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }
        updateNumberSummary(mPrefs.getString(PREF_PREFIX_TIME + "num_" + mSubId, ""));
        String strStatus = mPrefs.getString(PREF_PREFIX_TIME + "status_" + mSubId, "0");
        mStatus = TextUtils.isEmpty(strStatus) ? 0 : Integer.parseInt(strStatus);
    }

    public void restoreFromBoundle(Bundle outState){
        if (outState != null) {
            boolean isShowingEditDialog = outState.getBoolean(IS_TIME_EDIT_DIALOG_SHOWING);
            if (isShowingEditDialog) {
                String editPhoneNumber = outState.getString(TIME_EDIT_PHONE_NUMBER);
                if (!TextUtils.isEmpty(editPhoneNumber)) {
                    mNumberEditPref.setPhoneNumber(editPhoneNumber);
                }
            } else {
                String editPhoneNumber = outState.getString(TIME_EDIT_PHONE_NUMBER);
                if (!TextUtils.isEmpty(editPhoneNumber)) {
                    updateNumberSummary(editPhoneNumber);
                    mNumberEditPref.setPhoneNumber(editPhoneNumber);
                }
            }
            String startTime = outState.getString(START_TIME_FLAG);
            if (!TextUtils.isEmpty(startTime)) {
                mStartTime = startTime;
                updateTimeSummary(mStartTime, true);
            }
            String stopTime = outState.getString(STOP_TIME_FLAG);
            if (!TextUtils.isEmpty(stopTime)) {
                mStopTime = stopTime;
                updateTimeSummary(mStopTime, false);
            }
            boolean isTimePickerDialogShow = outState.getBoolean(IS_TIME_PICKER_DIALOG_SHOWING);
            if (isTimePickerDialogShow) {
                mCurrentMinute = outState.getInt(CURRENT_TIME_MINUTE);
                mCurrentHour = outState.getInt(CURRENT_TIME_HOUR);
                showCurrentTimePicker();
            }
        }
        if (getActivity() != null) {
            ((CallForwardTimeEditPreference) getActivity()).notifySetNumberChange();
        }
    }
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mStartTimePref || preference == mStopTimePref) {
            final Calendar calendar = Calendar.getInstance();
            mTimePickerDialog = new TimePickerDialog(
                    getActivity(),
                    this,
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true);
            mTimePickerDialog.show();
            if (preference == mStopTimePref) {
                mIsStopTimeSetting = true;
            }
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        log("onTimeSet hourOfDay = " + hourOfDay + "  minute = " + minute);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hourOfDay);
        c.set(Calendar.MINUTE, minute);

        SimpleDateFormat simpleFmt = new SimpleDateFormat("HH:mm");
        Date curDate = new Date(c.getTimeInMillis());
        String hhmm = simpleFmt.format(curDate);

        if (mIsStopTimeSetting) {
            mStopTime = hhmm;
            mStopTimePref.setSummary(mStopTime);
            updateTimeSummary(mStopTime, false);
            mIsStopTimeSetting = false;
        } else {
            mStartTime = hhmm;
            updateTimeSummary(mStartTime, true);
            mStartTimePref.setSummary(mStartTime);
        }
        notifySetNumberChange();
    }

    @Override
    public void onDialogClosed(EditPhoneNumberPreference preference, int buttonClicked) {
        log("onDialogClosed: request preference click on dialog close: " + buttonClicked);
        if (buttonClicked == DialogInterface.BUTTON_NEGATIVE) {
            if (getActivity() != null) {
                String defaultSetting = getActivity().getString(R.string.sum_cft_default_setting);
                if (!TextUtils.isEmpty(defaultSetting)
                        && defaultSetting.equals(mNumberEditPref.getSummary())) {
                    mNumberEditPref.setPhoneNumber("");
                }
            }
            return;
        }

        if (preference == mNumberEditPref) {
            final String number = mNumberEditPref.getPhoneNumber();
            CallForwardTimeUtils.getInstance(mContext).writeNumber(mIccId, number);
            updateNumberSummary(number);
            notifySetNumberChange();
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private void updateNumberSummary(String number) {
        CharSequence summaryOn;

        if (number != null && number.length() > 0) {
            summaryOn = number;
            mSetNumber |= NUMBER_SET;
            //add for unisoc 1178784
        } else if (getActivity() != null) {
            summaryOn = getActivity().getString(R.string.sum_cft_default_setting);
            mSetNumber &= (STARTTIME_SET | STOPTIME_SET);
        } else {
            return;
        }
        mNumberEditPref.setSummary(summaryOn);
        mNumberEditPref.setPhoneNumber(number);
    }

    private void updateTimeSummary(String time, boolean isStartTime) {
        CharSequence summaryOn;
        if (time != null && time.length() > 0) {
            summaryOn = time;
            mSetNumber |= isStartTime ? STARTTIME_SET : STOPTIME_SET;
            //add for unisoc 1178784
        } else if (getActivity() != null) {
            summaryOn = getActivity().getString(R.string.sum_cft_default_setting);
        } else {
            return;
        }
        if (isStartTime) {
            mStartTimePref.setSummary(summaryOn);
        } else {
            mStopTimePref.setSummary(summaryOn);
        }
    }

    protected void setParentActivity(int identifier) {
        mNumberEditPref.setParentActivity(getActivity(), identifier);
    }

    protected void onPickActivityResult(String pickedValue) {
        mNumberEditPref.onPickActivityResult(pickedValue);
    }

    protected int getSetNumber() {
        return mSetNumber;
    }

    protected int getStatus(){
        return mStatus;
    }

    protected void notifySetNumberChange() {
        //add for unisoc 1178784
        if (getActivity() != null) {
            ((CallForwardTimeEditPreference) getActivity()).notifySetNumberChange();
        }
    }

    protected void notifyFinish() {
        if (getActivity() != null) {
            ((CallForwardTimeEditPreference) getActivity()).dismissDialogAndFinish();
        }
    }

    protected void notifyShowDialog() {
        if (getActivity() != null) {
            ((CallForwardTimeEditPreference) getActivity()).showDialog();
        }
    }

    private class MyHandler extends Handler {
        static final int MESSAGE_GET_TIME_CF = 0;
        static final int MESSAGE_SET_TIME_CF = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SET_TIME_CF:
                    handleSetCFResponse(msg);
                    break;
                case MESSAGE_GET_TIME_CF:
                    handleGetCFResponse(msg);
                    break;
                default:
                    break;
            }
        }

        private void handleGetCFResponse(Message msg) {
            notifyFinish();
            AsyncResult ar = (AsyncResult) msg.obj;
            if (DBG) {
                log("handleGetCFResponse: done...msg1=" + msg.arg1 + " / msg2=" + msg.arg2);
            }
            if ((ar.userObj instanceof Throwable) || ar.exception != null
                    || msg.arg2 != MyHandler.MESSAGE_SET_TIME_CF) {
                if (mContext != null) {
                    Toast.makeText(mContext, R.string.response_error, Toast.LENGTH_LONG).show();
                }
                return;
            }
            ImsCallForwardInfoEx cfInfoArray[] = (ImsCallForwardInfoEx[]) ar.result;
            if (cfInfoArray.length == 0) {
                if (DBG) {
                    Log.d(LOG_TAG, "handleGetCFResponse: cfInfoArray.length==0");
                }
                if (mContext != null) {
                    Toast.makeText(mContext, R.string.response_error, Toast.LENGTH_LONG).show();
                }
            } else {
                for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                    if (DBG) {
                        Log.d(LOG_TAG, "handleGetCFResponse, cfInfoArray[" + i + "]="
                                + cfInfoArray[i]);
                    }
                    if ((CommandsInterface.SERVICE_CLASS_VOICE & cfInfoArray[i].mServiceClass) != 0
                            && (CommandsInterface.CF_REASON_UNCONDITIONAL
                            == cfInfoArray[i].mCondition)) {

                        String number = cfInfoArray[i].mNumber;
                        String numberToSave = null;

                        if (!TextUtils.isEmpty(number)
                                && !TextUtils.isEmpty(cfInfoArray[i].mRuleset)) {
                            numberToSave = handleNum(number);
                            savePrefData(PREF_PREFIX_TIME + "num_" + mSubId, numberToSave);
                            savePrefData(PREF_PREFIX_TIME + "ruleset_" + mSubId,
                                    cfInfoArray[i].mRuleset);
                            savePrefData(PREF_PREFIX_TIME + "status_" + mSubId,
                                    String.valueOf(cfInfoArray[i].mStatus));
                            CallForwardTimeUtils.getInstance(mContext).writeStatus(
                                    mIccId, cfInfoArray[i].mStatus);
                        } else {
                            if (!TextUtils.isEmpty(number)) {//UNISOC:add for bug1178792
                                numberToSave = handleNum(number);
                                savePrefData(PREF_PREFIX_TIME + "num_" + mSubId, numberToSave);
                            } else {
                                savePrefData(PREF_PREFIX_TIME + "num_" + mSubId, "");
                            }
                            savePrefData(PREF_PREFIX_TIME + "ruleset_" + mSubId, "");
                            savePrefData(PREF_PREFIX_TIME + "status_" + mSubId, "");
                            CallForwardTimeUtils.getInstance(mContext).writeStatus(mIccId, 0);
                        }
                        if (mListeners != null) {
                            for (Listener listener : mListeners) {
                                listener.onCallForawrdTimeStateChanged(numberToSave);
                            }
                        }
                    }
                }
            }
        }

        private String handleNum(String num) {//UNISOC:add for bug1178792
            if (PhoneNumberUtils.isUriNumber(num)){
                return num;
            } else {
                return  PhoneNumberUtils.stripSeparators(num);
            }
        }

        private void handleSetCFResponse(Message msg) {
            if (DBG) {
                log("handleSetCFResponse: done");
            }
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) {
                    log("handleSetCFResponse: ar.exception=" + ar.exception);
                }
                if (mContext != null) {
                    Toast.makeText(mContext, R.string.exception_error, Toast.LENGTH_LONG).show();
                }
                notifyFinish();
            } else {
                mGsmCdmaPhoneEx.getCallForwardingOption(CommandsInterface.CF_REASON_UNCONDITIONAL,
                        CommandsInterface.SERVICE_CLASS_VOICE,
                        null,
                        mHandler.obtainMessage(MyHandler.MESSAGE_GET_TIME_CF,
                                msg.arg1,
                                msg.arg2));
            }
        }

    }

    protected void onMenuOKClicked(String status) {
        if (mPreferenceScreen != null) {
            mPreferenceScreen.setEnabled(false);
        }
        notifyShowDialog();
        int action = ("MENU_OK".equals(status))
                ? CommandsInterface.CF_ACTION_REGISTRATION : CommandsInterface.CF_ACTION_DISABLE;
        String number = mNumberEditPref.getPhoneNumber();
        timeRule = "time" + String.valueOf(mStartTime) + "," + String.valueOf(mStopTime);
        if (null == mStartTime || null == mStopTime) {
            timeRule = mPrefs.getString(PREF_PREFIX_TIME + "ruleset_" + mSubId, "");
        }
        if (null == number) {
            number = mPrefs.getString(PREF_PREFIX_TIME + "num_" + mSubId, "");
        }
        int reason = CommandsInterface.CF_REASON_UNCONDITIONAL;
        int serviceClass = CommandsInterface.SERVICE_CLASS_VOICE;
        Log.d(LOG_TAG, "reason=" + reason + "  action=" + action + " number="
                + number + "  timeRule=" + timeRule);
        mGsmCdmaPhoneEx.setCallForwardingOption(action,
                reason,
                serviceClass,
                number,
                TIME_ZERO,
                timeRule,
                mHandler.obtainMessage(MyHandler.MESSAGE_SET_TIME_CF,
                        action,
                        MyHandler.MESSAGE_SET_TIME_CF));

    }

    public static void addListener(Listener listener) {
        Preconditions.checkNotNull(listener);
        mListeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        Preconditions.checkNotNull(listener);
        mListeners.remove(listener);
    }

    public interface Listener {
        public void onCallForawrdTimeStateChanged(String num);
    }

    private String getIccId(int subId) {
        String iccId = "";
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            IccCard iccCard = phone.getIccCard();
            if (iccCard != null) {
                IccRecords iccRecords = iccCard.getIccRecords();
                if (iccRecords != null) {
                    iccId = iccRecords.getIccId();
                }
            }
        }
        return iccId;
    }

    public void savePrefData(String key, String value) {
        Log.w(LOG_TAG, "savePrefData(" + key + ", " + value + ")");
        if (mPrefs != null) {
            SharedPreferences.Editor editor = mPrefs.edit();
            editor.putString(key, value);
            editor.apply();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mTimePickerDialog != null && mTimePickerDialog.isShowing()) {
            mTimePicker = mTimePickerDialog.getTimePicker();
            mCurrentHour = mTimePicker.getCurrentHour();
            mCurrentMinute = mTimePicker.getCurrentMinute();
            mTimePickerDialog.dismiss();
            showCurrentTimePicker();
        }
        super.onConfigurationChanged(newConfig);
    }

    private void showCurrentTimePicker() {
        mTimePickerDialog = new TimePickerDialog(
                getActivity(),
                this,
                mCurrentHour,
                mCurrentMinute,
                true);
        mTimePickerDialog.show();
    }

    public EditPhoneNumberPreference getEditPhoneNumberPreference() {
        return mNumberEditPref;
    }
    public TimePickerDialog getTimePickerDialog () {
        return mTimePickerDialog;
    }

    public Preference getStartTimePref() {
        return mStartTimePref;
    }

    public Preference getStopTimePref() {
        return mStopTimePref;
    }
}

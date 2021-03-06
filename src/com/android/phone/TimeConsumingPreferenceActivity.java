package com.android.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.WindowManager;

import com.android.ims.ImsManager;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Phone;

import android.os.Bundle;
import android.os.PersistableBundle;
import java.util.ArrayList;

interface  TimeConsumingPreferenceListener {
    public void onStarted(Preference preference, boolean reading);
    public void onFinished(Preference preference, boolean reading);
    public void onError(Preference preference, int error);
    public void onException(Preference preference, CommandException exception);
    /* UNISOC: add for FEATURE_VIDEO_CALL_FOR @{ */
    public void onEnableStatus(Preference preference, int status);
    /* @} */
}

public class TimeConsumingPreferenceActivity extends PreferenceActivity
                        implements TimeConsumingPreferenceListener,
                        DialogInterface.OnCancelListener {
    private static final String LOG_TAG = "TimeConsumingPrefActivity";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private Phone mPhone = null;
    private ImsManager mImsManager = null;

    // add for Bug 1071722
    public void initParent(Phone phone) {
        mPhone = phone;
        if (mPhone != null) {
            mImsManager = new ImsManager(this, mPhone.getPhoneId());
        }
    }

    private class DismissOnClickListener implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
            finish();//UNISOC: add for bug 952922
        }
    }
    private class DismissAndFinishOnClickListener implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
            finish();
        }
    }
    private final DialogInterface.OnClickListener mDismiss = new DismissOnClickListener();
    private final DialogInterface.OnClickListener mDismissAndFinish
            = new DismissAndFinishOnClickListener();

    private static final int BUSY_READING_DIALOG = 100;
    private static final int BUSY_SAVING_DIALOG = 200;
    static final int EXCEPTION_ERROR = 300;
    static final int RESPONSE_ERROR = 400;
    static final int RADIO_OFF_ERROR = 500;
    static final int FDN_CHECK_FAILURE = 600;
    static final int STK_CC_SS_TO_DIAL_ERROR = 700;
    static final int STK_CC_SS_TO_USSD_ERROR = 800;
    static final int STK_CC_SS_TO_SS_ERROR = 900;
    static final int STK_CC_SS_TO_DIAL_VIDEO_ERROR = 1000;
    // UNISOC: add for bug1071722
    static final int VOLTE_NOT_SUPPORT_ERROR = 1100;
    private static final String SAVE_DIALOG_PREFERENCE_KEY = "save_dialog_preference_key";
    private static final String SAVE_DIALOG_ERROR = "save_dialog_error";
    private static final String BUTTON_CLIR_KEY = "button_clir_key";


    private final ArrayList<String> mBusyList = new ArrayList<String>();

    protected boolean mIsForeground = false;
    private String  mDlgPrerenceKey;
    private int mDlgError = -1;

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == BUSY_READING_DIALOG || id == BUSY_SAVING_DIALOG) {
            ProgressDialog dialog = new ProgressDialog(this);
            dialog.setTitle(getText(R.string.updating_title));
            dialog.setIndeterminate(true);

            switch(id) {
                case BUSY_READING_DIALOG:
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(this);
                    dialog.setMessage(getText(R.string.reading_settings));
                    return dialog;
                case BUSY_SAVING_DIALOG:
                    dialog.setCancelable(false);
                    dialog.setMessage(getText(R.string.updating_settings));
                    return dialog;
            }
            return null;
        }

        if (id == RESPONSE_ERROR || id == RADIO_OFF_ERROR || id == EXCEPTION_ERROR
                || id == FDN_CHECK_FAILURE || id == STK_CC_SS_TO_DIAL_ERROR
                || id == STK_CC_SS_TO_USSD_ERROR || id == STK_CC_SS_TO_SS_ERROR
                || id == STK_CC_SS_TO_DIAL_VIDEO_ERROR
                || id == VOLTE_NOT_SUPPORT_ERROR) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            int msgId;
            int titleId = R.string.error_updating_title;

            switch (id) {
                case RESPONSE_ERROR:
                    msgId = R.string.response_error;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case RADIO_OFF_ERROR:
                    msgId = R.string.radio_off_error;
                    // The error is not recoverable on dialog exit.
                    builder.setPositiveButton(R.string.close_dialog, mDismissAndFinish);
                    break;
                case FDN_CHECK_FAILURE:
                    msgId = R.string.fdn_check_failure;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case STK_CC_SS_TO_DIAL_ERROR:
                    msgId = R.string.stk_cc_ss_to_dial_error;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case STK_CC_SS_TO_USSD_ERROR:
                    msgId = R.string.stk_cc_ss_to_ussd_error;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case STK_CC_SS_TO_SS_ERROR:
                    msgId = R.string.stk_cc_ss_to_ss_error;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case STK_CC_SS_TO_DIAL_VIDEO_ERROR:
                    msgId = R.string.stk_cc_ss_to_dial_video_error;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case VOLTE_NOT_SUPPORT_ERROR:
                    msgId = R.string.volte_not_supported_error;
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
                case EXCEPTION_ERROR:
                default:
                    msgId = R.string.exception_error;
                    // The error is not recoverable on dialog exit.
                    builder.setPositiveButton(R.string.close_dialog, mDismiss);
                    break;
            }

            builder.setTitle(getText(titleId));
            builder.setMessage(getText(msgId));
            builder.setCancelable(false);
            AlertDialog dialog = builder.create();

            // make the dialog more obvious by blurring the background.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            return dialog;
        }
        return null;
    }

    /* UNISOC: add for 1184393 show dialog when recreate activity @{ */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDlgPrerenceKey = savedInstanceState != null ? savedInstanceState.getString(SAVE_DIALOG_PREFERENCE_KEY) : null;
        mDlgError = savedInstanceState != null ? savedInstanceState.getInt(SAVE_DIALOG_ERROR) : -1;
    }
    /* @} */


    @Override
    public void onResume() {
        super.onResume();
        mIsForeground = true;
        // UNISOC: add for bug1024083
        showErrorDialogAgain();
    }

    @Override
    public void onPause() {
        super.onPause();
        mIsForeground = false;
    }

    @Override
    public void onStarted(Preference preference, boolean reading) {
        if (DBG) dumpState();
        Log.i(LOG_TAG, "onStarted, preference=" + preference.getKey() + ", reading=" + reading);
        mBusyList.add(preference.getKey());

        if (mIsForeground) {
              if (reading) {
                  showDialog(BUSY_READING_DIALOG);
              } else {
                  showDialog(BUSY_SAVING_DIALOG);
              }
        }

    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (DBG) dumpState();
        Log.i(LOG_TAG, "onFinished, preference=" + preference.getKey() + ", reading=" + reading);
        mBusyList.remove(preference.getKey());

        if (mBusyList.isEmpty()) {
            if (reading) {
                dismissDialogSafely(BUSY_READING_DIALOG);
            } else {
                dismissDialogSafely(BUSY_SAVING_DIALOG);
            }
        }
        preference.setEnabled(true);
    }

    @Override
    public void onError(Preference preference, int error) {
        if (DBG) dumpState();
        if (preference == null) {
            return;
        }
        Log.i(LOG_TAG, "onError, preference=" + preference.getKey() + ", error=" + error);
        // add for Bug 1071722
        if (getResources().getBoolean(R.bool.config_display_ut_not_support_message_for_cdma)
                && mPhone != null && mImsManager != null
                && mImsManager.isEnhanced4gLteModeSettingEnabledByUser()
                && PhoneUtils.isCtCard(this, mPhone.getPhoneId())
                && mPhone.getImsPhone() != null
                && !mPhone.getImsPhone().isUtEnabled()) {
            Log.d(LOG_TAG, "change error code to VOLTE_NOT_SUPPORT_ERROR.");
            error = VOLTE_NOT_SUPPORT_ERROR;
        }

        if (mIsForeground) {
            /* UNISOC: add for feature not show error dialog for clir @{ */
            if (preference instanceof CLIRListPreference) {
                preference.setSummary(R.string.sum_default_caller_id);
            } else {
                showDialog(error);
            }
            /* @} */
        }
        // UNISOC: add for bug1024083
        if(!mIsForeground) {
            saveDialogInfo(preference.getKey(), error);
        }

        preference.setEnabled(false);
    }

    /* UNISOC: add for bug1024083 @{ */
    private void saveDialogInfo(String preferenceKey, int error){
        mDlgPrerenceKey = preferenceKey;
        mDlgError = error;
    }

    private void showErrorDialogAgain(){
        if(mDlgPrerenceKey != null && mDlgError != -1){
            if (!BUTTON_CLIR_KEY.equals(mDlgPrerenceKey)) {
                showDialog(mDlgError);
            }
            mDlgError = -1;
            mDlgPrerenceKey = null;
        }
    }
    /* @} */

    @Override
    public void onException(Preference preference, CommandException exception) {
        if (exception.getCommandError() == CommandException.Error.FDN_CHECK_FAILURE) {
            onError(preference, FDN_CHECK_FAILURE);
        } else if (exception.getCommandError() == CommandException.Error.RADIO_NOT_AVAILABLE) {
            onError(preference, RADIO_OFF_ERROR);
        } else if (exception.getCommandError() == CommandException.Error.SS_MODIFIED_TO_DIAL) {
            onError(preference, STK_CC_SS_TO_DIAL_ERROR);
        } else if (exception.getCommandError() == CommandException.Error
                .SS_MODIFIED_TO_DIAL_VIDEO) {
            onError(preference, STK_CC_SS_TO_DIAL_VIDEO_ERROR);
        } else if (exception.getCommandError() == CommandException.Error.SS_MODIFIED_TO_USSD) {
            onError(preference, STK_CC_SS_TO_USSD_ERROR);
        } else if (exception.getCommandError() == CommandException.Error.SS_MODIFIED_TO_SS) {
            onError(preference, STK_CC_SS_TO_SS_ERROR);
        } else {
            preference.setEnabled(false);
            onError(preference, EXCEPTION_ERROR);
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        if (DBG) dumpState();
        finish();
    }

    protected void dismissDialogSafely(int id) {
        try {
            dismissDialog(id);
        } catch (IllegalArgumentException e) {
            // This is expected in the case where we were in the background
            // at the time we would normally have shown the dialog, so we didn't
            // show it.
        }
    }

    /* package */ void dumpState() {
        Log.d(LOG_TAG, "dumpState begin");
        for (String key : mBusyList) {
            Log.d(LOG_TAG, "mBusyList: key=" + key);
        }
        Log.d(LOG_TAG, "dumpState end");
    }
    /* UNISOC: add for FEATURE_VIDEO_CALL_FOR @{ */
    @Override
    public void onEnableStatus(Preference preference, int status) {
    }

    /* @} */
    /* UNISOC: add for 1184393 if onError is reported when activity is background, save it
       and show dialog when it's re-created. @{ */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mDlgPrerenceKey != null) {
            outState.putString(SAVE_DIALOG_PREFERENCE_KEY, mDlgPrerenceKey);
            outState.putInt(SAVE_DIALOG_ERROR, mDlgError);
        }
    }
    /* @} */
}

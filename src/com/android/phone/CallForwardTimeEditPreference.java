package com.android.phone;

import android.app.ActionBar;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.provider.ContactsContract.CommonDataKinds;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.EditPhoneNumberPreference;
import com.android.phone.R;
import com.android.ims.internal.ImsCallForwardInfoEx;
import android.content.SharedPreferences;
import android.widget.TimePicker;
import android.app.TimePickerDialog;
import android.app.FragmentManager;

public class CallForwardTimeEditPreference extends PreferenceActivity {

    private static final String LOG_TAG = CallForwardTimeEditPreference.class.getSimpleName();
    protected static final int CFT_PREF_ID = 1;
    private static final String NUM_PROJECTION[] = { CommonDataKinds.Phone.NUMBER };
    private static final int SET_OK = (1 << 0) | (1 << 1) | (1 << 2);
    protected static final int MENU_CANCLE = Menu.FIRST;
    protected static final int MENU_OK = Menu.FIRST + 2;
    protected static final int MENU_DISABLE = Menu.FIRST + 1;
    CallForwardTimeEditPreFragement mFragment;
    Menu mOptionMenu;
    private int mPhoneId = 0;
    private static final String IS_TIME_EDIT_DIALOG_SHOWING = "is_time_edit_dialog_showing";
    private static final String TIME_EDIT_PHONE_NUMBER = "time_edit_phone_number";
    private static final String START_TIME_FLAG = "start_time_flag";
    private static final String STOP_TIME_FLAG = "stop_time_flag";
    private static final String PHONE_ID = "phone_id";
    private static final String CURRENT_TIME_MINUTE = "current_time_minute";
    private static final String CURRENT_TIME_HOUR = "current_time_hour";
    private static final String IS_TIME_PICKER_DIALOG_SHOWING="is_time_picker_dialog_showing";
    private static final String CALLFORWARDTIME_FRAGMENT_TAG = "callforwardtime_fragment_tag";
    private boolean mIsForeground = false;
    private ProgressDialog mSavingDialog;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        FragmentManager.enableDebugLogging(true);//UNISOC:add for bug1188722
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        //UNISOC: add for bug1170103
        if (getIntent() != null && getIntent().getStringExtra("phone_id") != null) {
            mPhoneId = Integer.valueOf(getIntent().getStringExtra("phone_id"));
        }

        // UNISOC: Add for bug1170063
        if (getFragmentManager().findFragmentByTag(CALLFORWARDTIME_FRAGMENT_TAG) == null ) {
            mFragment = new CallForwardTimeEditPreFragement();
            getFragmentManager().beginTransaction().replace(android.R.id.content, mFragment, CALLFORWARDTIME_FRAGMENT_TAG).commit();
         } else {
            mFragment = (CallForwardTimeEditPreFragement)getFragmentManager().findFragmentByTag(CALLFORWARDTIME_FRAGMENT_TAG);
         }
        Bundle arguments;
        if (icicle != null) {
            arguments = new Bundle(icicle);
        } else {
            arguments = new Bundle();
        }
        arguments.putInt(PHONE_ID, mPhoneId);
        // SPRD: add for bug560757
        mFragment.setArguments(arguments);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsForeground = true;
        mFragment.setParentActivity(CFT_PREF_ID);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsForeground = false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i("CallForwardTimeEditPreference", "onActivityResult: requestCode: " + requestCode
                + ", resultCode: " + resultCode
                + ", data: " + data);
        if (requestCode == CFT_PREF_ID) {
            if (resultCode != RESULT_OK) {
                log("onActivityResult: contact picker result not OK.");
                return;
            }

            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(data.getData(),
                        NUM_PROJECTION, null, null, null);
                if ((cursor == null) || (!cursor.moveToFirst())) {
                    log("onActivityResult: bad contact data, no results found.");
                    return;
                }
                mFragment.onPickActivityResult(cursor.getString(0));
                return;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        String sumCftDefaultSetting = getString(R.string.sum_cft_default_setting);
        EditPhoneNumberPreference phoneNumberPreference = mFragment.getEditPhoneNumberPreference();
        if (phoneNumberPreference != null) {
            if (phoneNumberPreference.getDialog() != null) {
                outState.putBoolean(IS_TIME_EDIT_DIALOG_SHOWING, phoneNumberPreference.getDialog()
                        .isShowing());
                EditText phoneNumberEditText = phoneNumberPreference.getEditText();
                if (phoneNumberEditText != null && phoneNumberEditText.getText() != null) {
                    outState.putString(TIME_EDIT_PHONE_NUMBER,
                            phoneNumberEditText.getText().toString());
                }
            } else {
                String phoneNumSummary = phoneNumberPreference.getSummary().toString();
                if (!TextUtils.equals(phoneNumSummary, sumCftDefaultSetting)) {
                    outState.putString(TIME_EDIT_PHONE_NUMBER, phoneNumSummary);
                }
            }
        }
        Preference startTimePre = mFragment.getStartTimePref();
        if (startTimePre != null) {
            String startTimeSummary = startTimePre.getSummary().toString();
            if (!TextUtils.equals(startTimeSummary, sumCftDefaultSetting)) {
                outState.putString(START_TIME_FLAG, startTimeSummary);
            }
        }
        Preference stopTimePre = mFragment.getStopTimePref();
        if (stopTimePre != null) {
            String stopTimeSummary = stopTimePre.getSummary().toString();
            if (!TextUtils.equals(stopTimeSummary, sumCftDefaultSetting)) {
                outState.putString(STOP_TIME_FLAG, stopTimeSummary);
            }
        }
        //add for unisoc 1178784,1195978
        if (mFragment.getTimePickerDialog() != null && mFragment.getTimePickerDialog().isShowing()
                && mFragment.getTimePickerDialog().getTimePicker() != null) {
            TimePicker mTimePicker = mFragment.getTimePickerDialog().getTimePicker();
            outState.putBoolean(IS_TIME_PICKER_DIALOG_SHOWING, true);
            outState.putInt(CURRENT_TIME_MINUTE, mTimePicker.getCurrentMinute());
            outState.putInt(CURRENT_TIME_HOUR, mTimePicker.getCurrentHour());
        }
    }

    protected void onRestoreInstanceState(Bundle outState) {
        super.onRestoreInstanceState(outState);
        if (mFragment != null) {
            mFragment.restoreFromBoundle(outState);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        mOptionMenu = menu;
        menu.add(1, MENU_DISABLE, 0, R.string.disable).setShowAsAction(
                MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(2, MENU_OK, 0, R.string.save).setShowAsAction(
                MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        notifySetNumberChange();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case MENU_OK:
                mFragment.onMenuOKClicked("MENU_OK");
                return true;
            case MENU_DISABLE:
                mFragment.onMenuOKClicked("MENU_DISABLE");
                return true;
            case MENU_CANCLE:
                finish();
                return true;
            case android.R.id.home:
                finish();
                return true;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public void notifySetNumberChange() {
        int setNumber = mFragment.getSetNumber();
        log("notifySetNumberChange  setNumber = " + setNumber);
        if (setNumber == SET_OK) {
            if (mFragment.getStatus() == CommandsInterface.CF_ACTION_ENABLE) {
                //UNISOC: add for bug1169146
                mOptionMenu.setGroupVisible(1, true);
            }
            mOptionMenu.setGroupVisible(2, true);
        } else {
            //add for unisoc 1188772
            mOptionMenu.setGroupVisible(1, mFragment.getStatus() == CommandsInterface.CF_ACTION_ENABLE ? true : false);
            mOptionMenu.setGroupVisible(2, false);
        }
    }

    private void notifyMenuEnable() {
        if (mOptionMenu != null) {
            int size = mOptionMenu.size();
            for (int i = 0; i < size; i++) {
                mOptionMenu.setGroupEnabled(i, false);
            }
        }
    }

    public void showDialog() {
        notifyMenuEnable();
        if (mIsForeground) {
            mSavingDialog = new ProgressDialog(this);
            mSavingDialog.setTitle(getText(R.string.updating_title));
            mSavingDialog.setIndeterminate(true);
            mSavingDialog.setCancelable(false);
            mSavingDialog.setMessage(getText(R.string.updating_settings));
            mSavingDialog.show();
        }
    }

    public void dismissDialogAndFinish() {
        if (mSavingDialog != null) {
            if (mSavingDialog.isShowing()) {
                mSavingDialog.dismiss();
            }
            mSavingDialog = null;
        }
        finish();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}

/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone.settings.fdn;

import static android.view.Window.PROGRESS_VISIBILITY_OFF;
import static android.view.Window.PROGRESS_VISIBILITY_ON;

import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.Window;
import android.widget.Toast;

import com.android.internal.telephony.Phone;
import com.android.phone.PhoneGlobals;
import com.android.phone.R;
import com.android.phone.SubscriptionInfoHelper;
import com.android.phone.settings.ActivityContainer;
import com.android.phone.settings.IccUriUtils;

/**
 * Activity to let the user delete an FDN contact.
 */
public class DeleteFdnContactScreen extends Activity {
    private static final String LOG_TAG = PhoneGlobals.LOG_TAG;
    private static final boolean DBG = false;

    private static final String INTENT_EXTRA_NAME = "name";
    private static final String INTENT_EXTRA_NUMBER = "number";

    private static final int PIN2_REQUEST_CODE = 100;

    private SubscriptionInfoHelper mSubscriptionInfoHelper;

    private String mName;
    private String mNumber;
    private String mPin2;

    protected QueryHandler mQueryHandler;

    /* UNISOC: delete for function FDN support. @{
     * @orig
     * private Handler mHandler = new Handler();
     **/
    /* SPRD: function FDN support. @{ */
    private static final int EVENT_PIN2_ENTRY_COMPLETE = PIN2_REQUEST_CODE + 1;
    private static final int RESULT_PIN2_TIMEOUT = PIN2_REQUEST_CODE + 2;
    private static final String FDN_UPDATE_ACTION = "android.callsettings.action.FDN_LIST_CHANGED";
    private static final String REMAINTIMES_KEY = "times";
    private static final String INTENT_EXTRA_SUB_ID = "subid";
    private Phone mPhone;
    private int mRemainPin2Times =  IccUriUtils.MAX_INPUT_TIMES;
    private static final String PROPERTY_PIN2_REMAINTIMES = "vendor.sim.pin2.remaintimes";
    private ActivityContainer mActivityContainer;
    private static final String PHONE_ID = "phone_id";
    private int mPhoneId = -1;
    /* }@ */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        resolveIntent();

        /* UNISOC: function FDN support. @{ */
        int mPhoneId = mPhone.getPhoneId();
        mRemainPin2Times = IccUriUtils.getInstance().getPIN2RemainTimes(getBaseContext(), mPhoneId);
        if (DBG) {
            log("onCreate(): [SUBSCRIPTION " + mSubscriptionInfoHelper.getSubId() +
                    "]: Remain pin2 times = " + mRemainPin2Times);
        }
        if (null == icicle) {
            authenticatePin2();
        }
        /* }@ */

        getWindow().requestFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.delete_fdn_contact_screen);
        /* SPRD: add for bug645817 @{ */
        mActivityContainer = ActivityContainer.getInstance();
        mActivityContainer.setApplication(getApplication());
        mActivityContainer.addActivity(this, mPhoneId);
        /* @} */
    }

    /* UNISOC: function FDN support. @{ */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        //SPRD Add back pressed behavior for Delete FDN Contact Screen
        case android.R.id.home:
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    /* }@ */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (DBG) log("onActivityResult");

        switch (requestCode) {
            case PIN2_REQUEST_CODE:
                Bundle extras = (intent != null) ? intent.getExtras() : null;
                if (extras != null) {
                    mPin2 = extras.getString("pin2");
                    /* UNISOC: function FDN support.. @{
                     * @orig
                     * showStatus(getResources().getText(R.string.deleting_fdn_contact));
                     * deleteContact();
                     **/
                    checkPin2(mPin2);
                    /* }@ */
                } else {
                    // if they cancelled, then we just cancel too.
                    if (DBG) log("onActivityResult: CANCELLED");
                    displayProgress(false);
                    finish();
                }
                break;
        }
    }

    private void resolveIntent() {
        Intent intent = getIntent();

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, intent);

        mPhone = mSubscriptionInfoHelper.getPhone();
        mName =  intent.getStringExtra(INTENT_EXTRA_NAME);
        mNumber =  intent.getStringExtra(INTENT_EXTRA_NUMBER);

        /** Orig
         * UNISOC: Add for bug980158 can't delete FDN list when number is null.
         * if (TextUtils.isEmpty(mNumber)) {
         * finish();
         * }
         */
    }

    private void deleteContact() {
        /** Orig
        StringBuilder buf = new StringBuilder();
        if (TextUtils.isEmpty(mName)) {
            buf.append("number='");
        } else {
            buf.append("tag='");
            buf.append(mName);
            buf.append("' AND number='");
        }
        buf.append(mNumber);
        buf.append("' AND pin2='");
        buf.append(mPin2);
        buf.append("'");*/
        Uri uri = FdnList.getContentUri(mSubscriptionInfoHelper);
        //add for 1190083 can't delete FDN contact with "AND" in name
        String selection = "tag=? AND number=? AND pin2=? ";
        String[] args = new String[]{mName, mNumber, mPin2};
        if (TextUtils.isEmpty(mName)) {
            selection = "number=? AND pin2=? ";
            args = new String[]{mNumber, mPin2};
        }
        mQueryHandler = new QueryHandler(getContentResolver());
        mQueryHandler.startDelete(0, null, uri, selection, args);
        displayProgress(true);
    }
    private void authenticatePin2() {
        Intent intent = new Intent();
        intent.setClass(this, GetPin2Screen.class);
        /* UNISOC: function FDN support. @{ */
        intent.putExtra(REMAINTIMES_KEY, mRemainPin2Times);
        intent.putExtra(PHONE_ID, mPhoneId);
        /* }@ */
        intent.setData(FdnList.getContentUri(mSubscriptionInfoHelper));
        startActivityForResult(intent, PIN2_REQUEST_CODE);
    }

    private void displayProgress(boolean flag) {
        getWindow().setFeatureInt(
                Window.FEATURE_INDETERMINATE_PROGRESS,
                flag ? PROGRESS_VISIBILITY_ON : PROGRESS_VISIBILITY_OFF);
    }

    // Replace the status field with a toast to make things appear similar
    // to the rest of the settings.  Removed the useless status field.
    private void showStatus(CharSequence statusMsg) {
        if (statusMsg != null) {
            Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT)
            .show();
        }
    }

    private void handleResult(boolean success) {
        if (success) {
            if (DBG) log("handleResult: success!");
            showStatus(getResources().getText(R.string.fdn_contact_deleted));
        } else {
            if (DBG) log("handleResult: failed!");
            if (mRemainPin2Times > 0) {
                showStatus(getResources().getText(R.string.pin2_invalid));
            }
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 2000);

    }

    private class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor c) {
        }

        @Override
        protected void onInsertComplete(int token, Object cookie, Uri uri) {
        }

        @Override
        protected void onUpdateComplete(int token, Object cookie, int result) {
        }

        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) {
            if (DBG) log("onDeleteComplete");
            displayProgress(false);
            handleResult(result > 0);
            // SPRD: add for Bug 617999
            sendBroadcast();
        }

    }

    /* SPRD: add for Bug 617999 @{ */
    private void sendBroadcast() {
        Intent intent = new Intent(FDN_UPDATE_ACTION);
        intent.putExtra(INTENT_EXTRA_SUB_ID, mSubscriptionInfoHelper.getSubId());
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent);
    }
    /* @{ */

    /* SPRD: function FDN support. @{ */
    protected void checkPin2(String pin2) {
        log("checkPin2: pin2 = " + pin2);

        if (IccUriUtils.validatePin(pin2, false)) {
            Message onComplete = mHandler.obtainMessage(EVENT_PIN2_ENTRY_COMPLETE);
            mPhone.getIccCard().supplyPin2(pin2, onComplete);
        } else {
            // throw up error if the pin is invalid.
            showStatus(getResources().getText(R.string.invalidPin2));
            finish();
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // when we are enabling FDN, either we are unsuccessful and
                // display a toast, or just update the UI.
                case EVENT_PIN2_ENTRY_COMPLETE:
                    if (DBG) {
                        log(" EVENT_PIN2_ENTRY_COMPLETE ");
                    }
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        mRemainPin2Times -= 1;
                        if (DBG) {
                            log(" EVENT_PIN2_ENTRY_COMPLETE remainTimesPIN2 = "
                                    + mRemainPin2Times);
                        }
                        if (mRemainPin2Times > 0) {
                            showStatus(getResources().getText(R.string.pin2_invalid));
                            authenticatePin2();
                        } else {
                            showStatus(getResources().getText(R.string.puk2_requested));
                            setResult(RESULT_PIN2_TIMEOUT);
                            finish();
                        }
                    } else {
                        showStatus(getResources().getText(
                                R.string.deleting_fdn_contact));
                        deleteContact();
                    }
                    break;
            }
        }
    };
    /* }@ */

    /* SPRD: add for bug645817 @{ */
    protected void onDestroy() {
        super.onDestroy();
        if (mActivityContainer != null) {
            mActivityContainer.removeActivity(this);
        }
    }
    /* @} */

    private void log(String msg) {
        Log.d(LOG_TAG, "[DeleteFdnContact] " + msg);
    }
}

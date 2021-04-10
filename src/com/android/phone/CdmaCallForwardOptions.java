
package com.android.phone;

import android.app.ActionBar;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import java.util.ArrayList;

import com.android.internal.telephony.Phone;

/**
 * UNISOC:CDMA Call forward Settings Activity.
 */
public class CdmaCallForwardOptions extends PreferenceActivity {
    private static final String LOG_TAG = "CdmaCallForwardOptions";

    private static final String BUTTON_CFU_KEY = "button_cfu_key";
    private static final String BUTTON_CFB_KEY = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";
    private static final String BUTTON_CFC_KEY = "button_cfc_key";

    private static final int DIALOG_CFU_KEY = 0;
    private static final int DIALOG_CFB_KEY = 1;
    private static final int DIALOG_CFNRY_KEY = 2;
    private static final int DIALOG_CFNRC_KEY = 3;

    private static final String ENABLE_CFU = "*72";
    private static final String ENABLE_CFB = "*90";
    private static final String ENABLE_CFNRY = "*92";
    private static final String ENABLE_CFNRC = "*68";
    private static final String ENABLE_CFC = "*730";

    private static final String DISABLE_CFU = "*720";
    private static final String DISABLE_CFB = "*900";
    private static final String DISABLE_CFNRY = "*920";
    private static final String DISABLE_CFNRC = "*680";

    private static final String NUM_PROJECTION[] = {android.provider.ContactsContract
            .CommonDataKinds.Phone.NUMBER};
    private static final int GET_CONTACTS_RESULT_CODE = 100;

    private static final String PREF_PREFIX = "cdmacallforward_";

    private Preference mButtonCFU;
    private Preference mButtonCFB;
    private Preference mButtonCFNRy;
    private Preference mButtonCFNRc;
    private Preference mButtonCFC;

    private ArrayList<Preference> mPreferences = null;

    private EditText mEditNumber = null;
    private int mSubId = -1;
    private Context mContext;
    private Phone mPhone;

    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private SharedPreferences mPrefs;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.cdma_callforward_options);

        // UNISOC: add for bug1075491
        boolean isStopped = getIntent().getBooleanExtra(PhoneUtils.IS_STOPPED_ACTIVITY_FLAG, false);
        if (isStopped) {
            moveTaskToBack(true);
        }

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubId = mSubscriptionInfoHelper.getSubId();
        mContext = this;
        mPhone = mSubscriptionInfoHelper.getPhone();
        mPrefs = mPhone.getContext().getSharedPreferences(PREF_PREFIX + mSubId, Context.MODE_PRIVATE);
        Log.d(LOG_TAG, "mSubId : " + mSubId );
        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonCFU = prefSet.findPreference(BUTTON_CFU_KEY);
        mButtonCFB = prefSet.findPreference(BUTTON_CFB_KEY);
        mButtonCFNRy = prefSet.findPreference(BUTTON_CFNRY_KEY);
        mButtonCFNRc = prefSet.findPreference(BUTTON_CFNRC_KEY);
        mButtonCFC = prefSet.findPreference(BUTTON_CFC_KEY);

        mPreferences = new ArrayList<Preference>();
        mPreferences.add(mButtonCFU);
        mPreferences.add(mButtonCFB);
        mPreferences.add(mButtonCFNRy);
        mPreferences.add(mButtonCFNRc);
        mPreferences.add(mButtonCFC);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonCFU) {
            showDialog(DIALOG_CFU_KEY);
        } else if (preference == mButtonCFB) {
            showDialog(DIALOG_CFB_KEY);
        } else if (preference == mButtonCFNRy) {
            showDialog(DIALOG_CFNRY_KEY);
        } else if (preference == mButtonCFNRc) {
            showDialog(DIALOG_CFNRC_KEY);
        } else if (preference == mButtonCFC) {
           setCallForward(ENABLE_CFC);
        }
        return true;
    }

    @Override
    protected Dialog onCreateDialog(final int id) {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.xml.cdma_cf_dialog);
        dialog.setTitle(mPreferences.get(id).getTitle());

        ImageButton mContactPickButton = (ImageButton) dialog.findViewById(R.id.select_contact);
        if (mContactPickButton != null) {
            mContactPickButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    startContacts();
                }
            });
        }

        Button enableBtn = (Button) dialog.findViewById(R.id.enable);
        if (enableBtn != null) {
            enableBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean isNumberEmpty = false;
                    String number = mEditNumber.getText().toString();
                    isNumberEmpty = TextUtils.isEmpty(number);
                    if (isNumberEmpty) {
                        Toast.makeText(mContext, R.string.number_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    String cfHeader = mPreferences.get(id).getTitle().toString();
                    saveStringPrefs(PREF_PREFIX + mSubId + "_" + cfHeader, number, mPrefs);
                    if (cfHeader.equals(mContext.getString(R.string.labelCFU))) {
                        number = ENABLE_CFU + number;
                    } else if (cfHeader.equals(mContext.getString(R.string.labelCFB))) {
                        number = ENABLE_CFB + number;
                    } else if (cfHeader.equals(mContext.getString(R.string.labelCFNRy))) {
                        number = ENABLE_CFNRY + number;
                    } else if (cfHeader.equals(mContext.getString(R.string.labelCFNRc))) {
                        number = ENABLE_CFNRC + number;
                    }
                    setCallForward(number);
                }
            });
        }

        Button disableBtn = (Button) dialog.findViewById(R.id.disable);
        if (disableBtn != null) {
            disableBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    String cfHeader = mPreferences.get(id).getTitle().toString();
                    String number = "";
                    if (cfHeader.equals(mContext.getString(R.string.labelCFU))) {
                        number = DISABLE_CFU;
                    } else if (cfHeader.equals(mContext.getString(R.string.labelCFB))) {
                        number = DISABLE_CFB;
                    } else if (cfHeader.equals(mContext.getString(R.string.labelCFNRy))) {
                        number = DISABLE_CFNRY;
                    } else if (cfHeader.equals(mContext.getString(R.string.labelCFNRc))) {
                        number = DISABLE_CFNRC;
                    }
                    setCallForward(number);
                }
            });
        }
        Button dialogCancelBtn = (Button) dialog.findViewById(R.id.cancel);
        if (dialogCancelBtn != null) {
            dialogCancelBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }
        return dialog;
    }

    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        mEditNumber = (EditText) dialog.findViewById(R.id.EditNumber);
        String cfHeader = mPreferences.get(id).getTitle().toString();
        String number = mPrefs.getString(PREF_PREFIX + mSubId + "_" + cfHeader, "");
        mEditNumber.setText(number);
        Log.d(LOG_TAG, "onPrepareDialog cfHeaders: " + cfHeader + ",number:" + number);
    }

    private void setCallForward(String cfNumber) {
        boolean isNumberEmpty = TextUtils.isEmpty(cfNumber);
        Log.d(LOG_TAG, "setCallForward mSubId: " + mSubId + ",cfNumber: " + cfNumber);
        if (!SubscriptionManager.isValidSubscriptionId(mSubId) || isNumberEmpty) {
            Log.d(LOG_TAG, "setCallForward return");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:" + cfNumber));
        int phoneId = SubscriptionManager.getPhoneId(mSubId);
        PhoneAccountHandle phoneAccountHandle = PhoneUtils.makePstnPhoneAccountHandle(phoneId);
        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
        startActivity(intent);
    }

    private void startContacts() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
        startActivityForResult(intent, GET_CONTACTS_RESULT_CODE);
    }

    void saveStringPrefs(String key, String value, SharedPreferences prefs) {
        Log.d(LOG_TAG, "saveStringPrefs(" + key + ", " + value + ")");
        try {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(key, value);
            editor.apply();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Exception happen, e = " + e);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    final Intent data) {

        if (resultCode != RESULT_OK || requestCode != GET_CONTACTS_RESULT_CODE || data == null) {
            return;
        }

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(data.getData(),
                    NUM_PROJECTION, null, null, null);
            if ((cursor != null) && (cursor.moveToFirst()) && mEditNumber != null) {
                mEditNumber.setText(cursor.getString(0));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}

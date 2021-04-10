package com.android.phone;

import android.app.Dialog;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.View.OnClickListener;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionManager;
import android.telecom.TelecomManager;
import android.text.TextUtils;

/**
 * UNISOC:CDMA Call Waiting Settings Activity.
 */
public class CdmaCallWaitingSetting extends PreferenceActivity {
    private static final int DIALOG_CW = 0;
    private static final String ENABLE_CW = "*74";
    private static final String DISABLE_CW = "*740";
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.cdma_callwaiting_options);
        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubId = mSubscriptionInfoHelper.getSubId();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        showDialog(DIALOG_CW);
        return true;
    }

    @Override
    protected Dialog onCreateDialog(final int id) {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.xml.cdma_cw_dialog);
        dialog.setTitle(R.string.labelCW);
        Button enableBtn = (Button) dialog.findViewById(R.id.enable);
        if (enableBtn != null) {
            enableBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setCW(ENABLE_CW);
                    dialog.dismiss();
                }
            });
        }
        Button disableBtn = (Button) dialog.findViewById(R.id.disable);
        if (disableBtn != null) {
            disableBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setCW(DISABLE_CW);
                    dialog.dismiss();
                }
            });
        }
        return dialog;
    }

    private void setCW(String cwNumber){
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:" + cwNumber));
        int phoneId = SubscriptionManager.getPhoneId(mSubId);
        PhoneAccountHandle phoneAccountHandle = PhoneUtils.makePstnPhoneAccountHandle(phoneId);
        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
        startActivity(intent);
    }
}
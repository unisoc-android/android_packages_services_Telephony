package com.android.phone;

import android.app.Dialog;
import android.os.Bundle;
import com.android.internal.telephony.Phone;
import android.app.AlertDialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.util.Log;
import android.content.Context;

/**
 * Fragment to warn the emergency dial can only modify the PIN code of SIM1 for unisoc 1111564
 */
public class PinEntryAlertDialog extends DialogFragment {
    public static final String TAG = "PinEntryAlertDialog";
    private static final String MESSAGE_KEY = "input_message";
    private String mMessage;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            mMessage = getArguments().getString(MESSAGE_KEY);
        }
    }
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Log.d(TAG, "onCreateDialog mMessage is :"+mMessage);
        return new AlertDialog.Builder(getContext())
                .setMessage(R.string.change_pin_when_double_sim_card_info)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Phone phone = PhoneGlobals.getPhone();
                        if (phone != null) {
                            phone.handlePinMmi(mMessage);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(true)
                .create();
    }
}
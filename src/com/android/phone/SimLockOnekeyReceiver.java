package com.android.phone;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.net.Uri;
import android.os.SystemProperties;
import android.util.Log;

public class SimLockOnekeyReceiver extends BroadcastReceiver {
    private static final String TAG = "SimLockOnekeyReceiver";
    private static final String SECRECT_CODE_ONEKEY_SIMLOCK = "54321";

    public SimLockOnekeyReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String host = null;
        Uri uri = intent.getData();
        if (uri != null) {
            host = uri.getHost();
        } else {
            Log.d(TAG,"uri is null");
            return;
        }
        Log.d(TAG, " host[" + host + "]");
        if (SECRECT_CODE_ONEKEY_SIMLOCK.equals(host)) {
            handleOneKeySimLock(context);
        } else {
            Log.d(TAG, "Unhandle host[" + host + "]");
        }
    }

    private void handleOneKeySimLock(Context context) {
        if(!SystemProperties.getBoolean("ro.simlock.onekey.lock", false)) {
            Log.d(TAG, "onekey simlock is turn off.");
            return;
        }
        Intent intent = new Intent();
        intent.setClass(context, ChooseSimLockTypeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Log.d(TAG,"handleOneKeySimLock");
    }

}

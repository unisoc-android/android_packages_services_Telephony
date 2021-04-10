/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;
import android.view.WindowManager;
import android.app.KeyguardManager;

/**
 * Helper class for displaying the a string triggered by a lower level Phone request
 */
public class PhoneDisplayMessage {
    private static final String LOG_TAG = "PhoneDisplayMessage";
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);

    /** Display message dialog */
    private static AlertDialog sDisplayMessageDialog = null;

    /**
     * Display the alert dialog with the network message.
     *
     * @param context context to get strings.
     * @param infoMsg Text message from Network.
     */
    public static void displayNetworkMessage(Context context, String infoMsg) {
        if (DBG) log("displayInfoRecord: infoMsg=" + infoMsg);

        String title = (String)context.getText(R.string.network_info_message);
        displayMessage(context, title, infoMsg);
    }

    /**
     * Display the alert dialog with the error message.
     *
     * @param context context to get strings.
     * @param errorMsg Error message describing the network triggered error
     */
    public static void displayErrorMessage(Context context, String errorMsg) {
        if (DBG) log("displayErrorMessage: errorMsg=" + errorMsg);

        String title = (String)context.getText(R.string.network_error_message);
        displayMessage(context, title, errorMsg);
    }

    public static void displayMessage(Context context, String title, String msg) {
        if (DBG) log("displayMessage: msg=" + msg);

        if (sDisplayMessageDialog != null) {
            sDisplayMessageDialog.dismiss();
        }

        // displaying system alert dialog on the screen instead of
        // using another activity to display the message.  This
        // places the message at the forefront of the UI.
        sDisplayMessageDialog = new AlertDialog.Builder(context)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(true)
                .create();

        // UNISOC: change for Bug1000712.
        if (isKeyguardLocked()) {
            sDisplayMessageDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }else {
            sDisplayMessageDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        }
        sDisplayMessageDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        sDisplayMessageDialog.show();
        PhoneGlobals.getInstance().wakeUpScreen();
    }

    // UNISOC: add for Bug1000712.
    private static boolean isKeyguardLocked() {
        KeyguardManager keguard = PhoneGlobals.getInstance().getKeyguardManager();
        if (DBG) log("isKeyguardLocked");
        return keguard.isKeyguardLocked();
    }

    /**
     * Dismiss the DisplayInfo record
     */
    public static void dismissMessage() {
        if (DBG) log("Dissmissing Display Info Record...");

        if (sDisplayMessageDialog != null) {
            sDisplayMessageDialog.dismiss();
            sDisplayMessageDialog = null;
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, "[PhoneDisplayMessage] " + msg);
    }
}

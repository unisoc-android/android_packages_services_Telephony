
package com.android.phone.settings;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.content.Context;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.SubscriptionController;

public class IccUriUtils {
    public static final String AUTHORITY = "icc";
    public static final int MIN_PIN_LENGTH = 4;
    public static final int MAX_PIN_LENGTH = 8;
    public static final int MAX_INPUT_TIMES =3;
    private static final String PROPERTY_PIN2_REMAINTIMES = "vendor.sim.pin2.remaintimes";
    private static final String PROPERTY_PUK2_REMAINTIMES = "vendor.sim.puk2.remaintimes";
    private static final int MIN_REMAINTIMES = 0;
    private static IccUriUtils Instance ;

    public static IccUriUtils getInstance() {
        if (Instance != null) return Instance;
        Instance = new IccUriUtils();
        return Instance;
    }

    public static Uri getIccURI(String path, int subId) {
        String uri = "content://" + AUTHORITY + "/" + getPathName(path, subId);
        return Uri.parse(uri);
    }

    private static String getPathName(String path, int subId) {
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            return path + "/" + "subId" + "/" + subId ;
        } else {
            return path;
        }
    }

    public int getPIN2RemainTimes(Context context, int phoneId) {
        String mRemainPin2TimesPropertyValue = TelephonyManager.from(
                context).getTelephonyProperty(phoneId, PROPERTY_PIN2_REMAINTIMES, "");
        if (!"".equals(mRemainPin2TimesPropertyValue)) {
          int mRemainPin2Times = Integer.valueOf(mRemainPin2TimesPropertyValue);
          return mRemainPin2Times;
        }
        return MIN_REMAINTIMES;
    }

    public int getPUK2RemainTimes(Context context, int phoneId) {
        String mRemainPuk2TimesPropertyValue = TelephonyManager.from(
                context).getTelephonyProperty(phoneId, PROPERTY_PUK2_REMAINTIMES, "");
        if (!"".equals(mRemainPuk2TimesPropertyValue)) {
           int mRemainPuk2Times = Integer.valueOf(mRemainPuk2TimesPropertyValue);
           return mRemainPuk2Times;
        }
        return MIN_REMAINTIMES;
    }

    /**
     * SPRD: Validate the pin entry.
     *
     * @param pin This is the pin to validate
     * @param isPuk Boolean indicating whether we are to treat the pin input as
     *            a puk.
     */
    public static boolean validatePin(String pin, boolean isPUK) {
        // for pin, we have 4-8 numbers, or puk, we use only 8.
        int pinMinimum = isPUK ? MAX_PIN_LENGTH : MIN_PIN_LENGTH;

        // check validity
        if (pin == null || pin.length() < pinMinimum || pin.length() > MAX_PIN_LENGTH) {
            return false;
        } else {
            return true;
        }
    }
}

package com.android.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

public class CallForwardTimeUtils {
    private static CallForwardTimeUtils sInstance ;
    private static final String CFT_SHARED_PREFERENCES_NAME = "ctf_prefs_name";
    public static final String CFT_STARTTIME_KEY = "cft_starttime_";
    public static final String CFT_STOPTIME_KEY = "cft_stoptime_";
    private static final String CFT_NUMBERS_KEY = "cft_numbers_";
    private static final String CFT_STATUS_ACTIVE_KEY = "cft_status_active_";
    private static final String CFT_REAL_NUMBERS_KEY = "cft_real_numbers_";
    private SharedPreferences mCFTPrefs;

    public static CallForwardTimeUtils getInstance(Context context) {
        if (sInstance != null) {
            return sInstance;
        }
        sInstance = new CallForwardTimeUtils(context);
        return sInstance;
    }

    public CallForwardTimeUtils(Context context) {
        mCFTPrefs = context.getSharedPreferences(CFT_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public long readTime(boolean isStartTime, String iccId) {
        if (isStartTime) {
            return mCFTPrefs.getLong(CFT_STARTTIME_KEY + iccId, 0);
        } else {
            return mCFTPrefs.getLong(CFT_STOPTIME_KEY + iccId, 0);
        }
    }

    public void writeTime(boolean isStartTime, String iccId, long when) {
        Editor editor = mCFTPrefs.edit();
        if (isStartTime) {
            editor.putLong(CFT_STARTTIME_KEY + iccId, when);
        } else {
            editor.putLong(CFT_STOPTIME_KEY + iccId, when);
        }
        editor.apply();
    }

    public String readNumber(String iccId) {
        return mCFTPrefs.getString(CFT_NUMBERS_KEY + iccId, "");
    }

    public void writeNumber(String iccId, String number) {
        Editor editor = mCFTPrefs.edit();
        editor.putString(CFT_NUMBERS_KEY + iccId, number);
        editor.apply();
    }

    public int readStatus(String iccId) {
        return mCFTPrefs.getInt(CFT_STATUS_ACTIVE_KEY + iccId, 0);
    }

    public void writeStatus(String iccId, int status) {
        Editor editor = mCFTPrefs.edit();
        editor.putInt(CFT_STATUS_ACTIVE_KEY + iccId, status);
        editor.apply();
    }

    public String readCFTNumber(String iccId) {
        return mCFTPrefs.getString(CFT_REAL_NUMBERS_KEY + iccId, "");
    }

    public void writeCFTNumber(String iccId, String number) {
        Editor editor = mCFTPrefs.edit();
        editor.putString(CFT_REAL_NUMBERS_KEY + iccId, number);
        editor.apply();
    }
}

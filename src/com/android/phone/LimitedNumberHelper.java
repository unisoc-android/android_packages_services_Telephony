package com.android.phone;

import java.util.List;

import android.content.Context;
import android.content.res.Resources.NotFoundException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.android.internal.telephony.SubscriptionController;

public class LimitedNumberHelper {

    private static final String LOG_TAG = "LimitedNumberHelper";
    private String[] mCheckedMccMnc;
    private String[] mCheckedSpn;
    private String[] mLimitedNumber;
    private TelephonyManager mTelephonyManager;
    private static Context mContext;

    private static LimitedNumberHelper sInstance;

    private LimitedNumberHelper(Context context) {
        mContext = context;
        mTelephonyManager = TelephonyManager.from(context);
        mCheckedMccMnc = mContext.getResources().getStringArray(
                R.array.config_checked_mccmnc);
        mCheckedSpn = mContext.getResources().getStringArray(
                R.array.config_checked_spn);
        mLimitedNumber = mContext.getResources().getStringArray(
                R.array.config_limited_number);
    }

    public static void init(Context context) {
        if (sInstance == null) {
            sInstance = new LimitedNumberHelper(context);
        } else {
            Log.d(LOG_TAG, "LimitedNumberHelper.init() called multiple times");
        }
    }

    public static LimitedNumberHelper getInstance() {
        if (sInstance == null) {
            sInstance = new LimitedNumberHelper(mContext);
        }
        return sInstance;
    }

    public boolean isLimitedNumber(String number) {
        if (getInstance() == null) {
            return false;
        }
        if (!mContext.getResources().getBoolean(R.bool.config_support_tip_limited_number)) {
            Log.i(LOG_TAG, "tip limited number not supported");
            return false;
        }
        return isLimitedNumberInternal(number);
    }

    /**
     * @param number The dialed number
     * @param subId  The using phone
     * @return
     */
    private boolean isLimitedNumberInternal(String number) {
        SubscriptionController subController = SubscriptionController.getInstance();
        List<SubscriptionInfo> activeSubInfos = subController.getActiveSubscriptionInfoList(mContext.getOpPackageName());
        if (activeSubInfos == null || activeSubInfos.size() == 0) {
            return false;
        }
        boolean tipLimitedNumber = true;
        for (SubscriptionInfo subInfo : activeSubInfos) {
            if (subInfo != null && !isLimitedSubscription(subInfo.getSubscriptionId())) {
                tipLimitedNumber = false;
                break;
            }
        }
        if (tipLimitedNumber) {
            for (String limitedNum : mLimitedNumber) {
                if (limitedNum.equals(number)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLimitedSubscription(int subId) {
        String simMccMnc = mTelephonyManager.getSimOperatorNumeric(subId);
        String simSpn = mTelephonyManager.getSimOperatorName(subId);

        Log.i(LOG_TAG, " SubId[" + subId + "]: The current mcc + mnc is  " + simMccMnc
                + ", the current Service Provider Name (SPN) is  " + simSpn);
        for (String spn : mCheckedSpn) {
            if (spn.equalsIgnoreCase(simSpn)) {
                return false;
            }
        }
        for (String mccMnc : mCheckedMccMnc) {
            if (mccMnc.equals(simMccMnc)) {
                return false;
            }
        }
        return true;
    }
}

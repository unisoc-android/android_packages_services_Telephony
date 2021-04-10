/**
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone;

import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.util.List;
/**
 * Helper for manipulating intents or components with subscription-related information.
 *
 * In settings, subscription ids and labels are passed along to indicate that settings
 * are being changed for particular subscriptions. This helper provides functions for
 * helping extract this info and perform common operations using this info.
 */
public class SubscriptionInfoHelper {

    // Extra on intent containing the id of a subscription.
    public static final String SUB_ID_EXTRA =
            "com.android.phone.settings.SubscriptionInfoHelper.SubscriptionId";
    // Extra on intent containing the label of a subscription.
    private static final String SUB_LABEL_EXTRA =
            "com.android.phone.settings.SubscriptionInfoHelper.SubscriptionLabel";
    private static final String LOG_TAG = "SubscriptionInfoHelper";

    private Context mContext;

    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private String mSubLabel;
    /* UNISOC: add for bug 1082937 @{ */
    private SubscriptionManager mSubscriptionManager;
    private ActionBar mActionBar;
    private Resources mRes;
    private int mResId;
    /* @} */

    /**
     * Instantiates the helper, by extracting the subscription id and label from the intent.
     */
    public SubscriptionInfoHelper(Context context, Intent intent) {
        mContext = context;
        PhoneAccountHandle phoneAccountHandle =
                intent.getParcelableExtra(TelephonyManager.EXTRA_PHONE_ACCOUNT_HANDLE);
        if (phoneAccountHandle != null) {
            mSubId = PhoneUtils.getSubIdForPhoneAccountHandle(phoneAccountHandle);
        }
        if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            mSubId = intent.getIntExtra(SUB_ID_EXTRA, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
        //UNISOC: add for bug #1024064
        //mSubLabel = intent.getStringExtra(SUB_LABEL_EXTRA);
        SubscriptionInfo subInfo = SubscriptionManager.from(mContext).getActiveSubscriptionInfo(mSubId);
        if (subInfo != null) {
            mSubLabel = subInfo.getDisplayName().toString();
        }
        // UNISOC: add for bug 1082937
        mSubscriptionManager = SubscriptionManager.from(mContext);
    }

    /**
     * @param newActivityClass The class of the activity for the intent to start.
     * @return Intent containing extras for the subscription id and label if they exist.
     */
    public Intent getIntent(Class newActivityClass) {
        Intent intent = new Intent(mContext, newActivityClass);

        if (hasSubId()) {
            intent.putExtra(SUB_ID_EXTRA, mSubId);
        }

        if (!TextUtils.isEmpty(mSubLabel)) {
            intent.putExtra(SUB_LABEL_EXTRA, mSubLabel);
        }

        return intent;
    }

    public static void addExtrasToIntent(Intent intent, SubscriptionInfo subscription) {
        if (subscription == null) {
            return;
        }

        intent.putExtra(SubscriptionInfoHelper.SUB_ID_EXTRA, subscription.getSubscriptionId());
        intent.putExtra(
                SubscriptionInfoHelper.SUB_LABEL_EXTRA, subscription.getDisplayName().toString());
    }

    /**
     * @return Phone object. If a subscription id exists, it returns the phone for the id.
     */
    // UNISOC: modify for bug896038
    public Phone getPhone() {
        return hasSubId()
                ? PhoneFactory.getPhone(SubscriptionManager.getPhoneId(mSubId))
                : /*PhoneGlobals.getPhone()*/getDefaultPhone();
    }

    /**
     * Sets the action bar title to the string specified by the given resource id, formatting
     * it with the subscription label. This assumes the resource string is formattable with a
     * string-type specifier.
     *
     * If the subscription label does not exists, leave the existing title.
     */
    public void setActionBarTitle(ActionBar actionBar, Resources res, int resId) {
        /* UNISOC: add for bug 1082937 @{ */
        updateActionBarTitle(actionBar, res, resId);
        mActionBar = actionBar;
        mRes = res;
        mResId = resId;
        /* @} */
    }

    public boolean hasSubId() {
        return mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    public int getSubId() {
        return mSubId;
    }

    /* UNISOC: FEATURE_VOLTE_CALLFORWARD_OPTIONS @{ */
    public Intent getCallSettingsIntent(Class newActivityClass, Intent intent) {
        if (hasSubId()) {
            intent.putExtra(SUB_ID_EXTRA, mSubId);
        }
        if (!TextUtils.isEmpty(mSubLabel)) {
            intent.putExtra(SUB_LABEL_EXTRA, mSubLabel);
        }
        if (mContext.getPackageManager().resolveActivity(intent, 0) == null) {
            Log.i(LOG_TAG, intent + " is not exist");
            if (newActivityClass == null) {
                return null;
            }
            intent.setClass(mContext, newActivityClass);
        }
        return intent;
    }

    public boolean isShowIPFeature(){
        return mContext.getResources().getBoolean(com.android.internal.R.bool.ip_dial_enabled_bool);
    }
    /* @} */

    /* UNISOC: add for bug896038 @{ */
    private Phone getDefaultPhone() {
        Phone phone = PhoneGlobals.getPhone();
        List<SubscriptionInfo> subList = SubscriptionManager.from(mContext)
                .getActiveSubscriptionInfoList();
        if (subList != null && subList.size() == 1) {
            phone = PhoneFactory.getPhone(subList.get(0).getSimSlotIndex());
        }
        Log.i(LOG_TAG, "phone slot : " + phone.getPhoneId() + " sub id : " + phone.getSubId());
        return phone;
    }
    /* @} */
    /* UNISOC: add for bug 1082937 @{ */
    private final SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangeListener
            = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            Log.d(LOG_TAG,"onSubscriptionsChanged:");
            List<SubscriptionInfo> subscriptions = mSubscriptionManager
                    .getActiveSubscriptionInfoList();
            if (subscriptions != null) {
                int subscriptionsLength = subscriptions.size();
                for (int i = 0; i < subscriptionsLength; i++) {
                    SubscriptionInfo subscriptionInfo = subscriptions.get(i);
                    Log.d(LOG_TAG,"subscriptionInfo = "+subscriptionInfo+ ", i = "+i);
                    if (subscriptionInfo != null) {
                        int subId = subscriptionInfo.getSubscriptionId();
                        if (mSubId == subId) {
                            String displayName = subscriptionInfo.getDisplayName().toString();
                            if (mSubLabel == null || !mSubLabel.equals(displayName)) {
                                mSubLabel = displayName;
                                updateActionBarTitle(mActionBar, mRes, mResId);
                            }
                        }
                    }
                }
            }
        }
    };

    public void addOnSubscriptionsChangedListener() {
        mSubscriptionManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
    }

    public void removeOnSubscriptionsChangedListener(){
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
    }

    private void updateActionBarTitle(ActionBar actionBar, Resources res, int resId) {
        if (actionBar == null || TextUtils.isEmpty(mSubLabel)) {
            return;
        }

        if (!TelephonyManager.from(mContext).isMultiSimEnabled()) {
            return;
        }

        String title = String.format(res.getString(resId), mSubLabel);
        actionBar.setTitle(title);
    }
    /* @} */
}

package com.android.phone;

import android.content.Context;
import android.content.ContextWrapper;
import com.android.internal.telephony.DataEnableController;
import com.android.internal.telephony.OmaApnReceiver;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SimEnabledController;
import com.android.phone.SuppServiceConsumer;
import com.android.phone.settings.fdn.FdnHelper;
import com.android.phone.TelephonyUIHelper;
import android.util.Log;
import com.android.telephony.DmykTelephonyManager;
import com.android.sprd.telephony.RadioInteractorCore;
import com.android.sprd.telephony.RadioInteractor;
import com.android.sprd.telephony.RadioInteractorFactory;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.Message;
/**
 * It is a convenient class for other feature support. We had better put this class in the same
 * package with {@link PhoneGlobals}, so we can touch all the package permission variables and
 * methods in it.
 */
public class PhoneGlobalsEx extends ContextWrapper {
    private static final String TAG = "PhoneGlobalsEx";
    private static PhoneGlobalsEx mInstance;
    private Context mContext;
    /*UNISOC : DM porting */
    private DmykTelephonyManager dmykMgr;
    /*UNISOC : DM porting */
    private OmaApnReceiver omaApnReceiver;
    protected static final int EVENT_UNSOL_RI_CONNECTED = 102;
    private RadioInteractorCore mRadioInteractorCore = null;

    public static PhoneGlobalsEx getInstance() {
        return mInstance;
    }

    public PhoneGlobalsEx(Context context) {
        super(context);
        mInstance = this;
        mContext = context;
        omaApnReceiver = new OmaApnReceiver(context);
    }

    public void onCreate() {
        Log.d(TAG, "onCreate");

        Log.d(TAG, "PhoneInterfaceManagerEx init");
        PhoneInterfaceManagerEx.init(this, PhoneFactory.getDefaultPhone());
        //add for unisoc for 1134318
        RadioInteractorFactory.init(mContext);
        //porting SS. Register Consumer Supplementary Service.
        for (Phone phone : PhoneFactory.getPhones()) {
            SuppServiceConsumer.getInstance(mInstance, phone);
        }
        //Add for fast shutdown
        FastShutdownHelper.init(this);
        /*UNISOC: DM porting for bug1073938 @{*/
        if (mContext.getResources().getBoolean(R.bool.config_dmyk_enable)) {
            dmykMgr = DmykTelephonyManager.init(this, mContext);
        }
        //add for unisoc 1068959
        if (getRadioInteractor() != null) {
            getRadioInteractor().registerForUnsolRiConnected(mHandler, EVENT_UNSOL_RI_CONNECTED,null);
        }
        /*@}*/
        // UNISOC:Bug1072957 Reliance block notification.
        TelephonyUIHelper.init(this);
        FdnHelper.getInstance();
        CallForwardHelper.getInstance();
        //UNISOC:Add for primary sub policy
        DataEnableController.init(this);
        //UNISOC: Bug1072674 tip limited number
        LimitedNumberHelper.init(mContext);
        // UNISOC:Improve the function of turning on and off the Sub
        SimEnabledController.init(mContext);
    }

    private RadioInteractorCore getRadioInteractor() {
        int mPhoneId = PhoneFactory.getDefaultPhone() != null ? PhoneFactory.getDefaultPhone().getPhoneId() : 0;
        if (mRadioInteractorCore == null && RadioInteractorFactory.getInstance() != null) {
            mRadioInteractorCore = RadioInteractorFactory.getInstance().getRadioInteractorCore(mPhoneId);
        }
        return mRadioInteractorCore;
    }

    //add for unisoc 1095306
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_UNSOL_RI_CONNECTED:
                    //add for unisoc 1068959
                    setSipUserAgent();
                    break;
                default:
                    break;
            }
        }
    };

    //add for unisoc 1137386
    //setImsUserAgent() instead of sendAtCmd
    public void setSipUserAgent() {
        String sipUserAgent = SystemProperties.get("ro.config.sipua", "");
        Log.d(TAG, "setSipUserAgent sipUserAgent is:" + sipUserAgent);
        if ("".equals(sipUserAgent)) {
            return;
        }
        RadioInteractor mRadioInteractor = new RadioInteractor(mContext);
        int mPhoneId = PhoneFactory.getDefaultPhone() != null ? PhoneFactory.getDefaultPhone().getPhoneId() : 0;
        mRadioInteractor.setImsUserAgent(sipUserAgent, null, mPhoneId);
    }
}

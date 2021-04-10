package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.android.sprd.telephony.RadioInteractor;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCallTracker;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.GsmCdmaPhone;
/** Helper to support fast shutdown */
public class FastShutdownHelper extends BroadcastReceiver {
    private static final String TAG = "FastShutdownHelper";
    private static final int SHUTDOWN_TIMEOUT = 5 * 1000;
    private static final String RI_SERVICE_NAME =
            "com.android.sprd.telephony.server.RADIOINTERACTOR_SERVICE";
    private static final String RI_SERVICE_PACKAGE =
            "com.android.sprd.telephony.server";
    //* UNISOC: Bug 631236 optimize time of RIL shutdown for multi sim cards
    private static final int THREAD_POOL_COUNT = 2;

    private static FastShutdownHelper sInstance;
    private Context mContext;
    private RadioInteractor mRi;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRi = new RadioInteractor(mContext);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mRi = null;
        }
    };

    public FastShutdownHelper(Context context) {
        mContext = context;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        context.registerReceiver(this, filter);

        Intent serviceIntent = new Intent(RI_SERVICE_NAME);
        serviceIntent.setPackage(RI_SERVICE_PACKAGE);
        context.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    public static void init(Context context) {
        synchronized (FastShutdownHelper.class) {
            if (sInstance == null) {
                sInstance = new FastShutdownHelper(context);
            } else {
                Log.d(TAG, "FastShutdownHelper.init() called multiple times");
            }
        }
    }

    /* UNISOC: Bug 631236 optimize time of RIL shutdown for multi sim cards @{ */
    private  class TaskCallable implements Callable<String> {

       private int id;

       public TaskCallable(int id) {
           this.id = id;
       }

       @Override
       public String call() throws Exception {
           GsmCdmaPhone phone = (GsmCdmaPhone)(PhoneFactory.getPhone(id));
           if(phone != null){
               ImsPhone imsPhone = (ImsPhone)(phone.getImsPhone());
               if(imsPhone != null){
                   Log.d(TAG, "hang up ims call before shutdown");
                   if(imsPhone.getForegroundCall().getState().isAlive() || imsPhone.getBackgroundCall().getState().isAlive()
                         || imsPhone.getRingingCall().getState().isAlive() ){
                       ((ImsPhoneCallTracker)(imsPhone.getCallTracker())).mRingingCall.hangupIfAlive();
                       ((ImsPhoneCallTracker)(imsPhone.getCallTracker())).mBackgroundCall.hangupIfAlive();
                       ((ImsPhoneCallTracker)(imsPhone.getCallTracker())).mForegroundCall.hangupIfAlive();
                   }
               }
               if (!phone.isPhoneTypeGsm() || phone.isInCall()) {
                   Log.d(TAG, "hang up before shutdown");
                   phone.mCT.mRingingCall.hangupIfAlive();
                   phone.mCT.mBackgroundCall.hangupIfAlive();
                   phone.mCT.mForegroundCall.hangupIfAlive();
               }
           }
           return String.valueOf(mRi.requestShutdown(id));
       }
    };
    /* @} */

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive() action=" + intent.getAction());
        SystemProperties.set("gsm.fast.shutdown", "true");

        new Thread() {
            public void run() {
                shutdownRadios();
            }
        }.start();
    }

    /* UNISOC: Bug 631236 optimize time of RIL shutdown for multi sim cards @ { */
    private boolean shutdownRadios() {
        boolean resultSuccess = true;
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_POOL_COUNT);
        ArrayList<Future<String>> results = new ArrayList<Future<String>>();
        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        if (mRi != null) {
            for (int i = 0; i < numPhones; i++) {
                try {
                    results.add(exec.submit(new TaskCallable(i)));
                } catch(Exception e) {
                    Log.e(TAG, "exec submit throw exception " + e);
                }
            }

            for (Future<String> result : results) {
                try {
                    resultSuccess = resultSuccess && Boolean.valueOf((String) result.get());
                } catch (InterruptedException e) {
                    Log.e(TAG, "result InterruptedException " + e);
                } catch (ExecutionException e) {
                    Log.e(TAG, "result ExecutionException " + e);
                }
            }
            exec.shutdown();
        } else {
            Log.d(TAG, "shutdownRadios() befor radio interactor is started");
            // RadioInteractor is not started yet, immediatley finish the broadcast
            return true;
        }
        Log.d(TAG, "shutdownRadios() result=" + resultSuccess);
        return resultSuccess;
    }
    /* @} */
}

/*
 * Wifi Fixer for Android
 *        Copyright (C) 2010-2016  David Van de Ven
 *
 *        This program is free software: you can redistribute it and/or modify
 *        it under the terms of the GNU General Public License as published by
 *        the Free Software Foundation, either version 3 of the License, or
 *        (at your option) any later version.
 *
 *        This program is distributed in the hope that it will be useful,
 *        but WITHOUT ANY WARRANTY; without even the implied warranty of
 *        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *        GNU General Public License for more details.
 *
 *        You should have received a copy of the GNU General Public License
 *        along with this program.  If not, see http://www.gnu.org/licenses
 */

package org.wahtod.wififixer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import org.wahtod.wififixer.prefs.PrefConstants;
import org.wahtod.wififixer.prefs.PrefConstants.Pref;
import org.wahtod.wififixer.prefs.PrefUtil;
import org.wahtod.wififixer.ui.MainActivity;
import org.wahtod.wififixer.utility.AsyncWifiManager;
import org.wahtod.wififixer.utility.BroadcastHelper;
import org.wahtod.wififixer.utility.FifoList;
import org.wahtod.wififixer.utility.HostMessage;
import org.wahtod.wififixer.utility.Hostup;
import org.wahtod.wififixer.utility.LogUtil;
import org.wahtod.wififixer.utility.LoggingWakeLock;
import org.wahtod.wififixer.utility.NotifUtil;
import org.wahtod.wififixer.utility.ScreenStateDetector;
import org.wahtod.wififixer.utility.ScreenStateDetector.OnScreenStateChangedListener;
import org.wahtod.wififixer.utility.ServiceAlarm;
import org.wahtod.wififixer.utility.StatusDispatcher;
import org.wahtod.wififixer.utility.StatusMessage;
import org.wahtod.wififixer.utility.StopWatch;
import org.wahtod.wififixer.utility.StringUtil;
import org.wahtod.wififixer.utility.SupplicantPattern;
import org.wahtod.wififixer.utility.WFConfig;
import org.wahtod.wififixer.utility.WakeLock;
import org.wahtod.wififixer.utility.WifiLock;
import org.wahtod.wififixer.widget.WidgetReceiver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/*
 * Handles all interaction 
 * with WifiManager
 *
 * "fix" part of Wifi Fixer.
 */
public class WFMonitor implements OnScreenStateChangedListener, Hostup.HostupResponse {
    // Wifi Connect Intent
    public static final String CONNECTINTENT = "org.wahtod.wififixer.CONNECT";
    public static final String NETWORKNAME = "net#";
    // User Event Intent
    public static final String REASSOCIATE_INTENT = "org.wahtod.wififixer.USEREVENT";
    // Sleep Check Intent for Alarm
    private static final String SLEEPCHECKINTENT = "org.wahtod.wififixer.SLEEPCHECK";
    // ms for network checks
    private final static int REACHABLE = 6000;
    private static final int DEFAULT_DBM_FLOOR = -90;
    private static final int FIFO_LENGTH = 10;
    private static final String COLON = ":";
    private static final String NEWLINE = "\n";
    // ms for signalhop check
    private static final long SIGNAL_CHECK_INTERVAL = 30000;
    // ms for main loop sleep
    private final static int LOOPWAIT = 20000;
    // ms for sleep loop check
    private final static long SLEEPWAIT = 300000;
    private static final int SHORTWAIT = 1500;
    // just long enough to avoid sleep bug with handler posts
    private static final int REALLYSHORTWAIT = 500;
    // various
    private static final int NULLVAL = -1;
    private static final String WATCHDOG_POLICY_KEY = "WDPOLICY";
    /*
     * Constants for mRepairLevel values
     */
    private static final int W_REASSOCIATE = 0;
    private static final int W_RECONNECT = 1;
    private static final int W_REPAIR = 2;
    /*
     * Supplicant State triggers Have to use string because some SupplicantState
     * enums aren't available in some Android versions
     */
    private static final String SSTATE_ASSOCIATING = "ASSOCIATING";
    private static final String SSTATE_ASSOCIATED = "ASSOCIATED";
    private static final String SSTATE_INVALID = "INVALID";
    private static final int CONNECTING_THRESHOLD = 5;
    private static final long CWDOG_DELAY = 8000;
    private static final int HOP_THRESHOLD = 10;
    protected static WeakReference<Context> ctxt;
    private static int mRepairLevel = W_REASSOCIATE;
    private static Runnable rDemoter = new Runnable() {
        @Override
        public void run() {
            int n = getNetworkID();
            if (n != -1)
                demoteNetwork(ctxt.get(), n);
        }
    };
    // flags
    private static boolean shouldrepair = false;
    private static boolean pendingscan = false;
    private static boolean pendingreconnect = false;
    private static int numKnownNetworks = 0;
    /*
     * Runs first time supplicant nonresponsive
     */
    private static Runnable rReconnect = new Runnable() {
        public void run() {
            if (!AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled()) {
                LogUtil.log(ctxt.get(), R.string.wifi_off_aborting_reconnect);
                return;
            }
            if (numKnownNetworks > 0) {
                pendingreconnect = false;
            } else {
                mRepairLevel = W_REASSOCIATE;
                AsyncWifiManager.get(ctxt.get()).startScan();
                LogUtil.log(ctxt.get(),
                        R.string.exiting_supplicant_fix_thread_starting_scan);
            }
        }
    };
    private static boolean repair_reset = false;
    private static boolean _signalhopping = false;
    private static volatile Hostup _hostup;
    private static long _signalCheckTime;
    /*
     * For connectToAP sticking
     */
    private static int connecting = 0;
    private static volatile Handler handler = new Handler();
    private static volatile boolean isUp;
    private static WFMonitor _wfmonitor;
    /*
     * Scanner runnable
     */
    protected static Runnable rScan = new Runnable() {
        public void run() {
            /*
             * Start scan
			 */
            if (supplicantInterruptCheck(ctxt.get())) {
                _wfmonitor.startScan(true);
                LogUtil.log(ctxt.get(), R.string.wifimanager_scan);
            } else {
                LogUtil.log(ctxt.get(), R.string.scan_interrupt);
            }

        }
    };    /*
     * Main tick
     */
    /*
     * Resets wifi shortly after screen goes off
     */
    private static Runnable rN1Fix = new Runnable() {

        @Override
        public void run() {
            n1Fix();

        }
    };
    /*
     * Runs second time supplicant nonresponsive
     */
    private static Runnable rRepair = new Runnable() {
        public void run() {
            if (!AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled()) {
                LogUtil.log(ctxt.get(), R.string.wifi_off_aborting_repair);
                return;
            }

            if (numKnownNetworks > 0) {
                pendingreconnect = false;
            } else if (!repair_reset) {
                pendingreconnect = true;
                toggleWifi();
                repair_reset = true;
                LogUtil.log(ctxt.get(), R.string.toggling_wifi);

            }
            /*
             * If repair_reset is true we should be in normal scan mode until
			 * connected
			 */
            LogUtil.log(ctxt.get(), R.string.scan_mode);
        }
    };
    private static String accesspointIP;
    private static boolean wifistate;
    /*
     * SignalHop runnable
     */
    private static Runnable rSignalhop = new Runnable() {
        public void run() {

            _wfmonitor.clearQueue();
            /*
             * run the signal hop check
			 */
            _wfmonitor.signalHop();
        }

    };
    private static Runnable PostExecuteRunnable = new Runnable() {
        @Override
        public void run() {
            /*
             * Notify state
			 */
            if (_wfmonitor.screenstate) {
                StatusMessage m = new StatusMessage();
                if (isUp)
                    m.setStatus(ctxt.get().getString(R.string.passed));
                else
                    m.setStatus(ctxt.get().getString(R.string.failed));
                StatusMessage.send(ctxt.get(), m);
            }
            _wfmonitor.handlerWrapper(new PostNetCheckRunnable(isUp));
        }
    };
    private static Runnable NetCheckRunnable = new Runnable() {
        @Override
        public void run() {
            /*
             * First check if wifi is current network
			 */
            if (!getIsOnWifi(ctxt.get())) {
                LogUtil.log(ctxt.get(), R.string.wifi_not_current_network);
                _wfmonitor
                        .clearConnectedStatus(
                                ctxt.get().getString(
                                        R.string.wifi_not_current_network)
                        );
            } else {
                _wfmonitor.networkUp(ctxt.get());
            }
        }
    };
    private static Runnable rMain = new Runnable() {
        public void run() {
            /*
             * Check for disabled state
			 */
            if (PrefUtil.getFlag(Pref.DISABLESERVICE))
                LogUtil.log(ctxt.get(), R.string.shouldrun_false_dying);
            else {
                // Queue next run of main runnable
                _wfmonitor.handlerWrapper(rMain, LOOPWAIT);
                /*
                 * First check if we should manage then do wifi checks
				 */
                if (shouldManage(ctxt.get())) {
                    // Check Supplicant
                    if (wifistate
                            && !AsyncWifiManager.getWifiManager(ctxt.get()).pingSupplicant()) {
                        LogUtil.log(ctxt.get(),
                                R.string.supplicant_nonresponsive_toggling_wifi);
                        toggleWifi();
                    } else if (_wfmonitor.screenstate)
                        /*
                         * Check wifi
						 */
                        if (wifistate) {
                            _wfmonitor.checkWifi();
                        }
                }
            }
        }
    };
    /*
     * Sleep tick if wifi is enabled and screenpref
     */
    private static Runnable rSleepcheck = new Runnable() {
        public void run() {
                /*
                 * This is all we want to do.
				 */

            if (wifistate) {
                if (!PrefUtil.readBoolean(ctxt.get(), Pref.WAKELOCK.key()))
                    _wfmonitor.wakelock.lock(true);
                _wfmonitor.checkWifi();
            } else
                _wfmonitor.wakelock.lock(false);
        }
    };
    /*
     * Handles non-supplicant wifi fixes.
     */
    private static Runnable rWifiTask = new Runnable() {
        public void run() {
            switch (mRepairLevel) {

                case W_REASSOCIATE:
                    // Let's try to reassociate first..
                    AsyncWifiManager.get(ctxt.get()).reassociate();
                    /*
                     * Schedule network check to verify reassociate
                     */
                    _wfmonitor.handlerWrapper(rSleepcheck, SHORTWAIT);
                    LogUtil.log(ctxt.get(), R.string.reassociating);
                    mRepairLevel++;
                    notifyWrap(ctxt.get(),
                            ctxt.get().getString(R.string.reassociating));
                    break;

                case W_RECONNECT:
                    // Ok, now force reconnect..
                    AsyncWifiManager.get(ctxt.get()).reconnect();
                    LogUtil.log(ctxt.get(), R.string.reconnecting);
                    mRepairLevel++;
                    notifyWrap(ctxt.get(),
                            ctxt.get().getString(R.string.reconnecting));
                    break;

                case W_REPAIR:
                    // Start Scan
                    AsyncWifiManager.get(ctxt.get()).disconnect();
                    AsyncWifiManager.get(ctxt.get()).startScan();
                /*
                 * Reset state
				 */
                    mRepairLevel = W_REASSOCIATE;
                    LogUtil.log(ctxt.get(), R.string.repairing);
                    notifyWrap(ctxt.get(), ctxt.get().getString(R.string.repairing));
                    break;
            }
            _wfmonitor.wakelock.lock(false);
            LogUtil.log(ctxt.get(),
                    ctxt.get().getString(
                            R.string.fix_algorithm) +
                            mRepairLevel
            );
        }
    };
    /*
     * For ongoing status notification, widget, and Status fragment
     */
    private StatusDispatcher _statusdispatcher;
    private boolean screenstate;
    private WakeLock wakelock;
    private WifiLock wifilock;
    private WifiInfo mLastConnectedNetwork;
    /*
     * Supplicant State FIFO for pattern matching
     */
    private FifoList _supplicantFifo;
    // Last Scan
    private StopWatch _scantimer;
    private WFConfig connectee;
    private SupplicantState lastSupplicantState;
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            handleBroadcast(intent);
        }
    };
    /*
     * Demotes networks we fail to connect to for one reason or another
     */
    private BroadcastReceiver localreceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            handleBroadcast(intent);
        }
    };

    private WFMonitor(final Context context) {
        _scantimer = new StopWatch();
        _supplicantFifo = new FifoList(FIFO_LENGTH);
        /*
         * Cache Context from service
		 */
        ctxt = new WeakReference<>(context);
    }

    private static int getSignalThreshold(Context context) {
        int detected = DEFAULT_DBM_FLOOR;
        try {
            detected = Integer.valueOf(PrefUtil.readString(context,
                    context.getString(R.string.dbmfloor_key)));
        } catch (NumberFormatException e) {
            /*
             * pref is null, that's ok we have the default
			 */
        }
        return detected;
    }

    private static int containsSSID(String ssid,
                                    List<WFConfig> wifiConfigs) {
        int found = 0;
        for (WFConfig w : wifiConfigs) {
            if (StringUtil.removeQuotes(w.wificonfig.SSID).equals(StringUtil.removeQuotes(ssid)))
                found++;
        }
        return found;
    }

    private static boolean containsBSSID(String bssid,
                                         List<WFConfig> results) {
        for (WFConfig sResult : results) {
            if (sResult.wificonfig.BSSID.equals(bssid))
                return true;
        }
        return false;
    }

    private static void demoteNetwork(Context context, int n) {
        WifiConfiguration network = getNetworkByNID(context, n);
        if (network == null)
            return;
        if (network.priority > -1) {
            network.priority--;
            AsyncWifiManager.get(context).updateNetwork(network);
            String out = (context.getString(R.string.demoting_network)) + network.SSID +
                    context.getString(R.string._to_) +
                    String.valueOf(network.priority);
            LogUtil.log(context, out);
        } else {
            LogUtil.log(context,
                    context
                            .getString(R.string.network_at_priority_floor) +
                            network.SSID
            );
        }
    }

    private static void fixDisabledNetworks(Context context) {
        List<WifiConfiguration> wflist = AsyncWifiManager.getWifiManager(context).getConfiguredNetworks();
        if (wflist == null)
            return;
        for (WifiConfiguration wfresult : wflist) {
            /*
             * Check for Android 2.x disabled network bug WifiConfiguration
			 * state won't match stored state.
			 *
			 * In addition, enforcing persisted network state.
			 */
            if (wfresult.status == WifiConfiguration.Status.DISABLED
                    && !PrefUtil.readNetworkState(context, wfresult.networkId)) {
                /*
                 * bugged, enable
				 */
                AsyncWifiManager.get(context)
                        .enableNetwork(wfresult.networkId, false);
                LogUtil.log(context,
                        (context.getString(R.string.reenablenetwork) + wfresult.SSID));
                AsyncWifiManager.get(context).saveConfiguration();
            }
        }
    }

    private static void enforceAttBlacklistState(Context context) {
        if (PrefUtil.readBoolean(context, Pref.ATT_BLACKLIST.key()))
            PrefUtil.setBlackList(context, true, false);
    }

    private static boolean getIsOnWifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni.getType() == ConnectivityManager.TYPE_WIFI && ni.isConnected() && ni.isAvailable())
                return true;
        } catch (NullPointerException e) {
            /*
             * NetworkInfo can return null
			 */
        }
        return false;
    }

    private static boolean getIsSupplicantConnected(Context context) {
        SupplicantState state = getSupplicantState();
        return state.equals(SupplicantState.ASSOCIATED) || state.equals(SupplicantState.COMPLETED);
    }

    private static List<WFConfig> getKnownAPsBySignal(Context context) {
        List<WFConfig> knownbysignal = new ArrayList<>();

		/*
         * Comparator class for sorting results
		 */
        class SortBySignal implements Comparator<ScanResult> {
            @Override
            public int compare(ScanResult o2, ScanResult o1) {
                /*
                 * Sort by signal
				 */
                if (o1.level < o2.level)
                    return -1;
                else if (o1.level > o2.level)
                    return 1;
                else
                    return 0;
            }
        }

		/*
         * Acquire scan results
		 */
        List<ScanResult> scanResults = AsyncWifiManager.getWifiManager(ctxt.get())
                .getScanResults();
         /*
         * Catch null if scan results fires after wifi disabled or while wifi is
		 * in intermediate state
		 */
        if (scanResults == null) {
            LogUtil.log(context, R.string.null_scan_results);
            return knownbysignal;
        }
        /*
         * Sort by ScanResult.level which is signal
		 */
        Collections.sort(scanResults, new SortBySignal());
        /*
         * Known networks from supplicant.
		 */
        List<WifiConfiguration> wifiConfigs = AsyncWifiManager.getWifiManager(ctxt.get())
                .getConfiguredNetworks();
        /*
         * Iterate the known networks over the scan results, adding found known
		 * networks.
		 */
        for (ScanResult sResult : scanResults) {
            for (WifiConfiguration configuration : wifiConfigs) {
            /*
             * Look for scan result in our known list
			 */
                if (isConfigurationEqual(sResult, configuration)
                        && PrefUtil.getNetworkState(ctxt.get(), configuration.networkId)) {
                    WFConfig w = new WFConfig(sResult,
                            configuration);
                    knownbysignal.add(w);
                    logScanResult(context, sResult, configuration);
                }
            }
        }

        LogUtil.log(context,
                context.getString(R.string.number_of_known) +
                        String.valueOf(knownbysignal.size()));
        //noinspection StatementWithEmptyBody
        switch (numKnownNetworks = knownbysignal.size()) {
        }
        multiBssidCheck(knownbysignal);
        return knownbysignal;
    }

    private static void multiBssidCheck(List<WFConfig> knownbysignal) {
        if (PrefUtil.readBoolean(ctxt.get(), Pref.MULTIBSSID.key()) && knownbysignal.size() > 1) {
            /*
             * We only need to set BSSID for best candidate
             * and only if there are other networks in range with its SSID
             */
            WFConfig w;
            AsyncWifiManager wm = AsyncWifiManager.get(ctxt.get());
            try {
                //Get best network
                w = knownbysignal.get(0);
                LogUtil.log(ctxt.get(), "Starting Multipoint Network Check");
                if (containsSSID(w.wificonfig.SSID, knownbysignal) > 1) {
                    LogUtil.log(ctxt.get(), "Multipoint Network Detected");
                    WifiConfiguration config = wm.getConfiguredNetworks().get(w.wificonfig.networkId);
                    if (config.BSSID == null || !config.BSSID.equals(w.wificonfig.BSSID)) {
                        setNetworkBssid(config, w.wificonfig.BSSID);
                    }
                }
            } catch (NullPointerException | IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }
    }

    private static WifiConfiguration setNetworkBssid(WifiConfiguration config, String bssid) {
        AsyncWifiManager wm = AsyncWifiManager.get(ctxt.get());
        config.BSSID = bssid;
        if (bssid == null) {
            wm.removeNetwork(config.networkId);
            wm.addNetwork(config);
            LogUtil.log(ctxt.get(), "BSSID unset for network " + config.SSID);
        } else {
            wm.updateNetwork(config);
            LogUtil.log(ctxt.get(), "BSSID " + config.BSSID + " set for network " + config.SSID);
        }
        wm.saveConfiguration();
        return config;
    }

    private static boolean isConfigurationEqual(ScanResult sResult, WifiConfiguration configuration) {
        return StringUtil.removeQuotes(sResult.SSID).equals(StringUtil.removeQuotes(configuration.SSID));
    }

    private static int getNetworkID() {
        WifiInfo wi = AsyncWifiManager.getWifiManager(ctxt.get()).getConnectionInfo();
        if (wi != null)
            return wi.getNetworkId();
        else
            return -1;
    }

    private static WifiConfiguration getNetworkByNID(Context context,
                                                     int network) {
        List<WifiConfiguration> configs = AsyncWifiManager.get(context)
                .getConfiguredNetworks();
        if (configs != null) {
            for (WifiConfiguration w : configs) {
                if (w.networkId == network)
                    return w;
            }
        }
        return new WifiConfiguration();
    }

    private static String getSSID() {
        String s = null;
        try {
            s = (AsyncWifiManager.getWifiManager(ctxt.get()).getConnectionInfo().getSSID());
        } catch (NullPointerException e) {
            /*
             * whoops, no connectioninfo, or WifiManager is null
             */
        }
        if (s != null)
            return s;
        else
            return ("null");
    }

    private static SupplicantState getSupplicantState() {
        WifiInfo i = AsyncWifiManager.getWifiManager(ctxt.get()).getConnectionInfo();
        if (i != null)
            return i.getSupplicantState();
        else
            return SupplicantState.INVALID;
    }

    private static String getSupplicantStateString(SupplicantState sstate) {
        if (SupplicantState.isValidState(sstate))
            return (sstate.name());
        else
            return (SSTATE_INVALID);
    }

    private static void logScanResult(Context context,
                                      ScanResult sResult, WifiConfiguration wfResult) {
        String out = context.getString(R.string.found_ssid) + sResult.SSID +
                NEWLINE +
                sResult.BSSID +
                NEWLINE +
                context.getString(R.string.capabilities) +
                sResult.capabilities +
                NEWLINE +
                context.getString(R.string.signal_level) +
                String.valueOf(sResult.level) +
                NEWLINE +
                context.getString(R.string.priority) +
                String.valueOf(wfResult.priority);
        LogUtil.log(context, out);
    }

    private static void logBestNetwork(Context context,
                                       WFConfig best) {
        String output = context.getString(R.string.best_signal_ssid) + best.wificonfig.SSID +
                COLON +
                best.wificonfig.BSSID +
                NEWLINE +
                context.getString(R.string.signal_level) +
                String.valueOf(best.level) +
                NEWLINE +
                context.getString(R.string.nid) +
                String.valueOf(best.wificonfig.networkId);
        LogUtil.log(context, output);
    }

    private static void notifyWrap(Context context, String string) {

        if (PrefUtil.getFlag(Pref.NOTIFICATIONS)) {
            NotifUtil.show(context,
                    context.getString(R.string.wifi_connection_problem)
                            + string, string, PendingIntent
                            .getActivity(context, NotifUtil.getPendingIntentCode(),
                                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    PendingIntent.FLAG_UPDATE_CURRENT)
            );
        }
    }

    private static boolean supplicantPatternCheck() {
        WFMonitor me = _wfmonitor;
        me._supplicantFifo.add(me.lastSupplicantState);
        SupplicantPattern pattern = me._supplicantFifo
                .containsPatterns(SupplicantPatterns.getSupplicantPatterns());
        if (pattern != null) {
            LogUtil.log(ctxt.get(), "Supplicant Reset Triggered:");
            LogUtil.log(ctxt.get(), pattern.toString());
            me._supplicantFifo.clear();
            toggleWifi();
            return true;
        } else
            return false;
    }

    private static void n1Fix() {
        /*
         * Nexus One Sleep Fix duplicating widget function
		 */
        if (AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled()
                && !_wfmonitor.screenstate) {
            toggleWifi();
        }
    }

    public static void removeNetwork(Context context, int network) {
        AsyncWifiManager.get(context).removeNetwork(network);
        AsyncWifiManager.get(context).saveConfiguration();
    }

    private static void restoreNetworkPriority(Context context,
                                               int n) {
        WifiConfiguration network = getNetworkByNID(context, n);
        if (network != null) {
            network.priority = 2;
            AsyncWifiManager.get(context).updateNetwork(network);
        }
    }

    private static boolean scancontainsBSSID(String bssid,
                                             List<ScanResult> results) {
        for (ScanResult sResult : results) {
            if (sResult.BSSID.equals(bssid))
                return true;
        }
        return false;
    }

    private static boolean shouldManage(Context ctx) {
        return !PrefUtil.readManagedState(ctx, getNetworkID());
    }

    private static boolean statNotifCheck() {
        return _wfmonitor.screenstate
                && AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled();
    }

    private static boolean supplicantInterruptCheck(Context context) {

        SupplicantState sstate = getSupplicantState();
        /*
         * First, make sure this won't interrupt anything
		 */
        return !(sstate.name().equals(SSTATE_ASSOCIATING)
                || sstate.name().equals(SSTATE_ASSOCIATED)
                || sstate.equals(SupplicantState.COMPLETED)
                || sstate.equals(SupplicantState.GROUP_HANDSHAKE)
                || sstate.equals(SupplicantState.FOUR_WAY_HANDSHAKE));
    }

    private static void toggleWifi() {
        /*
         * Send Toggle request to broadcastreceiver
		 */
        LogUtil.log(ctxt.get(), R.string.toggling_wifi);
        ctxt.get().sendBroadcast(new Intent(WidgetReceiver.TOGGLE_WIFI));
        _wfmonitor.clearConnectedStatus(
                ctxt.get().getString(R.string.toggling_wifi));
    }

    private static void restoreandReset(Context context,
                                        WFConfig network) {
        /*
         * Enable bugged disabled networks, reset
		 */
        fixDisabledNetworks(context);
        toggleWifi();
        connecting = 0;
    }

    public static WFMonitor newInstance(Context context) {
        if (_wfmonitor == null)
            _wfmonitor = new WFMonitor(context.getApplicationContext());
         /*
         * Set up system Intent filters
		 */
        IntentFilter filter = new IntentFilter(
                WifiManager.WIFI_STATE_CHANGED_ACTION);
        // Supplicant State filter
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        // Network State filter
        filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        // wifi scan results available callback
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        // Sleep Check
        filter.addAction(SLEEPCHECKINTENT);
        BroadcastHelper.registerReceiver(context.getApplicationContext(), _wfmonitor.receiver, filter, false);
        /*
         * Local Intent filters
		 */
        // Connect intent
        filter = new IntentFilter(CONNECTINTENT);
        // User Event
        filter.addAction(REASSOCIATE_INTENT);
        BroadcastHelper.registerReceiver(context.getApplicationContext(), _wfmonitor.localreceiver, filter, true);


        _wfmonitor._statusdispatcher = new StatusDispatcher(context, handler);
        ScreenStateDetector.setOnScreenStateChangedListener(_wfmonitor);
        _wfmonitor.screenstate = ScreenStateDetector.getScreenState(context);

        /*
         * Set current AP int
		 */
        /*
         * Set current supplicant state
		 */
        _wfmonitor.lastSupplicantState = getSupplicantState();
        /*
         * Set current wifi radio state
		 */
        wifistate = AsyncWifiManager.getWifiManager(context).isWifiEnabled();
        if (wifistate)
            enforceAttBlacklistState(context);

        /*
         * Initialize WakeLock and WifiLock
		 */
        _wfmonitor.wakelock = new LoggingWakeLock(context, "WFMonitor");

        _wfmonitor.wifilock = new WifiLock(context) {
            @Override
            public void onAcquire() {
                LogUtil.log(ctxt.get(), R.string.acquiring_wifi_lock);
                super.onAcquire();
            }

            @Override
            public void onRelease() {
                LogUtil.log(ctxt.get(), R.string.releasing_wifi_lock);
                super.onRelease();
            }
        };

		/*
         * acquire wifi lock if should
		 */
        if (PrefUtil.getFlag(Pref.WIFILOCK))
            _wfmonitor.wifilock.lock(true);

		/*
         * Start status notification if should
		 */
        if (PrefUtil.readBoolean(context, Pref.STATUS_NOTIFICATION.key()))
            _wfmonitor.setStatNotif(true);

		/*
         * Instantiate network checker
		 */
        _hostup = Hostup.newInstance(context);
        _hostup.registerClient(_wfmonitor);
        return _wfmonitor;
    }

    private void networkUp(Context context) {
        /*
         * _hostup.getHostup does all the heavy lifting
		 */
        LogUtil.log(context, R.string.network_check);

		/*
         * Launches ICMP/HTTP HEAD check threads which compete for successful
		 * state return.
		 *
		 * If we fail the first check, try again with _hostup default to be sure
		 */
        if (!getIsOnWifi(context) || !getIsSupplicantConnected(context))
            return;
        _hostup.getHostup(REACHABLE);
    }

    private void handleBroadcast(Intent intent) {
        /*
         * Dispatches the broadcast intent to the handler for processing
		 */
        Bundle data = new Bundle();
        data.putString(PrefUtil.INTENT_ACTION, intent.getAction());
        if (intent.getExtras() != null)
            data.putAll(intent.getExtras());
        IntentRunnable i = new IntentRunnable(data);
        handler.post(i);
    }

    private void clearHandler() {
        handler.removeCallbacksAndMessages(null);
        /*
         * Also clear all relevant flags
		 */
        shouldrepair = false;
        pendingreconnect = false;
    }

    private void clearMessage(Runnable r) {
        handler.removeCallbacks(r);
    }

    private void clearConnectedStatus(String state) {
        StatusMessage.send(
                ctxt.get(),
                StatusMessage.getNew().setSSID(StatusMessage.EMPTY)
                        .setSignal(0).setStatus(state)
                        .setLinkSpeed("0")
                        .setShow(1)
        );
    }

    private void checkSignal(Context context) {
        WifiInfo ci = AsyncWifiManager.getWifiManager(context).getConnectionInfo();
        int signal = ci.getRssi();

        if (statNotifCheck()) {
            StatusMessage m = new StatusMessage().setSSID(getSSID());
            m.setSignal(WifiManager.calculateSignalLevel(signal, 5));
            m.setLinkSpeed(String.valueOf(ci.getLinkSpeed()));
            StatusMessage.send(context, m);
        }
        if (!screenstate && signal == -127) {
            LogUtil.log(context, context.getString(R.string.deep_radio_sleep));
        } else if (_signalCheckTime < System.currentTimeMillis()
                && Math.abs(signal) > Math.abs(getSignalThreshold(context))) {
            notifyWrap(context, context.getString(R.string.signal_poor));
            AsyncWifiManager.getWifiManager(ctxt.get()).startScan();
            _signalhopping = true;
            _signalCheckTime = System.currentTimeMillis()
                    + SIGNAL_CHECK_INTERVAL;
        }
        LogUtil.log(context,
                (new StringBuilder(context.getString(R.string.current_dbm))
                        .append(String.valueOf(signal))).toString()
        );
    }

    private void connectToAP(Context context, int network) {

        if (!AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled())
            return;
        /*
         * Back to explicit connection
		 */

        if (network == -1)
            return;

        WifiConfiguration target = getNetworkByNID(context, network);
        /*
         * Create sparse WifiConfiguration with details of desired connectee
		 */
        connectee = new WFConfig();
        connectee.wificonfig = target;
        target.status = WifiConfiguration.Status.CURRENT;
        AsyncWifiManager.get(context).updateNetwork(target);
        AsyncWifiManager.get(context).enableNetwork(target.networkId, false);
        AsyncWifiManager.get(context).disconnect();
        /*
         * Remove all posts to handler
		 */
        clearHandler();
        /*
         * Connect
		 */
        AsyncWifiManager.get(ctxt.get()).enableNetwork(
                connectee.wificonfig.networkId, true);

        LogUtil.log(context,
                context
                        .getString(R.string.connecting_to_network) +
                        connectee.wificonfig.SSID
        );
    }

    private int connectToBest(Context context, List<WFConfig> networks) {
        /*
         * Check for connectee (explicit connection)
		 */
        if (connectee != null) for (WFConfig network : networks)
            if (network.wificonfig.networkId == connectee.wificonfig.networkId) {
                logBestNetwork(context, network);
                connecting++;
                if (connecting >= CONNECTING_THRESHOLD) {
                    LogUtil.log(context, R.string.connection_threshold_exceeded);
                    restoreandReset(context, network);
                } else {
                    connectToAP(context, connectee.wificonfig.networkId);
                }
                return network.wificonfig.networkId;
            }
        /*
         * Select by best available
		 */

        WFConfig best;
        try {
            best = getKnownAPsBySignal(context).get(0);
        } catch (IndexOutOfBoundsException e) {
            return NULLVAL;
        }
        /*
         * Until BSSID blacklisting is implemented, no point
		 */
        connectToAP(context, best.wificonfig.networkId);
        logBestNetwork(context, best);

        return best.wificonfig.networkId;
    }

    private void dispatchIntent(Context context, Bundle data) {

        String iAction = data.getString(PrefUtil.INTENT_ACTION);
        switch (iAction != null ? iAction : null) {
            case WifiManager.WIFI_STATE_CHANGED_ACTION:
            /*
             * Wifi state, e.g. on/off
			 */
                handleWifiState(data);
                break;
            case WifiManager.SUPPLICANT_STATE_CHANGED_ACTION:
            /*
             * Supplicant events
			 */
                handleSupplicantIntent(data);
                break;
            case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION:
            /*
             * Scan Results
			 */
                handleScanResults();
                break;
            case ConnectivityManager.CONNECTIVITY_ACTION:
            /*
             * IP connectivity established
			 */
                handleNetworkAction(data);
                break;
            case CONNECTINTENT:
                handleConnectIntent(context, data);
                break;
            case REASSOCIATE_INTENT:
                handleReassociateEvent();
                break;
            case SLEEPCHECKINTENT:
            /*
             * Run Sleep Check immediately
             * with wake lock,
             */
                if (shouldManage(ctxt.get())) {
                    handlerWrapper(rSleepcheck);
                }
                break;
            default:
                LogUtil.log(context, (iAction));
                break;
        }

    }

    private void handleConnect() {
        String ssid = getSSID();
        if (StringUtil.removeQuotes(connectee.wificonfig.SSID)
                .equals(StringUtil.removeQuotes(ssid))) {
            LogUtil.log(ctxt.get(),
                    ctxt.get().getString(R.string.connnection_completed)
                            + connectee.wificonfig.SSID
            );
            connectee = null;
        } else {
            LogUtil.log(ctxt.get(), R.string.connect_failed);

            //noinspection StatementWithEmptyBody
            if (supplicantInterruptCheck(ctxt.get()))
                toggleWifi();
            else {
            }
        }
    }

    private void handleConnectIntent(Context context, Bundle data) {
        connectToAP(ctxt.get(), data.getString(NETWORKNAME));
    }

    private void connectToAP(Context context, String s) {
        connectToAP(context, PrefUtil.getNid(context, s));
    }

    private void handleNetworkAction(Bundle data) {
        NetworkInfo networkInfo = data.getParcelable(WifiManager.EXTRA_NETWORK_INFO);
        /*
         * This action means network connectivty has changed but, we only want
		 * to run this code for wifi
		 */
        if ((networkInfo != null ? networkInfo.getType() : 0) == ConnectivityManager.TYPE_WIFI) {
            if (networkInfo.getState().equals(NetworkInfo.State.CONNECTED)) {
                WifiInfo connectionInfo = AsyncWifiManager.getWifiManager(ctxt.get())
                        .getConnectionInfo();
                onNetworkConnected(connectionInfo);
            } else if (networkInfo.getState().equals(NetworkInfo.State.DISCONNECTED))
                onNetworkDisconnected();
        }
    }

    private void handleReassociateEvent() {
        if (getNetworkID() != -1) {
            AsyncWifiManager.get(ctxt.get()).reassociate();
            LogUtil.log(ctxt.get(), R.string.repairing);
        } else
            NotifUtil.showToast(ctxt.get(), R.string.not_connected);
    }

    private void checkWifi() {
        if (getIsSupplicantConnected(ctxt.get())) {
            /*
             * Starts network via Hostup
			 */
            handlerWrapper(NetCheckRunnable);
        } else {
            wakelock.lock(false);
        }
    }

    private void handleNetworkResult(boolean state) {
        if (!state) {
            wakelock.lock(true);
            shouldrepair = true;
            wifiRepair();
        } else
            wakelock.lock(false);
    }

    void cleanup() {
        BroadcastHelper.unregisterReceiver(ctxt.get(), receiver);
        clearQueue();
        clearHandler();
        setStatNotif(false);
        _statusdispatcher.unregister();
        wifilock.lock(false);
        _hostup.finish();
    }

    private void clearQueue() {
        pendingscan = false;
        pendingreconnect = false;
        shouldrepair = false;
    }

    private void prepareConnect() {
        /*
         * Flush queue if connected
		 *
		 * Also clear any error notifications
		 */
        if (connectee != null) {
            handleConnect();
        }
        clearQueue();
        pendingscan = false;
        pendingreconnect = false;
    }

    /*
     * Lets us control duplicate posts and odd handler behavior when screen is
     * off
     */
    private boolean handlerWrapper(Runnable r) {
        if (handler.hasMessages(r.hashCode()))
            handler.removeCallbacks(r);
        Message out = Message.obtain(handler, r);
        out.what = r.hashCode();
        if (screenstate)
            return handler.sendMessage(out);
        else
            return handler.sendMessageDelayed(out, REALLYSHORTWAIT);
    }

    private boolean handlerWrapper(Runnable r, long delay) {
        if (handler.hasMessages(r.hashCode()))
            handler.removeCallbacks(r);
        Message out = Message.obtain(handler, r);
        out.what = r.hashCode();
        return handler.sendMessageDelayed(out, delay);
    }

    private void handleScanResults() {
        /*
         * Disabled network check/enforcement
         */
        fixDisabledNetworks(ctxt.get());
        enforceAttBlacklistState(ctxt.get());
        /*
         * Reset timer: we've successfully scanned
		 */
        _scantimer.start();
        /*
         * Sanity check
		 */
        if (!AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled())
            return;
        if (_signalhopping) {
            _signalhopping = false;
            handlerWrapper(rSignalhop);
        } else if (!pendingscan) {
            if (getIsOnWifi(ctxt.get())) {
                /*
                 * Signalhop code out
				 */
            } else {
                /*
                 * Parse scan and connect if any known networks discovered
				 */
                List<WFConfig> networks = getKnownAPsBySignal(ctxt.get());
                if (supplicantInterruptCheck(ctxt.get())) {
                    LogUtil.log(ctxt.get(), R.string.parsing_scan_results);
                    connectToBest(ctxt.get(), networks);
                }
            }
        } else if (!pendingreconnect) {
            /*
             * Service called the scan: dispatch appropriate runnable
			 */
            pendingscan = false;
            handlerWrapper(rRepair);
            LogUtil.log(ctxt.get(), R.string.repairhandler);
        } else {
            pendingscan = false;
            handlerWrapper(rReconnect);
            LogUtil.log(ctxt.get(), R.string.reconnecthandler);
        }

    }

    private void handleSupplicantIntent(Bundle data) {
        /*
         * Get Supplicant New State but first make sure it's new
		 */
        SupplicantState sState = data
                .getParcelable(WifiManager.EXTRA_NEW_STATE);

        lastSupplicantState = sState;
        /*
         * Supplicant state pattern wedge detection
		 */
        if (supplicantPatternCheck())
            return;

		/*
         * Check for auth error
		 */
        if (data.containsKey(WifiManager.EXTRA_SUPPLICANT_ERROR)
                && data.getInt(WifiManager.EXTRA_SUPPLICANT_ERROR)
                == WifiManager.ERROR_AUTHENTICATING)
            authError();
        /*
         * Supplicant state-specific logic
		 */
        handleSupplicantState(sState);
    }

    private void authError() {
        LogUtil.log(ctxt.get(), R.string.authentication_error);
        LogUtil.log(ctxt.get(), R.string.verify_passphrase);
    }

    private void handleSupplicantState(SupplicantState sState) {
        if (!AsyncWifiManager.getWifiManager(ctxt.get()).isWifiEnabled())
            return;
        /*
         * Status notification updating supplicant state
		 */
        if (statNotifCheck()) {
            StatusMessage.send(ctxt.get(),
                    new StatusMessage().setStatus(sState.name()));
        }
        /*
         * Log new supplicant state
		 */
        LogUtil.log(ctxt.get(),
                ctxt.get().getString(
                        R.string.supplicant_state) +
                        String.valueOf(sState)
        );
        /*
         * Supplicant State triggers
		 */
        if (sState.equals(SupplicantState.INACTIVE)) {
            /*
             * DHCP bug?
			 */
            if (PrefUtil.getWatchdogPolicy(ctxt.get())) {
                /*
                 * Notify user of watchdog policy issue
                 */
                if (!PrefUtil.readBoolean(ctxt.get(), WATCHDOG_POLICY_KEY)) {
                    notifyWrap(ctxt.get(), ctxt.get().getString(R.string.watchdog_policy_notification));
                    PrefUtil.writeBoolean(ctxt.get(), WATCHDOG_POLICY_KEY, true);
                }
            }

        } else if (sState.name().equals(SSTATE_INVALID))
            supplicantFix();
        else if (sState.name().equals(SSTATE_ASSOCIATING)) {
            onNetworkConnecting();
        } else if (sState.name().equals(SupplicantState.ASSOCIATED.name()) && getIsOnWifi(ctxt.get()))
            onNetworkDisconnected();
    }

    private void handleWifiState(Bundle data) {
        // What kind of state change is it?
        int state = data.getInt(WifiManager.EXTRA_WIFI_STATE,
                WifiManager.WIFI_STATE_UNKNOWN);
        switch (state) {
            case WifiManager.WIFI_STATE_ENABLED:
                LogUtil.log(ctxt.get(), R.string.wifi_state_enabled);
                onWifiEnabled();
                break;
            case WifiManager.WIFI_STATE_ENABLING:
                LogUtil.log(ctxt.get(), R.string.wifi_state_enabling);
                break;
            case WifiManager.WIFI_STATE_DISABLED:
                LogUtil.log(ctxt.get(), R.string.wifi_state_disabled);
                onWifiDisabled();
                break;
            case WifiManager.WIFI_STATE_DISABLING:
                LogUtil.log(ctxt.get(), R.string.wifi_state_disabling);
                break;
            case WifiManager.WIFI_STATE_UNKNOWN:
                LogUtil.log(ctxt.get(), R.string.wifi_state_unknown);
                break;
        }
    }

    private void onNetworkDisconnected() {
        String ssid = null;
        try {
            ssid = mLastConnectedNetwork.getSSID();
        } catch (NullPointerException ignored) {
        }
        if (ssid == null)
            ssid = "none";
        LogUtil.log(ctxt.get(), ssid
                + " : "
                + ctxt.get().getString(R.string.network_disconnected));
        if (wifistate)
            clearConnectedStatus((ctxt.get().getString(R.string.disconnected)));
    }

    private void onNetworkConnecting() {
        //Demote bad networks
        handlerWrapper(rDemoter, CWDOG_DELAY);
        StatusMessage message = _statusdispatcher.getStatusMessage()
                .setSSID(getSSID()).setStatus(ctxt.get(), R.string.connecting);
        StatusMessage.send(ctxt.get(), message);
        _statusdispatcher.refreshWidget(null);
    }

    private void onNetworkConnected(WifiInfo wifiInfo) {
        mLastConnectedNetwork = wifiInfo;
        prepareConnect();
        /*
         * Disable Demoter, we've connected
		 */
        clearMessage(rDemoter);
		/*
		 * If this was a bad network before, it's good now.
		 */
        int n = getNetworkID();
        restoreNetworkPriority(ctxt.get(), n);
        StatusMessage.send(ctxt.get(), _statusdispatcher.getStatusMessage().setSSID(getSSID())
                .setStatus(ctxt.get(), R.string.connected_to_network).setSignal(0));
        _statusdispatcher.refreshWidget(null);
		/*
		 * Make sure connectee is null
		 */
        connectee = null;
		/*
		 * Reset repair_reset flag to false
		 */
        repair_reset = false;
		/*
		 * restart the Main tick
		 */
        _hostup.setFailover();
        sleepCheck(!screenstate);
        /*
		 * Log Non-Managed network
		 */
        if (!shouldManage(ctxt.get()))
            LogUtil.log(ctxt.get(),
                    ctxt.get().getString(
                            R.string.not_managing_network) +
                            getSSID()
            );

		/*
		 * Log connection
		 */
        LogUtil.log(ctxt.get(),
                ctxt.get().getString(
                        R.string.connected_to_network) +
                        " : " +
                        getSSID()
        );
    }

    private void onScreenOff() {
		/*
		 * Clear StatusDispatcher
		 */
        _statusdispatcher.clearQueue();

		/*
		 * Disable Sleep check
		 */
        if (PrefUtil.getFlag(Pref.MANAGESLEEP))
            sleepCheck(true);
        else {

            if (PrefUtil.getFlag(Pref.WIFILOCK))
                wifilock.lock(false);
        }

		/*
		 * Schedule N1 fix
		 */
        if (PrefUtil.getFlag(Pref.N1FIX2)) {
            handlerWrapper(rN1Fix, REACHABLE);
            LogUtil.log(ctxt.get(), R.string.scheduling_n1_fix);
        }
        LogUtil.log(ctxt.get(), R.string.screen_off_handler);
    }

    private void onScreenOn() {
		/*
		 * Re-enable lock if it's off
		 */
        if (PrefUtil.getFlag(Pref.WIFILOCK))
            wifilock.lock(true);

        sleepCheck(false);
        LogUtil.log(ctxt.get(), R.string.screen_on_handler);

		/*
		 * Notify current state on resume
		 */
        if (PrefUtil.getFlag(Pref.STATUS_NOTIFICATION)) {
            if (!wifistate)
                clearConnectedStatus(ctxt.get().getString(R.string.wifi_is_disabled));
            else {
                if (getIsOnWifi(ctxt.get())) {
                    StatusMessage.send(ctxt.get(), _statusdispatcher.getStatusMessage().setShow(1));
                } else {
                    clearConnectedStatus(getSupplicantStateString(getSupplicantState()));
                }
            }
        }
        if (getIsOnWifi(ctxt.get()) && wifistate) {
            handlerWrapper(rMain);
            _statusdispatcher.refreshWidget(null);
        }
    }

    public void onScreenStateChanged(boolean state) {
        screenstate = state;
        if (state)
            onScreenOn();
        else
            onScreenOff();
    }

    private void onWifiDisabled() {
        wifistate = false;
        clearHandler();
        clearConnectedStatus(ctxt.get().getString(R.string.wifi_is_disabled));
        _statusdispatcher.refreshWidget(null);
    }

    private void onWifiEnabled() {
        clearConnectedStatus(ctxt.get().getString(R.string.wifi_is_enabled));
        _statusdispatcher.refreshWidget(new StatusMessage().setStatus(ctxt
                .get().getString(R.string.wifi_is_enabled)));
        wifistate = true;
        handlerWrapper(rMain, LOOPWAIT);

        if (PrefUtil.getFlag(Pref.STATUS_NOTIFICATION) && screenstate)
            setStatNotif(true);

		/*
		 * Remove wifi state lock
		 */
        if (PrefUtil.readBoolean(ctxt.get(), PrefConstants.WIFI_STATE_LOCK))
            PrefUtil.writeBoolean(ctxt.get(), PrefConstants.WIFI_STATE_LOCK,
                    false);
        /*
         *  Enforce disabled/enabled networks
         */
        fixDisabledNetworks(ctxt.get());
        enforceAttBlacklistState(ctxt.get());
    }

    public void setStatNotif(boolean state) {

        StatusMessage sm = StatusMessage.getNew().setStatus(
                getSupplicantStateString(getSupplicantState())).setSSID(getSSID());
        if (state) {
            sm.setShow(1);
        } else {
            sm.setShow(-1);
            NotifUtil.addStatNotif(ctxt.get(), sm);
        }

        StatusMessage.send(ctxt.get(), sm);
        _statusdispatcher.refreshWidget(null);
    }

    private void signalHop() {
		/*
         * Find network with signal at least HOP_THRESHOLD greater than current network
		 * or just stay connected
		 */
        if (!wifistate)
            return;
		/*
		 * Switch to best
		 */
        WFConfig bestap = null;
        int current = AsyncWifiManager.getWifiManager(ctxt.get()).getConnectionInfo().getNetworkId();
        int level = -100;
        List<WFConfig> knownbysignal = getKnownAPsBySignal(ctxt.get());

        if (numKnownNetworks > 1) {
            for (WFConfig config : knownbysignal) {
                if (config.level - level >= HOP_THRESHOLD) {
                    bestap = config;
                }
            }
        }
        if (bestap != null && bestap.wificonfig.networkId != getNetworkID()) {
            logBestNetwork(ctxt.get(), bestap);
            LogUtil.log(ctxt.get(),
                    ctxt.get().getString(R.string.hopping) +
                            bestap.wificonfig.SSID +
                            " : " +
                            bestap.wificonfig.BSSID);
            connectToAP(ctxt.get(), bestap.wificonfig.networkId);
        } else {
            LogUtil.log(ctxt.get(), R.string.signalhop_no_result);
        }
    }

    private void sleepCheck(boolean screenoff) {
        Intent i = new Intent(SLEEPCHECKINTENT);
        PendingIntent p = PendingIntent.getBroadcast(ctxt.get(), 0, i,
                PendingIntent.FLAG_CANCEL_CURRENT);
        if (screenoff && wifistate) {
            /*
			 * Start sleep check Using alarm here because some devices seem to
			 * not fire handlers in deep sleep.
			 */
            clearMessage(rMain);
            if (!ServiceAlarm.alarmExists(ctxt.get(), i))
                ServiceAlarm.addAlarm(ctxt.get(),
                        SHORTWAIT, true, SLEEPWAIT, p);
        } else {
			/*
			 * Screen is on, remove any posts
			 */
            ServiceAlarm.unsetAlarm(ctxt.get(), p);
            clearMessage(rSleepcheck);
			/*
			 * Check state
			 */
            handlerWrapper(rMain, REALLYSHORTWAIT);
        }
    }

    private void startScan(boolean pending) {
        pendingscan = pending;
        if (AsyncWifiManager.getWifiManager(ctxt.get()).startScan()) {
            LogUtil.log(ctxt.get(), R.string.initiating_scan);
        } else {
			/*
			 * Reset supplicant, log
			 */
            toggleWifi();
            LogUtil.log(ctxt.get(), R.string.scan_failed);
        }
    }

    private void supplicantFix() {
        // Toggling wifi resets the supplicant
        toggleWifi();

        LogUtil.log(ctxt.get(), R.string.running_supplicant_fix);
    }

    private void wifiRepair() {
        if (!shouldrepair) {
            wifilock.lock(false);
            return;
        }
        handlerWrapper(rWifiTask);
        LogUtil.log(ctxt.get(), R.string.running_wifi_repair);
        shouldrepair = false;
    }

    public void wifiLock(boolean b) {
        wifilock.lock(b);
    }

    @Override
    public void onHostupResponse(HostMessage out) {
        Context context = ctxt.get();
        if (!out.state) {
            /*
             * Try #2
			 */
            if (!getIsOnWifi(ctxt.get()))
                return;
            LogUtil.log(context, context.getString(R.string.network_check_retry));
            _hostup.getHostup(REACHABLE);

        }
        isUp = out.state;
        if (isUp)
            mRepairLevel = W_REASSOCIATE;
        LogUtil.log(context, out.status);
        handlerWrapper(PostExecuteRunnable);
    }

    /*
         * Processes intent message
         */
    private static class IntentRunnable implements Runnable {
        Bundle d;

        IntentRunnable(Bundle b) {
            this.d = b;
        }

        @Override
        public void run() {
            try {
                _wfmonitor.dispatchIntent(ctxt.get(), d);
            } catch (NullPointerException e) {
                LogUtil.log(ctxt.get(), R.string.dispatchintent_err);
            }
        }
    }

    /*
         * Signal Check and Network Result handler for AsyncTask postExcecute
         */
    private static class PostNetCheckRunnable implements Runnable {
        Boolean state;

        PostNetCheckRunnable(boolean b) {
            this.state = b;
        }

        @Override
        public void run() {
            _wfmonitor.checkSignal(ctxt.get());
            _wfmonitor.handleNetworkResult(state);
        }
    }


}

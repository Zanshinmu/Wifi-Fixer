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

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import org.wahtod.wififixer.legacy.StrictModeDetector;
import org.wahtod.wififixer.prefs.MyPrefs;
import org.wahtod.wififixer.prefs.PrefConstants.Pref;
import org.wahtod.wififixer.prefs.PrefUtil;
import org.wahtod.wififixer.utility.LogUtil;
import org.wahtod.wififixer.utility.ScreenStateDetector;
import org.wahtod.wififixer.utility.ScreenStateDetector.OnScreenStateChangedListener;
import org.wahtod.wififixer.utility.ServiceAlarm;
import org.wahtod.wififixer.utility.StatusDispatcher;
import org.wahtod.wififixer.utility.StatusMessage;
import org.wahtod.wififixer.widget.WidgetHelper;

public class WFMonitorService extends Service implements
        OnScreenStateChangedListener {

	/*
     * Loads WFConnection class, Prefs
	 */

    // Screen State SharedPref key
    public static final String SCREENOFF = "SCREENOFF";
    protected static boolean screenstate;
    // Version
    private static int version = 0;
    private WFMonitor wifi;
    private ScreenStateDetector screenstateHandler;

    private void cleanup() {
        wifi.wifiLock(false);
        screenstateHandler.unsetOnScreenStateChangedListener(this);
        if (PrefUtil.readBoolean(this, Pref.HASWIDGET.key()))
            resetWidget();
        unregisterReceivers();
        wifi.setStatNotif(false);
        wifi.cleanup();
    }

    private void getPackageInfo() {
        PackageManager pm = getPackageManager();
        try {
            // ---get the package info---
            PackageInfo pi = pm.getPackageInfo(getString(R.string.packagename),
                    0);
            // ---display the versioncode--
            version = pi.versionCode;
        } catch (NameNotFoundException e) {
            /*
             * If own package isn't found, something is horribly wrong.
			 */
            e.printStackTrace();
        }
    }

    private void handleStart(Intent intent) {

        if (intent != null) {
            if (intent.hasExtra(ServiceAlarm.ALARM_START))
                LogUtil.log(this, getString(R.string.alarm_intent));
            else
                LogUtil.log(this, getString(R.string.start_intent));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        getPackageInfo();
        logStart();
        /*
         * Set Default Exception handler
		 */
        DefaultExceptionHandler.register(this);
        /*
         * Strict Mode check
		 */

        if (StrictModeDetector.setPolicy(false))
            LogUtil.log(this, (getString(R.string.strict_mode_extant)));
        else
            LogUtil.log(this, (getString(R.string.strict_mode_unavailable)));
        /*
         * Make sure service settings are enforced.
		 */
        ServiceAlarm.enforceServicePrefs(this);
        super.onCreate();
        /*
         * Load Preferences
		 */
        MyPrefs prefs = MyPrefs.newInstance(this);
        prefs.loadPrefs();
        /*
         * Set initial screen state
		 */
        setInitialScreenState();
		/*
		 * Initialize Wifi Connection class
		 */
        wifi = WFMonitor.newInstance(this);
		/*
		 * Start Service watchdog alarm
		 */
        ServiceAlarm.setServiceAlarm(this.getApplicationContext(), true);

        LogUtil.log(this, LogUtil.getLogTag(),
                (getString(R.string.oncreate)));
        WidgetHelper.findAppWidgets(this);
    }

    private void logStart() {
        String out = "\n\n********************\n" +
                getString(R.string.wififixerservice_build) + version +
                "\n" +
                "********************\n";

        LogUtil.log(this, LogUtil.getLogTag(),
                out);
    }

    @Override
    public void onDestroy() {
        LogUtil.log(this, getString(R.string.ondestroy));
        cleanup();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        /*
         *  All we want to do is log this for diagnosis:
         *  Service lifecycle should occur naturally
         */
        LogUtil.log(this, (getString(R.string.low_memory)));
        super.onLowMemory();
    }

    private void onScreenOff() {

		/*
		 * Set shared pref state for non-Eclair clients
		 */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ECLAIR_MR1)
            PrefUtil.writeBoolean(this, SCREENOFF, true);
        screenstate = false;
    }

    private void onScreenOn() {

		/*
		 * Set shared pref state
		 */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ECLAIR_MR1)
            PrefUtil.writeBoolean(this, SCREENOFF, false);
        screenstate = true;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        handleStart(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleStart(intent);
        return START_STICKY;
    }

    @Override
    public void onScreenStateChanged(boolean state) {
        if (state)
            onScreenOn();
        else
            onScreenOff();
    }

    private void resetWidget() {
        Handler h = new Handler();
		/*
		 * Shut down handler set widget to default make sure notification is
		 * cancelled
		 */
        StatusDispatcher._statusMessage = null;
        h.post(new StatusDispatcher.Widget(StatusMessage.getNew().setSSID(
                this.getString(R.string.service_inactive))));
        h.post(new StatusDispatcher.StatNotif(StatusMessage.getNew()
                .setShow(-1)));
    }

    private void setInitialScreenState() {
        screenstateHandler = ScreenStateDetector.newInstance(this);
        screenstate = ScreenStateDetector.getScreenState(this);
        ScreenStateDetector.setOnScreenStateChangedListener(this);
    }

    private void unregisterReceivers() {
        //prefs.unRegisterReceiver();
        screenstateHandler.unsetOnScreenStateChangedListener(this);
    }
}

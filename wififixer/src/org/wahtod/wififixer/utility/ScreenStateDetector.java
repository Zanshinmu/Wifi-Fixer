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

package org.wahtod.wififixer.utility;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;

import org.wahtod.wififixer.legacy.VersionedScreenState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class ScreenStateDetector {
    /*
	 * This should only ever be instantiated from a service. Clients can be
	 * whatever, so long as they implement the interface, and use the
	 * setOnScreenStateListener and unsetOnScreenStateListener methods
	 */

    private static final int SCREEN_EVENT_OFF = 0;
    private static final int SCREEN_EVENT_ON = 1;
    private static ArrayList<WeakReference<OnScreenStateChangedListener>> _clients = new ArrayList<WeakReference<OnScreenStateChangedListener>>();
    private static Handler statehandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case SCREEN_EVENT_OFF:
                    onScreenEvent(false);
                    break;

                case SCREEN_EVENT_ON:
                    onScreenEvent(true);
                    break;
            }
        }
    };
    private static ScreenStateDetector _screenStateDetector;
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String iAction = intent.getAction();

            if (iAction.equals(Intent.ACTION_SCREEN_ON))
                statehandler.sendEmptyMessage(SCREEN_EVENT_ON);
            else
                statehandler.sendEmptyMessage(SCREEN_EVENT_OFF);
        }
    };

    private ScreenStateDetector(Context context) {
            /*
			 * Register for screen state events
			 *
			 * Note: this Initializer must be used if you want to receive the
			 * intent broadcast: must use the unregister method appropriately in
			 * the context where you instantiated it or leak receiver
			 */
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastHelper.registerReceiver(context, receiver, filter, false);
    }

    public static boolean getScreenState(Context context) {
        return VersionedScreenState.getScreenState(context);
    }

    private static void onScreenEvent(boolean state) {
        for (WeakReference<OnScreenStateChangedListener> listener : _clients) {
            if (listener.get() != null)
                listener.get().onScreenStateChanged(state);
        }
    }

    public static void setOnScreenStateChangedListener(
            OnScreenStateChangedListener listener) {
        boolean found = false;
        for (WeakReference<OnScreenStateChangedListener> n : _clients) {
            if (n.get() != null && n.get().equals(listener)) {
                found = true;
                break;
            }
        }

        if (!found)
            _clients.add(new WeakReference<OnScreenStateChangedListener>(
                    listener));
    }

    public static ScreenStateDetector newInstance(Context context) {
        if (_screenStateDetector == null)
            _screenStateDetector = new ScreenStateDetector(context.getApplicationContext());
        return _screenStateDetector;
    }

    public void unregister(Context context) {
        BroadcastHelper.unregisterReceiver(context, receiver);
    }

    public void unsetOnScreenStateChangedListener(
            OnScreenStateChangedListener listener) {
        if (_clients.contains(listener))
            _clients.remove(listener);
    }

    public interface OnScreenStateChangedListener {
        void onScreenStateChanged(boolean state);
    }
}

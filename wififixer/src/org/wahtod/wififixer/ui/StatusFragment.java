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

package org.wahtod.wififixer.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.wahtod.wififixer.R;
import org.wahtod.wififixer.utility.AsyncWifiManager;
import org.wahtod.wififixer.utility.BroadcastHelper;
import org.wahtod.wififixer.utility.NotifUtil;
import org.wahtod.wififixer.utility.StatusDispatcher;
import org.wahtod.wififixer.utility.StatusMessage;
import org.wahtod.wififixer.utility.StringUtil;

import java.lang.ref.WeakReference;

public class StatusFragment extends Fragment {
    protected static final int REFRESH = 0;
    protected static final int REFRESH_DELAY = 5000;
    private static final int STATUS_MESSAGE = 337;
    private static final String EMPTYSTRING = "";
    private static final String DBM = "dBm";
    private static final String MB = "Mb";
    private static WeakReference<StatusFragment> self;
    private static Handler drawhandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            /*
             * handle new scanresult
			 * asynchronously (to avoid ANR)
			 */
            switch (message.what) {
                case REFRESH:
                    if (self.get().getActivity() != null) {
                        self.get().refresh();
                    }
                    break;

                case STATUS_MESSAGE:
                /*
                 * Change status text
				 */
                    if (message == null)
                        return;
                    if (!message.getData().isEmpty() && self.get()._views != null)
                        self.get()._views.setStatus(StatusMessage
                                .fromMessage(message).getStatus());
                    break;
            }
        }
    };
    private BroadcastReceiver statusreceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {

			/*
             * Dispatch intent commands to handler
			 */
            Message message = drawhandler.obtainMessage(STATUS_MESSAGE);
            Bundle data = new Bundle();
            if (intent.getExtras() != null) {
                data.putAll(intent.getExtras());
            }
            message.setData(data);
            drawhandler.sendMessage(message);
        }
    };
    private ViewHolder _views;

    public static StatusFragment newInstance(int num) {
        StatusFragment f = new StatusFragment();
        // Supply num input as an argument.
        Bundle args = new Bundle();
        args.putInt("num", num);
        f.setArguments(args);
        return f;
    }

    /*
     * Note that this WILL return a null String[] if called while wifi is off.
     */
    private static WifiInfo getNetwork(Context context) {
        WifiManager wm = AsyncWifiManager.getWifiManager(context);
        if (wm.isWifiEnabled()) {
            return wm.getConnectionInfo();
        } else
            return null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        self = new WeakReference<StatusFragment>(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.status, null);
        _views = new ViewHolder(v);
        return v;
    }

    @Override
    public void onDestroyView() {
        _views = null;
        super.onDestroyView();
    }

    @Override
    public void onPause() {
        unregisterReceiver();
        drawhandler.removeMessages(REFRESH);
        super.onPause();
    }

    @Override
    public void onResume() {
        registerReceiver();
        super.onResume();
        drawhandler.sendEmptyMessage(REFRESH);
    }

    private void refresh() {
        WifiInfo info = getNetwork(getContext());

        if (info == null) {
            _views.setSsid(getContext().getString(R.string.wifi_is_disabled));
            _views.setSignal(EMPTYSTRING);
            _views.setLinkspeed(EMPTYSTRING);
            _views.setStatus(EMPTYSTRING);
            _views.setIcon(R.drawable.icon);
        } else if (info.getRssi() == -200) {
            _views.setSsid(EMPTYSTRING);
            _views.setSignal(EMPTYSTRING);
            _views.setLinkspeed(EMPTYSTRING);
            _views.setIcon(R.drawable.icon);
        } else {
            _views.setSsid(StringUtil.removeQuotes(info.getSSID()));
            _views.setSignal(String.valueOf(info.getRssi()) + DBM);
            _views.setLinkspeed(String.valueOf(info.getLinkSpeed()) + MB);
            _views.setStatus(info.getSupplicantState().name());
            _views.setIcon(NotifUtil.getIconfromSignal(
                    WifiManager.calculateSignalLevel(
                            info.getRssi(), 5),
                    NotifUtil.ICON_SET_LARGE));
        }

        drawhandler.sendEmptyMessageDelayed(REFRESH, REFRESH_DELAY);
    }

    private void unregisterReceiver() {
        BroadcastHelper.unregisterReceiver(getContext(), statusreceiver);
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(StatusDispatcher.STATUS_ACTION);
        BroadcastHelper.registerReceiver(getContext(), statusreceiver, filter,
                true);
    }

    private static class ViewHolder {
        private TextView linkspeed;
        private TextView ssid;
        private TextView signal;
        private TextView status;
        private ImageView icon;

        public ViewHolder(View container) {
            linkspeed = (TextView) container.findViewById(R.id.linkspeed);
            ssid = (TextView) container.findViewById(R.id.SSID);
            signal = (TextView) container.findViewById(R.id.signal);
            status = (TextView) container.findViewById(R.id.status);
            icon = (ImageView) container.findViewById(R.id.signal_icon);
        }

        public void setLinkspeed(String l) {
            linkspeed.setText(l);
        }

        public void setSsid(String l) {
            ssid.setText(l);
        }

        public void setSignal(String l) {
            signal.setText(l);
        }

        public void setStatus(String l) {
            status.setText(l);
        }

        public void setIcon(int i) {
            icon.setImageResource(i);
        }
    }
}

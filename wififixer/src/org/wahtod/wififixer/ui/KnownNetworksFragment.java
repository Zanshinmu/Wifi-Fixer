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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.view.ActionMode.Callback;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.wahtod.wififixer.R;
import org.wahtod.wififixer.WFMonitor;
import org.wahtod.wififixer.prefs.PrefConstants;
import org.wahtod.wififixer.prefs.PrefUtil;
import org.wahtod.wififixer.utility.AsyncWifiManager;
import org.wahtod.wififixer.utility.BroadcastHelper;
import org.wahtod.wififixer.utility.NotifUtil;
import org.wahtod.wififixer.utility.StringUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class KnownNetworksFragment extends Fragment {
    public static final String ATTWIFI = "attwifi";
    private static final int SCAN_MESSAGE = 31337;
    private static final int REFRESH_MESSAGE = 2944;
    private static final int SCAN_DELAY = 15000;
    private static final String NETWORKS_KEY = "NETWORKS_KEY";
    private static final String SSID_KEY = "SSID";
    private static WeakReference<KnownNetworksFragment> self;
    private static NetworkListAdapter adapter;
    private static List<String> knownnetworks;
    private static List<String> known_in_range;
    private static ListView mListView;
    private static Handler scanhandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            if (self.get().getActivity() == null)
                return;

            switch (message.what) {

                case SCAN_MESSAGE:
                /*
                 * If wifi is on, scan. If not, make sure no networks in range
				 */
                    if (AsyncWifiManager.getWifiManager(self.get().getActivity())
                            .isWifiEnabled())
                        AsyncWifiManager.get(self.get().getActivity())
                                .startScan();
                    else {
                        if (known_in_range != null && known_in_range.size() >= 1) {
                            known_in_range.clear();
                            if (adapter != null)
                                adapter.notifyDataSetChanged();
                        }
                    }
                    scanhandler.sendEmptyMessageDelayed(SCAN_MESSAGE, SCAN_DELAY);
                    break;

                case REFRESH_MESSAGE:
                    refreshNetworkAdapter(message.getData().getStringArrayList(
                            NETWORKS_KEY));
                    break;

            }
        }
    };
    protected Object mActionMode;
    protected String mSSID;
    DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            switch (which) {
                case DialogInterface.BUTTON_POSITIVE:
                    removeNetwork(PrefUtil.getNid(getContext(), mSSID));
                    break;

                case DialogInterface.BUTTON_NEGATIVE:
                    // Ok, do nothing
                    break;
            }
        }
    };
    private OnFragmentPauseRequestListener mFragmentPauseRequestListener;
    public Callback mActionModeCallback = new Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (!isWifiOn(getContext()))
                return false;

            mFragmentPauseRequestListener.onFragmentPauseRequest(false);

            MenuInflater inflater = mode.getMenuInflater();
            mode.setTitle(mSSID);
            int n = PrefUtil.getNid(getContext(), mSSID);
            if (getStatusFromSSID(getContext(), mSSID))
                inflater.inflate(R.menu.enable, menu);
            else
                inflater.inflate(R.menu.disable, menu);

            if (known_in_range.contains(mSSID))
                inflater.inflate(R.menu.connect, menu);

            if (PrefUtil.readManagedState(getContext(), n))
                inflater.inflate(R.menu.managed, menu);
            else
                inflater.inflate(R.menu.nonmanaged, menu);

            inflater.inflate(R.menu.remove, menu);
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            mFragmentPauseRequestListener.onFragmentPauseRequest(true);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int n = PrefUtil.getNid(getContext(), mSSID);
            int i = item.getItemId();
            switch (i) {
                case R.id.menu_enable:
                    PrefUtil.setNetworkState(getContext(), n, false);
                    PrefUtil.writeNetworkState(getContext(), n, false);
                    adapter.notifyDataSetChanged();
                    break;

                case R.id.menu_disable:
                    PrefUtil.setNetworkState(getContext(), n, true);
                    PrefUtil.writeNetworkState(getContext(), n, true);
                    adapter.notifyDataSetChanged();
                    break;

                case R.id.menu_managed:
                    PrefUtil.writeManagedState(getContext(), n, false);
                    adapter.notifyDataSetChanged();
                    break;

                case R.id.menu_nonmanaged:
                    PrefUtil.writeManagedState(getContext(), n, true);
                    adapter.notifyDataSetChanged();
                    break;

                case R.id.menu_connect:
                    if (!getStatusFromSSID(getContext(), mSSID)) {
                        Intent intent = new Intent(WFMonitor.CONNECTINTENT);
                        intent.putExtra(WFMonitor.NETWORKNAME,
                                PrefUtil.getStringfromNetwork(getContext(), n));
                        BroadcastHelper.sendBroadcast(getContext(), intent, true);
                    } else {
                        AsyncWifiManager.get(getContext()).enableNetwork(n, true);
                    }
                    break;

                case R.id.menu_remove:
                    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                    builder.setMessage("Remove network:" + mSSID)
                            .setPositiveButton("Yes", dialogClickListener)
                            .setNegativeButton("No", dialogClickListener).show();
                    break;
            }
            mode.finish();
            return true;
        }
    };
    private OnItemLongClickListener il = new OnItemLongClickListener() {

        @SuppressLint("NewApi")
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View v, int p,
                                       long id) {

            TextView ssid = (TextView) v.findViewById(R.id.ssid);
            mSSID = ssid.getText().toString();
            if (mActionMode != null || (PrefUtil.readBoolean(getActivity(), PrefConstants.Pref.ATT_BLACKLIST.key()))
                    && mSSID.equals(ATTWIFI)) {
                return false;
            }
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            mActionMode = activity.startSupportActionMode(mActionModeCallback);
            v.setSelected(true);
            return true;

        }
    };
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            /*
             * we know this is going to be a scan result notification
			 */
            Message msg = Message.obtain();
            msg.what = REFRESH_MESSAGE;
            Bundle data = new Bundle();
            data.putStringArrayList(NETWORKS_KEY, getKnownAPArray(context));
            msg.setData(data);
            scanhandler.sendMessage(msg);
        }
    };

    public static KnownNetworksFragment newInstance(int num) {
        KnownNetworksFragment f = new KnownNetworksFragment();
        // Supply num input as an argument.
        Bundle args = new Bundle();
        args.putInt("num", num);
        f.setArguments(args);
        return f;
    }

    public static List<String> getNetworks(Context context) {
        WifiManager wm = AsyncWifiManager.getWifiManager(context);
        List<WifiConfiguration> wifiConfigs = wm.getConfiguredNetworks();
        if (wifiConfigs == null || wifiConfigs.isEmpty())
            return new ArrayList<>();

        List<String> networks = new ArrayList<String>();
        for (WifiConfiguration wfResult : wifiConfigs) {
            /*
             * Make sure there's a 1:1 correlation between
			 * getConfiguredNetworks() and the array
			 */
            if (wfResult.SSID != null && wfResult.SSID.length() > 0)
                networks.add(StringUtil.removeQuotes(wfResult.SSID));
            else
                networks.add(context.getString(R.string.null_ssid));
        }
        return networks;
    }

    private static void refreshNetworkAdapter(ArrayList<String> networks) {
        /*
         * Don't refresh if knownnetworks is empty (wifi is off)
		 */
        knownnetworks = getNetworks(self.get().getActivity());
        if (knownnetworks.size() > 0) {
            known_in_range = networks;
            if (adapter == null) {
                adapter = self.get().new NetworkListAdapter(knownnetworks);
                mListView.setAdapter(adapter);
            } else {
                refreshArray();
                adapter.notifyDataSetChanged();
            }
        }
    }

    private static boolean isWifiOn(Context ctxt) {
        return AsyncWifiManager.getWifiManager(ctxt).isWifiEnabled();
    }

    private static void refreshArray() {
        if (knownnetworks.equals(adapter.ssidArray))
            return;

        ArrayList<String> remove = new ArrayList<String>();

        for (String ssid : knownnetworks) {
            if (!adapter.ssidArray.contains(ssid))
                adapter.ssidArray.add(ssid);
        }

        for (String ssid : adapter.ssidArray) {
            if (!knownnetworks.contains(ssid))
                remove.add(ssid);
        }

        for (String ssid : remove) {
            adapter.ssidArray.remove(ssid);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        /*
         * Preserve ActionMode state
         */
        if (mActionMode != null) {
            outState.putString(SSID_KEY, mSSID);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.containsKey(SSID_KEY)) {
            mSSID = savedInstanceState.getString(SSID_KEY);
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            mActionMode = activity.startSupportActionMode(mActionModeCallback);
            for (int c = 1; c < adapter.getCount(); c++) {
                if (adapter.getItem(c).equals(mSSID)) {
                    mListView.setSelection(c);
                }
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        self = new WeakReference<KnownNetworksFragment>(this);
        super.onCreate(savedInstanceState);
    }

    public boolean getStatusFromSSID(Context context, String ssid) {
        WifiConfiguration net = PrefUtil.getNetworkByNID(context, PrefUtil.getNid(context, ssid));
        if (net == null)
            return false;
        else {
            boolean enabled = net.status == WifiConfiguration.Status.DISABLED;
            return enabled;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.knownnetworks, null);
        mListView = (ListView) v.findViewById(R.id.knownlist);
        adapter = self.get().new NetworkListAdapter(knownnetworks);
        mListView.setAdapter(adapter);
        mListView.setOnItemLongClickListener(il);
        return v;
    }

    @Override
    public void onAttach(Activity activity) {
        /*
         * Grab and set up ListView
		 */
        knownnetworks = getNetworks(getContext());
        known_in_range = new ArrayList<>();
        mFragmentPauseRequestListener = (OnFragmentPauseRequestListener) activity;
        super.onAttach(activity);
    }

    @SuppressLint("NewApi")
    @Override
    public void onPause() {
        unregisterReceiver();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceiver();
    }

    private ArrayList<String> getKnownAPArray(Context context) {

        WifiManager wm = AsyncWifiManager.getWifiManager(context);

        List<ScanResult> scanResults = wm.getScanResults();
        /*
         * Catch null if scan results fires after wifi disabled or while wifi is
		 * in intermediate state
		 */
        if (scanResults == null) {
            return null;
        }
        /*
         * Iterate the known networks over the scan results, adding found known
		 * networks.
		 */
        ArrayList<String> known_in_range = new ArrayList<String>();
        for (ScanResult sResult : scanResults) {
            /*
             * Add known networks in range
			 */

            if (knownnetworks.contains(sResult.SSID)) {
                /*
                 * Add result to known_in_range
				 */
                known_in_range.add(sResult.SSID);
            }
        }

        return known_in_range;
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        BroadcastHelper.registerReceiver(getContext(), receiver, filter, false);
        scanhandler.sendEmptyMessage(SCAN_MESSAGE);
    }

    private void unregisterReceiver() {
        BroadcastHelper.unregisterReceiver(getContext(), receiver);
        scanhandler.removeMessages(SCAN_MESSAGE);
        scanhandler.removeMessages(REFRESH_MESSAGE);
    }

    private void removeNetwork(int network) {
        NotifUtil.showToast(getContext(),
                getContext().getString(R.string.removing_network) + mSSID);
        AsyncWifiManager.getWifiManager(getActivity()).removeNetwork(network);
        AsyncWifiManager.getWifiManager(getActivity()).saveConfiguration();
        scanhandler.sendEmptyMessage(SCAN_MESSAGE);
    }

    public interface OnFragmentPauseRequestListener {
        void onFragmentPauseRequest(boolean state);
    }

    /*
     * custom adapter for Network List ListView
     */
    private class NetworkListAdapter extends BaseAdapter {
        private List<String> ssidArray;
        private LayoutInflater inflater;

        public NetworkListAdapter(List<String> knownnetworks) {
            inflater = (LayoutInflater) getContext().getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            ssidArray = knownnetworks;
        }

        /*
         * Disable ATTWIFI when it's disabled.
         */
        @Override
        public boolean isEnabled(int position) {
            String item = ssidArray.get(position);
            if (item != null) {
                return !blackListCheck(item);
            }
            return super.isEnabled(position);
        }

        public int getCount() {
            return ssidArray.size();
        }

        public Object getItem(int position) {
            return ssidArray.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater
                        .inflate(R.layout.known_list_layout, null);
                holder = new ViewHolder();
                holder.text = (TextView) convertView.findViewById(R.id.ssid);
                holder.icon = (ImageView) convertView
                        .findViewById(R.id.NETWORK_ICON);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            if (ssidArray.get(position) != null) {
                if (known_in_range.contains(ssidArray.get(position)))
                    holder.text.setTextColor(Color.GREEN);
                else
                    holder.text.setTextColor(Color.WHITE);
                /*
                 * Set SSID text and color
				 */
                holder.text.setText(ssidArray.get(position));
                if (PrefUtil.readManagedState(getContext(), position))
                    holder.text.setTextColor(Color.BLACK);
                else {
                    if (!getStatusFromSSID(getContext(), ssidArray.get(position)))
                        holder.icon.setColorFilter(Color.WHITE,
                                PorterDuff.Mode.SRC_ATOP);
                    else
                        holder.icon.setColorFilter(Color.BLACK,
                                PorterDuff.Mode.SRC_ATOP);
                    if (blackListCheck(holder.text.getText().toString())) {
                        holder.text.setTextColor(Color.RED);
                    }
                }
            }
            return convertView;
        }

        private boolean blackListCheck(String item) {
            if (!PrefUtil.readBoolean(getContext(), PrefConstants.Pref.ATT_BLACKLIST.key()))
                return false;
            else
                return (item.equals(ATTWIFI));
        }

        private class ViewHolder {
            TextView text;
            ImageView icon;
        }
    }
}

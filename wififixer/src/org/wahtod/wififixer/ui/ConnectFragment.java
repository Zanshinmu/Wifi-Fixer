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

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.wahtod.wififixer.R;
import org.wahtod.wififixer.WFMonitor;
import org.wahtod.wififixer.utility.AsyncWifiManager;
import org.wahtod.wififixer.utility.BroadcastHelper;
import org.wahtod.wififixer.utility.NotifUtil;
import org.wahtod.wififixer.utility.StringUtil;
import org.wahtod.wififixer.utility.WFScanResult;

import java.lang.reflect.Field;

public class ConnectFragment extends Fragment implements OnClickListener {
    public static final String TAG = "SFFJSHFTWFW";
    protected static final int CANCEL = 1;
    private static final String PROXY_CLASS = "android.net.wifi.WifiConfiguration$ProxySettings";
    private static final String BUGGED = "Proxy";
    private static final String DHCP_CONSTANT = "DHCP";
    private static final String NONE_CONSTANT = "NONE";
    private static final String IPASSIGNMENT_CLASS = "android.net.wifi.WifiConfiguration$IpAssignment";
    private static final String IP_ASSIGNMENT = "ipAssignment";
    private static final String PROXY_SETTINGS = "proxySettings";
    private static final String WPA = "WPA";
    private static final String WEP = "WEP";
    private static final String NETWORK_KEY = "WFSCANRESULT";
    private WFScanResult mNetwork;
    private PasswordHolder mPasswordHolder;

    /*
     * Reflection magic ahead
     */
    private static WifiConfiguration addHiddenFields(WifiConfiguration w) {
        try {
            Field f = w.getClass().getField(IP_ASSIGNMENT);
            Field f2 = w.getClass().getField(PROXY_SETTINGS);
            Class<?> ipc = Class.forName(IPASSIGNMENT_CLASS);
            Class<?> proxy = Class.forName(PROXY_CLASS);
            Field dhcp = ipc.getField(DHCP_CONSTANT);
            Field none = proxy.getField(NONE_CONSTANT);
            Object v = dhcp.get(null);
            Object v2 = none.get(null);
            f.set(w, v);
            f2.set(w, v2);
        } catch (Exception e) {
            /*
             * Log
			 */
            e.printStackTrace();
        }
        return w;
    }

    public static ConnectFragment newInstance(WFScanResult n) {
        ConnectFragment f = new ConnectFragment();
        f.mNetwork = n;
        return f;
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connect_fragment, null);
    }

    private int addNetwork(String password) {
        WifiConfiguration wf = getKeyAppropriateConfig(password);
        AsyncWifiManager wm = AsyncWifiManager.get(getActivity());
        int n = wm.addNetwork(wf);
        if (n != -1) {
            wm.enableNetwork(n, false);
            wm.saveConfiguration();
        }
        return n;
    }

    private WifiConfiguration getKeyAppropriateConfig(String password) {
        WifiConfiguration wf = new WifiConfiguration();
        if (wf.toString().contains(BUGGED)) {
            /*
             * Add hidden fields on bugged Android 3.1+ configs
			 */
            wf = addHiddenFields(wf);
        }
        wf.SSID = StringUtil.addQuotes(mNetwork.SSID);
        if (mNetwork.capabilities.length() == 0) {
            wf.BSSID = mNetwork.BSSID;
            wf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wf.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            return wf;
        }

        wf.status = WifiConfiguration.Status.ENABLED;
        wf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        if (mNetwork.capabilities.contains(WPA)) {
            wf.preSharedKey = StringUtil.addQuotes(password);
            wf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            wf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            wf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            wf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        } else if (mNetwork.capabilities.contains(WEP)) {
            wf.wepKeys[0] = StringUtil.addQuotes(password);
            wf.wepTxKeyIndex = 0;
            wf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        }

        return wf;
    }

    @Override
    public void onDestroyView() {
        View e = getView().findViewById(R.id.password);
        closeInputMethod(e);
        super.onDestroyView();
    }

    private void connectNetwork() {
        Intent intent = new Intent(WFMonitor.CONNECTINTENT);
        intent.putExtra(WFMonitor.NETWORKNAME, mNetwork.SSID);
        BroadcastHelper.sendBroadcast(getActivity(), intent, true);
    }

    private void notifyConnecting() {
        NotifUtil.showToast(getActivity(),
                getActivity().getString(R.string.connecting_to_network)
                        + mNetwork.SSID
        );
    }

    public void onClick(View v) {
        String password = null;
        try {
            password = String.valueOf(((EditText) mPasswordHolder.password).getText());
        } catch (NullPointerException e1) {
        }
        if (password == null || password.length() == 0) {
            if (mNetwork.capabilities.length() == 0) {
                addNetwork(null);
                notifyConnecting();
                connectNetwork();
            } else if (KnownNetworksFragment.getNetworks(getActivity())
                    .contains(mNetwork.SSID))
                notifyConnecting();
            connectNetwork();
        } else
            addNetwork(password);

        closeInputMethod(mPasswordHolder.password);
        FragmentTransaction f = this.getParentFragment()
                .getChildFragmentManager().beginTransaction();
        f.remove(this);
        f.commit();
    }

    private void closeInputMethod(View e) {
        InputMethodManager imm = (InputMethodManager) getActivity()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState == null) {
            /*
             * Do nothing
             */
        } else {
            /*
             * Restore Network
             */
            mNetwork = WFScanResult.fromBundle(savedInstanceState.getBundle(NETWORK_KEY));
        }
        Button b = (Button) getActivity().findViewById(R.id.connect);
        mPasswordHolder = new PasswordHolder();

        mPasswordHolder.password = getActivity().findViewById(R.id.password);
        TextView summary = (TextView) getActivity().findViewById(R.id.password_summary);
        if (StringUtil.getCapabilitiesString(mNetwork.capabilities).equals(
                StringUtil.OPEN)
                || KnownNetworksFragment.getNetworks(getActivity()).contains(
                mNetwork.SSID)) {
            mPasswordHolder.password.setVisibility(View.INVISIBLE);
            b.setText(getString(R.string.connect));
            summary.setText(R.string.button_connect);
        }
        b.setOnClickListener(this);
        TextView s = (TextView) getActivity().findViewById(R.id.network_name);
        if (mNetwork.SSID.length() > 0)
            s.setText(mNetwork.SSID);
        else
            s.setText(mNetwork.BSSID);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mNetwork != null)
            outState.putBundle(NETWORK_KEY, mNetwork.toBundle());
    }

    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        /*
         * Animate the view
		 */
        if (enter) {
            ExpandViewAnimation ev = new ExpandViewAnimation(getView()
                    .findViewById(R.id.connect_fragment_layout), ExpandViewAnimation.DURATION);
            return ev;
        } else
            return null;
    }

    private static class PasswordHolder {
        View password;
    }
}

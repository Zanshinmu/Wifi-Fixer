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

import android.net.wifi.ScanResult;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

public class WFScanResult {
    public static final String SSID_BUNDLE_KEY = "SSID";
    public static final String BSSID_BUNDLE_KEY = "BSSID";
    public static final String CAPABILITIES_BUNDLE_KEY = "CAPABILITIES";
    public static final String FREQUENCY_BUNDLE_KEY = "FREQUENCY";
    public static final String LEVEL_BUNDLE_KEY = "LEVEL";
    public static final String BUNDLE_KEY = "WFSCANRESULT";
    public String SSID;
    public String BSSID;
    public String capabilities;
    public int level;
    public int frequency;

    public WFScanResult() {
        SSID = "";
        capabilities = "";
        BSSID = "";
    }

    public WFScanResult(ScanResult result) {
        if (result.SSID == null)
            SSID = "";
        else
            SSID = result.SSID;
        if (result.capabilities == null)
            capabilities = "";
        else
            capabilities = result.capabilities;

        if (result.BSSID == null)
            BSSID = "";
        else
            BSSID = result.BSSID;

        level = result.level;
        frequency = result.frequency;
    }

    public static WFScanResult fromBundle(Bundle bundle) {
        WFScanResult out = new WFScanResult();
        if (bundle != null && bundle.containsKey(BUNDLE_KEY)) {
            Bundle fields = bundle.getBundle(BUNDLE_KEY);
            out.SSID = fields.getString(SSID_BUNDLE_KEY);
            out.BSSID = fields.getString(BSSID_BUNDLE_KEY);
            out.capabilities = fields.getString(CAPABILITIES_BUNDLE_KEY);
            out.level = fields.getInt(LEVEL_BUNDLE_KEY);
            out.frequency = fields.getInt(FREQUENCY_BUNDLE_KEY);
        }
        return out;
    }

    public static List<WFScanResult> fromScanResultArray(
            List<ScanResult> results) {
        if (results == null)
            return null;

        List<WFScanResult> out = new ArrayList<WFScanResult>();
        for (ScanResult result : results) {
            if (result == null) {
                out.add(new WFScanResult());
            } else
                out.add(new WFScanResult(result));
        }
        return out;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = hash * 31 + (BSSID == null ? 0 : BSSID.hashCode());
        hash = hash * 31 + (SSID == null ? 0 : SSID.hashCode());
        hash = hash * 31 + (capabilities == null ? 0 : capabilities.hashCode());
        return hash;

    }

    @Override
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (this.getClass() != other.getClass())
            return false;
        WFScanResult o = (WFScanResult) other;
        return o.BSSID.equals(this.BSSID);

    }

    public Bundle toBundle() {
        Bundle wrapper = new Bundle();
        Bundle bundle = new Bundle();
        bundle.putString(SSID_BUNDLE_KEY, SSID);
        bundle.putString(BSSID_BUNDLE_KEY, BSSID);
        bundle.putString(CAPABILITIES_BUNDLE_KEY, capabilities);
        bundle.putInt(LEVEL_BUNDLE_KEY, level);
        bundle.putInt(FREQUENCY_BUNDLE_KEY, frequency);
        wrapper.putBundle(BUNDLE_KEY, bundle);
        return wrapper;
    }
}
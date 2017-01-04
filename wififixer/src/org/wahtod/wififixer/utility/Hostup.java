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

import android.content.Context;
import android.net.DhcpInfo;
import android.os.Build;
import android.text.format.Formatter;

import org.wahtod.wififixer.R;
import org.wahtod.wififixer.prefs.PrefConstants;
import org.wahtod.wififixer.prefs.PrefUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.concurrent.RejectedExecutionException;

public class Hostup {
    /*
     * getHostUp method: Performs network check calibrated with SetFailover
     */
    // Target for header check
    public static final String FAILOVER = "www.google.com";
    public static final String FAILOVER2 = "www.baidu.com";
    public static final int HTTP_TYPE = 1;
    public static final int ICMP_TYPE = 0;
    protected static final String INET_LOOPBACK = "127.0.0.1";
    protected static final String INET_INVALID = "0.0.0.0";
    protected static final int TIMEOUT_EXTRA = 2000;
    private static final int HTTP_TIMEOUT = 4000;
    private static final String REJECTED_EXECUTION = "Rejected Execution";
    protected static int checktype = -1;
    protected static volatile String target;
    protected static volatile HostMessage response;
    protected static volatile URI headURI;
    protected static volatile int reachable;
    protected static volatile int mCurrentSession;
    protected static volatile boolean mFinished;
    private static Hostup _hostup;
    private static ThreadHandler _nethandler;
    private static ThreadHandler httpHandler;
    private static ThreadHandler icmpHandler;
    protected volatile WeakReference<Context> mContext;
    protected volatile Thread masterThread;
    private volatile String accesspointIP;
    private HostupResponse mClient;
    private Hostup(Context c) {
        mCurrentSession = 0;
        mContext = new WeakReference<Context>(c.getApplicationContext());
        disableConnectionReuse();
    }

    public static Hostup newInstance(Context context) {
        if (_hostup == null)
            _hostup = new Hostup(context.getApplicationContext());
        else {
            httpHandler = new ThreadHandler(context.getString(R.string.httpcheckthread));
            icmpHandler = new ThreadHandler(context.getString(R.string.icmpcheckthread));
            _nethandler = new ThreadHandler(
                    context.getString(R.string.netcheckthread));
            _hostup.masterThread = _nethandler.getLooper().getThread();
        }
        return _hostup;
    }

    public void registerClient(HostupResponse client) {
        mClient = client;
    }

    private void submitRunnable(Runnable r) {
        if (_nethandler != null)
            _nethandler.get().post(r);
    }

    @SuppressWarnings("deprecation")
    public void icmpCache(Context context) {
        /*
         * Caches DHCP gateway IP for ICMP check
		 */
        try {
            DhcpInfo info = AsyncWifiManager.getWifiManager(context).getDhcpInfo();
            accesspointIP = (Formatter.formatIpAddress(info.gateway));
            LogUtil.log(context, new StringBuilder(context.getString(R.string.cached_ip))
                    .append(accesspointIP).toString());
        } catch (NullPointerException e) {
            if (PrefUtil.getFlag(PrefConstants.Pref.DEBUG))
                LogUtil.log(context, "Invalid Gateway on ICMP cache");
        }
    }

    protected synchronized void complete(HostMessage h, int session) {
        if (session == mCurrentSession) {
            mFinished = true;
            response = h;
            if (masterThread != null)
                masterThread.interrupt();
        }
    }

    public void getHostup(int timeout) {
       /*
        * Track Sessions to find ordering problem in deep sleep
        */
        mCurrentSession++;
        response = new HostMessage();

        target = accesspointIP;

        reachable = timeout + TIMEOUT_EXTRA;

        // Check for bugged checktype
        if (checktype == -1){
            LogUtil.log(mContext.get(),R.string.bugged_checktype);
            setFailover();
        }

        /*
         * Submit hostCheck, response is via HostupResponse interface
         */
        if (checktype == ICMP_TYPE
                & !PrefUtil.getFlag(PrefConstants.Pref.FORCE_HTTP))
            icmpHandler.get().post(new GetICMP(mCurrentSession));
        else
            httpHandler.get().post(new GetHeaders(mCurrentSession));

        submitRunnable(new HostCheck(target));
    }

    public void setFailover() {
        submitRunnable(new SetFailover());
    }

    /*
     * Performs ICMP ping/echo and returns boolean success or failure
     */
    private HostMessage icmpHostup(Context context) {
        HostMessage out = new HostMessage();
        out.timer.start();

        try {
            if (InetAddress.getByName(target).isReachable(reachable))
                out.state = true;

        } catch (UnknownHostException e) {

        } catch (IOException e) {

        }

        if (out.state)
            out.status = target + context.getString(R.string.icmp_ok);
        else
            out.status = target + context.getString(R.string.icmp_fail);
        out.timer.stop();
        return out;
    }

    /*
     * Performs HTTP HEAD request and returns boolean success or failure
     */
    private HostMessage getHttpHeaders(Context context) {
        /*
         * get URI
		 */
        HostMessage out = new HostMessage();
        out.timer.start();
        try {
            headURI = new URI(context.getString(R.string.http) + target);
        } catch (URISyntaxException e1) {
            e1.printStackTrace();
        }
        int code = -1;
        boolean state = false;
        StringBuilder info = new StringBuilder();
        /*
         * Get response
		 */
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) headURI.toURL().openConnection();
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setConnectTimeout(HTTP_TIMEOUT);
            code = con.getResponseCode();
            con.disconnect();

        } catch (MalformedURLException e) {
            info.append(context.getString(R.string.malformed_url_exception));
        } catch (IOException e) {
            info.append(context.getString(R.string.i_o_exception));
        } catch (NullPointerException e) {
            info.append(context.getString(R.string.npe));
        }

        if (code == HttpURLConnection.HTTP_OK
                || code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN
                || code == HttpURLConnection.HTTP_NOT_FOUND
                || code == HttpURLConnection.HTTP_PROXY_AUTH)

            state = true;
        info.append(target);
        if (state)
            info.append(context.getString(R.string.http_ok));
        else
            info.append(context.getString(R.string.http_fail));
        out.timer.stop();
        out.status = info.toString();
        out.state = state;
        return out;
    }

    @SuppressWarnings("deprecation")
    private void disableConnectionReuse() {
        // Work around pre-Froyo bugs in HTTP connection reuse.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    public void finish() {
        icmpHandler.get().getLooper().quit();
        httpHandler.get().getLooper().quit();
        masterThread = null;
        _nethandler.get().getLooper().quit();
        icmpHandler = null;
        httpHandler = null;
        _nethandler = null;
    }

    public interface HostupResponse {
        void onHostupResponse(HostMessage hostMessage);
    }

    private class SetFailover implements Runnable {
        @Override
        public void run() {
            Context context = mContext.get();
            icmpCache(context);
            target = accesspointIP;
            HostMessage i = icmpHostup(context);
            HostMessage h = getHttpHeaders(context);
            if (!h.state && !i.state) {
                accesspointIP = Hostup.FAILOVER;
                i = icmpHostup(context);
                h = getHttpHeaders(context);
                if(!h.state && !i.state )
                    accesspointIP = Hostup.FAILOVER2;
            }
            String type;
            if (i.state) {
                checktype = ICMP_TYPE;
                type = "ICMP";
            }
            else {
                checktype = HTTP_TYPE;
                type = "HTTP";
            }

            LogUtil.log(context, context.getString(R.string.failover) + accesspointIP);
            LogUtil.log(context, context.getString(R.string.checktype) + type);

        }
    }

    /*
         * http header check thread
         */
    private class GetHeaders implements Runnable {
        int session;

        public GetHeaders(int id) {
            session = id;
        }

        @Override
        public void run() {
            HostMessage h = getHttpHeaders(mContext.get());
            if (!mFinished)
                complete(h, session);
        }
    }

    /*
         * icmp check thread
         */
    private class HostCheck implements Runnable {
        String router;

        public HostCheck(String r) {
            router = r;
        }

        @Override
        public void run() {
        /*
         * Start Check Threads
		 */

            mFinished = false;
            try {
                Thread.sleep(reachable);
            } catch (InterruptedException e) {
            /*
             * We have a response
			 */
                response.status += (String.valueOf(response.timer.getElapsed()));
                response.status += (mContext.get().getString(R.string.ms));
            } catch (RejectedExecutionException e) {
                response.status += (REJECTED_EXECUTION);
            }
        /*
         * End session for critical timeouts
         */
            mFinished = true;
            if (response.status == null)
                response.status = target + ":" + mContext.get().getString(R.string.critical_timeout);
            mClient.onHostupResponse(response);
        }
    }

    /*
         * icmp check thread
         */
    private class GetICMP implements Runnable {
        int session;

        public GetICMP(int id) {
            session = id;
        }

        @Override
        public void run() {
            HostMessage h = icmpHostup(mContext.get());
            if (!mFinished)
                complete(h, session);
        }
    }
}

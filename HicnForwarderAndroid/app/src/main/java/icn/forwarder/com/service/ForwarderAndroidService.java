/*
 * Copyright (c) 2019 Cisco and/or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package icn.forwarder.com.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Inet4Address;

import icn.forwarder.com.forwarderandroid.ForwarderAndroidActivity;
import icn.forwarder.com.supportlibrary.Forwarder;
import icn.forwarder.com.supportlibrary.NetworkServiceHelper;
import icn.forwarder.com.supportlibrary.SocketBinder;
import icn.forwarder.com.utility.Constants;
import icn.forwarder.com.utility.ResourcesEnumerator;

public class ForwarderAndroidService extends Service {
    private final static String TAG = "ForwarderService";

    private static Thread sForwarderThread = null;
    private NetworkServiceHelper mNetService = new NetworkServiceHelper();
    private SocketBinder mSocketBinder = new SocketBinder();

    public ForwarderAndroidService() {
    }

    private String path;
    private int capacity;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Forwarder.getInstance().isRunning()) {
            mNetService.init(this, mSocketBinder);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_START_FORWARDER), 1000); // wait for mobile network is up
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Forwarder forwarder = Forwarder.getInstance();
        Log.d(TAG, "Destroying Forwarder");
        if (forwarder.isRunning()) {
            forwarder.stop();
            stopForeground(true);
        }
        mNetService.clear();
        super.onDestroy();
    }

    protected Runnable mForwarderRunner = new Runnable() {

        //private String path;
        @Override
        public void run() {
            Forwarder forwarder = Forwarder.getInstance();
            forwarder.setSocketBinder(mSocketBinder);
            forwarder.start(path, capacity);
        }
    };

    private static final int EVENT_START_FORWARDER = 1;

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_START_FORWARDER:
                    if (saveConfiguration())
                        startForwarder();
                    break;
            }
            super.handleMessage(msg);
        }
    };

    private boolean saveConfiguration() {
        Forwarder forwarder = Forwarder.getInstance();
        if (!forwarder.isRunning()) {
            Log.d(TAG, "Starting Forwarder");
            SharedPreferences sharedPreferences = getSharedPreferences(Constants.FORWARDER_PREFERENCES, MODE_PRIVATE);
            String configuration = getConfiguration(sharedPreferences);

            int capacity = Integer.parseInt(sharedPreferences.getString(ResourcesEnumerator.CAPACITY.key(), Constants.DEFAULT_CAPACITY));
            try {
                String configurationDir = getPackageManager().getPackageInfo(getPackageName(), 0).applicationInfo.dataDir +
                        File.separator + Constants.CONFIGURATION_PATH;
                File folder = new File(configurationDir);
                if (!folder.exists()) {
                    folder.mkdirs();
                }

                String path = configurationDir + File.separator + Constants.CONFIGURATION_FILE_NAME;
                writeToFile(configuration, path);

                this.path = path;
                this.capacity = capacity;
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Error Package name not found ", e);
            }
        } else {
            Log.d(TAG, "Forwarder already running.");
        }
        return false;
    }

    private String getAddress(Network network) {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null)
            return "";

        for (LinkAddress addr : lp.getLinkAddresses()) {
            if (addr.getAddress() instanceof Inet4Address) {
                return addr.toString().split("/")[0];
            }
        }
        return "";
    }

    private String getConfiguration(SharedPreferences sharedPreferences) {
        String allConfigurations = "";

        int connId = 0;
        for (String ifname : mSocketBinder.getAllNetworkNames()) {
            Log.d(TAG, "ifname=" + ifname);
            String configuration = sharedPreferences.getString(ResourcesEnumerator.CONFIGURATION.key(), Constants.DEFAULT_CONFIGURATION);
            //String sourceIp = sharedPreferences.getString(ResourcesEnumerator.SOURCE_IP.key(), null);
            String sourceIp = getAddress(mSocketBinder.getNetwork(ifname));
            String sourcePort = sharedPreferences.getString(ResourcesEnumerator.SOURCE_PORT.key(), null);
            String nextHopIp = sharedPreferences.getString(ResourcesEnumerator.NEXT_HOP_IP.key(), null);
            String nextHopPort = sharedPreferences.getString(ResourcesEnumerator.NEXT_HOP_PORT.key(), null);
            String prefix = sharedPreferences.getString(ResourcesEnumerator.PREFIX.key(), null);
            String netmask = sharedPreferences.getString(ResourcesEnumerator.NETMASK.key(), null);
            configuration = configuration.replace(Constants.SOURCE_IP, sourceIp);
            configuration = configuration.replace(Constants.INTERFACE_NAME, ifname);
            configuration = configuration.replace(Constants.CONNECTION_NAME, "conn" + connId++);
            configuration = configuration.replace(Constants.SOURCE_PORT, sourcePort);
            configuration = configuration.replace(Constants.NEXT_HOP_IP, nextHopIp);
            configuration = configuration.replace(Constants.NEXT_HOP_PORT, nextHopPort);
            configuration = configuration.replace(Constants.PREFIX, prefix);
            configuration = configuration.replace(Constants.NETMASK, netmask);

            allConfigurations += configuration;
        }
        return allConfigurations;
    }

    private boolean writeToFile(String data, String path) {
        Log.v(TAG, path);
        Log.v(TAG, data);
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(path), "utf-8"))) {
            writer.write(data);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "File write failed: " + e.toString());
            return false;
        }
    }

    private void startForwarder() {
        String NOTIFICATION_CHANNEL_ID = "12345";
        Notification notification = null;
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder notificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);

            Intent notificationIntent = new Intent(this, ForwarderAndroidActivity.class);
            PendingIntent activity = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            notificationBuilder.setContentTitle("ForwarderAndroid").setContentText("ForwarderAndroid").setOngoing(true).setContentIntent(activity);
            notification = notificationBuilder.build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("ForwarderAndroid")
                    .setContentText("ForwarderAndroid")
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "ForwarderAndroid", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("ForwarderAndroid");
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);

        }

        startForeground(Constants.FOREGROUND_SERVICE, notification);

        Forwarder forwarder = Forwarder.getInstance();
        if (!forwarder.isRunning()) {
            sForwarderThread = new Thread(mForwarderRunner, "ForwarderRunner");
            sForwarderThread.start();
        }

        Log.i(TAG, "ForwarderAndroid started");
    }
}
/*
 * Copyright (C) 2012 The Exodus Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.exodus.updater.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.exodus.updater.R;
import com.exodus.updater.UpdateApplication;
import com.exodus.updater.UpdatesSettings;
import com.exodus.updater.misc.Constants;
import com.exodus.updater.misc.State;
import com.exodus.updater.misc.UpdateInfo;
import com.exodus.updater.receiver.DownloadReceiver;
import com.exodus.updater.utils.Utils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;

public class UpdateCheckService extends IntentService {
    private static final String TAG = "UpdateCheckService";

    // Set this to true if the update service should check for smaller, test updates
    // This is for internal testing only
    private static final boolean TESTING_DOWNLOAD = false;

    // request actions
    public static final String ACTION_CHECK = "com.exodus.exodusupdater.action.CHECK";
    public static final String ACTION_CANCEL_CHECK = "com.exodus.exodusupdater.action.CANCEL_CHECK";

    // broadcast actions
    public static final String ACTION_CHECK_FINISHED = "com.exodus.exodusupdater.action.UPDATE_CHECK_FINISHED";
    // extra for ACTION_CHECK_FINISHED: total amount of found updates
    public static final String EXTRA_UPDATE_COUNT = "update_count";
    // extra for ACTION_CHECK_FINISHED: amount of updates that are newer than what is installed
    public static final String EXTRA_REAL_UPDATE_COUNT = "real_update_count";
    // extra for ACTION_CHECK_FINISHED: amount of updates that were found for the first time
    public static final String EXTRA_NEW_UPDATE_COUNT = "new_update_count";

    // max. number of updates listed in the expanded notification
    private static final int EXPANDED_NOTIF_UPDATE_COUNT = 4;

    //private HttpRequestExecutor mHttpExecutor;

    public UpdateCheckService() {
        super("UpdateCheckService");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (TextUtils.equals(intent.getAction(), ACTION_CANCEL_CHECK)) {

            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final Resources res = getResources();

        UpdateApplication app = (UpdateApplication) getApplicationContext();
        final boolean updaterIsForeground = app.isMainActivityActive();
	final boolean fromQuicksettings = intent.hasExtra("isFromQuicksettings");

        if (!Utils.isOnline(this)) {
            // Only check for updates if the device is actually connected to a network
            Log.i(TAG, "Could not check for updates. Not connected to the network.");
            if (!updaterIsForeground) {
                final Context mContext = getApplicationContext();
                final String cheese = mContext.getString(R.string.update_check_failed);
                Toast.makeText(mContext, cheese, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Set up a progressbar notification
        final int progressID = 1;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
	Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        if (fromQuicksettings) {
            Notification.Builder progress = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.cm_updater)
                    .setWhen(System.currentTimeMillis())
                    .setTicker(res.getString(R.string.checking_for_updates))
                    .setContentTitle(res.getString(R.string.checking_for_updates))
                    .setProgress(0, 0, true);
            // Trigger the progressbar notification
            nm.notify(progressID, progress.build());
         }

        // Start the update check
        Intent finishedIntent = new Intent(ACTION_CHECK_FINISHED);
        LinkedList<UpdateInfo> availableUpdates;
        try {
            availableUpdates = getAvailableUpdatesAndFillIntent(finishedIntent);
        } catch (IOException e) {
            Log.e(TAG, "Could not check for updates", e);
            availableUpdates = null;
            if (!updaterIsForeground) nm.cancel(progressID);
        }

        if (availableUpdates == null) {// || mHttpExecutor.isAborted()) {
            if (fromQuicksettings) nm.cancel(progressID);
            sendBroadcast(finishedIntent);
            return;
        }

        // Store the last update check time and ensure boot check completed is true
        Date d = new Date();
        PreferenceManager.getDefaultSharedPreferences(UpdateCheckService.this).edit()
                .putLong(Constants.LAST_UPDATE_CHECK_PREF, d.getTime())
                .putBoolean(Constants.BOOT_CHECK_COMPLETED, true)
                .apply();

        int realUpdateCount = finishedIntent.getIntExtra(EXTRA_REAL_UPDATE_COUNT, 0);

        // Write to log
        Log.i(TAG, "The update check successfully completed at " + d + " and found "
                + availableUpdates.size() + " updates ("
                + realUpdateCount + " newer than installed)");

        if (realUpdateCount == 0 && fromQuicksettings) {
            Intent i = new Intent(this, UpdatesSettings.class);
            i.putExtra(UpdatesSettings.EXTRA_UPDATE_LIST_UPDATED, true);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_ONE_SHOT);

	    // Get the notification ready
	    Notification.Builder builder = new Notification.Builder(this)
		    .setSmallIcon(R.drawable.cm_updater)
		    .setWhen(System.currentTimeMillis())
		    .setTicker(res.getString(R.string.no_updates_found))
		    .setContentTitle(res.getString(R.string.no_updates_found))
		    .setContentText(res.getString(R.string.no_updates_found_body))
		    .setContentIntent(contentIntent)
		    .setSound(soundUri)
		    .setAutoCancel(true);
	    // Trigger the notification
	    nm.cancel(progressID);
	    nm.notify(R.string.no_updates_found, builder.build());

	    sendBroadcast(finishedIntent);
	}

	if (realUpdateCount != 0 && !updaterIsForeground) {
	    Intent i = new Intent(this, UpdatesSettings.class);
	    i.putExtra(UpdatesSettings.EXTRA_UPDATE_LIST_UPDATED, true);
	    PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
		PendingIntent.FLAG_ONE_SHOT);

            String text = res.getQuantityString(R.plurals.not_new_updates_found_body,
                    realUpdateCount, realUpdateCount);

            // Get the notification ready
            Notification.Builder builder = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.cm_updater)
                    .setWhen(System.currentTimeMillis())
                    .setTicker(res.getString(R.string.not_new_updates_found_ticker))
                    .setContentTitle(res.getString(R.string.not_new_updates_found_title))
                    .setContentText(text)
                    .setContentIntent(contentIntent)
		    .setSound(soundUri)
                    .setAutoCancel(true);

            LinkedList<UpdateInfo> realUpdates = new LinkedList<UpdateInfo>();
            for (UpdateInfo ui : availableUpdates) {
                if (ui.isNewerThanInstalled()) {
                    realUpdates.add(ui);
                }
            }

            Collections.sort(realUpdates, new Comparator<UpdateInfo>() {
                @Override
                public int compare(UpdateInfo lhs, UpdateInfo rhs) {
                    /* sort by date descending */
                    long lhsDate = lhs.getDate();
                    long rhsDate = rhs.getDate();
                    if (lhsDate == rhsDate) {
                        return 0;
                    }
                    return lhsDate < rhsDate ? 1 : -1;
                }
            });

            Notification.InboxStyle inbox = new Notification.InboxStyle(builder)
                    .setBigContentTitle(text);
            int added = 0, count = realUpdates.size();

            for (UpdateInfo ui : realUpdates) {
                if (added < EXPANDED_NOTIF_UPDATE_COUNT) {
                    inbox.addLine(ui.getName());
                    added++;
                }
            }
            if (added != count) {
                inbox.setSummaryText(res.getQuantityString(R.plurals.not_additional_count,
                            count - added, count - added));
            }
            builder.setStyle(inbox);
            builder.setNumber(availableUpdates.size());

            if (count == 1) {
                i = new Intent(this, DownloadReceiver.class);
                i.setAction(DownloadReceiver.ACTION_START_DOWNLOAD);
                i.putExtra(DownloadReceiver.EXTRA_UPDATE_INFO, (Parcelable) realUpdates.getFirst());
                PendingIntent downloadIntent = PendingIntent.getBroadcast(this, 0, i,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);

                builder.addAction(R.drawable.ic_tab_download,
                        res.getString(R.string.not_action_download), downloadIntent);
            }

            // Trigger the notification
            if (fromQuicksettings) nm.cancel(progressID);
            nm.notify(R.string.not_new_updates_found_title, builder.build());
        }

        sendBroadcast(finishedIntent);
    }

    private void addRequestHeaders(HttpRequestBase request) {
        String userAgent = Utils.getUserAgentString(this);
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        request.addHeader("Cache-Control", "no-cache");
    }

    private LinkedList<UpdateInfo> getAvailableUpdatesAndFillIntent(Intent intent) throws IOException {
        // Get the type of update we should check for
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //TODO handle releases too!
        int updateType = 0 ; // prefs.getInt(Constants.UPDATE_TYPE_PREF, 0);

        LinkedList<UpdateInfo> lastUpdates = State.loadState(this);

        LinkedList<UpdateInfo> updates = getUpdateInfos(getString(R.string.conf_update_server_url)+Utils.getDeviceType()+"/", updateType);

        //updates.addAll(getUpdateInfos(getString(R.string.conf_nightly_server_url)+Utils.getDeviceType()+"/", updateType));

        int newUpdates = 0, realUpdates = 0;
        for (UpdateInfo ui : updates) {
            if (!lastUpdates.contains(ui)) {
                newUpdates++;
            }
            if (ui.isNewerThanInstalled()) {
                realUpdates++;
            }
        }
        Log.d(TAG, "Found: "+newUpdates+" NEW and "+realUpdates+" REAL updates");

        intent.putExtra(EXTRA_UPDATE_COUNT, updates.size());
        intent.putExtra(EXTRA_REAL_UPDATE_COUNT, realUpdates);
        intent.putExtra(EXTRA_NEW_UPDATE_COUNT, newUpdates);

        State.saveState(this, updates);

        return updates;
    }

    private LinkedList<UpdateInfo> getUpdateInfos(String url, int updateType) {
        Context mContext = getApplicationContext();
        boolean includeAll = true ; //updateType == Constants.UPDATE_TYPE_ALL_NIGHTLY;
            //|| updateType == Constants.UPDATE_TYPE_ALL_STABLE;
        Log.d(TAG, "Looking for updates at "+url+"exodus_update_list");
        LinkedList<String> versions = Utils.readMultilineFile(url+getString(R.string.conf_update_filename));
        LinkedList<UpdateInfo> infos = new LinkedList<UpdateInfo>();        
        for (String v : versions) {
            Log.d(TAG, "Fetching info for build "+v);
            UpdateInfo ui = getUpdateInfo(url, v,mContext);
            if (ui != null) {
                if (!includeAll && !ui.isNewerThanInstalled()) {
                     Log.d(TAG, "Build " + ui.getFileName() + " is older than the installed build");
                     continue;
                }
                infos.add(ui);
            } else {
                Log.e(TAG, "getUpdateInfo returned null UpdateInfo");
            }
        }
        return infos;
    }

    private UpdateInfo getUpdateInfo(String urlBase, String version,Context mContext) {
        String[] parts = version.split(";");
        //Log.v(TAG, "getting update info for: "+urlBase+version+"*");
        UpdateInfo ui = null;
        String Filename = parts[0].trim();
        String md5sum = parts[1].trim(); //Utils.readFile(urlBase+version+".zip.md5");
        //if (md5sum == null) md5sum = Utils.readFile(urlBase+version+".zip.md5sum");
        String utcStr = parts[2].trim(); //Utils.readFile(urlBase+version+".utc");
        String apiStr = parts[3].trim(); //Utils.readFile(urlBase+version+".api");
        if (md5sum != null && utcStr != null && apiStr != null) {
            Log.i(TAG, Filename+" INFO -- md5:"+md5sum+" utc:"+utcStr+" api:"+apiStr);
            try {
                long utc = Long.valueOf(utcStr).longValue();
                int api = Integer.valueOf(apiStr).intValue();
                ui = new UpdateInfo(Filename+".zip", utc, api, urlBase+Filename+".zip", md5sum, UpdateInfo.Type.NIGHTLY);
                if (!ui.getChangeLogFile(mContext).exists()) {
                    Utils.DownloadChangelog(ui, mContext);
                }
            } catch (Exception anyexception) {
                Log.e(TAG, "getUpdateInfo()", anyexception);
            }
        } else {
            if (md5sum == null) Log.w(TAG, version+": NO MD5");
            if (utcStr == null) Log.w(TAG, version+": NO DATE");
            if (apiStr == null) Log.w(TAG, version+": NO API LEVEL");
        }
        
        return ui;
    }

    /*private JSONObject buildUpdateRequest(int updateType) throws JSONException {
        JSONArray channels = new JSONArray();
        channels.put("stable");
        channels.put("snapshot");
        channels.put("RC");
        if (updateType == Constants.UPDATE_TYPE_NEW_NIGHTLY
                || updateType == Constants.UPDATE_TYPE_ALL_NIGHTLY) {
            channels.put("nightly");
        }

        JSONObject params = new JSONObject();
        params.put("device", TESTING_DOWNLOAD ? "cmtestdevice" : Utils.getDeviceType());
        params.put("channels", channels);

        JSONObject request = new JSONObject();
        request.put("method", "get_all_builds");
        request.put("params", params);

        return request;
    }

    private LinkedList<UpdateInfo> parseJSON(String jsonString, int updateType) {
        LinkedList<UpdateInfo> updates = new LinkedList<UpdateInfo>();
        try {
            JSONObject result = new JSONObject(jsonString);
            JSONArray updateList = result.getJSONArray("result");
            int length = updateList.length();

            Log.d(TAG, "Got update JSON data with " + length + " entries");

            for (int i = 0; i < length; i++) {
                if (mHttpExecutor.isAborted()) {
                    break;
                }
                if (updateList.isNull(i)) {
                    continue;
                }
                JSONObject item = updateList.getJSONObject(i);
                UpdateInfo info = parseUpdateJSONObject(item, updateType);
                if (info != null) {
                    updates.add(info);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error in JSON result", e);
        }
        return updates;
    }

    private UpdateInfo parseUpdateJSONObject(JSONObject obj, int updateType) throws JSONException {
        String fileName = obj.getString("filename");
        String url = obj.getString("url");
        String md5 = obj.getString("md5sum");
        int apiLevel = obj.getInt("api_level");
        long timestamp = obj.getLong("timestamp");
        String typeString = obj.getString("channel");
        UpdateInfo.Type type;

        if (TextUtils.equals(typeString, "stable")) {
            type = UpdateInfo.Type.STABLE;
        } else if (TextUtils.equals(typeString, "RC")) {
            type = UpdateInfo.Type.RC;
        } else if (TextUtils.equals(typeString, "snapshot")) {
            type = UpdateInfo.Type.SNAPSHOT;
        } else if (TextUtils.equals(typeString, "nightly")) {
            type = UpdateInfo.Type.NIGHTLY;
        } else {
            type = UpdateInfo.Type.UNKNOWN;
        }

        UpdateInfo ui = new UpdateInfo(fileName, timestamp, apiLevel, url, md5, type);
        boolean includeAll = updateType == Constants.UPDATE_TYPE_ALL_STABLE
            || updateType == Constants.UPDATE_TYPE_ALL_NIGHTLY;

        if (!includeAll && !ui.isNewerThanInstalled()) {
            Log.d(TAG, "Build " + fileName + " is older than the installed build");
            return null;
        }

        // fetch change log after checking whether to include this build to
        // avoid useless network traffic
        if (!ui.getChangeLogFile(this).exists()) {
            fetchChangeLog(ui, obj.getString("changes"));
        }

        return ui;
    }

    private void fetchChangeLog(UpdateInfo info, String url) {
        Log.d(TAG, "Getting change log for " + info + ", url " + url);

        BufferedReader reader = null;
        BufferedWriter writer = null;
        boolean finished = false;

        try {
            HttpGet request = new HttpGet(URI.create(url));
            addRequestHeaders(request);

            HttpEntity entity = mHttpExecutor.execute(request);
            writer = new BufferedWriter(new FileWriter(info.getChangeLogFile(this)));

            if (entity != null) {
                reader = new BufferedReader(new InputStreamReader(entity.getContent()), 2 * 1024);
                boolean categoryMatch = false, hasData = false;
                String line;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (mHttpExecutor.isAborted()) {
                        break;
                    }
                    if (line.isEmpty()) {
                        continue;
                    }

                    if (line.startsWith("=")) {
                        categoryMatch = !categoryMatch;
                    } else if (categoryMatch) {
                        if (hasData) {
                            writer.append("<br />");
                        }
                        writer.append("<b><u>");
                        writer.append(line);
                        writer.append("</u></b>");
                        writer.append("<br />");
                        hasData = true;
                    } else if (line.startsWith("*")) {
                        writer.append("<br /><b>");
                        writer.append(line.replaceAll("\\*", ""));
                        writer.append("</b>");
                        writer.append("<br />");
                        hasData = true;
                    } else {
                        writer.append("&#8226;&nbsp;");
                        writer.append(line);
                        writer.append("<br />");
                        hasData = true;
                    }
                }
            } else {
                writer.write("");
            }
            finished = true;
        } catch (IOException e) {
            Log.e(TAG, "Downloading change log for " + info + " failed", e);
            // keeping finished at false will delete the partially written file below
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
        }

        if (!finished) {
            info.getChangeLogFile(this).delete();
        }
    }

    private static class HttpRequestExecutor {
        private HttpClient mHttpClient;
        private HttpRequestBase mRequest;
        private boolean mAborted;

        public HttpRequestExecutor() {
            mHttpClient = new DefaultHttpClient();
            mAborted = false;
        }

        public HttpEntity execute(HttpRequestBase request) throws IOException {
            synchronized (this) {
                mAborted = false;
                mRequest = request;
            }

            HttpResponse response = mHttpClient.execute(request);
            HttpEntity entity = null;

            if (!mAborted && response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                entity = response.getEntity();
            }

            synchronized (this) {
                mRequest = null;
            }

            return entity;
        }

        public synchronized void abort() {
            if (mRequest != null) {
                mRequest.abort();
            }
            mAborted = true;
        }

        public synchronized boolean isAborted() {
            return mAborted;
        }
    }*/
}

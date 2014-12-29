/*
 * Copyright (C) 2012 The Exodus Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.exodus.updater.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.exodus.updater.misc.Constants;
import com.exodus.updater.GappsCheckerActivity;
import com.exodus.updater.service.UpdateCheckService;
import com.exodus.updater.utils.Utils;

import com.exodus.updater.R;

public class UpdateCheckReceiver extends BroadcastReceiver {
    private static final String TAG = "UpdateCheckReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Load the required settings from preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int updateFrequency = prefs.getInt(Constants.UPDATE_CHECK_PREF, Constants.UPDATE_FREQ_WEEKLY);

        // Parse the received action
        final String action = intent.getAction();

        if (updateFrequency == Constants.UPDATE_FREQ_NONE) {
            if (!Intent.ACTION_CHECK_FOR_UPDATES.equals(action)) {
                return;
            }
        }

        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
            // Connectivity has changed
            boolean hasConnection = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            Log.i(TAG, "Got connectivity change, has connection: " + hasConnection);
            if (!hasConnection) {
                return;
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // We just booted. Store the boot check state
            prefs.edit().putBoolean(Constants.BOOT_CHECK_COMPLETED, false).apply();

            if (!Utils.areGappsInstalled(context)) {
                // Check for Gapps install && open message in the case of their absence
                Intent i = new Intent(context.getApplicationContext(), GappsCheckerActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.getApplicationContext().startActivity(i);
            }
        }

        if (Intent.ACTION_CHECK_FOR_UPDATES.equals(action)) {
            Log.i(TAG, "Received quicksettings check request");
            Intent i = new Intent(context, UpdateCheckService.class);
            i.setAction(UpdateCheckService.ACTION_CHECK);
            i.putExtra("isFromQuicksettings", 1);
            context.startService(i);
            return;
        }

        // Handle the actual update check based on the defined frequency
        if (updateFrequency == Constants.UPDATE_FREQ_AT_BOOT) {
            boolean bootCheckCompleted = prefs.getBoolean(Constants.BOOT_CHECK_COMPLETED, false);
            if (!bootCheckCompleted) {
                Log.i(TAG, "Start an on-boot check");
                Intent i = new Intent(context, UpdateCheckService.class);
                i.setAction(UpdateCheckService.ACTION_CHECK);
                context.startService(i);
            } else {
                // Nothing to do
                Log.i(TAG, "On-boot update check was already completed.");
                return;
            }
        } else if (updateFrequency > 0) {
            Log.i(TAG, "Scheduling future, repeating update checks.");
            Utils.scheduleUpdateService(context, updateFrequency * 1000);
        }
    }
}

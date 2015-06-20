/*
 * Copyright (C) 2013 The Exodus Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.exodus.updater.utils;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;

import android.view.DisplayInfo;
import android.view.WindowManager;
import com.exodus.updater.R;
import com.exodus.updater.misc.Constants;
import com.exodus.updater.misc.UpdateInfo;
import com.exodus.updater.service.UpdateCheckService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;

import java.net.URL;
import java.net.MalformedURLException;

import java.util.LinkedList;

public class Utils {
    // Device type reference
    private static int sDeviceType = -1;
    private static final String TAG="ExodusUpdater";
    // Device types
    private static final int DEVICE_PHONE = 0;
    private static final int DEVICE_HYBRID = 1;
    private static final int DEVICE_TABLET = 2;

    private Utils() {
        // this class is not supposed to be instantiated
    }

    public static File makeUpdateFolder() {
        return new File(Environment.getExternalStorageDirectory().getAbsolutePath(),
                Constants.UPDATES_FOLDER);
    }

    public static void cancelNotification(Context context) {
        final NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(R.string.not_new_updates_found_title);
        nm.cancel(R.string.not_download_success);
    }

    public static String getDeviceType() {
        return SystemProperties.get("ro.cm.device");
    }

    public static String getInstalledVersion() {
        return SystemProperties.get("ro.modversion");
    }

    public static int getInstalledApiLevel() {
        return SystemProperties.getInt("ro.build.version.sdk", 0);
    }

    public static long getInstalledBuildDate() {
        return SystemProperties.getLong("ro.build.date.utc", 0);
    }

    public static String getUserAgentString(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            return pi.packageName + "/" + pi.versionName;
        } catch (PackageManager.NameNotFoundException nnfe) {
            return null;
        }
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnected()) {
            return true;
        }
        return false;
    }

    public static void scheduleUpdateService(Context context, int updateFrequency) {
        // Load the required settings from preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long lastCheck = prefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0);

        // Get the intent ready
        Intent i = new Intent(context, UpdateCheckService.class);
        i.setAction(UpdateCheckService.ACTION_CHECK);
        PendingIntent pi = PendingIntent.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        // Clear any old alarms and schedule the new alarm
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);

        if (updateFrequency != Constants.UPDATE_FREQ_NONE) {
            am.setRepeating(AlarmManager.RTC_WAKEUP, lastCheck + updateFrequency, updateFrequency, pi);
        }
    }

    public static void triggerUpdate(Context context, String updateFileName) throws IOException {
        /*
         * Should perform the following steps.
         * 1.- mkdir -p /cache/recovery
         * 2.- echo 'boot-recovery' > /cache/recovery/command
         * 3.- if(mBackup) echo '--nandroid'  >> /cache/recovery/command
         * 4.- echo '--update_package=SDCARD:update.zip' >> /cache/recovery/command
         * 5.- reboot recovery
         */
        Process p = null;
        OutputStream os = null;
        try{
            // Set the 'boot recovery' command
            p = Runtime.getRuntime().exec("sh");
            os = p.getOutputStream();
            os.write("mkdir -p /cache/recovery/\n".getBytes());
            os.write("echo 'boot-recovery' >/cache/recovery/command\n".getBytes());

            // See if backups are enabled and add the nandroid flag
            /* TODO: add this back once we have a way of doing backups that is not recovery specific
               if (mPrefs.getBoolean(Constants.BACKUP_PREF, true)) {
               os.write("echo '--nandroid'  >> /cache/recovery/command\n".getBytes());
               }
               */

            // Add the update folder/file name
            // Emulated external storage moved to user-specific paths in 4.2
            String userPath = Environment.isExternalStorageEmulated() ? ("/" + UserHandle.myUserId()) : "";

            String cmd = "echo '--update_package=" + getStorageMountpoint(context) + userPath
                + "/" + Constants.UPDATES_FOLDER + "/" + updateFileName
                + "' >> /cache/recovery/command\n";
            os.write(cmd.getBytes());
            os.flush();

            // Trigger the reboot
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            powerManager.reboot("recovery");
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception e) {
                    // ignore, not much we can do anyway
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
        }
    }

    private static String getStorageMountpoint(Context context) {
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        StorageVolume[] volumes = sm.getVolumeList();
        String primaryStoragePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        boolean alternateIsInternal = context.getResources().getBoolean(R.bool.alternateIsInternal);

        if (volumes.length <= 1) {
            // single storage, assume only /sdcard exists
            return "/sdcard";
        }

        for (int i = 0; i < volumes.length; i++) {
            StorageVolume v = volumes[i];
            if (v.getPath().equals(primaryStoragePath)) {
                /* This is the primary storage, where we stored the update file
                 *
                 * For CM10, a non-removable storage (partition or FUSE)
                 * will always be primary. But we have older recoveries out there
                 * in which /sdcard is the microSD, and the internal partition is
                 * mounted at /emmc.
                 *
                 * At buildtime, we try to automagically guess from recovery.fstab
                 * what's the recovery configuration for this device. If "/emmc"
                 * exists, and the primary isn't removable, we assume it will be
                 * mounted there.
                 */
                if (!v.isRemovable() && alternateIsInternal) {
                    return "/emmc";
                }
            };
        }
        // Not found, assume non-alternate
        return "/sdcard";
    }

    public static LinkedList<String> readMultilineFile(String urlstr) {
        LinkedList<String> ret = new LinkedList<String>();
        BufferedReader br = null;
        InputStreamReader is = null;
        try {
            // Create a URL for the desired page
            URL url = new URL(urlstr);

            // Read all the text returned by the server
            is = new InputStreamReader(url.openStream());
            br = new BufferedReader(is);
            String str;
            while ((str = br.readLine()) != null) {
                // str is one line of text; readLine() strips the newline character(s)
                ret.add(str);
            }
        } catch (MalformedURLException e) {
        } catch (IOException e) {
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
        }
        return ret;
    }

    public static String readFile(String urlstr) {
        String ret = null;
        BufferedReader br = null;
        InputStreamReader is = null;
        try {
            // Create a URL for the desired page
            URL url = new URL(urlstr);

            // Read all the text returned by the server
            is = new InputStreamReader(url.openStream());
            br = new BufferedReader(is);
            ret = br.readLine();
            br.close();
        } catch (MalformedURLException e) {
        } catch (IOException e) {
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
        }
        return ret;
    }

    private static int getScreenType(Context context) {
        if (sDeviceType == -1) {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayInfo outDisplayInfo = new DisplayInfo();
            wm.getDefaultDisplay().getDisplayInfo(outDisplayInfo);
            int shortSize = Math.min(outDisplayInfo.logicalHeight, outDisplayInfo.logicalWidth);
            int shortSizeDp = shortSize * DisplayMetrics.DENSITY_DEFAULT
                    / outDisplayInfo.logicalDensityDpi;
            if (shortSizeDp < 600) {
                // 0-599dp: "phone" UI with a separate status & navigation bar
                sDeviceType =  DEVICE_PHONE;
            } else if (shortSizeDp < 720) {
                // 600-719dp: "phone" UI with modifications for larger screens
                sDeviceType = DEVICE_HYBRID;
            } else {
                // 720dp: "tablet" UI with a single combined status & navigation bar
                sDeviceType = DEVICE_TABLET;
            }
        }
        return sDeviceType;
    }

    public static boolean isPhone(Context context) {
        return getScreenType(context) == DEVICE_PHONE;
    }

    public static boolean isHybrid(Context context) {
        return getScreenType(context) == DEVICE_HYBRID;
    }

    public static boolean isTablet(Context context) {
        return getScreenType(context) == DEVICE_TABLET;
    }

    // it tastes just like it smells.
    public static boolean areGappsInstalled(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(Constants.GMS_CORE_PKG, PackageManager.GET_ACTIVITIES);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }
    
    public static boolean DownloadChangelog(UpdateInfo Info,Context context) {
        File f = Info.getChangeLogFile(context);
        if (!f.exists() ) {
            f.delete();
        }
        String churl=Info.getDownloadUrl() + ".changelog";
        BufferedReader reader = null;
        BufferedWriter writer = null;
        InputStreamReader is = null;
        boolean finished = false;
        try {
            Log.d(TAG, "Getting change log for " + Info.getFileName() + ", url " + churl);
            URL url= new URL(churl);
            writer = new BufferedWriter(new FileWriter(f));
            is = new InputStreamReader(url.openStream());
            reader = new BufferedReader(is);
            boolean categoryMatch = false, hasData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
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
            finished = true;
        } catch (MalformedURLException e) {    
            Log.e(TAG, "URL failure for " + churl , e);
        } catch (IOException e) {
            Log.e(TAG, "Downloading change log for " + Info.getFileName() + " failed", e);
            // keeping finished at false will delete the partially written file below
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore, not much we can do anyway
                }
            }
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
            f.delete();
        }            
        return finished;
    }

}

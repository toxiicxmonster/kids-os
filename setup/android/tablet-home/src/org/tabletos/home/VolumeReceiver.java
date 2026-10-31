package org.tabletos.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The kid volume from outside Android: the parents' web portal, through the
 * host's tablet-volume helper (`am broadcast` as the shell user, which holds
 * the DUMP permission the manifest requires; ordinary apps can't send this).
 *
 *   am broadcast -n org.tabletos.home/.VolumeReceiver [--ei percent N]
 *
 * Sets the volume if a percent is given; either way answers with the current
 * one as the broadcast's result data. Same setting as the parent-mode slider.
 */
public class VolumeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        if (intent.hasExtra("percent")) {
            int percent = Math.max(0, Math.min(100, intent.getIntExtra("percent", HomeActivity.DEFAULT_KID_VOLUME)));
            prefs.edit().putInt(HomeActivity.KEY_VOLUME, percent).commit();
            HomeActivity.applyVolume(context, percent);
        }
        setResultData(String.valueOf(prefs.getInt(HomeActivity.KEY_VOLUME, HomeActivity.DEFAULT_KID_VOLUME)));
    }
}

package com.evan.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED &&
            Cfg.load(Prefs.sp(c)).boot &&
            Settings.canDrawOverlays(c)
        ) {
            c.startForegroundService(Intent(c, IslandService::class.java))
        }
    }
}

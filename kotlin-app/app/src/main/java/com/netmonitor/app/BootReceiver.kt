package com.netmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Relance la surveillance après un redémarrage si l'utilisateur l'avait activée. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Prefs.isEnabled(context)) return
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, NetworkMonitorService::class.java)
            )
        } catch (_: Exception) {
            // Démarrage refusé par le système : l'utilisateur relancera depuis l'app.
        }
    }
}

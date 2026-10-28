package com.netmonitor.app

import android.content.Context

/**
 * Mémorise si l'utilisateur veut la surveillance (pour la relancer après un redémarrage).
 * Activée par défaut : la surveillance démarre dès la première ouverture de l'app.
 */
object Prefs {
    private const val FILE = "netmonitor"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

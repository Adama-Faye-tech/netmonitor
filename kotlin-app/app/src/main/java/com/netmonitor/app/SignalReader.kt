package com.netmonitor.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Lecture passive de la force du signal (aucun scan radio : coût batterie nul).
 *
 * Wi-Fi     : RSSI en dBm  → >= -60 très fort | -61..-75 modéré | < -75 faible
 * Cellulaire: niveau 0..4 fourni par Android → 4 très fort | 2-3 modéré | 0-1 faible
 */
object SignalReader {

    fun read(context: Context, transport: Transport): SignalLevel = try {
        when (transport) {
            Transport.WIFI -> readWifi(context)
            Transport.CELLULAR -> readCellular(context)
            Transport.OTHER -> SignalLevel.UNKNOWN
        }
    } catch (_: Exception) {
        SignalLevel.UNKNOWN
    }

    @Suppress("DEPRECATION") // connectionInfo reste la source la plus simple et fiable pour le RSSI
    private fun readWifi(context: Context): SignalLevel {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val rssi = wifi.connectionInfo?.rssi ?: return SignalLevel.UNKNOWN
        return when {
            rssi <= -127 -> SignalLevel.UNKNOWN   // -127 = valeur « invalide » d'Android
            rssi >= -60 -> SignalLevel.VERY_STRONG
            rssi >= -75 -> SignalLevel.MODERATE
            else -> SignalLevel.WEAK
        }
    }

    private fun readCellular(context: Context): SignalLevel {
        // 1) Chemin principal : niveau 0..4 du TelephonyManager (API 28+).
        //    Sur certains appareils/versions, une SecurityException peut survenir : on bascule alors sur le plan B.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val level = tm.signalStrength?.level
                if (level != null) return levelToSignal(level)
            } catch (_: SecurityException) {
                // on continue vers le plan B
            }
        }
        // 2) Plan B (API 29+) : valeur de signal exposée par NetworkCapabilities (RSRP en dBm en 4G/5G)
        if (Build.VERSION.SDK_INT >= 29) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val dbm = caps?.signalStrength
            if (dbm != null && dbm != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                return when {
                    dbm >= -90 -> SignalLevel.VERY_STRONG
                    dbm >= -105 -> SignalLevel.MODERATE
                    else -> SignalLevel.WEAK
                }
            }
        }
        return SignalLevel.UNKNOWN
    }

    private fun levelToSignal(level: Int) = when {
        level >= 4 -> SignalLevel.VERY_STRONG
        level >= 2 -> SignalLevel.MODERATE
        else -> SignalLevel.WEAK
    }
}

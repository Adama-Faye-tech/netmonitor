package com.netmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service de premier plan qui surveille la qualité du réseau.
 *
 * Stratégie basse consommation :
 *  - AUCUN polling pour détecter un changement : on s'abonne à ConnectivityManager
 *    (callback réseau par défaut) et on ne mesure que lorsque le système nous prévient.
 *  - Entre deux événements, un re-test périodique dont l'intervalle s'allonge tant que
 *    la qualité reste stable (1 min → 5 min) et double écran éteint.
 *  - Test de débit rare (1 Mo toutes les 15 min en Wi-Fi, 30 min en données mobiles).
 *  - Une seule coroutine, aucun WakeLock : le CPU peut dormir entre deux cycles.
 */
class NetworkMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Réveil immédiat de la boucle (changement réseau). CONFLATED : les rafales fusionnent. */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private lateinit var cm: ConnectivityManager
    private lateinit var nm: NotificationManager

    @Volatile private var network: Network? = null
    @Volatile private var lastValidated: Boolean? = null

    // Dernier débit connu, valable uniquement pour le réseau en cours.
    private var speedMbps: Double? = null
    private var speedNetwork: Network? = null
    private var speedAt = 0L

    // Anti-clignotement : un nouvel état doit être observé 2 fois de suite avant alerte.
    private var announced: NetworkQuality? = null
    private var candidate: NetworkQuality? = null
    private var stableCycles = 0
    private var lastForegroundText = ""

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            network = n
            lastValidated = null
            wakeUp.trySend(Unit)
        }

        override fun onLost(n: Network) {
            if (n == network) {
                network = null
                wakeUp.trySend(Unit)
            }
        }

        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            // Cet événement est très fréquent (variations de signal) : on ne réagit
            // qu'au changement d'état « validé » (accès internet réel ou non).
            if (n != network) return
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (lastValidated != null && lastValidated != validated) wakeUp.trySend(Unit)
            lastValidated = validated
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this, ID_FOREGROUND, buildForeground("Analyse en cours…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        NetworkStateHolder.update { it.copy(running = true) }

        // Idempotent : un second démarrage ne duplique ni callback ni boucle.
        if (!started) {
            started = true
            cm.registerDefaultNetworkCallback(callback)
            scope.launch { monitorLoop() }
        }
        return START_STICKY
    }

    private var started = false

    private suspend fun monitorLoop() {
        // Laisse au callback le temps de livrer le réseau actif : évite une fausse alerte
        // « Mauvais » au démarrage. Hors ligne, on conclut après ce court délai.
        withTimeoutOrNull(STARTUP_GRACE_MS) { wakeUp.receive() }
        while (true) {
            val quality = runCycle()
            val wait = nextDelayMs(quality)
            // Attend soit la fin du délai, soit un changement réseau (le premier arrivé).
            withTimeoutOrNull(wait) { wakeUp.receive() }
        }
    }

    /** Un cycle de mesure complet. Retourne la qualité brute (avant anti-clignotement). */
    private suspend fun runCycle(): NetworkQuality {
        val net = network
        val metrics = if (net == null) {
            NetworkMetrics(false, null, null, 100.0, SignalLevel.UNKNOWN, null, Transport.OTHER)
        } else {
            val caps = cm.getNetworkCapabilities(net)
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Transport.WIFI
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Transport.CELLULAR
                else -> Transport.OTHER
            }
            val probe = NetworkProbe.ping(net)
            val online = probe.lossPercent < 100.0

            if (speedNetwork != net) {
                speedNetwork = net
                speedMbps = null
                speedAt = 0L
            }
            val interval = if (transport == Transport.CELLULAR) SPEED_EVERY_CELL_MS else SPEED_EVERY_WIFI_MS
            val due = speedMbps == null || System.currentTimeMillis() - speedAt > interval
            // On ne gaspille pas de données sur un réseau déjà jugé mauvais.
            if (online && due && (probe.pingMs ?: Double.MAX_VALUE) <= QualityEvaluator.PING_FAIBLE_MAX) {
                NetworkProbe.downloadMbps(net)?.let { speedMbps = it }
                speedAt = System.currentTimeMillis()
            }

            NetworkMetrics(
                hasInternet = online,
                pingMs = probe.pingMs,
                jitterMs = probe.jitterMs,
                lossPercent = probe.lossPercent,
                signal = SignalReader.read(this, transport),
                downloadMbps = speedMbps,
                transport = transport
            )
        }

        val quality = QualityEvaluator.evaluate(metrics)
        NetworkStateHolder.update {
            it.copy(
                running = true,
                quality = quality,
                metrics = metrics,
                lastSpeedMbps = metrics.downloadMbps,
                updatedAt = System.currentTimeMillis()
            )
        }
        updateForeground(quality, metrics)
        maybeAlert(quality, metrics)
        return quality
    }

    /** Alerte uniquement lors d'un changement confirmé (ou perte totale de connexion). */
    private fun maybeAlert(quality: NetworkQuality, m: NetworkMetrics) {
        if (quality == announced) {
            candidate = null
            stableCycles++
            return
        }
        stableCycles = 0
        val immediate = !m.hasInternet || announced == null
        if (immediate || candidate == quality) {
            announced = quality
            candidate = null
            notifyAlert(quality, m)
        } else {
            candidate = quality
        }
    }

    private fun nextDelayMs(quality: NetworkQuality): Long {
        // Un changement est en attente de confirmation : on re-mesure vite.
        if (candidate != null) return CONFIRM_DELAY_MS
        var delay = when {
            quality == NetworkQuality.MAUVAIS || quality == NetworkQuality.FAIBLE -> 30_000L
            stableCycles >= 6 -> 300_000L
            stableCycles >= 3 -> 120_000L
            else -> 60_000L
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) delay = (delay * 2).coerceAtMost(600_000L)
        return delay
    }

    // ---------------------------------------------------------------- Notifications

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Surveillance réseau", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alertes de qualité", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildForeground(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Surveillance réseau active")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0, "Arrêter",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, NetworkMonitorService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateForeground(quality: NetworkQuality, m: NetworkMetrics) {
        val text = "${quality.label} · ${m.summary()}"
        if (text == lastForegroundText) return   // évite des mises à jour inutiles
        lastForegroundText = text
        nm.notify(ID_FOREGROUND, buildForeground(text))
    }

    private fun notifyAlert(quality: NetworkQuality, m: NetworkMetrics) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Réseau : ${quality.label}")
            .setContentText(m.summary())
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        // Sans permission POST_NOTIFICATIONS (Android 13+), la notification est ignorée sans erreur.
        nm.notify(ID_ALERT, n)
    }

    // ---------------------------------------------------------------- Cycle de vie

    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        scope.cancel()
        NetworkStateHolder.update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.netmonitor.app.STOP"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alert"
        private const val ID_FOREGROUND = 1
        private const val ID_ALERT = 2

        private const val STARTUP_GRACE_MS = 1_500L
        private const val CONFIRM_DELAY_MS = 8_000L
        private const val SPEED_EVERY_WIFI_MS = 15 * 60_000L
        private const val SPEED_EVERY_CELL_MS = 30 * 60_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, NetworkMonitorService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NetworkMonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

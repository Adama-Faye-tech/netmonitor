package com.netmonitor.app

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlin.math.abs

/**
 * Sondes réseau ultra-légères.
 *
 * Ping : Android n'autorise pas l'ICMP sans root. On mesure donc la durée d'une
 * poignée de main TCP (SYN → SYN/ACK), qui correspond à ~1 aller-retour réseau.
 * On vise des IP littérales (pas de DNS) pour ne mesurer que le réseau.
 *
 * Toutes les sondes sont liées à un objet [Network] précis : indispensable quand
 * Wi-Fi et données mobiles sont actifs en même temps.
 */
object NetworkProbe {

    private const val PRIMARY_HOST = "1.1.1.1"
    private const val SECONDARY_HOST = "8.8.8.8"
    private const val PORT = 443

    private const val SAMPLES = 8            // assez pour une gigue fiable, assez court pour la batterie
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val GAP_MS = 100L          // pause entre deux sondes

    // Point de test de débit (1 Mo). Remplaçable par votre propre serveur.
    private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=1000000"

    data class PingResult(
        val pingMs: Double?,      // moyenne des RTT réussis
        val jitterMs: Double?,    // moyenne des écarts entre RTT consécutifs
        val lossPercent: Double   // sondes échouées / sondes envoyées
    )

    suspend fun ping(network: Network): PingResult = withContext(Dispatchers.IO) {
        val first = sample(network, PRIMARY_HOST)
        // Si 100 % de perte sur le premier hôte, on vérifie avec un second avant de conclure
        // « pas d'internet » : évite les faux positifs si un seul hôte est bloqué.
        if (first.lossPercent < 100.0) first
        else sample(network, SECONDARY_HOST).takeIf { it.lossPercent < first.lossPercent } ?: first
    }

    private suspend fun sample(network: Network, host: String): PingResult {
        val rtts = ArrayList<Double>(SAMPLES)
        var failures = 0

        repeat(SAMPLES) { i ->
            val rtt = tcpRoundTripMs(network, host)
            if (rtt == null) failures++ else rtts.add(rtt)
            if (i < SAMPLES - 1) delay(GAP_MS)
        }

        val ping = if (rtts.isEmpty()) null else rtts.average()
        // Gigue = moyenne des |RTT(n) - RTT(n-1)| (définition simplifiée de la RFC 3550)
        val jitter = if (rtts.size < 2) null
        else rtts.zipWithNext { a, b -> abs(b - a) }.average()
        val loss = failures * 100.0 / SAMPLES

        return PingResult(ping, jitter, loss)
    }

    /** Durée d'une connexion TCP en ms, ou null en cas d'échec / timeout. */
    private fun tcpRoundTripMs(network: Network, host: String): Double? {
        return try {
            network.socketFactory.createSocket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                (System.nanoTime() - start) / 1_000_000.0
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Débit descendant approximatif en Mbps.
     * Le chronomètre démarre après le premier bloc reçu : on exclut ainsi la
     * latence de connexion/TLS qui fausserait un petit transfert.
     * Retourne null si le transfert est trop court pour être significatif.
     */
    suspend fun downloadMbps(network: Network): Double? = withContext(Dispatchers.IO) {
        try {
            val conn = network.openConnection(URL(SPEED_URL)) as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            try {
                conn.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    if (input.read(buffer) <= 0) return@withContext null
                    val start = System.nanoTime()
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                    }
                    val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                    if (bytes < 100_000 || seconds <= 0.0) null
                    else bytes * 8 / seconds / 1_000_000.0
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}

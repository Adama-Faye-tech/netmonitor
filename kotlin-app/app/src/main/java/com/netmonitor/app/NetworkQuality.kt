package com.netmonitor.app

import kotlin.math.roundToInt

/** Les 4 états de qualité — et uniquement ceux-là. */
enum class NetworkQuality(val label: String) {
    EXCELLENT("Excellent"),
    MOYEN("Moyen"),
    FAIBLE("Faible"),
    MAUVAIS("Mauvais")
}

/** Force du signal ramenée à 3 niveaux (+ inconnu si le capteur ne répond pas). */
enum class SignalLevel(val label: String) {
    VERY_STRONG("Très fort"),
    MODERATE("Modéré"),
    WEAK("Faible"),
    UNKNOWN("Inconnu")
}

enum class Transport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Données mobiles"),
    OTHER("Autre")
}

/**
 * Photo instantanée du réseau.
 * pingMs / jitterMs sont null quand aucune sonde n'a abouti.
 * Les débits sont ceux du dernier test sur ce réseau (rare, pour économiser batterie et data).
 */
data class NetworkMetrics(
    val hasInternet: Boolean,
    val pingMs: Double?,
    val jitterMs: Double?,
    val lossPercent: Double,
    val signal: SignalLevel,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val transport: Transport
) {
    /** Résumé court pour les notifications. */
    fun summary(): String {
        if (!hasInternet) return "Aucun accès internet · ${transport.label}"
        val ping = pingMs?.roundToInt()?.let { "$it ms" } ?: "—"
        val jitter = jitterMs?.roundToInt()?.let { "$it ms" } ?: "—"
        return "Ping $ping · Gigue $jitter · Perte ${lossPercent.roundToInt()} % · ${transport.label}"
    }
}

/**
 * Évaluateur pur (aucune dépendance Android) : facile à tester en JVM.
 *
 * Ordre d'évaluation : du pire au meilleur. Le premier critère rempli gagne,
 * ce qui garantit qu'un seul mauvais indicateur suffit à dégrader l'état.
 */
object QualityEvaluator {

    // ---- Seuils (ms, %, Mbps) — modifiez-les ici uniquement ----
    const val PING_EXCELLENT_MAX = 20.0   // < 20  => compatible Excellent
    const val PING_MOYEN_MAX = 50.0       // <= 50 => compatible Moyen
    const val PING_FAIBLE_MAX = 100.0     // <= 100 => compatible Faible ; au-delà : Mauvais

    const val JITTER_EXCELLENT_MAX = 5.0
    const val JITTER_MOYEN_MAX = 20.0
    const val JITTER_FAIBLE_MAX = 40.0

    /** Toute perte strictement supérieure à ce taux => Mauvais (0 % = tolérance zéro). */
    const val LOSS_MAX_PERCENT = 0.0

    /** Seuil Wi-Fi : un débit supérieur peut remplacer un signal « très fort » pour Excellent. */
    const val SPEED_EXCELLENT_MIN_MBPS = 25.0
    /** Seuil mobile : tient compte des débits plus faibles des réseaux cellulaires. */
    const val CELLULAR_SPEED_EXCELLENT_MIN_MBPS = 5.0

    fun evaluate(m: NetworkMetrics): NetworkQuality {
        val ping = m.pingMs
        val jitter = m.jitterMs

        // 1) MAUVAIS : pas d'internet, mesure impossible, perte, ping ou gigue trop élevés
        if (!m.hasInternet || ping == null || jitter == null) return NetworkQuality.MAUVAIS
        if (m.lossPercent > LOSS_MAX_PERCENT) return NetworkQuality.MAUVAIS
        if (ping > PING_FAIBLE_MAX || jitter > JITTER_FAIBLE_MAX) return NetworkQuality.MAUVAIS

        // 2) FAIBLE : ping 51-100, gigue 21-40, ou signal faible
        if (ping > PING_MOYEN_MAX || jitter > JITTER_MOYEN_MAX || m.signal == SignalLevel.WEAK) {
            return NetworkQuality.FAIBLE
        }

        // 3) EXCELLENT : ping < 20, gigue < 5, et signal très fort OU débit > 25 Mbps.
        //    Si le capteur de signal est indisponible (UNKNOWN), on ne pénalise pas :
        //    ce sont alors le ping et la gigue qui décident.
        val speedThreshold = if (m.transport == Transport.CELLULAR) {
            CELLULAR_SPEED_EXCELLENT_MIN_MBPS
        } else {
            SPEED_EXCELLENT_MIN_MBPS
        }
        val fastEnough = (m.downloadMbps ?: 0.0) > speedThreshold
        val signalOk = m.signal == SignalLevel.VERY_STRONG || m.signal == SignalLevel.UNKNOWN || fastEnough
        if (ping < PING_EXCELLENT_MAX && jitter < JITTER_EXCELLENT_MAX && signalOk) {
            return NetworkQuality.EXCELLENT
        }

        // 4) MOYEN : tout le reste (ping 20-50, gigue 5-20, signal modéré)
        return NetworkQuality.MOYEN
    }
}

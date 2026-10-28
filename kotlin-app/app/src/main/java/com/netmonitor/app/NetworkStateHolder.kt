package com.netmonitor.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pont service → interface, dans le même processus.
 * Un StateFlow suffit : pas de broadcast, pas de binder, aucun coût en veille.
 */
object NetworkStateHolder {

    data class UiState(
        val running: Boolean = false,
        val quality: NetworkQuality? = null,
        val metrics: NetworkMetrics? = null,
        /** Dernier débit mesuré (conservé entre deux tests pour l'affichage). */
        val lastSpeedMbps: Double? = null,
        val updatedAt: Long = 0L
    ) {
        /** Version sérialisable (utilisée par le pont Flutter). */
        fun toMap(): Map<String, Any?> = mapOf(
            "running" to running,
            "quality" to quality?.label,
            "ping" to metrics?.pingMs,
            "jitter" to metrics?.jitterMs,
            "loss" to metrics?.lossPercent,
            "download" to lastSpeedMbps,
            "signal" to metrics?.signal?.label,
            "transport" to metrics?.transport?.label,
            "updatedAt" to updatedAt
        )
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun update(transform: (UiState) -> UiState) = _state.update(transform)
}

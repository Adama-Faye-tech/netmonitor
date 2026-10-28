package com.netmonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Écran unique : statut courant + métriques en direct + bouton marche/arrêt. */
class MainActivity : AppCompatActivity() {

    private lateinit var badge: TextView
    private lateinit var ping: TextView
    private lateinit var jitter: TextView
    private lateinit var speed: TextView
    private lateinit var loss: TextView
    private lateinit var signal: TextView
    private lateinit var transport: TextView
    private lateinit var toggle: Button

    private var running = false

    // Android 13+ : la permission d'afficher des notifications est demandée à l'exécution.
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startMonitoringIfWanted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        badge = findViewById(R.id.badge)
        ping = findViewById(R.id.ping)
        jitter = findViewById(R.id.jitter)
        speed = findViewById(R.id.speed)
        loss = findViewById(R.id.loss)
        signal = findViewById(R.id.signal)
        transport = findViewById(R.id.transport)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (running) {
                Prefs.setEnabled(this, false)
                NetworkMonitorService.stop(this)
            } else {
                Prefs.setEnabled(this, true)
                NetworkMonitorService.start(this)
            }
        }

        // Collecte uniquement quand l'écran est visible : zéro travail UI en arrière-plan.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NetworkStateHolder.state.collect { render(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoringIfWanted()
        }
    }

    private fun startMonitoringIfWanted() {
        if (Prefs.isEnabled(this) && !NetworkStateHolder.state.value.running) {
            NetworkMonitorService.start(this)
        }
    }

    private fun render(s: NetworkStateHolder.UiState) {
        running = s.running
        toggle.text = if (running) "Arrêter la surveillance" else "Démarrer la surveillance"

        val q = s.quality
        badge.text = if (!running || q == null) "—" else q.label
        (badge.background.mutate() as GradientDrawable).setColor(colorFor(if (running) q else null))

        val m = s.metrics
        ping.text = m?.pingMs?.let { "${it.roundToInt()} ms" } ?: "—"
        jitter.text = m?.jitterMs?.let { "${it.roundToInt()} ms" } ?: "—"
        speed.text = s.lastSpeedMbps?.let { "%.1f Mbps".format(it) } ?: "—"
        loss.text = m?.let { "${it.lossPercent.roundToInt()} %" } ?: "—"
        signal.text = m?.signal?.label ?: "—"
        transport.text = m?.transport?.label ?: "—"
    }

    private fun colorFor(q: NetworkQuality?): Int = when (q) {
        NetworkQuality.EXCELLENT -> Color.parseColor("#2E7D32")
        NetworkQuality.MOYEN -> Color.parseColor("#F9A825")
        NetworkQuality.FAIBLE -> Color.parseColor("#EF6C00")
        NetworkQuality.MAUVAIS -> Color.parseColor("#C62828")
        null -> Color.parseColor("#9E9E9E")
    }
}

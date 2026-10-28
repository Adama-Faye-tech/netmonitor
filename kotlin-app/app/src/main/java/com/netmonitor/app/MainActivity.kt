package com.netmonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Écran unique : statut courant + métriques en direct + bouton marche/arrêt. */
class MainActivity : AppCompatActivity() {

    private lateinit var badge: TextView
    private lateinit var ping: TextView
    private lateinit var jitter: TextView
    private lateinit var download: TextView
    private lateinit var upload: TextView
    private lateinit var loss: TextView
    private lateinit var signal: TextView
    private lateinit var transport: TextView
    private lateinit var toggle: Button
    private lateinit var networkTab: Button
    private lateinit var usagesTab: Button
    private lateinit var networkScroll: ScrollView
    private lateinit var usagesScroll: ScrollView
    private lateinit var usagesContent: LinearLayout
    private lateinit var usageWarning: TextView

    private data class UsageRow(
        val badge: TextView,
        val check: (Double?, Double?, Double?, Double?) -> Boolean?
    )

    private data class EstimateRow(val sizeMb: Double, val value: TextView)

    private val usageRows = mutableListOf<UsageRow>()
    private val estimateRows = mutableListOf<EstimateRow>()

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
        download = findViewById(R.id.download)
        upload = findViewById(R.id.upload)
        loss = findViewById(R.id.loss)
        signal = findViewById(R.id.signal)
        transport = findViewById(R.id.transport)
        toggle = findViewById(R.id.toggle)
        networkTab = findViewById(R.id.tab_network)
        usagesTab = findViewById(R.id.tab_usages)
        networkScroll = findViewById(R.id.network_scroll)
        usagesScroll = findViewById(R.id.usages_scroll)
        usagesContent = findViewById(R.id.usages_content)
        createUsageContent()
        networkTab.setOnClickListener { selectTab(showUsages = false) }
        usagesTab.setOnClickListener { selectTab(showUsages = true) }
        selectTab(showUsages = false)
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
        toggle.text = if (running) "ARRÊTER LA SURVEILLANCE" else "LANCER LA SURVEILLANCE"

        val q = s.quality
        badge.text = if (!running || q == null) "—" else q.label
        val badgeColor = colorFor(if (running) q else null)
        (badge.background.mutate() as GradientDrawable).apply {
            setColor(badgeColor)
            setStroke(dp(1), Color.parseColor("#6638FF91"))
        }

        val m = s.metrics
        ping.text = m?.pingMs?.let { "${it.roundToInt()} ms" } ?: "—"
        jitter.text = m?.jitterMs?.let { "${it.roundToInt()} ms" } ?: "—"
        download.text = s.lastSpeedMbps?.let { "%.1f Mbps".format(it) } ?: "—"
        upload.text = s.lastUploadMbps?.let { "%.1f Mbps".format(it) } ?: "—"
        loss.text = m?.let { "${it.lossPercent.roundToInt()} %" } ?: "—"
        signal.text = m?.signal?.label ?: "—"
        transport.text = m?.transport?.label ?: "—"
        updateUsageContent(
            if (running) s.lastSpeedMbps else null,
            if (running) m?.pingMs else null,
            if (running) m?.jitterMs else null,
            if (running) m?.lossPercent else null,
            running && UsageEligibility.shouldShowStabilityWarning(s.quality, m?.lossPercent)
        )
    }

    private fun createUsageContent() {
        addUsageRow("🎮", "Cloud gaming · 1080p 60 fps / 4K") { downloadMbps, pingMs, jitterMs, lossPercent ->
            UsageEligibility.isGamingEligible(
                downloadMbps,
                pingMs,
                jitterMs,
                lossPercent,
                UsageEligibility.CLOUD_HIGH_DOWNLOAD_MBPS,
                UsageEligibility.CLOUD_HIGH_MAX_PING_MS,
                pingLimitIsInclusive = false
            )
        }
        addUsageRow("🎮", "Cloud gaming · 720p") { downloadMbps, pingMs, jitterMs, lossPercent ->
            UsageEligibility.isGamingEligible(
                downloadMbps,
                pingMs,
                jitterMs,
                lossPercent,
                UsageEligibility.CLOUD_STANDARD_DOWNLOAD_MBPS,
                40.0,
                pingLimitIsInclusive = true
            )
        }
        addUsageRow("📺", "Film UHD / 4K") { downloadMbps, _, _, _ ->
            UsageEligibility.isEligible(downloadMbps, UsageEligibility.UHD_DOWNLOAD_MBPS)
        }
        addUsageRow("📺", "Film HD / 1080p") { downloadMbps, _, _, _ ->
            UsageEligibility.isEligible(downloadMbps, UsageEligibility.HD_DOWNLOAD_MBPS)
        }
        addUsageRow("🌐", "Web et musique") { downloadMbps, _, _, _ ->
            UsageEligibility.isEligible(downloadMbps, UsageEligibility.WEB_MUSIC_DOWNLOAD_MBPS)
        }

        usageWarning = TextView(this).apply {
            text = "⚠ Réseau instable : des coupures ou chargements peuvent survenir."
            textSize = 13f
            setTextColor(Color.parseColor("#FFFFB74D"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#332B1B0A"))
                setStroke(dp(1), Color.parseColor("#66FFB74D"))
            }
            visibility = View.GONE
        }
        val warningParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
        usagesContent.addView(usageWarning, warningParams)

        val estimateTitle = TextView(this).apply {
            text = "Temps de téléchargement estimé"
            textSize = 16f
            setTextColor(Color.parseColor("#E6F1E9"))
            letterSpacing = 0.04f
            setPadding(0, dp(20), 0, dp(8))
        }
        usagesContent.addView(estimateTitle)
        addEstimateRow("🎵", "Chanson · 5 Mo", 5.0)
        addEstimateRow("🎬", "Film HD · 1 500 Mo", 1_500.0)
        addEstimateRow("🎬", "Film UHD · 7 000 Mo", 7_000.0)
    }

    private fun addUsageRow(
        icon: String,
        label: String,
        check: (Double?, Double?, Double?, Double?) -> Boolean?
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 22f
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#E6F1E9"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            contentDescription = "Disponibilité non mesurée"
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.background = cardBackground()
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(4)
        }
        usagesContent.addView(row, rowParams)
        usageRows.add(UsageRow(badge, check))
    }

    private fun addEstimateRow(icon: String, label: String, sizeMb: Double) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 20f
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#B6C7BB"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            text = "—"
            textSize = 14f
            setTextColor(Color.parseColor("#E6F1E9"))
            gravity = android.view.Gravity.END
        }
        row.addView(value)
        row.background = cardBackground()
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(4)
        }
        usagesContent.addView(row, rowParams)
        estimateRows.add(EstimateRow(sizeMb, value))
    }

    private fun updateUsageContent(
        downloadMbps: Double?,
        pingMs: Double?,
        jitterMs: Double?,
        lossPercent: Double?,
        showStabilityWarning: Boolean
    ) {
        usageRows.forEach { row ->
            val eligible = row.check(downloadMbps, pingMs, jitterMs, lossPercent)
            val (label, color, description) = when (eligible) {
                true -> Triple("Oui", Color.parseColor("#2E7D32"), "Disponible")
                false -> Triple("Non", Color.parseColor("#C62828"), "Non disponible")
                null -> Triple("—", Color.GRAY, "En attente d'une mesure")
            }
            row.badge.text = label
            row.badge.contentDescription = description
            row.badge.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color)
            }
        }
        estimateRows.forEach { row ->
            row.value.text = formatEstimate(
                UsageEligibility.estimatedDownloadSeconds(row.sizeMb, downloadMbps)
            )
        }
        usageWarning.visibility = if (showStabilityWarning) View.VISIBLE else View.GONE
    }

    private fun formatEstimate(seconds: Double?): String {
        if (seconds == null) return "—"
        val totalSeconds = ceil(seconds).toLong()
        return when {
            totalSeconds < 60 -> "~$totalSeconds s"
            totalSeconds < 3_600 -> "~${ceil(totalSeconds / 60.0).toInt()} min"
            else -> {
                val hours = totalSeconds / 3_600
                val minutes = ceil((totalSeconds % 3_600) / 60.0).toInt()
                if (minutes == 0) "~${hours} h" else "~${hours} h ${minutes} min"
            }
        }
    }

    private fun selectTab(showUsages: Boolean) {
        usagesScroll.visibility = if (showUsages) android.view.View.VISIBLE else android.view.View.GONE
        networkScroll.visibility = if (showUsages) android.view.View.GONE else android.view.View.VISIBLE
        styleTab(networkTab, selected = !showUsages)
        styleTab(usagesTab, selected = showUsages)
    }

    private fun styleTab(tab: Button, selected: Boolean) {
        tab.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor(if (selected) "#B3132419" else "#66101914"))
            if (selected) setStroke(dp(1), Color.parseColor("#38FF91"))
        }
        tab.setTextColor(Color.parseColor(if (selected) "#56FF9F" else "#A5B5AA"))
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.parseColor("#A0101914"))
        setStroke(dp(1), Color.parseColor("#2638FF91"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun colorFor(q: NetworkQuality?): Int = when (q) {
        NetworkQuality.EXCELLENT -> Color.parseColor("#0B8F4D")
        NetworkQuality.MOYEN -> Color.parseColor("#A87913")
        NetworkQuality.FAIBLE -> Color.parseColor("#B95714")
        NetworkQuality.MAUVAIS -> Color.parseColor("#A02C38")
        null -> Color.parseColor("#27312B")
    }
}

package com.netmonitor.app

object UsageEligibility {
    const val CLOUD_HIGH_DOWNLOAD_MBPS = 25.0
    const val CLOUD_HIGH_MAX_PING_MS = 30.0
    const val CLOUD_STANDARD_DOWNLOAD_MBPS = 15.0
    const val CLOUD_STANDARD_MAX_PING_MS = 60.0
    const val UHD_DOWNLOAD_MBPS = 25.0
    const val HD_DOWNLOAD_MBPS = 5.0
    const val WEB_MUSIC_DOWNLOAD_MBPS = 1.0

    fun isGamingEligible(
        downloadMbps: Double?,
        pingMs: Double?,
        jitterMs: Double?,
        lossPercent: Double?,
        minimumDownloadMbps: Double,
        maximumPingMs: Double,
        pingLimitIsInclusive: Boolean
    ): Boolean? {
        if (downloadMbps == null || !downloadMbps.isFinite() || downloadMbps <= 0.0) return null
        if (pingMs == null || !pingMs.isFinite() || pingMs < 0.0) return null
        if (jitterMs == null || !jitterMs.isFinite() || jitterMs < 0.0) return null
        if (lossPercent == null || !lossPercent.isFinite() || lossPercent < 0.0) return null

        val pingEligible = if (pingLimitIsInclusive) pingMs <= maximumPingMs else pingMs < maximumPingMs
        return downloadMbps >= minimumDownloadMbps &&
            pingEligible &&
            jitterMs <= 20.0 &&
            lossPercent == 0.0
    }

    fun isEligible(
        downloadMbps: Double?,
        minimumDownloadMbps: Double,
        pingMs: Double? = null,
        maximumPingMs: Double? = null
    ): Boolean? {
        if (downloadMbps == null || !downloadMbps.isFinite() || downloadMbps <= 0.0) return null
        if (maximumPingMs == null) return downloadMbps >= minimumDownloadMbps
        if (pingMs == null || !pingMs.isFinite() || pingMs < 0.0) return null
        return downloadMbps >= minimumDownloadMbps && pingMs < maximumPingMs
    }

    fun estimatedDownloadSeconds(fileSizeMb: Double, downloadMbps: Double?): Double? {
        if (fileSizeMb <= 0.0 || !fileSizeMb.isFinite()) return null
        if (downloadMbps == null || downloadMbps <= 0.0 || !downloadMbps.isFinite()) return null
        return fileSizeMb * 8.0 / downloadMbps
    }

    fun shouldShowStabilityWarning(quality: NetworkQuality?, lossPercent: Double?): Boolean =
        quality == NetworkQuality.MAUVAIS && lossPercent != null &&
            lossPercent.isFinite() && lossPercent > 0.0
}

package com.netmonitor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEligibilityTest {
    @Test
    fun cloudGamingUsesRequestedSpeedAndPingLimits() {
        assertTrue(
            UsageEligibility.isGamingEligible(
                25.0,
                29.9,
                20.0,
                0.0,
                UsageEligibility.CLOUD_HIGH_DOWNLOAD_MBPS,
                UsageEligibility.CLOUD_HIGH_MAX_PING_MS,
                pingLimitIsInclusive = false
            )!!
        )
        assertFalse(
            UsageEligibility.isGamingEligible(
                25.0,
                30.0,
                20.0,
                0.0,
                UsageEligibility.CLOUD_HIGH_DOWNLOAD_MBPS,
                UsageEligibility.CLOUD_HIGH_MAX_PING_MS,
                pingLimitIsInclusive = false
            )!!
        )
        assertFalse(
            UsageEligibility.isGamingEligible(
                24.9,
                20.0,
                20.0,
                0.0,
                UsageEligibility.CLOUD_HIGH_DOWNLOAD_MBPS,
                UsageEligibility.CLOUD_HIGH_MAX_PING_MS,
                pingLimitIsInclusive = false
            )!!
        )
        assertTrue(
            UsageEligibility.isGamingEligible(
                15.0,
                40.0,
                20.0,
                0.0,
                UsageEligibility.CLOUD_STANDARD_DOWNLOAD_MBPS,
                40.0,
                pingLimitIsInclusive = true
            )!!
        )
        assertFalse(
            UsageEligibility.isGamingEligible(
                15.0,
                50.0,
                20.0,
                0.0,
                UsageEligibility.CLOUD_STANDARD_DOWNLOAD_MBPS,
                40.0,
                pingLimitIsInclusive = true
            )!!
        )
        assertFalse(
            UsageEligibility.isGamingEligible(
                15.0,
                30.0,
                32.0,
                0.0,
                UsageEligibility.CLOUD_STANDARD_DOWNLOAD_MBPS,
                40.0,
                pingLimitIsInclusive = true
            )!!
        )
        assertFalse(
            UsageEligibility.isGamingEligible(
                25.0,
                20.0,
                10.0,
                0.125,
                UsageEligibility.CLOUD_HIGH_DOWNLOAD_MBPS,
                UsageEligibility.CLOUD_HIGH_MAX_PING_MS,
                pingLimitIsInclusive = false
            )!!
        )
    }

    @Test
    fun multimediaUsesInclusiveDownloadThresholds() {
        assertTrue(UsageEligibility.isEligible(25.0, UsageEligibility.UHD_DOWNLOAD_MBPS)!!)
        assertFalse(UsageEligibility.isEligible(24.99, UsageEligibility.UHD_DOWNLOAD_MBPS)!!)
        assertTrue(UsageEligibility.isEligible(5.0, UsageEligibility.HD_DOWNLOAD_MBPS)!!)
        assertTrue(UsageEligibility.isEligible(1.0, UsageEligibility.WEB_MUSIC_DOWNLOAD_MBPS)!!)
    }

    @Test
    fun unavailableMeasurementsRemainUnknown() {
        assertNull(UsageEligibility.isEligible(null, UsageEligibility.UHD_DOWNLOAD_MBPS))
        assertNull(
            UsageEligibility.isEligible(
                25.0,
                UsageEligibility.CLOUD_HIGH_DOWNLOAD_MBPS,
                null,
                UsageEligibility.CLOUD_HIGH_MAX_PING_MS
            )
        )
    }

    @Test
    fun downloadTimeUsesMegabytesAndMegabitsConversion() {
        assertEquals(4.0, UsageEligibility.estimatedDownloadSeconds(5.0, 10.0)!!, 0.0)
        assertEquals(1_200.0, UsageEligibility.estimatedDownloadSeconds(1_500.0, 10.0)!!, 0.0)
        assertEquals(5_600.0, UsageEligibility.estimatedDownloadSeconds(7_000.0, 10.0)!!, 0.0)
        assertNull(UsageEligibility.estimatedDownloadSeconds(5.0, null))
    }

    @Test
    fun stabilityWarningOnlyAppearsForBadQualityWithPacketLoss() {
        assertTrue(UsageEligibility.shouldShowStabilityWarning(NetworkQuality.MAUVAIS, 0.125))
        assertFalse(UsageEligibility.shouldShowStabilityWarning(NetworkQuality.MAUVAIS, 0.0))
        assertFalse(UsageEligibility.shouldShowStabilityWarning(NetworkQuality.FAIBLE, 0.125))
    }
}

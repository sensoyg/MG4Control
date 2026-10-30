package com.mg4.control.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirmwareInfoTest {

    @Before
    fun setUp() {
        FirmwareInfo.resetCacheForTests()
    }

    @Test
    fun `parse standard SWI firmwares`() {
        assertEquals(FirmwareInfo.Gen.SWI165, FirmwareInfo.parseGeneration("SWI165-29176-1300R32"))
        assertEquals(FirmwareInfo.Gen.SWI133, FirmwareInfo.parseGeneration("SWI133-29176-1300R32"))
        assertEquals(FirmwareInfo.Gen.SWI132, FirmwareInfo.parseGeneration("SWI132-29176-1300R32"))
        assertEquals(FirmwareInfo.Gen.SWI131, FirmwareInfo.parseGeneration("SWI131-29176-1300R32"))
        assertEquals(FirmwareInfo.Gen.SWI68,  FirmwareInfo.parseGeneration("SWI68-29176-1300R32"))
        assertEquals(FirmwareInfo.Gen.SWI69,  FirmwareInfo.parseGeneration("SWI69-29176-1300R32"))
    }

    @Test
    fun `parse distributor and regional firmwares such as Turkish DBI68`() {
        // Turkish market distributor firmware reported by user
        val turkishFirmware = "DBI68-29558-1X00RXX-20250530"
        val gen = FirmwareInfo.parseGeneration(turkishFirmware)
        assertEquals(FirmwareInfo.Gen.SWI68, gen)

        // ClusterModuleSettings must support this Turkish firmware
        assertTrue("Cluster module must be supported on Turkish DBI68 firmware",
            ClusterModuleSettings.supportedOn(gen))

        // Other distributor prefixes (DBI133, DBI165, DBI132, DBI131, DBI69)
        assertEquals(FirmwareInfo.Gen.SWI165, FirmwareInfo.parseGeneration("DBI165-12345"))
        assertEquals(FirmwareInfo.Gen.SWI133, FirmwareInfo.parseGeneration("DBI133-12345"))
        assertEquals(FirmwareInfo.Gen.SWI132, FirmwareInfo.parseGeneration("DBI132-12345"))
        assertEquals(FirmwareInfo.Gen.SWI131, FirmwareInfo.parseGeneration("DBI131-12345"))
        assertEquals(FirmwareInfo.Gen.SWI69,  FirmwareInfo.parseGeneration("DBI69-12345"))

        // Other 3-letter regional variants
        assertEquals(FirmwareInfo.Gen.SWI68,  FirmwareInfo.parseGeneration("THI68-12345"))
        assertEquals(FirmwareInfo.Gen.SWI68,  FirmwareInfo.parseGeneration("ASI68-12345"))
    }

    @Test
    fun `parse invalid or unknown firmware strings`() {
        assertEquals(FirmwareInfo.Gen.UNKNOWN, FirmwareInfo.parseGeneration(null))
        assertEquals(FirmwareInfo.Gen.UNKNOWN, FirmwareInfo.parseGeneration(""))
        assertEquals(FirmwareInfo.Gen.UNKNOWN, FirmwareInfo.parseGeneration("UNKNOWN_FW_1234"))
        assertFalse(ClusterModuleSettings.supportedOn(FirmwareInfo.parseGeneration("UNKNOWN_FW_1234")))
    }

    @Test
    fun `hasHeatFeatures correctly identifies supported firmwares including DBI68`() {
        val turkishGen = FirmwareInfo.parseGeneration("DBI68-29558-1X00RXX-20250530")
        assertEquals(FirmwareInfo.Gen.SWI68, turkishGen)

        // Directly verify with forceGeneration
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        FirmwareInfo.forceGeneration(context, turkishGen)
        assertTrue("Turkish DBI68 firmware must support heat features (seats and steering wheel)",
            FirmwareInfo.hasHeatFeatures(context))

        FirmwareInfo.forceGeneration(context, FirmwareInfo.Gen.SWI69)
        assertFalse("SWI69 standard trim has no heat features",
            FirmwareInfo.hasHeatFeatures(context))
    }
}


package receiptdsl.model

import kotlin.test.Test
import kotlin.test.assertEquals

public class PrinterProfilesTest {
    @Test
    public fun factoriesExposeAllSupportedModelsInStrictMode() {
        val profiles = listOf(
            PrinterProfiles.fvp10(),
            PrinterProfiles.tmT90Kp(),
            PrinterProfiles.tmM30(),
            PrinterProfiles.tsp650II(),
            PrinterProfiles.mcPrint3(),
            PrinterProfiles.rpF10(),
        )

        assertEquals(PrinterModel.entries.filterNot { it == PrinterModel.Custom }, profiles.map { it.model })
        assertEquals(setOf(CompatibilityMode.Strict), profiles.map { it.compatibilityMode }.toSet())
    }

    @Test
    public fun profileCapabilitiesExposeModelDifferences() {
        assertEquals(8, PrinterProfiles.tmM30().capabilities.maxWidthScale)
        assertEquals(6, PrinterProfiles.fvp10().capabilities.maxWidthScale)
        assertEquals(ReverseMode.Unsupported, PrinterProfiles.tmM30().capabilities.reverseMode)
        assertEquals(ReverseMode.ReorderLinesAndReverseGlyphs, PrinterProfiles.rpF10().capabilities.reverseMode)
        assertEquals(SoundMode.DrawerKickPulse, PrinterProfiles.tmM30().capabilities.soundMode)
        assertEquals(SoundMode.ExternalBuzzer, PrinterProfiles.tsp650II().capabilities.soundMode)
    }
}

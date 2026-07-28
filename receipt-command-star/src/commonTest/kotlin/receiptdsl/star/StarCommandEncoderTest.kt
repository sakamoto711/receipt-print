package receiptdsl.star

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.encoding.ByteString
import receiptdsl.layout.LaidOutFeed
import receiptdsl.layout.LaidOutJob
import receiptdsl.layout.LaidOutQrCode
import receiptdsl.layout.LaidOutRule
import receiptdsl.layout.LaidOutTextLine
import receiptdsl.layout.LaidOutTicket
import receiptdsl.layout.SetStyle
import receiptdsl.layout.TextBytes
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrinterProfile
import receiptdsl.model.PrinterProfiles
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.ReverseMode
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class StarCommandEncoderTest {
    @Test
    public fun starLineEncodesInitializationSoundReverseTextAndPartialCut() {
        val result = StarLineModeCommandEncoder.encode(
            job = job(
                blocks = listOf(textLine("A")),
                startActions = listOf(JobAction.PlaySound),
                direction = PrintDirection.Reverse,
            ),
            profile = PrinterProfiles.fvp10(),
        )
        val bytes = result.bytes!!.toByteArray().unsigned()

        assertEquals(listOf(0x1B, 0x40, 0x1B, 0x73, 0x00, 0x00), bytes.take(6))
        assertTrue(bytes.containsSubsequence(listOf(0x1B, 0x1D, 0x73, 0x4F, 0x00, 0x01, 0x03, 0x01)))
        assertTrue(bytes.containsSubsequence(listOf(0x1B, 0x1D, 0x68, 0x30, 0x01, 0x00, 0x00)))
        assertTrue(bytes.containsSubsequence(listOf(0x41, 0x0A, 0x1B, 0x64, 0x03)))
        assertEquals(listOf(0x1B, 0x1D, 0x68, 0x30, 0x00, 0x00, 0x00), bytes.takeLast(7))
    }

    @Test
    public fun emitsAllThreeModelSpecificSoundSequences() {
        val fvp = encodedSound(PrinterProfiles.fvp10())
        val tsp = encodedSound(PrinterProfiles.tsp650II())
        val mc = encodedSound(PrinterProfiles.mcPrint3())

        assertTrue(fvp.containsSubsequence(listOf(0x1B, 0x1D, 0x73, 0x4F)))
        assertTrue(tsp.containsSubsequence(listOf(0x1B, 0x1D, 0x19, 0x11, 0x01, 0x32, 0x00)))
        assertTrue(tsp.containsSubsequence(listOf(0x1B, 0x1D, 0x19, 0x12, 0x01, 0x01, 0x00)))
        assertEquals(1, mc.count { it == 0x07 })
    }

    @Test
    public fun disabledSoundIsAnIntentionalWarningEvenInStrictMode() {
        val result = StarPrntCommandEncoder.encode(
            job = job(emptyList(), startActions = listOf(JobAction.PlaySound)),
            profile = PrinterProfiles.mcPrint3(
                compatibilityMode = CompatibilityMode.Strict,
                soundEnabled = false,
            ),
        )

        assertTrue(result.bytes != null)
        assertEquals(DiagnosticSeverity.Warning, result.diagnostics.single().severity)
        assertTrue(result.diagnostics.single().message.contains("sound disabled"))
    }

    @Test
    public fun starPrntInitializesTwiceAndMapsNoMarginToFullCut() {
        val result = StarPrntCommandEncoder.encode(
            job = job(listOf(textLine("A")), cut = CutMode.NoMargin),
            profile = PrinterProfiles.mcPrint3(compatibilityMode = CompatibilityMode.Compatibility),
        )
        val bytes = result.bytes!!.toByteArray().unsigned()

        assertEquals(listOf(0x1B, 0x40, 0x1B, 0x40, 0x1B, 0x73, 0x00, 0x00), bytes.take(8))
        assertEquals(listOf(0x1B, 0x64, 0x02), bytes.takeLast(3))
    }

    @Test
    public fun strictQrAndRuleHonorLayoutData() {
        val result = StarLineModeCommandEncoder.encode(
            job = job(
                listOf(
                    LaidOutRule(1),
                    LaidOutQrCode(
                        data = ByteString.copyFrom(byteArrayOf(0x41)),
                        cellSize = 3,
                        errorCorrection = QrErrorCorrection.High,
                        align = TextAlign.Center,
                    ),
                    LaidOutFeed(1),
                ),
            ),
            profile = PrinterProfiles.tsp650II(compatibilityMode = CompatibilityMode.Strict),
        )
        val bytes = result.bytes!!.toByteArray().unsigned()

        val rule = bytes.indexOfSubsequence(listOf(0x1B, 0x4C, 0x40, 0x02))
        assertTrue(rule >= 0)
        assertTrue(bytes.subList(rule + 4, rule + 4 + 576).all { it == 0x08 })
        assertTrue(bytes.containsSubsequence(listOf(0x1B, 0x1D, 0x79, 0x53, 0x31, 0x03)))
        assertTrue(bytes.containsSubsequence(listOf(0x1B, 0x1D, 0x79, 0x53, 0x32, 0x03)))
    }

    @Test
    public fun returnsNoBytesForWrongStarCommandSet() {
        val result = StarPrntCommandEncoder.encode(job(emptyList()), PrinterProfiles.fvp10())

        assertNull(result.bytes)
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    public fun strictModeRejectsCompatibilityNoMarginFallback() {
        val result = StarLineModeCommandEncoder.encode(
            job(listOf(textLine("A")), cut = CutMode.NoMargin),
            PrinterProfiles.fvp10(compatibilityMode = CompatibilityMode.Strict),
        )

        assertNull(result.bytes)
        assertTrue(result.diagnostics.single().message.contains("cut:NoMargin"))
    }

    @Test
    public fun rejectsQrPayloadLongerThanUnsignedSixteenBitLength() {
        val result = StarPrntCommandEncoder.encode(
            job(
                listOf(
                    LaidOutQrCode(
                        data = ByteString.copyFrom(ByteArray(0x10000)),
                        cellSize = 6,
                        errorCorrection = QrErrorCorrection.Medium,
                        align = TextAlign.Center,
                    ),
                ),
                cut = CutMode.None,
            ),
            PrinterProfiles.mcPrint3(),
        )

        assertNull(result.bytes)
        assertTrue(result.diagnostics.single().message.contains("65535"))
    }

    @Test
    public fun compatibilityModeOmitsUnsupportedQrAndNativeReverseCommands() {
        val payload = "QR_PAYLOAD".encodeToByteArray()
        val baseProfile = PrinterProfiles.tsp650II(compatibilityMode = CompatibilityMode.Compatibility)
        val result = StarLineModeCommandEncoder.encode(
            job(
                blocks = listOf(
                    LaidOutQrCode(
                        data = ByteString.copyFrom(payload),
                        cellSize = 6,
                        errorCorrection = QrErrorCorrection.Medium,
                        align = TextAlign.Center,
                    ),
                ),
                direction = PrintDirection.Reverse,
                cut = CutMode.None,
            ),
            baseProfile.copy(
                capabilities = baseProfile.capabilities.copy(
                    supportsQrCode = false,
                    reverseMode = ReverseMode.Unsupported,
                ),
            ),
        )

        val bytes = result.bytes!!.toByteArray().unsigned()
        assertFalse(bytes.containsSubsequence(payload.unsigned()))
        assertFalse(bytes.containsSubsequence(listOf(0x1B, 0x1D, 0x68, 0x30, 0x01, 0x00, 0x00)))
        assertEquals(2, result.diagnostics.size)
    }

    private fun encodedSound(profile: PrinterProfile): List<Int> =
        (if (profile.commandSet == receiptdsl.model.CommandSet.StarPrnt) {
            StarPrntCommandEncoder
        } else {
            StarLineModeCommandEncoder
        }).encode(
            job = job(emptyList(), startActions = listOf(JobAction.PlaySound)),
            profile = profile,
        ).bytes!!.toByteArray().unsigned()

    private fun job(
        blocks: List<receiptdsl.layout.LaidOutBlock>,
        startActions: List<JobAction> = emptyList(),
        direction: PrintDirection = PrintDirection.Normal,
        cut: CutMode = CutMode.Partial,
    ): LaidOutJob = LaidOutJob(
        tickets = persistentListOf(LaidOutTicket(blocks.toPersistentList(), cut)),
        direction = direction,
        startActions = startActions.toPersistentList(),
    )

    private fun textLine(text: String, style: TextStyle = TextStyle.Default): LaidOutTextLine =
        LaidOutTextLine(
            persistentListOf(
                SetStyle(style),
                TextBytes(text, ByteString.copyFrom(text.encodeToByteArray())),
            ),
        )
}

private fun ByteArray.unsigned(): List<Int> = map { it.toInt() and 0xFF }

private fun List<Int>.containsSubsequence(needle: List<Int>): Boolean = indexOfSubsequence(needle) >= 0

private fun List<Int>.indexOfSubsequence(needle: List<Int>): Int {
    if (needle.isEmpty()) return 0
    return indices.firstOrNull { start ->
        start + needle.size <= size && subList(start, start + needle.size) == needle
    } ?: -1
}

package receiptdsl.escpos

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import receiptdsl.encoding.ByteString
import receiptdsl.layout.LaidOutFeed
import receiptdsl.layout.LaidOutJob
import receiptdsl.layout.LaidOutQrCode
import receiptdsl.layout.LaidOutRule
import receiptdsl.layout.LaidOutTextLine
import receiptdsl.layout.LaidOutTicket
import receiptdsl.layout.SetStyle
import receiptdsl.layout.TextBytes
import receiptdsl.model.CommandSet
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrinterProfiles
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class EscPosCommandEncoderTest {
    @Test
    public fun encodesInitializationSoundStyleTextAndCut() {
        val style = TextStyle(
            widthScale = 2,
            heightScale = 2,
            emphasized = true,
            inverted = true,
            align = TextAlign.Center,
        )
        val result = EscPosCommandEncoder.encode(
            job = job(
                blocks = listOf(textLine("A", style)),
                startActions = listOf(JobAction.PlaySound),
            ),
            profile = PrinterProfiles.tmM30(
                compatibilityMode = CompatibilityMode.Compatibility,
                optionalExternalBuzzerEnabled = true,
            ),
        )

        assertContentEquals(
            ints(
                0x1B, 0x40, 0x1B, 0x21, 0x00, 0x1B, 0x52, 0x08,
                0x1C, 0x26, 0x1C, 0x2E, 0x00, 0x1C, 0x43, 0x01,
                0x1B, 0x28, 0x41, 0x03, 0x00, 0x61, 0x01, 0x01,
                0x1B, 0x61, 0x00, 0x1D, 0x21, 0x00, 0x1B, 0x45, 0x00, 0x1D, 0x42, 0x00,
                0x1B, 0x61, 0x01, 0x1D, 0x21, 0x11, 0x1B, 0x45, 0x01, 0x1D, 0x42, 0x01,
                0x41, 0x0A,
                0x1D, 0x56, 0x42, 0x00,
            ),
            result.bytes?.toByteArray(),
        )
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    public fun reversesLineOrderAndEmitsTmT90NoMarginCut() {
        val result = EscPosCommandEncoder.encode(
            job = job(
                blocks = listOf(textLine("A"), textLine("B")),
                direction = PrintDirection.Reverse,
                cut = CutMode.NoMargin,
            ),
            profile = PrinterProfiles.tmT90Kp(compatibilityMode = CompatibilityMode.Compatibility),
        )
        val bytes = result.bytes!!.toByteArray().unsigned()

        val reverseStart = bytes.indexOfSubsequence(listOf(0x1B, 0x7B, 0x01))
        val b = bytes.indexOfFrom(0x42, reverseStart)
        val a = bytes.indexOfFrom(0x41, b + 1)
        val cut = bytes.indexOfSubsequence(listOf(0x1D, 0x56, 0x62, 0x0A, 0x0A))
        assertTrue(reverseStart >= 0)
        assertTrue(b in (reverseStart + 1) until a)
        assertTrue(cut > a)
        assertEquals(listOf(0x1B, 0x7B, 0x00), bytes.takeLast(3))
    }

    @Test
    public fun strictQrHonorsOptionsAndRuleEndsItsBitImageLine() {
        val result = EscPosCommandEncoder.encode(
            job = job(
                blocks = listOf(
                    LaidOutRule(thickness = 2),
                    LaidOutQrCode(
                        data = ByteString.copyFrom(byteArrayOf(0x41, 0x42)),
                        cellSize = 4,
                        errorCorrection = QrErrorCorrection.High,
                        align = TextAlign.Right,
                    ),
                    LaidOutFeed(1),
                ),
            ),
            profile = PrinterProfiles.rpF10(compatibilityMode = CompatibilityMode.Strict),
        )
        val bytes = result.bytes!!.toByteArray().unsigned()

        val rule = bytes.indexOfSubsequence(listOf(0x1B, 0x2A, 0x00, 0x20, 0x01))
        assertTrue(rule >= 0)
        assertTrue(bytes.subList(rule + 5, rule + 5 + 0x120).all { it == 0x18 })
        val ruleEnd = rule + 5 + 0x120
        assertEquals(0x0A, bytes[ruleEnd])
        val qrModel = listOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)
        assertTrue(bytes.indexOfSubsequence(qrModel) > ruleEnd)
        assertTrue(bytes.containsSubsequence(listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x33)))
        assertTrue(bytes.containsSubsequence(listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x04)))
    }

    @Test
    public fun returnsNoBytesForWrongCommandSet() {
        val profile = PrinterProfiles.fvp10()
        val result = EscPosCommandEncoder.encode(job(emptyList()), profile)

        assertNull(result.bytes)
        assertFalse(result.isSuccess)
        assertEquals(CommandSet.StarLineMode, profile.commandSet)
    }

    @Test
    public fun strictModeDistinguishesFullAndPartialCut() {
        val full = EscPosCommandEncoder.encode(
            job(listOf(textLine("A")), cut = CutMode.Full),
            PrinterProfiles.rpF10(compatibilityMode = CompatibilityMode.Strict),
        )
        val partial = EscPosCommandEncoder.encode(
            job(listOf(textLine("A")), cut = CutMode.Partial),
            PrinterProfiles.rpF10(compatibilityMode = CompatibilityMode.Strict),
        )

        assertContentEquals(ints(0x1D, 0x56, 0x00), full.bytes!!.toByteArray().takeLast(3).toByteArray())
        assertContentEquals(ints(0x1D, 0x56, 0x01), partial.bytes!!.toByteArray().takeLast(3).toByteArray())
    }

    @Test
    public fun rejectsQrPayloadThatCannotFitCommandLength() {
        val result = EscPosCommandEncoder.encode(
            job(
                blocks = listOf(
                    LaidOutQrCode(
                        data = ByteString.copyFrom(ByteArray(0xFFFF - 2)),
                        cellSize = 6,
                        errorCorrection = QrErrorCorrection.Medium,
                        align = TextAlign.Center,
                    ),
                ),
                cut = CutMode.None,
            ),
            PrinterProfiles.rpF10(),
        )

        assertNull(result.bytes)
        assertTrue(result.diagnostics.single().message.contains("65532"))
    }

    @Test
    public fun compatibilityModeWarnsAndOmitsQrCommandsWhenTheProfileDoesNotSupportQr() {
        val payload = "QR_PAYLOAD".encodeToByteArray()
        val baseProfile = PrinterProfiles.rpF10(compatibilityMode = CompatibilityMode.Compatibility)
        val result = EscPosCommandEncoder.encode(
            job(
                blocks = listOf(
                    LaidOutQrCode(
                        data = ByteString.copyFrom(payload),
                        cellSize = 6,
                        errorCorrection = QrErrorCorrection.Medium,
                        align = TextAlign.Center,
                    ),
                ),
                cut = CutMode.None,
            ),
            baseProfile.copy(
                capabilities = baseProfile.capabilities.copy(supportsQrCode = false),
            ),
        )

        val bytes = result.bytes!!.toByteArray().unsigned()
        assertFalse(bytes.containsSubsequence(payload.unsigned()))
        assertTrue(result.diagnostics.single().message.contains("qr-code"))
    }

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

private fun ints(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.unsigned(): List<Int> = map { it.toInt() and 0xFF }

private fun List<Int>.containsSubsequence(needle: List<Int>): Boolean = indexOfSubsequence(needle) >= 0

private fun List<Int>.indexOfSubsequence(needle: List<Int>): Int {
    if (needle.isEmpty()) return 0
    return indices.firstOrNull { start ->
        start + needle.size <= size && subList(start, start + needle.size) == needle
    } ?: -1
}

private fun List<Int>.indexOfFrom(value: Int, startIndex: Int): Int =
    indices.firstOrNull { it >= startIndex && this[it] == value } ?: -1

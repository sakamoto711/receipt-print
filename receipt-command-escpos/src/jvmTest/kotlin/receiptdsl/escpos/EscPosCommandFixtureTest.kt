package receiptdsl.escpos

import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertTrue
import receiptdsl.encoding.ByteString
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
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle

class EscPosCommandFixtureTest {
    @Test
    fun allEscPosProfilesMatchTheirPinnedPrimitiveFixtures() {
        val cases = listOf(
            Case("epson-tm-t90kp.json", PrinterProfiles.tmT90Kp(compatibilityMode = CompatibilityMode.Compatibility)),
            Case(
                "epson-tm-m30.json",
                PrinterProfiles.tmM30(
                    compatibilityMode = CompatibilityMode.Compatibility,
                    optionalExternalBuzzerEnabled = true,
                ),
            ),
            Case("sii-rp-f10.json", PrinterProfiles.rpF10(compatibilityMode = CompatibilityMode.Compatibility)),
        )

        cases.forEach { case ->
            val fixture = CommandFixture.load(case.fileName)
            val init = EscPosCommandEncoder.encode(ticketJob(cut = CutMode.None), case.profile).bytes!!.toByteArray()
            assertTrue(init.startsWith(fixture.bytes("session-init")), case.fileName)

            val sound = EscPosCommandEncoder.encode(
                LaidOutJob(
                    tickets = persistentListOf(LaidOutTicket(persistentListOf(), CutMode.None)),
                    direction = PrintDirection.Normal,
                    startActions = persistentListOf(JobAction.PlaySound),
                ),
                case.profile,
            ).bytes!!.toByteArray()
            assertTrue(sound.contains(fixture.bytes("sound")), "${case.fileName}:sound")

            val styled = EscPosCommandEncoder.encode(styledTextJob(), case.profile).bytes!!.toByteArray()
            listOf(
                "ticket-style-reset",
                "align-center",
                "scale-2x2",
                "emphasis-on",
                "inverse-on",
                "cp932-日本-lf",
            ).forEach { operation ->
                assertTrue(styled.contains(fixture.bytes(operation)), "${case.fileName}:$operation")
            }

            val qr = EscPosCommandEncoder.encode(qrJob(), case.profile).bytes!!.toByteArray()
            assertTrue(qr.contains(fixture.bytes("qr-A-compatibility")), "${case.fileName}:qr")

            val rule = EscPosCommandEncoder.encode(ruleJob(), case.profile).bytes!!.toByteArray()
            assertTrue(rule.contains(fixture.bytes("rule-thin")), "${case.fileName}:rule")

            listOf(CutMode.Partial, CutMode.Full, CutMode.NoMargin).forEach { cutMode ->
                val cutOperation = when {
                    case.fileName == "epson-tm-t90kp.json" && cutMode == CutMode.NoMargin ->
                        "cut-no-margin"
                    cutMode == CutMode.Partial -> "cut-partial-compatibility"
                    cutMode == CutMode.Full -> "cut-full-compatibility"
                    else -> "cut-no-margin-compatibility"
                }
                val cut = EscPosCommandEncoder.encode(ticketJob(cut = cutMode), case.profile)
                    .bytes!!
                    .toByteArray()
                assertTrue(cut.endsWith(fixture.bytes(cutOperation)), "${case.fileName}:$cutMode")
            }
        }
    }

    @Test
    fun reverseFixtureDistinguishesSupportedAndIgnoredModels() {
        listOf(
            Case("epson-tm-t90kp.json", PrinterProfiles.tmT90Kp(compatibilityMode = CompatibilityMode.Compatibility)),
            Case("sii-rp-f10.json", PrinterProfiles.rpF10(compatibilityMode = CompatibilityMode.Compatibility)),
        ).forEach { case ->
            val fixture = CommandFixture.load(case.fileName)
            val result = EscPosCommandEncoder.encode(
                ticketJob(direction = PrintDirection.Reverse),
                case.profile,
            )
            val bytes = result.bytes!!.toByteArray()
            assertTrue(bytes.contains(fixture.bytes("reverse-on")))
            assertTrue(bytes.endsWith(fixture.bytes("reverse-off")))
        }

        val unsupported = EscPosCommandEncoder.encode(
            ticketJob(direction = PrintDirection.Reverse),
            PrinterProfiles.tmM30(compatibilityMode = CompatibilityMode.Compatibility),
        )
        assertTrue(unsupported.diagnostics.any { it.code == "UNSUPPORTED_FEATURE" })
    }

    private fun ticketJob(
        cut: CutMode = CutMode.Partial,
        direction: PrintDirection = PrintDirection.Normal,
    ): LaidOutJob = LaidOutJob(
        tickets = persistentListOf(LaidOutTicket(persistentListOf(), cut)),
        direction = direction,
        startActions = persistentListOf(),
    )

    private fun styledTextJob(): LaidOutJob {
        val style = TextStyle(
            widthScale = 2,
            heightScale = 2,
            emphasized = true,
            inverted = true,
            align = TextAlign.Center,
        )
        return LaidOutJob(
            tickets = persistentListOf(
                LaidOutTicket(
                    persistentListOf(
                        LaidOutTextLine(
                            persistentListOf(
                                SetStyle(style),
                                TextBytes("日本", ByteString.copyFrom(byteArrayOf(0x93.toByte(), 0xFA.toByte(), 0x96.toByte(), 0x7B))),
                            ),
                        ),
                    ),
                    CutMode.None,
                ),
            ),
            direction = PrintDirection.Normal,
            startActions = persistentListOf(),
        )
    }

    private fun qrJob(): LaidOutJob = LaidOutJob(
        tickets = persistentListOf(
            LaidOutTicket(
                persistentListOf(
                    LaidOutQrCode(
                        data = ByteString.copyFrom(byteArrayOf(0x41)),
                        cellSize = 6,
                        errorCorrection = QrErrorCorrection.Medium,
                        align = TextAlign.Center,
                    ),
                ),
                CutMode.None,
            ),
        ),
        direction = PrintDirection.Normal,
        startActions = persistentListOf(),
    )

    private fun ruleJob(): LaidOutJob = LaidOutJob(
        tickets = persistentListOf(
            LaidOutTicket(persistentListOf(LaidOutRule(thickness = 1)), CutMode.None),
        ),
        direction = PrintDirection.Normal,
        startActions = persistentListOf(),
    )

    private data class Case(val fileName: String, val profile: PrinterProfile)
}

private class CommandFixture(private val source: String) {
    fun bytes(operation: String): ByteArray {
        val objectText = Regex(
            """\{[^{}]*"operation"\s*:\s*"${Regex.escape(operation)}"[^{}]*}""",
        ).find(source)?.value ?: error("Missing operation $operation")
        val hex = Regex(""""bytesHex"\s*:\s*"([0-9a-f]*)"""")
            .find(objectText)?.groupValues?.get(1) ?: error("Missing bytesHex for $operation")
        val prefix = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val repeatHex = Regex(""""repeatByteHex"\s*:\s*"([0-9a-f]{2})"""")
            .find(objectText)?.groupValues?.get(1) ?: return prefix
        val repeatCount = Regex(""""repeatCount"\s*:\s*(\d+)""")
            .find(objectText)?.groupValues?.get(1)?.toInt()
            ?: error("Missing repeatCount for $operation")
        return prefix + ByteArray(repeatCount) { repeatHex.toInt(16).toByte() }
    }

    companion object {
        fun load(fileName: String): CommandFixture {
            val root = requireNotNull(System.getProperty("receipt.fixtures.dir"))
            return CommandFixture(File(root, "commands/$fileName").readText())
        }
    }
}

private fun ByteArray.contains(needle: ByteArray): Boolean =
    needle.isEmpty() || indices.any { start ->
        start + needle.size <= size && copyOfRange(start, start + needle.size).contentEquals(needle)
    }

private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
    suffix.size <= size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    prefix.size <= size && copyOfRange(0, prefix.size).contentEquals(prefix)

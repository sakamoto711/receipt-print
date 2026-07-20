package receiptdsl.star

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
import receiptdsl.model.CommandSet
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrinterProfile
import receiptdsl.model.PrinterProfiles
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle

class StarCommandFixtureTest {
    @Test
    fun allStarProfilesMatchTheirPinnedPrimitiveFixtures() {
        val cases = listOf(
            Case("star-fvp10.json", PrinterProfiles.fvp10(compatibilityMode = CompatibilityMode.Compatibility)),
            Case("star-tsp650ii.json", PrinterProfiles.tsp650II(compatibilityMode = CompatibilityMode.Compatibility)),
            Case("star-mc-print3.json", PrinterProfiles.mcPrint3(compatibilityMode = CompatibilityMode.Compatibility)),
        )

        cases.forEach { case ->
            val fixture = StarFixture.load(case.fileName)
            val encoder = encoder(case.profile)
            val initialized = encoder.encode(ticketJob(CutMode.None), case.profile).bytes!!.toByteArray()
            assertTrue(initialized.startsWith(fixture.bytes("session-init")), case.fileName)

            val sound = encoder.encode(
                LaidOutJob(
                    tickets = persistentListOf(LaidOutTicket(persistentListOf(), CutMode.None)),
                    direction = PrintDirection.Normal,
                    startActions = persistentListOf(JobAction.PlaySound),
                ),
                case.profile,
            ).bytes!!.toByteArray()
            assertTrue(sound.contains(fixture.bytes("sound")), "${case.fileName}:sound")

            val styled = encoder.encode(styledTextJob(), case.profile).bytes!!.toByteArray()
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

            val qr = encoder.encode(qrJob(), case.profile).bytes!!.toByteArray()
            assertTrue(qr.contains(fixture.bytes("qr-A-compatibility")), "${case.fileName}:qr")

            val rule = encoder.encode(ruleJob(), case.profile).bytes!!.toByteArray()
            assertTrue(rule.contains(fixture.bytes("rule-thin")), "${case.fileName}:rule")

            listOf(CutMode.Partial, CutMode.Full, CutMode.NoMargin).forEach { cutMode ->
                val cutOperation = when (cutMode) {
                    CutMode.Partial -> "cut-partial"
                    CutMode.Full -> "cut-full"
                    CutMode.NoMargin -> "cut-no-margin-compatibility"
                    CutMode.None -> error("None is not part of the cut fixture matrix")
                }
                val cut = encoder.encode(ticketJob(cutMode), case.profile).bytes!!.toByteArray()
                assertTrue(cut.endsWith(fixture.bytes(cutOperation)), "${case.fileName}:$cutMode")
            }

            val reverse = encoder.encode(
                ticketJob(CutMode.None, PrintDirection.Reverse),
                case.profile,
            ).bytes!!.toByteArray()
            assertTrue(reverse.contains(fixture.bytes("reverse-on")), "${case.fileName}:reverse-on")
            assertTrue(reverse.endsWith(fixture.bytes("reverse-off")), "${case.fileName}:reverse-off")
        }
    }

    private fun encoder(profile: PrinterProfile) =
        if (profile.commandSet == CommandSet.StarPrnt) StarPrntCommandEncoder else StarLineModeCommandEncoder

    private fun ticketJob(
        cut: CutMode,
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

private class StarFixture(private val source: String) {
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
        fun load(fileName: String): StarFixture {
            val root = requireNotNull(System.getProperty("receipt.fixtures.dir"))
            return StarFixture(File(root, "commands/$fileName").readText())
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

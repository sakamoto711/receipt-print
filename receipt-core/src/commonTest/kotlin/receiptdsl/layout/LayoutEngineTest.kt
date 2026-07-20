package receiptdsl.layout

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.NodePath
import receiptdsl.diagnostic.UnsupportedGlyph
import receiptdsl.diagnostic.UnsupportedFeature
import receiptdsl.diagnostic.ValueClamped
import receiptdsl.encoding.ByteString
import receiptdsl.encoding.EncodedText
import receiptdsl.encoding.TextCodec
import receiptdsl.encoding.TextMeasurement
import receiptdsl.encoding.UnsupportedGlyphPolicy
import receiptdsl.model.Column
import receiptdsl.model.CommandSet
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterCapabilities
import receiptdsl.model.PrinterProfile
import receiptdsl.model.QrCode
import receiptdsl.model.ReverseMode
import receiptdsl.model.Rule
import receiptdsl.model.SoundMode
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.Ticket
import receiptdsl.model.TwoColumnLine
import receiptdsl.model.TwoColumnOverflow
import receiptdsl.model.WrapMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class LayoutEngineTest {
    private val engine = DefaultLayoutEngine(TestTextCodec)

    @Test
    public fun wrapsTextByEncodedWidthAndPreservesResolvedStyle() {
        val style = TextStyle(emphasized = true)
        val result = engine.layout(
            job = job(TextBlock("AB日本C", style, wrap = WrapMode.Character)),
            profile = profile(columnsPerLine = 6),
        )

        val laidOut = assertNotNull(result.job)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(listOf("AB日本", "C"), renderedLines(laidOut))
        val firstStyle = laidOut.tickets.single().blocks
            .filterIsInstance<LaidOutTextLine>()
            .first()
            .operations
            .filterIsInstance<SetStyle>()
            .single()
            .style
        assertEquals(style, firstStyle)
    }

    @Test
    public fun preservesCrLfAndExplicitEmptyLinesButOmitsEmptyTextBlock() {
        val content = Column(
            children = persistentListOf(
                TextBlock(""),
                TextBlock("A\r\n\r\nB", wrap = WrapMode.Character),
            ),
        )

        val result = engine.layout(job(content), profile())

        assertEquals(listOf("A", "", "B"), renderedLines(assertNotNull(result.job)))
    }

    @Test
    public fun normalizesNewLinesWhenTheyAreNotPreserved() {
        val result = engine.layout(
            job = job(
                TextBlock(
                    text = "A\r\n\nB",
                    wrap = WrapMode.None,
                    preserveNewLines = false,
                ),
            ),
            profile = profile(),
        )

        assertEquals(listOf("A B"), renderedLines(assertNotNull(result.job)))
    }

    @Test
    public fun doesNotAddAnExtraEmptyLineAfterATrailingTerminator() {
        val result = engine.layout(
            job = job(TextBlock("A\n\n", wrap = WrapMode.Character)),
            profile = profile(),
        )

        assertEquals(listOf("A", ""), renderedLines(assertNotNull(result.job)))
    }

    @Test
    public fun wordWrapPrefersWhitespaceAndFallsBackToCharacterBoundaries() {
        val content = Column(
            children = persistentListOf(
                TextBlock("one two", wrap = WrapMode.WordOrCharacter),
                TextBlock("日本語", wrap = WrapMode.WordOrCharacter),
            ),
        )

        val result = engine.layout(job(content), profile(columnsPerLine = 5))

        assertEquals(
            listOf("one ", "two", "日本", "語"),
            renderedLines(assertNotNull(result.job)),
        )
    }

    @Test
    public fun keepsTicketBoundariesAndNonTextOperations() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        children = persistentListOf(
                            Rule(thickness = 2),
                            QrCode("QR"),
                            Feed(lines = 3),
                        ),
                    ),
                    cut = CutMode.Full,
                ),
            ),
            direction = PrintDirection.Reverse,
            startActions = persistentListOf(JobAction.PlaySound),
        )

        val result = engine.layout(job, profile())

        val laidOut = assertNotNull(result.job)
        assertEquals(PrintDirection.Reverse, laidOut.direction)
        assertEquals(persistentListOf(JobAction.PlaySound), laidOut.startActions)
        val ticket = laidOut.tickets.single()
        assertEquals(CutMode.Full, ticket.cut)
        assertIs<LaidOutRule>(ticket.blocks[0])
        assertIs<LaidOutQrCode>(ticket.blocks[1])
        assertIs<LaidOutFeed>(ticket.blocks[2])
        assertEquals(byteArrayOf(0x51, 0x52).toList(), (ticket.blocks[1] as LaidOutQrCode).data.toByteArray().toList())
    }

    @Test
    public fun compatibilityModeDropsUnsupportedGlyphAndAttachesNodePath() {
        val result = engine.layout(
            job = job(
                Column(
                    children = persistentListOf(
                        TextBlock("A🙂B", wrap = WrapMode.Character),
                    ),
                ),
            ),
            profile = profile(mode = CompatibilityMode.Compatibility),
        )

        assertEquals(listOf("AB"), renderedLines(assertNotNull(result.job)))
        val diagnostic = result.diagnostics.filterIsInstance<UnsupportedGlyph>().single()
        assertEquals(DiagnosticSeverity.Warning, diagnostic.severity)
        assertEquals(
            NodePath(ticketIndex = 0, childIndexes = persistentListOf(0)),
            diagnostic.path,
        )
    }

    @Test
    public fun strictModeRejectsUnsupportedGlyph() {
        val result = engine.layout(
            job = job(TextBlock("A🙂B", wrap = WrapMode.Character)),
            profile = profile(),
        )

        assertNull(result.job)
        assertEquals(DiagnosticSeverity.Error, result.diagnostics.single().severity)
    }

    @Test
    public fun compatibilityModeKeepsLineBoundaryWhenEveryGlyphIsDropped() {
        val result = engine.layout(
            job = job(TextBlock("🙂", wrap = WrapMode.Character)),
            profile = profile(mode = CompatibilityMode.Compatibility),
        )

        assertEquals(listOf(""), renderedLines(assertNotNull(result.job)))
        assertEquals(1, result.diagnostics.filterIsInstance<UnsupportedGlyph>().size)
    }

    @Test
    public fun rightAlignsTrailingTextAndMovesItWhenTheLeadingTailCannotFit() {
        val sameLine = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("品"),
                    trailing = TextRun("12"),
                ),
            ),
            profile = profile(columnsPerLine = 10),
        )
        val nextLine = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("ABCDEFGHI"),
                    trailing = TextRun("99"),
                ),
            ),
            profile = profile(columnsPerLine = 10),
        )

        assertEquals(listOf("品      12"), renderedLines(assertNotNull(sameLine.job)))
        assertEquals(
            listOf("ABCDEFGHI", "        99"),
            renderedLines(assertNotNull(nextLine.job)),
        )
    }

    @Test
    public fun convertsMixedWidthScalesToPhysicalColumns() {
        val content = TwoColumnLine(
            leading = TextRun("A", style = TextStyle(widthScale = 2)),
            trailing = TextRun("12"),
        )

        val result = engine.layout(job(content), profile(columnsPerLine = 10))

        assertEquals(listOf("A      12"), renderedLines(assertNotNull(result.job)))
    }

    @Test
    public fun restoresParentStyleAfterAnExplicitDefaultStyleScope() {
        val content = Column(
            children = persistentListOf(
                TextBlock("ABCD", wrap = WrapMode.Character),
                Column(
                    children = persistentListOf(
                        TextBlock("ABCDEFGH", wrap = WrapMode.Character),
                    ),
                    style = TextStyle.Default,
                ),
                TextBlock("EFGH", wrap = WrapMode.Character),
            ),
            style = TextStyle(widthScale = 2),
        )

        val result = engine.layout(job(content), profile(columnsPerLine = 8))

        val laidOut = assertNotNull(result.job)
        val scales = laidOut.tickets.single().blocks
            .filterIsInstance<LaidOutTextLine>()
            .map { line -> line.operations.filterIsInstance<SetStyle>().single().style.widthScale }
        assertEquals(listOf(2, 1, 2), scales)
    }

    @Test
    public fun appliesReservedPaddingAndInverseDecorationBeforeAlignment() {
        val reserved = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("A"),
                    trailing = TextRun("1"),
                    leadingReservedColumns = 3,
                    trailingReservedColumns = 3,
                ),
            ),
            profile = profile(columnsPerLine = 10),
        )
        val inverted = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("A", style = TextStyle(inverted = true)),
                    trailing = TextRun("1"),
                ),
            ),
            profile = profile(columnsPerLine = 10),
        )

        assertEquals(listOf("  A    1  "), renderedLines(assertNotNull(reserved.job)))
        assertEquals(listOf(" A       1"), renderedLines(assertNotNull(inverted.job)))
    }

    @Test
    public fun supportsExplicitTrailingNextLineAndFailOverflowModes() {
        val nextLine = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("left"),
                    trailing = TextRun("1"),
                    overflow = TwoColumnOverflow.TrailingOnNextLine,
                ),
            ),
            profile = profile(columnsPerLine = 10),
        )
        val fittingFail = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("left"),
                    trailing = TextRun("1"),
                    overflow = TwoColumnOverflow.Fail,
                ),
            ),
            profile = profile(columnsPerLine = 10),
        )
        val overflowingFail = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("123456"),
                    trailing = TextRun("789"),
                    overflow = TwoColumnOverflow.Fail,
                ),
            ),
            profile = profile(columnsPerLine = 8),
        )

        assertEquals(listOf("left", "         1"), renderedLines(assertNotNull(nextLine.job)))
        assertEquals(listOf("left     1"), renderedLines(assertNotNull(fittingFail.job)))
        assertNull(overflowingFail.job)
        assertTrue(
            overflowingFail.diagnostics.any { it.message.contains("with Fail overflow") },
        )
    }

    @Test
    public fun compatibilityModeKeepsLaterTrailingWrapsLeftAligned() {
        val content = TwoColumnLine(
            leading = TextRun(""),
            trailing = TextRun("1234567"),
        )

        val strict = engine.layout(job(content), profile(columnsPerLine = 5))
        val compatibility = engine.layout(
            job(content),
            profile(columnsPerLine = 5, mode = CompatibilityMode.Compatibility),
        )

        assertEquals(listOf("12345", "   67"), renderedLines(assertNotNull(strict.job)))
        assertEquals(listOf("12345", "67"), renderedLines(assertNotNull(compatibility.job)))
    }

    @Test
    public fun validatesReservedWidthAgainstEncodedText() {
        val content = TwoColumnLine(
            leading = TextRun("日本"),
            trailing = TextRun("1"),
            leadingReservedColumns = 2,
        )

        val strict = engine.layout(job(content), profile())
        val compatibility = engine.layout(job(content), profile(mode = CompatibilityMode.Compatibility))

        assertNull(strict.job)
        assertNotNull(compatibility.job)
        assertEquals(
            ValueClamped(
                property = "leadingReservedColumns",
                requested = 2,
                actual = 4,
                path = NodePath(ticketIndex = 0),
            ),
            compatibility.diagnostics.filterIsInstance<ValueClamped>().single(),
        )
    }

    @Test
    public fun leavesNoWrapTextUntouchedAndRejectsPendingClipPolicy() {
        val noWrap = engine.layout(
            job = job(TextBlock("12345", wrap = WrapMode.None)),
            profile = profile(columnsPerLine = 4),
        )
        val clip = engine.layout(
            job = job(
                TwoColumnLine(
                    leading = TextRun("left"),
                    trailing = TextRun("right"),
                    overflow = TwoColumnOverflow.Clip,
                ),
            ),
            profile = profile(columnsPerLine = 8),
        )

        assertEquals(listOf("12345"), renderedLines(assertNotNull(noWrap.job)))
        assertNull(clip.job)
        assertTrue(clip.diagnostics.any { it.message.contains("Clip policy") })
    }

    @Test
    public fun clampsWidthScaleToLayoutCapacityInCompatibilityMode() {
        val result = engine.layout(
            job = job(TextBlock("A", style = TextStyle(widthScale = 2))),
            profile = profile(
                columnsPerLine = 1,
                mode = CompatibilityMode.Compatibility,
            ),
        )

        val laidOut = assertNotNull(result.job)
        assertEquals(1, result.diagnostics.filterIsInstance<ValueClamped>().single().actual)
        val style = laidOut.tickets.single().blocks
            .filterIsInstance<LaidOutTextLine>()
            .single()
            .operations
            .filterIsInstance<SetStyle>()
            .single()
            .style
        assertEquals(1, style.widthScale)
    }

    @Test
    public fun clampsQrCellSizeToProfileLimitInCompatibilityMode() {
        val baseProfile = profile(mode = CompatibilityMode.Compatibility)
        val result = engine.layout(
            job = job(QrCode(data = "qr", cellSize = 9)),
            profile = baseProfile.copy(
                capabilities = baseProfile.capabilities.copy(maxQrCellSize = 8),
            ),
        )

        val qrCode = assertIs<LaidOutQrCode>(assertNotNull(result.job).tickets.single().blocks.single())
        assertEquals(8, qrCode.cellSize)
        assertEquals(8, result.diagnostics.filterIsInstance<ValueClamped>().single().actual)
    }

    @Test
    public fun compatibilityPrinterProfileUsesItsFixedQrCellSize() {
        val result = engine.layout(
            job = job(QrCode(data = "qr", cellSize = 4)),
            profile = receiptdsl.model.PrinterProfiles.fvp10(
                compatibilityMode = CompatibilityMode.Compatibility,
            ),
        )

        val qrCode = assertIs<LaidOutQrCode>(assertNotNull(result.job).tickets.single().blocks.single())
        assertEquals(6, qrCode.cellSize)
        assertEquals(6, result.diagnostics.filterIsInstance<ValueClamped>().single().actual)
    }

    @Test
    public fun compatibilityModeRemovesUnsupportedTextDecorationsAfterReportingWarnings() {
        val baseProfile = profile(mode = CompatibilityMode.Compatibility)
        val result = engine.layout(
            job = job(
                TextBlock(
                    text = "decorated",
                    style = TextStyle(emphasized = true, inverted = true),
                ),
            ),
            profile = baseProfile.copy(
                capabilities = baseProfile.capabilities.copy(
                    supportsEmphasis = false,
                    supportsInverse = false,
                ),
            ),
        )

        val style = assertNotNull(result.job).tickets.single().blocks
            .filterIsInstance<LaidOutTextLine>()
            .single()
            .operations
            .filterIsInstance<SetStyle>()
            .single()
            .style
        assertFalse(style.emphasized)
        assertFalse(style.inverted)
        assertEquals(
            setOf("emphasis", "inverse"),
            result.diagnostics.filterIsInstance<UnsupportedFeature>().map { it.feature }.toSet(),
        )
    }

    private fun job(content: receiptdsl.model.BlockNode): PrintJob =
        PrintJob(tickets = persistentListOf(Ticket(content = content)))

    private fun renderedLines(job: LaidOutJob): List<String> =
        job.tickets.flatMap { ticket ->
            ticket.blocks.filterIsInstance<LaidOutTextLine>().map { line ->
                line.operations.filterIsInstance<TextBytes>().joinToString("") { it.source }
            }
        }

    private fun profile(
        columnsPerLine: Int = 48,
        mode: CompatibilityMode = CompatibilityMode.Strict,
    ): PrinterProfile =
        PrinterProfile(
            id = "test-profile",
            commandSet = CommandSet.EscPos,
            columnsPerLine = columnsPerLine,
            capabilities = PrinterCapabilities(
                maxWidthScale = 6,
                maxHeightScale = 6,
                supportedCuts = persistentSetOf(
                    CutMode.Full,
                    CutMode.Partial,
                    CutMode.NoMargin,
                ),
                supportsQrCode = true,
                supportsEmphasis = true,
                supportsInverse = true,
                reverseMode = ReverseMode.NativePageReverse,
                soundMode = SoundMode.NativeBuzzer,
            ),
            compatibilityMode = mode,
        )

    private data object TestTextCodec : TextCodec {
        override fun encode(text: String, policy: UnsupportedGlyphPolicy): EncodedText {
            if (text == "🙂") {
                val severity = if (policy == UnsupportedGlyphPolicy.Fail) {
                    DiagnosticSeverity.Error
                } else {
                    DiagnosticSeverity.Warning
                }
                val bytes = if (policy == UnsupportedGlyphPolicy.Replace) {
                    ByteString.copyFrom(byteArrayOf(0x3f))
                } else {
                    ByteString.Empty
                }
                return EncodedText(
                    bytes = bytes,
                    diagnostics = persistentListOf(
                        UnsupportedGlyph(
                            codePoint = 0x1f642,
                            source = text,
                            severity = severity,
                            path = null,
                        ),
                    ),
                )
            }

            val bytes = mutableListOf<Byte>()
            text.forEach { character ->
                if (character.code <= 0x7f) {
                    bytes += character.code.toByte()
                } else {
                    bytes += 0x81.toByte()
                    bytes += (character.code and 0xff).toByte()
                }
            }
            return EncodedText(
                bytes = ByteString.copyFrom(bytes.toByteArray()),
                diagnostics = persistentListOf(),
            )
        }

        override fun measure(text: String, policy: UnsupportedGlyphPolicy): TextMeasurement {
            val encoded = encode(text, policy)
            return TextMeasurement(
                columns = encoded.bytes.size,
                diagnostics = encoded.diagnostics,
            )
        }
    }
}

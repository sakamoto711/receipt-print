package receiptdsl.receiptline

import kotlinx.collections.immutable.persistentListOf
import receiptdsl.model.Column
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.QrCode
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.Rule
import receiptdsl.model.TextAlign
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class ReceiptLineExporterTest {
    @Test
    public fun exportsAllCoreNodesAndEscapesReceiptLineSyntax() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        children = persistentListOf(
                            TextBlock(
                                text = "A|B\nC",
                                style = TextStyle(
                                    widthScale = 2,
                                    heightScale = 2,
                                    emphasized = true,
                                    inverted = true,
                                    align = TextAlign.Center,
                                ),
                                wrap = WrapMode.None,
                            ),
                            TwoColumnLine(TextRun("品名"), TextRun("100")),
                            Rule(),
                            QrCode("a;b", cellSize = 6, errorCorrection = QrErrorCorrection.High),
                            Feed(2),
                        ),
                    ),
                    cut = CutMode.Full,
                ),
            ),
        )

        val result = DefaultReceiptLineExporter.export(job)

        assertEquals(
            listOf(
                "{text:nowrap}",
                "| `\"^^^A\\|B |",
                "| `\"^^^C |",
                "{text:wrap}",
                "品名 | 100",
                "---",
                "{code:a\\;b; option:qrcode,6,h; align:center}",
                "",
                "",
                "=",
            ).joinToString("\n"),
            result.markdown,
        )
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    public fun reportsPreviewOnlyApproximations() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        persistentListOf(
                            TextBlock("scale", style = TextStyle(widthScale = 3, heightScale = 2)),
                            TwoColumnLine(
                                TextRun("left"),
                                TextRun("right"),
                                widthColumns = 20,
                                overflow = TwoColumnOverflow.TrailingOnNextLine,
                            ),
                            Rule(thickness = 2),
                            QrCode("qr", cellSize = 20),
                        ),
                    ),
                    cut = CutMode.Partial,
                ),
            ),
            direction = PrintDirection.Reverse,
            startActions = persistentListOf(JobAction.PlaySound),
        )

        val result = DefaultReceiptLineExporter.export(job)

        assertTrue(result.isSuccess)
        assertEquals(8, result.diagnostics.filterIsInstance<PreviewApproximation>().size)
    }

    @Test
    public fun rejectsInvalidPreviewProfile() {
        val result = DefaultReceiptLineExporter.export(
            PrintJob(persistentListOf(Ticket(TextBlock("x")))),
            PreviewProfile(columnsPerLine = 0),
        )

        assertNull(result.markdown)
        assertFalse(result.isSuccess)
    }

    @Test
    public fun rejectsInvalidPrintJobsBeforeProducingPreviewMarkdown() {
        val empty = DefaultReceiptLineExporter.export(PrintJob(persistentListOf()))
        assertNull(empty.markdown)
        assertFalse(empty.isSuccess)

        val malformed = DefaultReceiptLineExporter.export(
            PrintJob(
                persistentListOf(
                    Ticket(
                        Column(
                            persistentListOf(
                                Rule(thickness = 0),
                                QrCode(data = "", cellSize = 0),
                                Feed(lines = 0),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertNull(malformed.markdown)
        assertEquals(4, malformed.diagnostics.size)
    }

    @Test
    public fun rejectsTextThatThePrinterProfileCannotEncode() {
        val result = DefaultReceiptLineExporter.export(
            PrintJob(persistentListOf(Ticket(TextBlock("A🙂B")))),
        )

        assertNull(result.markdown)
        assertEquals("UNSUPPORTED_GLYPH", result.diagnostics.single().code)
    }

    @Test
    public fun preservesIntermediateBlankLinesButNotTrailingLineSplitterElement() {
        val result = DefaultReceiptLineExporter.export(
            PrintJob(
                persistentListOf(
                    Ticket(TextBlock("a\r\n\nb\n"), cut = CutMode.None),
                ),
            ),
        )

        assertEquals("a\n\nb", result.markdown)
    }
}

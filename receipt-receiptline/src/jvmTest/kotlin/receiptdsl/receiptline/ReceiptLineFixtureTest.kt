package receiptdsl.receiptline

import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import receiptdsl.model.Column
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
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
import receiptdsl.model.WrapMode

class ReceiptLineFixtureTest {
    @Test
    fun coreNodeSnapshotMatchesTheRepositoryFixture() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        persistentListOf(
                            TextBlock(
                                "A|B\nC",
                                TextStyle(
                                    widthScale = 2,
                                    heightScale = 2,
                                    emphasized = true,
                                    inverted = true,
                                    align = TextAlign.Center,
                                ),
                                WrapMode.None,
                            ),
                            TwoColumnLine(TextRun("品名"), TextRun("100")),
                            Rule(),
                            QrCode("a;b", 6, QrErrorCorrection.High),
                            Feed(2),
                        ),
                    ),
                    cut = CutMode.Full,
                ),
            ),
        )
        val fixtureRoot = requireNotNull(System.getProperty("receipt.fixtures.dir"))
        val expected = File(fixtureRoot, "previews/core-nodes.receiptline")
            .readText()
            .removeSuffix("\n")

        assertEquals(expected, DefaultReceiptLineExporter.export(job).markdown)
    }
}

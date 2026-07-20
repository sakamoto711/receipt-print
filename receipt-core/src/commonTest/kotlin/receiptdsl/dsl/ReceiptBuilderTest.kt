package receiptdsl.dsl

import kotlinx.collections.immutable.persistentListOf
import receiptdsl.model.Column
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.QrCode
import receiptdsl.model.Rule
import receiptdsl.model.TextAlign
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.TwoColumnLine
import receiptdsl.model.WrapMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

public class ReceiptBuilderTest {
    @Test
    public fun buildsImmutableTreeFromDsl() {
        val titleStyle = TextStyle(
            widthScale = 2,
            heightScale = 2,
            inverted = true,
            align = TextAlign.Center,
        )
        val sectionStyle = TextStyle(emphasized = true)

        val job = printJob {
            direction = PrintDirection.Reverse
            playSound()
            ticket(cut = CutMode.NoMargin) {
                text("追加注文", style = titleStyle)
                twoColumn(
                    leading = "スタッフ:坂本",
                    trailing = "12:34",
                    widthColumns = 48,
                    leadingReservedColumns = 24,
                    trailingReservedColumns = 8,
                )
                column(style = sectionStyle) {
                    rule(thickness = 2)
                    qrCode("receipt-id", cellSize = 5)
                    feed(lines = 2)
                }
            }
        }

        assertEquals(PrintDirection.Reverse, job.direction)
        assertEquals(persistentListOf(JobAction.PlaySound), job.startActions)
        assertEquals(CutMode.NoMargin, job.tickets.single().cut)
        assertEquals(
            Column(
                children = persistentListOf(
                    TextBlock(
                        text = "追加注文",
                        style = titleStyle,
                        wrap = WrapMode.Character,
                    ),
                    TwoColumnLine(
                        leading = TextRun("スタッフ:坂本"),
                        trailing = TextRun("12:34"),
                        widthColumns = 48,
                        leadingReservedColumns = 24,
                        trailingReservedColumns = 8,
                    ),
                    Column(
                        children = persistentListOf(
                            Rule(thickness = 2),
                            QrCode(data = "receipt-id", cellSize = 5),
                            Feed(lines = 2),
                        ),
                        style = sectionStyle,
                    ),
                ),
            ),
            job.tickets.single().content,
        )
    }

    @Test
    public fun usesDslDefaults() {
        val job = printJob {
            ticket {
                text("本文")
                twoColumn("左")
                rule()
                qrCode("qr")
                feed()
            }
        }

        assertEquals(PrintDirection.Normal, job.direction)
        assertEquals(CutMode.Partial, job.tickets.single().cut)
        assertEquals(persistentListOf(), job.startActions)

        val children = (job.tickets.single().content as Column).children
        assertEquals(WrapMode.Character, (children[0] as TextBlock).wrap)
        assertNull((children[1] as TwoColumnLine).trailing)
        assertEquals(Rule(), children[2])
        assertEquals(QrCode("qr"), children[3])
        assertEquals(Feed(), children[4])
    }

    @Test
    public fun keepsTicketCutBoundaries() {
        val job = printJob {
            ticket(cut = CutMode.NoMargin) { text("明細1") }
            ticket(cut = CutMode.Partial) { text("明細2") }
            ticket(cut = CutMode.None) { text("明細3") }
        }

        assertEquals(
            listOf(CutMode.NoMargin, CutMode.Partial, CutMode.None),
            job.tickets.map { it.cut },
        )
    }
}

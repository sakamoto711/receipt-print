package receiptdsl.dsl

import kotlinx.collections.immutable.toPersistentList
import receiptdsl.model.BlockNode
import receiptdsl.model.Column
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.QrCode
import receiptdsl.model.Rule
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.Ticket
import receiptdsl.model.TwoColumnLine
import receiptdsl.model.WrapMode

public fun printJob(block: PrintJobScope.() -> Unit): PrintJob =
    PrintJobScope().apply(block).build()

@ReceiptDsl
public class PrintJobScope internal constructor() {
    private val tickets: MutableList<Ticket> = mutableListOf()
    private val startActions: MutableList<JobAction> = mutableListOf()

    public var direction: PrintDirection = PrintDirection.Normal

    public fun playSound() {
        startActions += JobAction.PlaySound
    }

    public fun ticket(
        cut: CutMode = CutMode.Partial,
        block: ColumnScope.() -> Unit,
    ) {
        val content = ColumnScope().apply(block).build()
        tickets += Ticket(content = content, cut = cut)
    }

    internal fun build(): PrintJob =
        PrintJob(
            tickets = tickets.toPersistentList(),
            direction = direction,
            startActions = startActions.toPersistentList(),
        )
}

@ReceiptDsl
public class ColumnScope internal constructor() {
    private val children: MutableList<BlockNode> = mutableListOf()

    public fun text(
        value: String,
        style: TextStyle = TextStyle.Default,
        wrap: WrapMode = WrapMode.Character,
    ) {
        children += TextBlock(
            text = value,
            style = style,
            wrap = wrap,
        )
    }

    public fun twoColumn(
        leading: String,
        trailing: String? = null,
        leadingStyle: TextStyle = TextStyle.Default,
        trailingStyle: TextStyle = TextStyle.Default,
        widthColumns: Int? = null,
        leadingReservedColumns: Int? = null,
        trailingReservedColumns: Int? = null,
    ) {
        children += TwoColumnLine(
            leading = TextRun(leading, leadingStyle),
            trailing = trailing?.let { TextRun(it, trailingStyle) },
            widthColumns = widthColumns,
            leadingReservedColumns = leadingReservedColumns,
            trailingReservedColumns = trailingReservedColumns,
        )
    }

    public fun rule(thickness: Int = 1) {
        children += Rule(thickness)
    }

    public fun qrCode(data: String, cellSize: Int = 6) {
        children += QrCode(data = data, cellSize = cellSize)
    }

    public fun feed(lines: Int = 1) {
        children += Feed(lines)
    }

    public fun column(
        style: TextStyle? = null,
        block: ColumnScope.() -> Unit,
    ) {
        children += ColumnScope().apply(block).build(style)
    }

    internal fun build(style: TextStyle? = null): Column =
        Column(
            children = children.toPersistentList(),
            style = style,
        )
}

package receiptdsl.receiptline

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import receiptdsl.diagnostic.CompilationIssue
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.InvalidLayout
import receiptdsl.diagnostic.NodePath
import receiptdsl.model.BlockNode
import receiptdsl.model.Column
import receiptdsl.model.CommandSet
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterCapabilities
import receiptdsl.model.PrinterProfile
import receiptdsl.model.QrCode
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.Rule
import receiptdsl.model.ReverseMode
import receiptdsl.model.SoundMode
import receiptdsl.model.TextAlign
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.TwoColumnLine
import receiptdsl.model.TwoColumnOverflow
import receiptdsl.model.WrapMode
import receiptdsl.validation.DefaultPrintJobValidator

public interface ReceiptLineExporter {
    public fun export(job: PrintJob, profile: PreviewProfile = PreviewProfile()): ReceiptLineResult

    public fun export(job: PrintJob, profile: PrinterProfile): ReceiptLineResult
}

public data class PreviewProfile(
    val columnsPerLine: Int = 48,
)

public data class ReceiptLineResult(
    val markdown: String?,
    val diagnostics: PersistentList<Diagnostic>,
) {
    public val isSuccess: Boolean
        get() = markdown != null && diagnostics.none { it.severity == DiagnosticSeverity.Error }
}

public data class PreviewApproximation(
    val feature: String,
    override val path: NodePath?,
    override val code: String = "PREVIEW_APPROXIMATION",
    override val severity: DiagnosticSeverity = DiagnosticSeverity.Warning,
    override val message: String = "$feature is approximated in ReceiptLine preview",
) : CompilationIssue

public object DefaultReceiptLineExporter : ReceiptLineExporter {
    override fun export(job: PrintJob, profile: PreviewProfile): ReceiptLineResult {
        if (profile.columnsPerLine <= 0) {
            return ReceiptLineResult(
                markdown = null,
                diagnostics = listOf<Diagnostic>(
                    InvalidLayout(
                        reason = "PreviewProfile.columnsPerLine must be greater than zero",
                        path = null,
                    ),
                ).toPersistentList(),
            )
        }

        return export(job, previewPrinterProfile(profile))
    }

    override fun export(job: PrintJob, profile: PrinterProfile): ReceiptLineResult {
        val validation = DefaultPrintJobValidator().validate(job, profile)
        if (!validation.isSuccess) {
            return ReceiptLineResult(
                markdown = null,
                diagnostics = validation.diagnostics,
            )
        }

        val lines = mutableListOf<String>()
        val diagnostics = validation.diagnostics.toMutableList()
        if (job.direction == PrintDirection.Reverse) {
            diagnostics += PreviewApproximation("reverse printing", path = null)
        }
        if (job.startActions.isNotEmpty()) {
            diagnostics += PreviewApproximation("start actions", path = null)
        }

        job.tickets.forEachIndexed { ticketIndex, ticket ->
            appendNode(
                node = ticket.content,
                inheritedStyle = TextStyle.Default,
                path = NodePath(ticketIndex = ticketIndex),
                lines = lines,
                diagnostics = diagnostics,
            )
            appendCut(ticket.cut, NodePath(ticketIndex = ticketIndex), lines, diagnostics)
            if (ticketIndex < job.tickets.lastIndex && ticket.cut == CutMode.None) {
                lines += ""
            }
        }

        return ReceiptLineResult(
            markdown = lines.joinToString("\n"),
            diagnostics = diagnostics.toPersistentList(),
        )
    }

    private fun appendNode(
        node: BlockNode,
        inheritedStyle: TextStyle,
        path: NodePath,
        lines: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        when (node) {
            is Column -> {
                val style = node.style ?: inheritedStyle
                node.children.forEachIndexed { childIndex, child ->
                    appendNode(
                        node = child,
                        inheritedStyle = style,
                        path = path.child(childIndex),
                        lines = lines,
                        diagnostics = diagnostics,
                    )
                }
            }
            is TextBlock -> appendTextBlock(node, inheritedStyle, path, lines, diagnostics)
            is TwoColumnLine -> appendTwoColumn(node, inheritedStyle, path, lines, diagnostics)
            is Rule -> {
                lines += "---"
                if (node.thickness != 1) {
                    diagnostics += PreviewApproximation("rule thickness ${node.thickness}", path)
                }
            }
            is QrCode -> appendQrCode(node, path, lines, diagnostics)
            is Feed -> repeat(node.lines.coerceAtLeast(0)) { lines += "" }
        }
    }

    private fun appendTextBlock(
        block: TextBlock,
        inheritedStyle: TextStyle,
        path: NodePath,
        lines: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (block.text.isEmpty()) {
            return
        }
        val style = resolveStyle(inheritedStyle, block.style)
        if (block.wrap == WrapMode.None) {
            lines += "{text:nowrap}"
        }
        splitLines(block.text, block.preserveNewLines).forEach { value ->
            lines += alignText(
                decorateText(escapeText(value), style, path, diagnostics),
                style.align,
            )
        }
        if (block.wrap == WrapMode.None) {
            lines += "{text:wrap}"
        }
    }

    private fun appendTwoColumn(
        line: TwoColumnLine,
        inheritedStyle: TextStyle,
        path: NodePath,
        lines: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val leading = decorateRun(line.leading, inheritedStyle, path, diagnostics)
        val trailing = line.trailing?.let { decorateRun(it, inheritedStyle, path, diagnostics) }
        lines += if (trailing == null) leading else "$leading | $trailing"
        if (line.widthColumns != null ||
            line.leadingReservedColumns != null ||
            line.trailingReservedColumns != null
        ) {
            diagnostics += PreviewApproximation("explicit two-column widths", path)
        }
        if (line.overflow != TwoColumnOverflow.WrapLeadingThenTrailing) {
            diagnostics += PreviewApproximation("two-column overflow ${line.overflow.name}", path)
        }
    }

    private fun appendQrCode(
        qrCode: QrCode,
        path: NodePath,
        lines: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val cellSize = qrCode.cellSize.coerceIn(MIN_PREVIEW_QR_CELL_SIZE, MAX_PREVIEW_QR_CELL_SIZE)
        if (cellSize != qrCode.cellSize) {
            diagnostics += PreviewApproximation("QR cell size ${qrCode.cellSize}", path)
        }
        lines += buildString {
            append("{code:")
            append(escapeProperty(qrCode.data))
            append("; option:qrcode,")
            append(cellSize)
            append(',')
            append(qrErrorCorrection(qrCode.errorCorrection))
            append("; align:")
            append(qrCode.align.name.lowercase())
            append('}')
        }
    }

    private fun appendCut(
        cut: CutMode,
        path: NodePath,
        lines: MutableList<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        when (cut) {
            CutMode.None -> Unit
            CutMode.Full -> lines += "="
            CutMode.Partial,
            CutMode.NoMargin,
            -> {
                lines += "="
                diagnostics += PreviewApproximation("${cut.name} cut", path)
            }
        }
    }

    private fun decorateRun(
        run: TextRun,
        inheritedStyle: TextStyle,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ): String = decorateText(
        text = escapeText(run.text),
        style = resolveStyle(inheritedStyle, run.style),
        path = path,
        diagnostics = diagnostics,
    )

    private fun decorateText(
        text: String,
        style: TextStyle,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ): String {
        val marker = styleMarker(style, path, diagnostics)
        return buildString {
            if (style.inverted) append('`')
            if (style.emphasized) append('"')
            append(marker)
            append(text)
        }
    }

    private fun styleMarker(
        style: TextStyle,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ): String = when {
        style.widthScale == 1 && style.heightScale == 1 -> ""
        style.widthScale == 2 && style.heightScale == 1 -> "^"
        style.widthScale == 1 && style.heightScale == 2 -> "^^"
        style.widthScale == style.heightScale -> {
            val scale = style.widthScale.coerceIn(2, MAX_PREVIEW_SCALE)
            if (scale != style.widthScale) {
                diagnostics += PreviewApproximation("text scale ${style.widthScale}", path)
            }
            "^".repeat(scale + 1)
        }
        else -> {
            diagnostics += PreviewApproximation(
                "asymmetric text scale ${style.widthScale}x${style.heightScale}",
                path,
            )
            val scale = maxOf(style.widthScale, style.heightScale).coerceIn(2, MAX_PREVIEW_SCALE)
            "^".repeat(scale + 1)
        }
    }

    private fun alignText(text: String, align: TextAlign): String = when (align) {
        TextAlign.Left -> text
        TextAlign.Center -> "| $text |"
        TextAlign.Right -> "| $text"
    }

    private fun resolveStyle(inheritedStyle: TextStyle, localStyle: TextStyle): TextStyle =
        if (localStyle == TextStyle.Default) inheritedStyle else localStyle

    private fun splitLines(text: String, preserveNewLines: Boolean): List<String> {
        if (!preserveNewLines) {
            return listOf(text.replaceNewLinesWithSpaces())
        }
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var endedWithNewLine = false
        while (index < text.length) {
            val character = text[index]
            if (character == '\r' || character == '\n') {
                lines += current.toString()
                current.clear()
                endedWithNewLine = true
                if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                    index += 1
                }
            } else {
                current.append(character)
                endedWithNewLine = false
            }
            index += 1
        }
        if (!endedWithNewLine) {
            lines += current.toString()
        }
        return lines
    }

    private fun String.replaceNewLinesWithSpaces(): String {
        val result = StringBuilder()
        var index = 0
        while (index < length) {
            val character = this[index]
            if (character == '\r' || character == '\n') {
                if (result.isEmpty() || result.last() != ' ') result.append(' ')
                if (character == '\r' && index + 1 < length && this[index + 1] == '\n') index += 1
            } else {
                result.append(character)
            }
            index += 1
        }
        return result.toString()
    }

    private fun escapeText(value: String): String = buildString {
        value.forEach { character ->
            if (character in TEXT_SPECIAL_CHARACTERS) append('\\')
            append(character)
        }
    }

    private fun escapeProperty(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '\r' -> {
                    append("\\n")
                    if (index + 1 < value.length && value[index + 1] == '\n') index += 1
                }
                '\n' -> append("\\n")
                else -> {
                    if (character in PROPERTY_SPECIAL_CHARACTERS) append('\\')
                    append(character)
                }
            }
            index += 1
        }
    }

    private fun qrErrorCorrection(value: QrErrorCorrection): String = when (value) {
        QrErrorCorrection.Low -> "l"
        QrErrorCorrection.Medium -> "m"
        QrErrorCorrection.Quartile -> "q"
        QrErrorCorrection.High -> "h"
    }

    private fun NodePath.child(index: Int): NodePath =
        copy(childIndexes = childIndexes.add(index))

    private val TEXT_SPECIAL_CHARACTERS: Set<Char> =
        setOf('\\', '|', '{', '}', '-', '=', '~', '_', '"', '`', '^')
    private val PROPERTY_SPECIAL_CHARACTERS: Set<Char> = setOf('\\', '|', '{', '}', ';')
    private const val MIN_PREVIEW_QR_CELL_SIZE: Int = 3
    private const val MAX_PREVIEW_QR_CELL_SIZE: Int = 8
    private const val MAX_PREVIEW_SCALE: Int = 6
}

private fun previewPrinterProfile(profile: PreviewProfile): PrinterProfile = PrinterProfile(
    id = "receiptline-preview",
    commandSet = CommandSet.EscPos,
    columnsPerLine = profile.columnsPerLine,
    capabilities = PrinterCapabilities(
        maxWidthScale = profile.columnsPerLine,
        maxHeightScale = Int.MAX_VALUE,
        supportedCuts = persistentSetOf(CutMode.Full, CutMode.Partial, CutMode.NoMargin),
        supportsQrCode = true,
        supportsEmphasis = true,
        supportsInverse = true,
        reverseMode = ReverseMode.NativePageReverse,
        soundMode = SoundMode.NativeBuzzer,
        maxQrCellSize = Int.MAX_VALUE,
    ),
    compatibilityMode = CompatibilityMode.Strict,
)

package receiptdsl.layout

import kotlinx.collections.immutable.toPersistentList
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.InvalidLayout
import receiptdsl.diagnostic.NodePath
import receiptdsl.diagnostic.UnsupportedFeature
import receiptdsl.diagnostic.UnsupportedGlyph
import receiptdsl.diagnostic.ValueClamped
import receiptdsl.encoding.ByteString
import receiptdsl.encoding.Cp932TextCodec
import receiptdsl.encoding.TextCodec
import receiptdsl.encoding.UnsupportedGlyphPolicy
import receiptdsl.model.BlockNode
import receiptdsl.model.Column
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.Feed
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterProfile
import receiptdsl.model.QrCode
import receiptdsl.model.Rule
import receiptdsl.model.TextAlign
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.TwoColumnLine
import receiptdsl.model.TwoColumnOverflow
import receiptdsl.model.WrapMode
import receiptdsl.validation.DefaultPrintJobValidator
import receiptdsl.validation.PrintJobValidator

public interface LayoutEngine {
    public fun layout(job: PrintJob, profile: PrinterProfile): LayoutResult
}

public class DefaultLayoutEngine(
    private val textCodec: TextCodec = Cp932TextCodec,
    private val validator: PrintJobValidator = DefaultPrintJobValidator(),
) : LayoutEngine {
    override fun layout(job: PrintJob, profile: PrinterProfile): LayoutResult {
        val validation = validator.validate(job, profile)
        if (!validation.isSuccess) {
            return LayoutResult(job = null, diagnostics = validation.diagnostics)
        }

        val diagnostics = validation.diagnostics.toMutableList()
        val tickets = job.tickets.mapIndexed { ticketIndex, ticket ->
            LaidOutTicket(
                blocks = layoutNode(
                    node = ticket.content,
                    inheritedStyle = TextStyle.Default,
                    path = NodePath(ticketIndex = ticketIndex),
                    profile = profile,
                    diagnostics = diagnostics,
                ).toPersistentList(),
                cut = ticket.cut,
            )
        }.toPersistentList()
        val persistentDiagnostics = diagnostics.toPersistentList()
        val hasErrors = persistentDiagnostics.any { it.severity == DiagnosticSeverity.Error }
        return LayoutResult(
            job = if (hasErrors) {
                null
            } else {
                LaidOutJob(
                    tickets = tickets,
                    direction = job.direction,
                    startActions = job.startActions,
                )
            },
            diagnostics = persistentDiagnostics,
        )
    }

    private fun layoutNode(
        node: BlockNode,
        inheritedStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<LaidOutBlock> =
        when (node) {
            is Column -> {
                val style = effectiveStyle(node.style ?: inheritedStyle, profile)
                node.children.flatMapIndexed { childIndex, child ->
                    layoutNode(
                        node = child,
                        inheritedStyle = style,
                        path = path.child(childIndex),
                        profile = profile,
                        diagnostics = diagnostics,
                    )
                }
            }

            is TextBlock -> layoutTextBlock(
                block = node,
                inheritedStyle = inheritedStyle,
                path = path,
                profile = profile,
                diagnostics = diagnostics,
            )

            is TwoColumnLine -> layoutTwoColumn(
                line = node,
                inheritedStyle = inheritedStyle,
                path = path,
                profile = profile,
                diagnostics = diagnostics,
            )

            is Rule -> listOf(LaidOutRule(node.thickness))
            is QrCode -> listOf(layoutQrCode(node, path, profile, diagnostics))
            is Feed -> listOf(LaidOutFeed(node.lines))
        }

    private fun layoutTextBlock(
        block: TextBlock,
        inheritedStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<LaidOutBlock> {
        if (block.text.isEmpty()) {
            return emptyList()
        }

        val style = resolveStyle(inheritedStyle, block.style, profile)
        val containsNewLine = block.text.any { it == '\r' || it == '\n' }
        return splitParagraphs(block.text, block.preserveNewLines).flatMap { paragraph ->
            if (paragraph.isEmpty()) {
                if (containsNewLine) listOf(emptyTextLine()) else emptyList()
            } else {
                val glyphs = encodeGlyphs(paragraph, style, path, profile, diagnostics)
                val lines = wrapGlyphs(
                    glyphs = glyphs,
                    maximumWidth = profile.columnsPerLine,
                    wrapMode = block.wrap,
                    path = path,
                    diagnostics = diagnostics,
                )
                if (lines.isEmpty() && glyphs.isEmpty()) {
                    listOf(emptyTextLine())
                } else {
                    lines.map(::textLine)
                }
            }
        }
    }

    private fun layoutQrCode(
        qrCode: QrCode,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): LaidOutQrCode {
        val encoded = textCodec.encode(qrCode.data, glyphPolicy(profile))
        diagnostics += encoded.diagnostics.map { it.withPath(path) }
        return LaidOutQrCode(
            data = encoded.bytes,
            cellSize = if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
                profile.capabilities.compatibilityQrCellSize
                    ?: qrCode.cellSize.coerceAtMost(profile.capabilities.maxQrCellSize)
            } else {
                qrCode.cellSize
            },
            errorCorrection = qrCode.errorCorrection,
            align = qrCode.align,
        )
    }

    private fun layoutTwoColumn(
        line: TwoColumnLine,
        inheritedStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<LaidOutBlock> {
        val baseStyle = effectiveStyle(inheritedStyle, profile)
        val baseScale = baseStyle.widthScale
        val availableColumns = profile.columnsPerLine / baseScale
        val targetWidth = (line.widthColumns ?: availableColumns) * baseScale
        val leading = prepareRun(
            run = line.leading,
            reservedColumns = line.leadingReservedColumns,
            padBefore = true,
            property = "leadingReservedColumns",
            inheritedStyle = baseStyle,
            baseScale = baseScale,
            path = path,
            profile = profile,
            diagnostics = diagnostics,
        )
        val trailing = line.trailing
            ?.takeUnless { it.text.isEmpty() }
            ?.let { run ->
                prepareRun(
                    run = run,
                    reservedColumns = line.trailingReservedColumns,
                    padBefore = false,
                    property = "trailingReservedColumns",
                    inheritedStyle = baseStyle,
                    baseScale = baseScale,
                    path = path,
                    profile = profile,
                    diagnostics = diagnostics,
                )
            }.orEmpty()

        if (trailing.isEmpty()) {
            val lines = wrapByCharacter(leading, targetWidth, path, diagnostics)
                .ifEmpty { listOf(emptyList()) }
            return lines.map(::textLine)
        }

        return when (line.overflow) {
            TwoColumnOverflow.WrapLeadingThenTrailing -> combineWrappedColumns(
                leading,
                trailing,
                targetWidth,
                baseStyle,
                path,
                profile,
                diagnostics,
            )

            TwoColumnOverflow.TrailingOnNextLine -> {
                val leadingLines = wrapByCharacter(leading, targetWidth, path, diagnostics)
                    .ifEmpty { listOf(emptyList()) }
                val trailingLines = wrapByCharacter(trailing, targetWidth, path, diagnostics)
                    .map { glyphs ->
                        rightAlign(
                            glyphs,
                            targetWidth,
                            baseStyle,
                            path,
                            profile,
                            diagnostics,
                        )
                    }
                (leadingLines + trailingLines).map(::textLine)
            }

            TwoColumnOverflow.Fail -> layoutFailingTwoColumn(
                leading,
                trailing,
                targetWidth,
                baseStyle,
                path,
                profile,
                diagnostics,
            )

            TwoColumnOverflow.Clip -> {
                // TODO(Phase 2): 金額などのTrailingを優先するか、入力順の右端を切るかを
                // Interop DTOの利用例で決め、決定後に明示的なClip Policyとして実装する。
                diagnostics += InvalidLayout(
                    reason = "TwoColumnOverflow.Clip policy is not defined yet",
                    path = path,
                )
                combineWrappedColumns(
                    leading,
                    trailing,
                    targetWidth,
                    baseStyle,
                    path,
                    profile,
                    diagnostics,
                )
            }
        }
    }

    private fun layoutFailingTwoColumn(
        leading: List<EncodedGlyph>,
        trailing: List<EncodedGlyph>,
        targetWidth: Int,
        baseStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<LaidOutBlock> {
        val combinedWidth = glyphWidth(leading) + glyphWidth(trailing)
        if (combinedWidth > targetWidth.toLong()) {
            diagnostics += InvalidLayout(
                reason = "TwoColumnLine content exceeds its target width with Fail overflow",
                path = path,
            )
            return combineWrappedColumns(
                leading,
                trailing,
                targetWidth,
                baseStyle,
                path,
                profile,
                diagnostics,
            )
        }
        val padding = paddingGlyphs(
            physicalColumns = targetWidth - combinedWidth.toInt(),
            style = alignmentPaddingStyle(baseStyle),
            path = path,
            profile = profile,
            diagnostics = diagnostics,
        )
        return listOf(textLine(leading + padding + trailing))
    }

    private fun prepareRun(
        run: TextRun,
        reservedColumns: Int?,
        padBefore: Boolean,
        property: String,
        inheritedStyle: TextStyle,
        baseScale: Int,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<EncodedGlyph> {
        // Alignment is a line-level printer state. Keep the enclosing Column alignment for every
        // run and padding glyph so a reserved-width TwoColumn can itself be right/center aligned.
        val style = resolveStyle(inheritedStyle, run.style, profile).copy(align = inheritedStyle.align)
        val decoratedText = if (style.inverted && run.text.isNotEmpty()) {
            " ${run.text} "
        } else {
            run.text
        }
        val glyphs = encodeGlyphs(decoratedText, style, path, profile, diagnostics)
        if (reservedColumns == null) {
            return glyphs
        }

        val actualWidth = glyphWidth(glyphs)
        val requestedWidth = reservedColumns.toLong() * baseScale
        val effectiveWidth = if (requestedWidth < actualWidth) {
            val actualColumns = ((actualWidth + baseScale - 1) / baseScale)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
                diagnostics += ValueClamped(
                    property = property,
                    requested = reservedColumns,
                    actual = actualColumns,
                    path = path,
                )
            } else {
                diagnostics += InvalidLayout(
                    reason = "TwoColumnLine.$property must fit its encoded text",
                    path = path,
                )
            }
            actualWidth
        } else {
            requestedWidth
        }
        val padding = paddingGlyphs(
            physicalColumns = (effectiveWidth - actualWidth).toInt(),
            style = style.copy(widthScale = 1),
            path = path,
            profile = profile,
            diagnostics = diagnostics,
        )
        return if (padBefore) padding + glyphs else glyphs + padding
    }

    private fun combineWrappedColumns(
        leading: List<EncodedGlyph>,
        trailing: List<EncodedGlyph>,
        targetWidth: Int,
        baseStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<LaidOutBlock> {
        val leadingLines = wrapByCharacter(leading, targetWidth, path, diagnostics)
            .ifEmpty { listOf(emptyList()) }
        val trailingLines = wrapByCharacter(trailing, targetWidth, path, diagnostics)
        val result = mutableListOf<List<EncodedGlyph>>()
        result += leadingLines.dropLast(1)

        val leadingTail = leadingLines.last()
        val trailingHead = trailingLines.first()
        val combinedWidth = glyphWidth(leadingTail) + glyphWidth(trailingHead)
        if (combinedWidth <= targetWidth.toLong()) {
            result += leadingTail + paddingGlyphs(
                physicalColumns = targetWidth - combinedWidth.toInt(),
                style = alignmentPaddingStyle(baseStyle),
                path = path,
                profile = profile,
                diagnostics = diagnostics,
            ) + trailingHead
        } else {
            result += leadingTail
            result += rightAlign(
                trailingHead,
                targetWidth,
                baseStyle,
                path,
                profile,
                diagnostics,
            )
        }
        result += if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
            trailingLines.drop(1)
        } else {
            trailingLines.drop(1).map { glyphs ->
                rightAlign(
                    glyphs,
                    targetWidth,
                    baseStyle,
                    path,
                    profile,
                    diagnostics,
                )
            }
        }
        return result.map(::textLine)
    }

    private fun rightAlign(
        glyphs: List<EncodedGlyph>,
        targetWidth: Int,
        baseStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<EncodedGlyph> {
        val gap = (targetWidth.toLong() - glyphWidth(glyphs)).coerceAtLeast(0L).toInt()
        return paddingGlyphs(
            physicalColumns = gap,
            style = alignmentPaddingStyle(baseStyle),
            path = path,
            profile = profile,
            diagnostics = diagnostics,
        ) + glyphs
    }

    private fun alignmentPaddingStyle(style: TextStyle): TextStyle =
        style.copy(
            widthScale = 1,
            emphasized = false,
            inverted = false,
        )

    private fun paddingGlyphs(
        physicalColumns: Int,
        style: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<EncodedGlyph> {
        if (physicalColumns <= 0) {
            return emptyList()
        }

        val glyph = encodeGlyphs(" ", style, path, profile, diagnostics).singleOrNull()
        if (glyph == null || glyph.physicalColumns != 1) {
            diagnostics += InvalidLayout(
                reason = "TextCodec must encode an ASCII space as one column",
                path = path,
            )
            return emptyList()
        }
        return List(physicalColumns) { glyph }
    }

    private fun wrapGlyphs(
        glyphs: List<EncodedGlyph>,
        maximumWidth: Int,
        wrapMode: WrapMode,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ): List<List<EncodedGlyph>> =
        when (wrapMode) {
            WrapMode.None -> glyphs.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()

            WrapMode.Character -> wrapByCharacter(glyphs, maximumWidth, path, diagnostics)
            WrapMode.WordOrCharacter -> wrapByWordOrCharacter(
                glyphs,
                maximumWidth,
                path,
                diagnostics,
            )
        }

    private fun wrapByCharacter(
        glyphs: List<EncodedGlyph>,
        maximumWidth: Int,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ): List<List<EncodedGlyph>> {
        if (glyphs.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<List<EncodedGlyph>>()
        var currentLine = mutableListOf<EncodedGlyph>()
        var currentWidth = 0L
        glyphs.forEach { glyph ->
            val glyphWidth = glyph.physicalColumns.toLong()
            if (glyphWidth > maximumWidth.toLong()) {
                diagnostics += InvalidLayout(
                    reason = "A single encoded glyph exceeds the available width",
                    path = path,
                )
            }
            if (currentLine.isNotEmpty() && currentWidth + glyphWidth > maximumWidth.toLong()) {
                lines += currentLine
                currentLine = mutableListOf()
                currentWidth = 0L
            }
            currentLine += glyph
            currentWidth += glyphWidth
        }
        if (currentLine.isNotEmpty()) {
            lines += currentLine
        }
        return lines
    }

    private fun wrapByWordOrCharacter(
        glyphs: List<EncodedGlyph>,
        maximumWidth: Int,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ): List<List<EncodedGlyph>> {
        if (glyphs.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<List<EncodedGlyph>>()
        var startIndex = 0
        while (startIndex < glyphs.size) {
            var endIndex = startIndex
            var width = 0L
            var lastWordBoundary = -1
            while (endIndex < glyphs.size) {
                val nextWidth = glyphs[endIndex].physicalColumns.toLong()
                if (width + nextWidth > maximumWidth.toLong()) {
                    break
                }
                width += nextWidth
                endIndex += 1
                if (glyphs[endIndex - 1].source.all(Char::isWhitespace)) {
                    lastWordBoundary = endIndex
                }
            }

            if (endIndex == startIndex) {
                diagnostics += InvalidLayout(
                    reason = "A single encoded glyph exceeds the available width",
                    path = path,
                )
                endIndex += 1
            } else if (endIndex < glyphs.size && lastWordBoundary > startIndex) {
                endIndex = lastWordBoundary
            }
            lines += glyphs.subList(startIndex, endIndex)
            startIndex = endIndex
        }
        return lines
    }

    private fun encodeGlyphs(
        text: String,
        style: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ): List<EncodedGlyph> =
        unicodeScalars(text).mapNotNull { scalar ->
            val encoded = textCodec.encode(scalar, glyphPolicy(profile))
            diagnostics += encoded.diagnostics.map { it.withPath(path) }
            encoded.bytes.takeUnless(ByteString::isEmpty)?.let { bytes ->
                val physicalColumns = bytes.size.toLong() * style.widthScale
                if (physicalColumns > Int.MAX_VALUE) {
                    diagnostics += InvalidLayout(
                        reason = "Encoded glyph width exceeds the supported range",
                        path = path,
                    )
                }
                EncodedGlyph(
                    source = scalar,
                    bytes = bytes,
                    style = style,
                    physicalColumns = physicalColumns.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        }

    private fun textLine(glyphs: List<EncodedGlyph>): LaidOutTextLine {
        if (glyphs.isEmpty()) {
            return emptyTextLine()
        }

        val operations = mutableListOf<LineOp>()
        var groupStyle: TextStyle? = null
        val groupSource = StringBuilder()
        val groupBytes = mutableListOf<ByteString>()

        fun flushGroup() {
            val style = groupStyle ?: return
            operations += SetStyle(style)
            operations += TextBytes(
                source = groupSource.toString(),
                bytes = ByteString.concat(groupBytes),
            )
            groupSource.clear()
            groupBytes.clear()
        }

        glyphs.forEach { glyph ->
            if (groupStyle != null && groupStyle != glyph.style) {
                flushGroup()
            }
            groupStyle = glyph.style
            groupSource.append(glyph.source)
            groupBytes += glyph.bytes
        }
        flushGroup()
        return LaidOutTextLine(operations.toPersistentList())
    }

    private fun emptyTextLine(): LaidOutTextLine =
        LaidOutTextLine(emptyList<LineOp>().toPersistentList())

    private fun glyphWidth(glyphs: List<EncodedGlyph>): Long =
        glyphs.fold(0L) { width, glyph -> width + glyph.physicalColumns }

    private fun resolveStyle(
        inheritedStyle: TextStyle,
        localStyle: TextStyle,
        profile: PrinterProfile,
    ): TextStyle =
        effectiveStyle(
            style = if (localStyle == TextStyle.Default) inheritedStyle else localStyle,
            profile = profile,
        )

    private fun effectiveStyle(style: TextStyle, profile: PrinterProfile): TextStyle {
        val maximumWidthScale = minOf(
            profile.capabilities.maxWidthScale.coerceAtLeast(1),
            profile.columnsPerLine.coerceAtLeast(1),
        )
        return style.copy(
            widthScale = style.widthScale.coerceIn(1, maximumWidthScale),
            heightScale = style.heightScale.coerceIn(
                1,
                profile.capabilities.maxHeightScale.coerceAtLeast(1),
            ),
            emphasized = style.emphasized && profile.capabilities.supportsEmphasis,
            inverted = style.inverted && profile.capabilities.supportsInverse,
        )
    }

    private fun glyphPolicy(profile: PrinterProfile): UnsupportedGlyphPolicy =
        if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
            UnsupportedGlyphPolicy.Drop
        } else {
            UnsupportedGlyphPolicy.Fail
        }

    private fun splitParagraphs(text: String, preserveNewLines: Boolean): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        var endedWithNewLine = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\r' || character == '\n') {
                endedWithNewLine = true
                if (preserveNewLines) {
                    paragraphs += current.toString()
                    current.clear()
                } else if (current.isEmpty() || current.last() != ' ') {
                    current.append(' ')
                }
                if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                    index += 1
                }
            } else {
                endedWithNewLine = false
                current.append(character)
            }
            index += 1
        }
        if (!preserveNewLines || !endedWithNewLine) {
            paragraphs += current.toString()
        }
        return paragraphs
    }

    private fun unicodeScalars(text: String): List<String> {
        val scalars = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val scalarLength = if (
                first.isHighSurrogate() &&
                index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            ) {
                2
            } else {
                1
            }
            scalars += text.substring(index, index + scalarLength)
            index += scalarLength
        }
        return scalars
    }

    private fun Diagnostic.withPath(path: NodePath): Diagnostic =
        when (this) {
            is UnsupportedGlyph -> copy(path = path)
            is UnsupportedFeature -> copy(path = path)
            is InvalidLayout -> copy(path = path)
            is ValueClamped -> copy(path = path)
            else -> this
        }

    private fun NodePath.child(index: Int): NodePath =
        copy(childIndexes = childIndexes.add(index))

    private data class EncodedGlyph(
        val source: String,
        val bytes: ByteString,
        val style: TextStyle,
        val physicalColumns: Int,
    )
}

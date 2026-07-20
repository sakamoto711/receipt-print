package receiptdsl.validation

import kotlinx.collections.immutable.toPersistentList
import receiptdsl.diagnostic.CompilationResult
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.InvalidLayout
import receiptdsl.diagnostic.NodePath
import receiptdsl.diagnostic.UnsupportedFeature
import receiptdsl.diagnostic.ValueClamped
import receiptdsl.model.BlockNode
import receiptdsl.model.Column
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterProfile
import receiptdsl.model.QrCode
import receiptdsl.model.ReverseMode
import receiptdsl.model.Rule
import receiptdsl.model.SoundMode
import receiptdsl.model.TextBlock
import receiptdsl.model.TextStyle
import receiptdsl.model.TwoColumnLine

public interface PrintJobValidator {
    public fun validate(job: PrintJob, profile: PrinterProfile): CompilationResult<PrintJob>
}

public class DefaultPrintJobValidator : PrintJobValidator {
    override fun validate(job: PrintJob, profile: PrinterProfile): CompilationResult<PrintJob> {
        val diagnostics = mutableListOf<Diagnostic>()

        validateProfile(profile, diagnostics)

        if (job.tickets.isEmpty()) {
            diagnostics += InvalidLayout(
                reason = "PrintJob must contain at least one ticket",
                path = null,
            )
        }

        if (job.direction == PrintDirection.Reverse &&
            profile.capabilities.reverseMode == ReverseMode.Unsupported
        ) {
            diagnostics += unsupportedFeature("reverse", profile, path = null)
        }

        if (job.startActions.any { it == JobAction.PlaySound } &&
            profile.capabilities.soundMode == SoundMode.Unsupported
        ) {
            diagnostics += unsupportedFeature("sound", profile, path = null)
        }

        job.tickets.forEachIndexed { ticketIndex, ticket ->
            val rootPath = NodePath(ticketIndex = ticketIndex)

            if (ticket.cut != CutMode.None && ticket.cut !in profile.capabilities.supportedCuts) {
                diagnostics += unsupportedFeature(
                    feature = "cut:${ticket.cut.name}",
                    profile = profile,
                    path = rootPath,
                )
            }

            validateNode(
                node = ticket.content,
                inheritedStyle = TextStyle.Default,
                availableColumns = profile.columnsPerLine,
                path = rootPath,
                profile = profile,
                diagnostics = diagnostics,
            )
        }

        val persistentDiagnostics = diagnostics.toPersistentList()
        val hasErrors = persistentDiagnostics.any { it.severity == DiagnosticSeverity.Error }
        return CompilationResult(
            value = if (hasErrors) null else job,
            diagnostics = persistentDiagnostics,
        )
    }

    private fun validateProfile(
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (profile.id.isBlank()) {
            diagnostics += InvalidLayout(
                reason = "PrinterProfile.id must not be blank",
                path = null,
            )
        }
        if (profile.columnsPerLine <= 0) {
            diagnostics += InvalidLayout(
                reason = "PrinterProfile.columnsPerLine must be greater than zero",
                path = null,
            )
        }
        if (profile.capabilities.maxWidthScale < 1) {
            diagnostics += InvalidLayout(
                reason = "PrinterCapabilities.maxWidthScale must be at least one",
                path = null,
            )
        }
        if (profile.capabilities.maxHeightScale < 1) {
            diagnostics += InvalidLayout(
                reason = "PrinterCapabilities.maxHeightScale must be at least one",
                path = null,
            )
        }
        if (profile.capabilities.maxQrCellSize < 1) {
            diagnostics += InvalidLayout(
                reason = "PrinterCapabilities.maxQrCellSize must be at least one",
                path = null,
            )
        }
        profile.capabilities.compatibilityQrCellSize?.let { compatibilityQrCellSize ->
            if (compatibilityQrCellSize !in 1..profile.capabilities.maxQrCellSize) {
                diagnostics += InvalidLayout(
                    reason = "PrinterCapabilities.compatibilityQrCellSize must be within the supported QR cell-size range",
                    path = null,
                )
            }
        }
    }

    private fun validateNode(
        node: BlockNode,
        inheritedStyle: TextStyle,
        availableColumns: Int,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        when (node) {
            is Column -> validateColumn(
                column = node,
                inheritedStyle = inheritedStyle,
                path = path,
                profile = profile,
                diagnostics = diagnostics,
            )

            is TextBlock -> {
                val style = resolveStyle(inheritedStyle, node.style)
                if (node.style != TextStyle.Default || inheritedStyle == TextStyle.Default) {
                    validateStyle(style, path, profile, diagnostics)
                }
            }

            is TwoColumnLine -> validateTwoColumn(
                line = node,
                inheritedStyle = inheritedStyle,
                availableColumns = availableColumns,
                path = path,
                profile = profile,
                diagnostics = diagnostics,
            )

            is Rule -> {
                if (node.thickness < 1) {
                    diagnostics += InvalidLayout(
                        reason = "Rule.thickness must be at least one",
                        path = path,
                    )
                }
            }

            is QrCode -> validateQrCode(node, path, profile, diagnostics)

            is Feed -> {
                if (node.lines < 1) {
                    diagnostics += InvalidLayout(
                        reason = "Feed.lines must be at least one",
                        path = path,
                    )
                }
            }
        }
    }

    private fun validateColumn(
        column: Column,
        inheritedStyle: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val style = column.style ?: inheritedStyle
        column.style?.let { validateStyle(it, path, profile, diagnostics) }
        val childAvailableColumns = profile.columnsPerLine / effectiveWidthScale(style, profile)

        column.children.forEachIndexed { childIndex, child ->
            validateNode(
                node = child,
                inheritedStyle = style,
                availableColumns = childAvailableColumns,
                path = path.child(childIndex),
                profile = profile,
                diagnostics = diagnostics,
            )
        }
    }

    private fun validateTwoColumn(
        line: TwoColumnLine,
        inheritedStyle: TextStyle,
        availableColumns: Int,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val leadingStyle = resolveStyle(inheritedStyle, line.leading.style)
        if (line.leading.style != TextStyle.Default || inheritedStyle == TextStyle.Default) {
            validateStyle(leadingStyle, path, profile, diagnostics)
        }

        line.trailing?.let { trailing ->
            val trailingStyle = resolveStyle(inheritedStyle, trailing.style)
            if (trailing.style != TextStyle.Default || inheritedStyle == TextStyle.Default) {
                validateStyle(trailingStyle, path, profile, diagnostics)
            }
        }

        line.widthColumns?.let { width ->
            if (width <= 0) {
                diagnostics += InvalidLayout(
                    reason = "TwoColumnLine.widthColumns must be greater than zero",
                    path = path,
                )
            } else if (width > availableColumns) {
                diagnostics += InvalidLayout(
                    reason = "TwoColumnLine.widthColumns must not exceed the available columns",
                    path = path,
                )
            }
        }

        val targetWidth = (line.widthColumns ?: availableColumns).takeIf { it > 0 }
        validateReservedColumns(
            property = "leadingReservedColumns",
            value = line.leadingReservedColumns,
            targetWidth = targetWidth,
            path = path,
            diagnostics = diagnostics,
        )
        validateReservedColumns(
            property = "trailingReservedColumns",
            value = line.trailingReservedColumns,
            targetWidth = targetWidth,
            path = path,
            diagnostics = diagnostics,
        )
        if (targetWidth != null &&
            line.leadingReservedColumns != null &&
            line.leadingReservedColumns >= 0 &&
            line.trailingReservedColumns != null &&
            line.trailingReservedColumns >= 0 &&
            line.leadingReservedColumns.toLong() + line.trailingReservedColumns.toLong() >
            targetWidth.toLong()
        ) {
            diagnostics += InvalidLayout(
                reason = "TwoColumnLine reserved columns must not exceed the target width",
                path = path,
            )
        }
    }

    private fun validateReservedColumns(
        property: String,
        value: Int?,
        targetWidth: Int?,
        path: NodePath,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (value != null && value < 0) {
            diagnostics += InvalidLayout(
                reason = "TwoColumnLine.$property must not be negative",
                path = path,
            )
        } else if (value != null && targetWidth != null && value > targetWidth) {
            diagnostics += InvalidLayout(
                reason = "TwoColumnLine.$property must not exceed the target width",
                path = path,
            )
        }
    }

    private fun validateQrCode(
        qrCode: QrCode,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (qrCode.data.isEmpty()) {
            diagnostics += InvalidLayout(
                reason = "QrCode.data must not be empty",
                path = path,
            )
        }
        if (qrCode.cellSize < 1) {
            diagnostics += InvalidLayout(
                reason = "QrCode.cellSize must be at least one",
                path = path,
            )
        } else if (profile.compatibilityMode == CompatibilityMode.Compatibility &&
            profile.capabilities.compatibilityQrCellSize != null &&
            qrCode.cellSize != profile.capabilities.compatibilityQrCellSize
        ) {
            diagnostics += ValueClamped(
                property = "qrCode.cellSize",
                requested = qrCode.cellSize,
                actual = profile.capabilities.compatibilityQrCellSize,
                path = path,
            )
        } else if (profile.capabilities.maxQrCellSize >= 1 &&
            qrCode.cellSize > profile.capabilities.maxQrCellSize
        ) {
            if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
                diagnostics += ValueClamped(
                    property = "qrCode.cellSize",
                    requested = qrCode.cellSize,
                    actual = profile.capabilities.maxQrCellSize,
                    path = path,
                )
            } else {
                diagnostics += InvalidLayout(
                    reason = "QrCode.cellSize must not exceed ${profile.capabilities.maxQrCellSize}",
                    path = path,
                )
            }
        }
        if (!profile.capabilities.supportsQrCode) {
            diagnostics += unsupportedFeature("qr-code", profile, path)
        }
    }

    private fun validateStyle(
        style: TextStyle,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        validateScale(
            property = "widthScale",
            requested = style.widthScale,
            maximum = effectiveMaximumWidthScale(profile),
            path = path,
            profile = profile,
            diagnostics = diagnostics,
        )
        validateScale(
            property = "heightScale",
            requested = style.heightScale,
            maximum = profile.capabilities.maxHeightScale,
            path = path,
            profile = profile,
            diagnostics = diagnostics,
        )
        if (style.emphasized && !profile.capabilities.supportsEmphasis) {
            diagnostics += unsupportedFeature("emphasis", profile, path)
        }
        if (style.inverted && !profile.capabilities.supportsInverse) {
            diagnostics += unsupportedFeature("inverse", profile, path)
        }
    }

    private fun validateScale(
        property: String,
        requested: Int,
        maximum: Int,
        path: NodePath,
        profile: PrinterProfile,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (requested < 1) {
            diagnostics += InvalidLayout(
                reason = "TextStyle.$property must be at least one",
                path = path,
            )
            return
        }

        if (maximum >= 1 && requested > maximum) {
            if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
                diagnostics += ValueClamped(
                    property = property,
                    requested = requested,
                    actual = maximum,
                    path = path,
                )
            } else {
                diagnostics += InvalidLayout(
                    reason = "TextStyle.$property must not exceed $maximum for ${profile.id}",
                    path = path,
                )
            }
        }
    }

    private fun unsupportedFeature(
        feature: String,
        profile: PrinterProfile,
        path: NodePath?,
    ): UnsupportedFeature =
        UnsupportedFeature(
            feature = feature,
            profileId = profile.id,
            severity = if (profile.compatibilityMode == CompatibilityMode.Strict) {
                DiagnosticSeverity.Error
            } else {
                DiagnosticSeverity.Warning
            },
            path = path,
        )

    private fun resolveStyle(inherited: TextStyle, local: TextStyle): TextStyle =
        if (local == TextStyle.Default) inherited else local

    private fun effectiveWidthScale(style: TextStyle, profile: PrinterProfile): Int {
        val maximum = effectiveMaximumWidthScale(profile)
        return style.widthScale.coerceIn(1, maximum)
    }

    private fun effectiveMaximumWidthScale(profile: PrinterProfile): Int =
        minOf(
            profile.capabilities.maxWidthScale.coerceAtLeast(1),
            profile.columnsPerLine.coerceAtLeast(1),
        )

    private fun NodePath.child(index: Int): NodePath =
        copy(childIndexes = childIndexes.add(index))
}

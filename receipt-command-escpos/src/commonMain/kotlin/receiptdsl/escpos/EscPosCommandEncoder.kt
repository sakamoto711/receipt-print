package receiptdsl.escpos

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import receiptdsl.command.CommandEncoder
import receiptdsl.command.EncodeResult
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.InvalidLayout
import receiptdsl.diagnostic.UnsupportedFeature
import receiptdsl.diagnostic.ValueClamped
import receiptdsl.encoding.ByteString
import receiptdsl.layout.LaidOutBlock
import receiptdsl.layout.LaidOutFeed
import receiptdsl.layout.LaidOutJob
import receiptdsl.layout.LaidOutQrCode
import receiptdsl.layout.LaidOutRule
import receiptdsl.layout.LaidOutTextLine
import receiptdsl.layout.LineOp
import receiptdsl.layout.SetStyle
import receiptdsl.layout.TextBytes
import receiptdsl.model.CommandSet
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrinterModel
import receiptdsl.model.PrinterProfile
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.ReverseMode
import receiptdsl.model.SoundMode
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle

public object EscPosCommandEncoder : CommandEncoder {
    override val supportedCommandSet: CommandSet = CommandSet.EscPos

    override fun encode(job: LaidOutJob, profile: PrinterProfile): EncodeResult {
        if (profile.commandSet != supportedCommandSet) {
            return errorResult("command set ${profile.commandSet}", profile)
        }
        if (job.tickets.isEmpty()) {
            return EncodeResult(ByteString.Empty, persistentListOf())
        }

        val output = ByteAccumulator()
        val diagnostics = collectPreflightDiagnostics(job, profile)
        if (diagnostics.any(::isError)) {
            return EncodeResult(bytes = null, diagnostics = diagnostics.toPersistentList())
        }
        output.append(SESSION_INITIALIZATION)
        encodeStartActions(job, profile, output, diagnostics)

        val usesReorderedReverse = job.direction == PrintDirection.Reverse &&
            profile.capabilities.reverseMode == ReverseMode.ReorderLinesAndReverseGlyphs
        if (usesReorderedReverse) {
            output.append(ESC, LEFT_BRACE, 0x01)
        }

        job.tickets.forEach { ticket ->
            var currentStyle = TextStyle.Default
            currentStyle = resetTicketStyle(output)
            val blocks = if (usesReorderedReverse) ticket.blocks.asReversed() else ticket.blocks
            blocks.forEach { block ->
                currentStyle = encodeBlock(block, currentStyle, profile, output)
            }
            if (ticket.cut != CutMode.None) {
                if (usesReorderedReverse && (
                        profile.compatibilityMode == CompatibilityMode.Compatibility ||
                            output.lastByte != LF.toByte()
                        )
                ) {
                    output.append(LF)
                }
                encodeCut(ticket.cut, profile, output)
            }
        }

        if (usesReorderedReverse) {
            output.append(ESC, LEFT_BRACE, 0x00)
        }
        return EncodeResult(
            bytes = output.toByteString().takeUnless { diagnostics.any(::isError) },
            diagnostics = diagnostics.toPersistentList(),
        )
    }

    private fun encodeStartActions(
        job: LaidOutJob,
        profile: PrinterProfile,
        output: ByteAccumulator,
        diagnostics: MutableList<Diagnostic>,
    ) {
        job.startActions.forEach { action ->
            when (action) {
                JobAction.PlaySound -> {
                    if (!profile.soundEnabled) {
                        diagnostics += UnsupportedFeature(
                            feature = "sound disabled by profile",
                            profileId = profile.id,
                            severity = DiagnosticSeverity.Warning,
                            path = null,
                        )
                    } else {
                        when (profile.capabilities.soundMode) {
                            SoundMode.NativeBuzzer -> output.append(0x10, 0x14, 0x03, 0x03, 0x00, 0x01, 0x01, 0x00)
                            SoundMode.DrawerKickPulse -> output.append(0x10, 0x14, 0x01, 0x00, 0x01)
                            SoundMode.ExternalBuzzer,
                            SoundMode.Unsupported,
                            -> diagnostics += UnsupportedFeature(
                                feature = "sound",
                                profileId = profile.id,
                                severity = severity(profile),
                                path = null,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun collectPreflightDiagnostics(
        job: LaidOutJob,
        profile: PrinterProfile,
    ): MutableList<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        if (job.direction == PrintDirection.Reverse &&
            profile.capabilities.reverseMode == ReverseMode.Unsupported
        ) {
            diagnostics += UnsupportedFeature(
                feature = "reverse",
                profileId = profile.id,
                severity = severity(profile),
                path = null,
            )
        }
        job.tickets.forEach { ticket ->
            if (ticket.cut != CutMode.None && ticket.cut !in profile.capabilities.supportedCuts) {
                diagnostics += UnsupportedFeature(
                    feature = "cut:${ticket.cut.name}",
                    profileId = profile.id,
                    severity = severity(profile),
                    path = null,
                )
            }
            ticket.blocks.filterIsInstance<LaidOutQrCode>().forEach { qrCode ->
                if (!profile.capabilities.supportsQrCode) {
                    diagnostics += UnsupportedFeature(
                        feature = "qr-code",
                        profileId = profile.id,
                        severity = severity(profile),
                        path = null,
                    )
                }
                if (qrCode.data.size > MAX_QR_DATA_BYTES) {
                    diagnostics += InvalidLayout(
                        reason = "ESC/POS QR data must not exceed $MAX_QR_DATA_BYTES bytes",
                        path = null,
                    )
                }
                val effectiveCellSize = effectiveQrCellSize(qrCode.cellSize, profile)
                if (qrCode.cellSize != effectiveCellSize) {
                    if (profile.compatibilityMode == CompatibilityMode.Compatibility &&
                        effectiveCellSize != null
                    ) {
                        diagnostics += ValueClamped(
                            property = "qrCode.cellSize",
                            requested = qrCode.cellSize,
                            actual = effectiveCellSize,
                            path = null,
                        )
                    } else {
                        diagnostics += InvalidLayout(
                            reason = "QR cell size must be in 1..${profile.capabilities.maxQrCellSize}",
                            path = null,
                        )
                    }
                }
            }
        }
        return diagnostics
    }

    private fun resetTicketStyle(output: ByteAccumulator): TextStyle {
        output.append(ESC, LOWERCASE_A, 0x00)
        output.append(GS, EXCLAMATION_MARK, 0x00)
        output.append(ESC, UPPERCASE_E, 0x00)
        output.append(GS, UPPERCASE_B, 0x00)
        return TextStyle.Default
    }

    private fun encodeBlock(
        block: LaidOutBlock,
        currentStyle: TextStyle,
        profile: PrinterProfile,
        output: ByteAccumulator,
    ): TextStyle = when (block) {
        is LaidOutTextLine -> encodeTextLine(block.operations, currentStyle, output)
        is LaidOutRule -> {
            encodeRule(block.thickness, output)
            currentStyle
        }
        is LaidOutQrCode -> {
            if (!profile.capabilities.supportsQrCode) {
                currentStyle
            } else {
                val qrStyle = if (currentStyle.align == block.align) {
                    currentStyle
                } else {
                    output.append(ESC, LOWERCASE_A, alignValue(block.align))
                    currentStyle.copy(align = block.align)
                }
                encodeQr(block, profile, output)
                qrStyle
            }
        }
        is LaidOutFeed -> {
            repeat(block.lines) { output.append(LF) }
            currentStyle
        }
    }

    private fun encodeTextLine(
        operations: List<LineOp>,
        initialStyle: TextStyle,
        output: ByteAccumulator,
    ): TextStyle {
        var currentStyle = initialStyle
        operations.forEach { operation ->
            when (operation) {
                is SetStyle -> {
                    encodeStyleDifference(currentStyle, operation.style, output)
                    currentStyle = operation.style
                }
                is TextBytes -> output.append(operation.bytes)
            }
        }
        output.append(LF)
        return currentStyle
    }

    private fun encodeStyleDifference(
        current: TextStyle,
        next: TextStyle,
        output: ByteAccumulator,
    ) {
        if (current.align != next.align) {
            output.append(ESC, LOWERCASE_A, alignValue(next.align))
        }
        if (current.widthScale != next.widthScale || current.heightScale != next.heightScale) {
            val value = ((next.widthScale - 1) shl 4) or (next.heightScale - 1)
            output.append(GS, EXCLAMATION_MARK, value)
        }
        if (current.emphasized != next.emphasized) {
            output.append(ESC, UPPERCASE_E, if (next.emphasized) 0x01 else 0x00)
        }
        if (current.inverted != next.inverted) {
            output.append(GS, UPPERCASE_B, if (next.inverted) 0x01 else 0x00)
        }
    }

    private fun encodeRule(thickness: Int, output: ByteAccumulator) {
        val imageByte = if (thickness <= 3) 0x08 else 0x18
        output.append(ESC, ASTERISK, 0x00, RULE_WIDTH_DOTS and 0xFF, RULE_WIDTH_DOTS ushr 8)
        repeat(RULE_WIDTH_DOTS) { output.append(imageByte) }
    }

    private fun encodeQr(
        qrCode: LaidOutQrCode,
        profile: PrinterProfile,
        output: ByteAccumulator,
    ) {
        if (profile.compatibilityMode == CompatibilityMode.Strict) {
            output.append(GS, LEFT_PARENTHESIS, LOWERCASE_K, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)
            output.append(
                GS,
                LEFT_PARENTHESIS,
                LOWERCASE_K,
                0x03,
                0x00,
                0x31,
                0x45,
                qrErrorCorrectionValue(qrCode.errorCorrection),
            )
        }
        val cellSize = requireNotNull(effectiveQrCellSize(qrCode.cellSize, profile))
        output.append(GS, LEFT_PARENTHESIS, LOWERCASE_K, 0x03, 0x00, 0x31, 0x43, cellSize)
        val commandLength = qrCode.data.size + 3
        output.append(
            GS,
            LEFT_PARENTHESIS,
            LOWERCASE_K,
            commandLength and 0xFF,
            commandLength ushr 8,
            0x31,
            0x50,
            0x30,
        )
        output.append(qrCode.data)
        output.append(GS, LEFT_PARENTHESIS, LOWERCASE_K, 0x03, 0x00, 0x31, 0x51, 0x30)
    }

    private fun encodeCut(cut: CutMode, profile: PrinterProfile, output: ByteAccumulator) {
        if (profile.model == PrinterModel.EpsonTmT90Kp && cut == CutMode.NoMargin) {
            output.append(GS, UPPERCASE_V, 0x62, 0x0A, LF)
        } else if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
            // Compatibility mode maps ordinary cuts to the broadly supported feed-and-cut command.
            output.append(GS, UPPERCASE_V, 0x42, 0x00)
        } else if (cut == CutMode.Full) {
            output.append(GS, UPPERCASE_V, 0x00)
        } else if (cut == CutMode.Partial) {
            output.append(GS, UPPERCASE_V, 0x01)
        } else {
            output.append(GS, UPPERCASE_V, 0x42, 0x00)
        }
    }

    private fun alignValue(align: TextAlign): Int = when (align) {
        TextAlign.Left -> 0x00
        TextAlign.Center -> 0x01
        TextAlign.Right -> 0x02
    }

    private fun qrErrorCorrectionValue(level: QrErrorCorrection): Int = when (level) {
        QrErrorCorrection.Low -> 0x30
        QrErrorCorrection.Medium -> 0x31
        QrErrorCorrection.Quartile -> 0x32
        QrErrorCorrection.High -> 0x33
    }

    private fun effectiveQrCellSize(requested: Int, profile: PrinterProfile): Int? {
        if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
            profile.capabilities.compatibilityQrCellSize?.let { return it }
        }
        return if (profile.capabilities.maxQrCellSize >= 1) {
            requested.coerceIn(1, profile.capabilities.maxQrCellSize)
        } else {
            null
        }
    }

    private fun errorResult(feature: String, profile: PrinterProfile): EncodeResult =
        EncodeResult(
            bytes = null,
            diagnostics = persistentListOf(
                UnsupportedFeature(
                    feature = feature,
                    profileId = profile.id,
                    severity = DiagnosticSeverity.Error,
                    path = null,
                ),
            ),
        )

    private fun severity(profile: PrinterProfile): DiagnosticSeverity =
        if (profile.compatibilityMode == CompatibilityMode.Strict) {
            DiagnosticSeverity.Error
        } else {
            DiagnosticSeverity.Warning
        }

    private fun isError(diagnostic: Diagnostic): Boolean =
        diagnostic.severity == DiagnosticSeverity.Error

    private val SESSION_INITIALIZATION: ByteString = bytes(
        ESC, AT_SIGN,
        ESC, EXCLAMATION_MARK, 0x00,
        ESC, UPPERCASE_R, 0x08,
        FS, AMPERSAND,
        FS, PERIOD, 0x00,
        FS, UPPERCASE_C, 0x01,
    )

    private const val ESC: Int = 0x1B
    private const val FS: Int = 0x1C
    private const val GS: Int = 0x1D
    private const val LF: Int = 0x0A
    private const val AMPERSAND: Int = 0x26
    private const val LEFT_PARENTHESIS: Int = 0x28
    private const val ASTERISK: Int = 0x2A
    private const val PERIOD: Int = 0x2E
    private const val AT_SIGN: Int = 0x40
    private const val UPPERCASE_B: Int = 0x42
    private const val UPPERCASE_C: Int = 0x43
    private const val UPPERCASE_E: Int = 0x45
    private const val UPPERCASE_R: Int = 0x52
    private const val UPPERCASE_V: Int = 0x56
    private const val LEFT_BRACE: Int = 0x7B
    private const val EXCLAMATION_MARK: Int = 0x21
    private const val LOWERCASE_A: Int = 0x61
    private const val LOWERCASE_K: Int = 0x6B
    private const val RULE_WIDTH_DOTS: Int = 0x120
    private const val MAX_QR_DATA_BYTES: Int = 0xFFFF - 3
}

private class ByteAccumulator {
    private val bytes: MutableList<Byte> = mutableListOf()

    val lastByte: Byte?
        get() = bytes.lastOrNull()

    fun append(vararg values: Int) {
        values.forEach { value ->
            require(value in 0..0xFF) { "Byte value out of range: $value" }
            bytes += value.toByte()
        }
    }

    fun append(value: ByteString) {
        bytes += value.toByteArray().toList()
    }

    fun toByteString(): ByteString = ByteString.copyFrom(bytes.toByteArray())
}

private fun bytes(vararg values: Int): ByteString =
    ByteString.copyFrom(ByteArray(values.size) { index -> values[index].toByte() })

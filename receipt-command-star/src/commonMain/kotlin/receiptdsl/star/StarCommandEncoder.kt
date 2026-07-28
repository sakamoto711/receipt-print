package receiptdsl.star

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
import receiptdsl.model.SoundMode
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle

public class StarCommandEncoder(
    override val supportedCommandSet: CommandSet,
) : CommandEncoder {
    init {
        require(supportedCommandSet == CommandSet.StarPrnt || supportedCommandSet == CommandSet.StarLineMode) {
            "StarCommandEncoder requires a Star command set"
        }
    }

    override fun encode(job: LaidOutJob, profile: PrinterProfile): EncodeResult {
        if (profile.commandSet != supportedCommandSet) {
            return errorResult("command set ${profile.commandSet}", profile)
        }
        if (job.tickets.isEmpty()) {
            return EncodeResult(ByteString.Empty, persistentListOf())
        }

        val output = ByteAccumulator()
        val diagnostics = collectPreflightDiagnostics(job, profile)
        if (diagnostics.any { it.severity == DiagnosticSeverity.Error }) {
            return EncodeResult(bytes = null, diagnostics = diagnostics.toPersistentList())
        }
        encodeSessionInitialization(output)
        encodeStartActions(job, profile, output, diagnostics)
        val reverse = job.direction == PrintDirection.Reverse &&
            profile.capabilities.reverseMode == receiptdsl.model.ReverseMode.NativePageReverse
        if (reverse) {
            output.append(PAGE_REVERSE_PREFIX, 0x01, 0x00, 0x00)
        }

        job.tickets.forEach { ticket ->
            var currentStyle = resetTicketStyle(output)
            ticket.blocks.forEach { block ->
                currentStyle = encodeBlock(block, currentStyle, profile, output)
            }
            encodeCut(ticket.cut, output)
        }

        if (reverse) {
            output.append(PAGE_REVERSE_PREFIX, 0x00, 0x00, 0x00)
        }
        return EncodeResult(
            bytes = output.toByteString().takeUnless {
                diagnostics.any { diagnostic -> diagnostic.severity == DiagnosticSeverity.Error }
            },
            diagnostics = diagnostics.toPersistentList(),
        )
    }

    private fun encodeSessionInitialization(output: ByteAccumulator) {
        output.append(ESC, AT_SIGN)
        if (supportedCommandSet == CommandSet.StarPrnt) {
            output.append(ESC, AT_SIGN)
        }
        output.append(ESC, LOWERCASE_S, 0x00, 0x00)
    }

    private fun collectPreflightDiagnostics(
        job: LaidOutJob,
        profile: PrinterProfile,
    ): MutableList<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        if (profile.capabilities.printableWidthDots !in 1..MAX_PRINTABLE_WIDTH_DOTS) {
            diagnostics += InvalidLayout(
                reason = "Star printable width must be in 1..$MAX_PRINTABLE_WIDTH_DOTS dots",
                path = null,
            )
        }
        if (job.direction == PrintDirection.Reverse &&
            profile.capabilities.reverseMode != receiptdsl.model.ReverseMode.NativePageReverse
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
                        reason = "Star QR data must not exceed $MAX_QR_DATA_BYTES bytes",
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
            ticket.blocks.filterIsInstance<LaidOutRule>().forEach { rule ->
                if (rule.thickness !in MIN_RULE_THICKNESS..MAX_RULE_THICKNESS) {
                    diagnostics += InvalidLayout(
                        reason = "Star rule thickness must be in $MIN_RULE_THICKNESS..$MAX_RULE_THICKNESS",
                        path = null,
                    )
                }
            }
        }
        return diagnostics
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
                        when (profile.model) {
                            PrinterModel.StarFvp10 -> output.append(
                                ESC, GS, LOWERCASE_S, UPPERCASE_O,
                                0x00, 0x01, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
                            )
                            PrinterModel.StarTsp650II -> {
                                output.append(ESC, GS, EM, DC1, 0x01, 0x32, 0x00)
                                output.append(ESC, GS, EM, DC2, 0x01, 0x01, 0x00)
                            }
                            PrinterModel.StarMcPrint3 -> output.append(BEL)
                            else -> when (profile.capabilities.soundMode) {
                                SoundMode.Unsupported -> diagnostics += unsupportedSound(profile, "sound")
                                else -> diagnostics += unsupportedSound(profile, "model-specific sound")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resetTicketStyle(output: ByteAccumulator): TextStyle {
        output.append(ESC, GS, LOWERCASE_A, 0x00)
        output.append(ESC, LOWERCASE_I, 0x00, 0x00)
        output.append(ESC, UPPERCASE_F)
        output.append(ESC, DIGIT_FIVE)
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
            encodeRule(block.thickness, profile.capabilities.printableWidthDots, output)
            currentStyle
        }
        is LaidOutQrCode -> {
            if (!profile.capabilities.supportsQrCode) {
                currentStyle
            } else {
                val qrStyle = if (currentStyle.align == block.align) {
                    currentStyle
                } else {
                    output.append(ESC, GS, LOWERCASE_A, alignValue(block.align))
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
            output.append(ESC, GS, LOWERCASE_A, alignValue(next.align))
        }
        if (current.widthScale != next.widthScale || current.heightScale != next.heightScale) {
            output.append(ESC, LOWERCASE_I, next.heightScale - 1, next.widthScale - 1)
        }
        if (current.emphasized != next.emphasized) {
            output.append(ESC, if (next.emphasized) UPPERCASE_E else UPPERCASE_F)
        }
        if (current.inverted != next.inverted) {
            output.append(ESC, if (next.inverted) DIGIT_FOUR else DIGIT_FIVE)
        }
    }

    private fun encodeRule(thickness: Int, widthDots: Int, output: ByteAccumulator) {
        val imageByte = if (thickness == MIN_RULE_THICKNESS) 0x08 else 0x18
        output.append(ESC, UPPERCASE_L, widthDots and 0xFF, widthDots ushr 8)
        repeat(widthDots) { output.append(imageByte) }
    }

    private fun encodeQr(
        qrCode: LaidOutQrCode,
        profile: PrinterProfile,
        output: ByteAccumulator,
    ) {
        val cellSize = requireNotNull(effectiveQrCellSize(qrCode.cellSize, profile))
        val errorCorrection = if (profile.compatibilityMode == CompatibilityMode.Compatibility) {
            2
        } else {
            qrErrorCorrectionValue(qrCode.errorCorrection)
        }
        output.append(ESC, GS, LOWERCASE_Y, UPPERCASE_S, DIGIT_ZERO, 0x02)
        output.append(ESC, GS, LOWERCASE_Y, UPPERCASE_S, DIGIT_ONE, errorCorrection)
        output.append(ESC, GS, LOWERCASE_Y, UPPERCASE_S, DIGIT_TWO, cellSize)
        output.append(
            ESC,
            GS,
            LOWERCASE_Y,
            UPPERCASE_D,
            DIGIT_ONE,
            0x00,
            qrCode.data.size and 0xFF,
            qrCode.data.size ushr 8,
        )
        output.append(qrCode.data)
        output.append(ESC, GS, LOWERCASE_Y, UPPERCASE_P)
    }

    private fun encodeCut(cut: CutMode, output: ByteAccumulator) {
        when (cut) {
            CutMode.None -> Unit
            CutMode.Partial -> output.append(ESC, LOWERCASE_D, 0x03)
            CutMode.Full,
            CutMode.NoMargin,
            -> output.append(ESC, LOWERCASE_D, 0x02)
        }
    }

    private fun alignValue(align: TextAlign): Int = when (align) {
        TextAlign.Left -> 0x00
        TextAlign.Center -> 0x01
        TextAlign.Right -> 0x02
    }

    private fun qrErrorCorrectionValue(level: QrErrorCorrection): Int = when (level) {
        QrErrorCorrection.Low -> 0
        QrErrorCorrection.Medium -> 1
        QrErrorCorrection.Quartile -> 2
        QrErrorCorrection.High -> 3
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

    private fun unsupportedSound(profile: PrinterProfile, feature: String): UnsupportedFeature =
        UnsupportedFeature(
            feature = feature,
            profileId = profile.id,
            severity = if (profile.compatibilityMode == CompatibilityMode.Strict) {
                DiagnosticSeverity.Error
            } else {
                DiagnosticSeverity.Warning
            },
            path = null,
        )

    private fun severity(profile: PrinterProfile): DiagnosticSeverity =
        if (profile.compatibilityMode == CompatibilityMode.Strict) {
            DiagnosticSeverity.Error
        } else {
            DiagnosticSeverity.Warning
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

    private val PAGE_REVERSE_PREFIX: ByteString = bytes(ESC, GS, LOWERCASE_H, DIGIT_ZERO)

    private companion object {
        private const val BEL: Int = 0x07
        private const val LF: Int = 0x0A
        private const val DC1: Int = 0x11
        private const val DC2: Int = 0x12
        private const val EM: Int = 0x19
        private const val ESC: Int = 0x1B
        private const val GS: Int = 0x1D
        private const val DIGIT_ZERO: Int = 0x30
        private const val DIGIT_ONE: Int = 0x31
        private const val DIGIT_TWO: Int = 0x32
        private const val DIGIT_FOUR: Int = 0x34
        private const val DIGIT_FIVE: Int = 0x35
        private const val AT_SIGN: Int = 0x40
        private const val UPPERCASE_D: Int = 0x44
        private const val UPPERCASE_E: Int = 0x45
        private const val UPPERCASE_F: Int = 0x46
        private const val UPPERCASE_L: Int = 0x4C
        private const val UPPERCASE_O: Int = 0x4F
        private const val UPPERCASE_P: Int = 0x50
        private const val UPPERCASE_S: Int = 0x53
        private const val LOWERCASE_A: Int = 0x61
        private const val LOWERCASE_D: Int = 0x64
        private const val LOWERCASE_H: Int = 0x68
        private const val LOWERCASE_I: Int = 0x69
        private const val LOWERCASE_S: Int = 0x73
        private const val LOWERCASE_Y: Int = 0x79
        private const val MIN_RULE_THICKNESS: Int = 1
        private const val MAX_RULE_THICKNESS: Int = 2
        private const val MAX_PRINTABLE_WIDTH_DOTS: Int = 0xFFFF
        private const val MAX_QR_DATA_BYTES: Int = 0xFFFF
    }
}

public object StarPrntCommandEncoder : CommandEncoder by StarCommandEncoder(CommandSet.StarPrnt)

public object StarLineModeCommandEncoder : CommandEncoder by StarCommandEncoder(CommandSet.StarLineMode)

private class ByteAccumulator {
    private val bytes: MutableList<Byte> = mutableListOf()

    fun append(vararg values: Int) {
        values.forEach { value ->
            require(value in 0..0xFF) { "Byte value out of range: $value" }
            bytes += value.toByte()
        }
    }

    fun append(prefix: ByteString, vararg values: Int) {
        append(prefix)
        append(*values)
    }

    fun append(value: ByteString) {
        bytes += value.toByteArray().toList()
    }

    fun toByteString(): ByteString = ByteString.copyFrom(bytes.toByteArray())
}

private fun bytes(vararg values: Int): ByteString =
    ByteString.copyFrom(ByteArray(values.size) { index -> values[index].toByte() })

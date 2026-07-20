package receiptdsl.engine

import receiptdsl.command.CommandEncoder
import receiptdsl.compiler.ReceiptCompiler
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.escpos.EscPosCommandEncoder
import receiptdsl.model.CommandSet
import receiptdsl.model.PrinterProfile
import receiptdsl.receiptline.DefaultReceiptLineExporter
import receiptdsl.star.StarLineModeCommandEncoder
import receiptdsl.star.StarPrntCommandEncoder

public enum class DiagnosticSeverityDto {
    Warning,
    Error,
}

public data class DiagnosticDto(
    val code: String,
    val severity: DiagnosticSeverityDto,
    val message: String,
    val ticketIndex: Int?,
    val childIndexes: List<Int>,
)

public class CompileResponseDto internal constructor(
    bytes: ByteArray?,
    public val diagnostics: List<DiagnosticDto>,
) {
    private val storedBytes: ByteArray? = bytes?.copyOf()

    public val bytes: ByteArray?
        get() = storedBytes?.copyOf()

    public val isSuccess: Boolean
        get() = storedBytes != null && diagnostics.none { it.severity == DiagnosticSeverityDto.Error }
}

public data class PreviewResponseDto(
    val receiptLine: String?,
    val diagnostics: List<DiagnosticDto>,
) {
    public val isSuccess: Boolean
        get() = receiptLine != null && diagnostics.none { it.severity == DiagnosticSeverityDto.Error }
}

public class ReceiptEngine {
    private val compiler: ReceiptCompiler = ReceiptCompiler()

    public fun compile(
        job: PrintJobDto,
        printer: PrinterProfileDto,
    ): CompileResponseDto {
        val printerProfile = printer.toPrinterProfile()
        val result = compiler.compile(
            job = job.toPrintJob(),
            profile = printerProfile,
            encoder = encoderFor(printerProfile),
        )
        return CompileResponseDto(
            bytes = result.value?.toByteArray(),
            diagnostics = result.diagnostics.toDtos(),
        )
    }

    public fun preview(
        job: PrintJobDto,
        printer: PrinterProfileDto,
    ): PreviewResponseDto {
        val result = DefaultReceiptLineExporter.export(
            job = job.toPrintJob(),
            profile = printer.toPrinterProfile(),
        )
        return PreviewResponseDto(
            receiptLine = result.markdown,
            diagnostics = result.diagnostics.toDtos(),
        )
    }

    private fun encoderFor(profile: PrinterProfile): CommandEncoder = when (profile.commandSet) {
        CommandSet.EscPos -> EscPosCommandEncoder
        CommandSet.StarPrnt -> StarPrntCommandEncoder
        CommandSet.StarLineMode -> StarLineModeCommandEncoder
    }
}

private fun Iterable<Diagnostic>.toDtos(): List<DiagnosticDto> = map { diagnostic ->
    DiagnosticDto(
        code = diagnostic.code,
        severity = when (diagnostic.severity) {
            DiagnosticSeverity.Warning -> DiagnosticSeverityDto.Warning
            DiagnosticSeverity.Error -> DiagnosticSeverityDto.Error
        },
        message = diagnostic.message,
        ticketIndex = diagnostic.path?.ticketIndex,
        childIndexes = diagnostic.path?.childIndexes?.toList().orEmpty(),
    )
}

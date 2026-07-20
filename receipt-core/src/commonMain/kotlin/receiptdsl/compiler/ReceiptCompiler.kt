package receiptdsl.compiler

import kotlinx.collections.immutable.toPersistentList
import receiptdsl.command.CommandEncoder
import receiptdsl.command.EncodeResult
import receiptdsl.diagnostic.CompilationResult
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.encoding.ByteString
import receiptdsl.layout.DefaultLayoutEngine
import receiptdsl.layout.LaidOutJob
import receiptdsl.layout.LayoutEngine
import receiptdsl.layout.LayoutResult
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterProfile

public class ReceiptCompiler(
    private val layoutEngine: LayoutEngine = DefaultLayoutEngine(),
) {
    public fun layout(job: PrintJob, profile: PrinterProfile): LayoutResult =
        layoutEngine.layout(job, profile)

    public fun encode(
        job: LaidOutJob,
        profile: PrinterProfile,
        encoder: CommandEncoder,
    ): EncodeResult = encoder.encode(job, profile)

    public fun compile(
        job: PrintJob,
        profile: PrinterProfile,
        encoder: CommandEncoder,
    ): CompilationResult<ByteString> {
        val layout = layout(job, profile)
        val laidOutJob = layout.job
            ?: return CompilationResult(value = null, diagnostics = layout.diagnostics)
        val encoded = encode(laidOutJob, profile, encoder)
        val diagnostics = layout.diagnostics.toMutableList()
        encoded.diagnostics.forEach { diagnostic ->
            if (diagnostics.none { existing ->
                    existing.code == diagnostic.code &&
                        existing.message == diagnostic.message &&
                        existing.path == diagnostic.path
                }
            ) {
                diagnostics += diagnostic
            }
        }
        val persistentDiagnostics = diagnostics.toPersistentList()
        return CompilationResult(
            value = encoded.bytes.takeUnless {
                persistentDiagnostics.any { diagnostic ->
                    diagnostic.severity == DiagnosticSeverity.Error
                }
            },
            diagnostics = persistentDiagnostics,
        )
    }
}

public fun LayoutResult.requireJob(): LaidOutJob =
    requireNotNull(job) { "Layout did not produce a job; inspect diagnostics first" }

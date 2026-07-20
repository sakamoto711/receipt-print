package receiptdsl.command

import kotlinx.collections.immutable.PersistentList
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.encoding.ByteString
import receiptdsl.layout.LaidOutJob
import receiptdsl.model.CommandSet
import receiptdsl.model.PrinterProfile

public interface CommandEncoder {
    public val supportedCommandSet: CommandSet

    public fun encode(job: LaidOutJob, profile: PrinterProfile): EncodeResult
}

public data class EncodeResult(
    val bytes: ByteString?,
    val diagnostics: PersistentList<Diagnostic>,
) {
    public val isSuccess: Boolean
        get() = bytes != null && diagnostics.none { it.severity == receiptdsl.diagnostic.DiagnosticSeverity.Error }
}

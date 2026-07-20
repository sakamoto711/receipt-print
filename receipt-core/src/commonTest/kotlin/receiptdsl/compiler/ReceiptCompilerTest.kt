package receiptdsl.compiler

import kotlinx.collections.immutable.persistentListOf
import receiptdsl.command.CommandEncoder
import receiptdsl.command.EncodeResult
import receiptdsl.diagnostic.InvalidLayout
import receiptdsl.encoding.ByteString
import receiptdsl.layout.LaidOutJob
import receiptdsl.model.CommandSet
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterProfile
import receiptdsl.model.PrinterProfiles
import receiptdsl.model.TextBlock
import receiptdsl.model.Ticket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

public class ReceiptCompilerTest {
    @Test
    public fun neverReturnsCommandBytesAlongsideAnErrorDiagnostic() {
        val profile = PrinterProfiles.tmM30()
        val encoder = object : CommandEncoder {
            override val supportedCommandSet: CommandSet = CommandSet.EscPos

            override fun encode(job: LaidOutJob, profile: PrinterProfile): EncodeResult =
                EncodeResult(
                    bytes = ByteString.copyFrom(byteArrayOf(0x01)),
                    diagnostics = persistentListOf(
                        InvalidLayout(reason = "synthetic encoder failure", path = null),
                    ),
                )
        }

        val result = ReceiptCompiler().compile(
            job = PrintJob(persistentListOf(Ticket(TextBlock("valid")))),
            profile = profile,
            encoder = encoder,
        )

        assertNull(result.value)
        assertFalse(result.isSuccess)
    }
}

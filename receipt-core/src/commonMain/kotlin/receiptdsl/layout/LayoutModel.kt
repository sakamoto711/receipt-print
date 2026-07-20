package receiptdsl.layout

import kotlinx.collections.immutable.PersistentList
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.encoding.ByteString
import receiptdsl.model.CutMode
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.TextAlign
import receiptdsl.model.TextStyle

public data class LayoutResult(
    val job: LaidOutJob?,
    val diagnostics: PersistentList<Diagnostic>,
)

public data class LaidOutJob(
    val tickets: PersistentList<LaidOutTicket>,
    val direction: PrintDirection,
    val startActions: PersistentList<JobAction>,
)

public data class LaidOutTicket(
    val blocks: PersistentList<LaidOutBlock>,
    val cut: CutMode,
)

public sealed interface LaidOutBlock

public data class LaidOutTextLine(
    val operations: PersistentList<LineOp>,
) : LaidOutBlock

public data class LaidOutRule(
    val thickness: Int,
) : LaidOutBlock

public data class LaidOutQrCode(
    val data: ByteString,
    val cellSize: Int,
    val errorCorrection: QrErrorCorrection,
    val align: TextAlign,
) : LaidOutBlock

public data class LaidOutFeed(
    val lines: Int,
) : LaidOutBlock

public sealed interface LineOp

public data class SetStyle(
    val style: TextStyle,
) : LineOp

public data class TextBytes(
    val source: String,
    val bytes: ByteString,
) : LineOp

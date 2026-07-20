package receiptdsl.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

public data class PrintJob(
    val tickets: PersistentList<Ticket>,
    val direction: PrintDirection = PrintDirection.Normal,
    val startActions: PersistentList<JobAction> = persistentListOf(),
)

public data class Ticket(
    val content: BlockNode,
    val cut: CutMode = CutMode.Partial,
)

public enum class PrintDirection {
    Normal,
    Reverse,
}

public sealed interface JobAction {
    public data object PlaySound : JobAction
}

public enum class CutMode {
    None,
    Full,
    Partial,
    NoMargin,
}

package receiptdsl.dsl

import receiptdsl.model.Column
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class BuilderImmutabilityTest {
    @Test
    public fun laterPrintJobScopeChangesDoNotMutateBuiltJob() {
        lateinit var escapedScope: PrintJobScope
        val job = printJob {
            escapedScope = this
            ticket { text("確定済み") }
        }

        escapedScope.playSound()
        escapedScope.ticket { text("後から追加") }

        assertEquals(1, job.tickets.size)
        assertTrue(job.startActions.isEmpty())
    }

    @Test
    public fun laterColumnScopeChangesDoNotMutateBuiltTicket() {
        lateinit var escapedScope: ColumnScope
        val job = printJob {
            ticket {
                escapedScope = this
                text("確定済み")
            }
        }

        escapedScope.text("後から追加")

        val content = job.tickets.single().content as Column
        assertEquals(1, content.children.size)
    }

    @Test
    public fun laterNestedScopeChangesDoNotMutateBuiltColumn() {
        lateinit var escapedNestedScope: ColumnScope
        val job = printJob {
            ticket {
                column {
                    escapedNestedScope = this
                    text("確定済み")
                }
            }
        }

        escapedNestedScope.text("後から追加")

        val root = job.tickets.single().content as Column
        val nested = root.children.single() as Column
        assertEquals(1, nested.children.size)
    }
}

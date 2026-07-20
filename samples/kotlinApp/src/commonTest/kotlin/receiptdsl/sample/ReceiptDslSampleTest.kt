package receiptdsl.sample

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ReceiptDslSampleTest {
    @Test
    fun createsCommandsAndPreviewFromTheSameImmutableTree() {
        val output = createReceiptSample()

        assertTrue(output.printerCommands.isNotEmpty())
        assertContains(output.receiptLine, "ご注文伝票")
        assertContains(output.receiptLine, "ブレンドコーヒー")
    }
}

package receipt.print

import kotlin.test.Test
import kotlin.test.assertTrue

class ReceiptPrintTest {
    @Test
    fun sharedModuleIsAvailable() {
        assertTrue(ReceiptPrint.isAvailable())
    }
}

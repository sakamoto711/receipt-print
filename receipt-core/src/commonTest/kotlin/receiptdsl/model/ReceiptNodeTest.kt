package receiptdsl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class ReceiptNodeTest {
    @Test
    public fun modelDefaultsMatchTheCoreContract() {
        val text = TextBlock("本文")
        val qrCode = QrCode("qr")

        assertEquals(TextStyle.Default, text.style)
        assertEquals(WrapMode.WordOrCharacter, text.wrap)
        assertTrue(text.preserveNewLines)
        assertEquals(6, qrCode.cellSize)
        assertEquals(QrErrorCorrection.Medium, qrCode.errorCorrection)
        assertEquals(TextAlign.Center, qrCode.align)
    }
}

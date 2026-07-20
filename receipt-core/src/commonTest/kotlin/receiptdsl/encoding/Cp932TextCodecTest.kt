package receiptdsl.encoding

import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.UnsupportedGlyph
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class Cp932TextCodecTest {
    @Test
    public fun encodesAsciiKanaKanjiAndVendorExtension() {
        val result = Cp932TextCodec.encode("Aｱ日本髙", UnsupportedGlyphPolicy.Fail)

        assertContentEquals(
            byteArrayOf(0x41, 0xB1.toByte(), 0x93.toByte(), 0xFA.toByte(), 0x96.toByte(), 0x7B, 0xFB.toByte(), 0xFC.toByte()),
            result.bytes.toByteArray(),
        )
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    public fun appliesAllUnsupportedGlyphPoliciesPerCodePoint() {
        val drop = Cp932TextCodec.encode("A🙂B", UnsupportedGlyphPolicy.Drop)
        val replace = Cp932TextCodec.encode("A🙂B", UnsupportedGlyphPolicy.Replace)
        val fail = Cp932TextCodec.encode("A🙂B", UnsupportedGlyphPolicy.Fail)

        assertContentEquals(byteArrayOf(0x41, 0x42), drop.bytes.toByteArray())
        assertContentEquals(byteArrayOf(0x41, 0x3F, 0x42), replace.bytes.toByteArray())
        assertContentEquals(byteArrayOf(0x41, 0x42), fail.bytes.toByteArray())
        assertEquals(DiagnosticSeverity.Warning, drop.diagnostics.single().severity)
        assertEquals(DiagnosticSeverity.Warning, replace.diagnostics.single().severity)
        assertEquals(DiagnosticSeverity.Error, fail.diagnostics.single().severity)
        assertEquals(0x1F642, fail.diagnostics.filterIsInstance<UnsupportedGlyph>().single().codePoint)
    }

    @Test
    public fun reportsLoneSurrogateWithoutCrashing() {
        val result = Cp932TextCodec.encode("\uD83D", UnsupportedGlyphPolicy.Fail)

        assertTrue(result.bytes.isEmpty())
        assertEquals(0xD83D, result.diagnostics.filterIsInstance<UnsupportedGlyph>().single().codePoint)
    }

    @Test
    public fun measureUsesEncodedByteCountAndPreservesDiagnostics() {
        val result = Cp932TextCodec.measure("A日🙂", UnsupportedGlyphPolicy.Drop)

        assertEquals(3, result.columns)
        assertEquals(1, result.diagnostics.size)
    }
}

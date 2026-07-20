package receiptdsl.encoding

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Cp932BootstrapCompatibilityTest {
    @Test
    fun everyBmpScalarMatchesThePinnedJdkBootstrapMapping() {
        val encoder = Charset.forName("windows-31j")
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        for (codePoint in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            if (codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END) continue
            val source = codePoint.toChar().toString()
            val actual = Cp932TextCodec.encode(source, UnsupportedGlyphPolicy.Fail)
            if (encoder.canEncode(source)) {
                encoder.reset()
                val expectedBuffer = encoder.encode(java.nio.CharBuffer.wrap(source))
                val expected = ByteArray(expectedBuffer.remaining()).also(expectedBuffer::get)
                assertContentEquals(expected, actual.bytes.toByteArray(), "U+${codePoint.toString(16)}")
                assertTrue(actual.diagnostics.isEmpty(), "U+${codePoint.toString(16)}")
            } else {
                assertTrue(actual.bytes.isEmpty(), "U+${codePoint.toString(16)}")
                assertEquals(1, actual.diagnostics.size, "U+${codePoint.toString(16)}")
            }
        }
    }

    private companion object {
        const val HIGH_SURROGATE_START: Int = 0xD800
        const val LOW_SURROGATE_END: Int = 0xDFFF
    }
}

package receiptdsl.encoding

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

public class ByteStringTest {
    @Test
    public fun copiesInputAndOutputArrays() {
        val source = byteArrayOf(0x01, 0x02)
        val bytes = ByteString.copyFrom(source)

        source[0] = 0x7f
        val exported = bytes.toByteArray()
        exported[1] = 0x7f

        assertContentEquals(byteArrayOf(0x01, 0x02), bytes.toByteArray())
    }

    @Test
    public fun comparesByByteContent() {
        val first = ByteString.copyFrom(byteArrayOf(0x01, 0x02))
        val same = ByteString.copyFrom(byteArrayOf(0x01, 0x02))
        val different = ByteString.copyFrom(byteArrayOf(0x01, 0x03))

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    public fun reusesImmutableEmptyValue() {
        val empty = ByteString.copyFrom(byteArrayOf())

        assertEquals(ByteString.Empty, empty)
        assertEquals(0, empty.size)
        assertTrue(empty.isEmpty())
    }

    @Test
    public fun concatenatesValuesWithoutExposingMutableStorage() {
        val first = ByteString.copyFrom(byteArrayOf(1, 2))
        val second = ByteString.copyFrom(byteArrayOf(3))

        val combined = ByteString.concat(listOf(first, ByteString.Empty, second))
        val exposed = combined.toByteArray()
        exposed[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), combined.toByteArray())
        assertSame(ByteString.Empty, ByteString.concat(emptyList()))
    }
}

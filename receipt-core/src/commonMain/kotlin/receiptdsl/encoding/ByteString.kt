package receiptdsl.encoding

public class ByteString private constructor(
    private val value: ByteArray,
) {
    public val size: Int
        get() = value.size

    public fun isEmpty(): Boolean = value.isEmpty()

    public fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ByteString && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    public companion object {
        public val Empty: ByteString = ByteString(ByteArray(0))

        public fun copyFrom(value: ByteArray): ByteString =
            if (value.isEmpty()) Empty else ByteString(value.copyOf())

        public fun concat(values: Iterable<ByteString>): ByteString {
            val parts = values.toList()
            var totalSize = 0
            parts.forEach { part ->
                require(part.size <= Int.MAX_VALUE - totalSize) { "Combined ByteString is too large" }
                totalSize += part.size
            }
            if (totalSize == 0) {
                return Empty
            }

            val result = ByteArray(totalSize)
            var destinationOffset = 0
            parts.forEach { part ->
                val bytes = part.value
                bytes.copyInto(result, destinationOffset = destinationOffset)
                destinationOffset += bytes.size
            }
            return ByteString(result)
        }
    }
}

package receiptdsl.encoding

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import receiptdsl.diagnostic.Diagnostic
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.UnsupportedGlyph

public interface TextCodec {
    public fun encode(text: String, policy: UnsupportedGlyphPolicy): EncodedText

    public fun measure(text: String, policy: UnsupportedGlyphPolicy): TextMeasurement
}

public data class EncodedText(
    val bytes: ByteString,
    val diagnostics: PersistentList<Diagnostic>,
)

public data class TextMeasurement(
    val columns: Int,
    val diagnostics: PersistentList<Diagnostic>,
)

public enum class UnsupportedGlyphPolicy {
    Drop,
    Replace,
    Fail,
}

public object Cp932TextCodec : TextCodec {
    override fun encode(text: String, policy: UnsupportedGlyphPolicy): EncodedText {
        if (text.isEmpty()) {
            return EncodedText(ByteString.Empty, persistentListOf())
        }

        val bytes = ArrayList<Byte>(text.length * 2)
        val diagnostics = mutableListOf<Diagnostic>()
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val hasSurrogatePair = first.isHighSurrogate() &&
                index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            val codePoint = if (hasSurrogatePair) {
                val high = first.code - HIGH_SURROGATE_START
                val low = text[index + 1].code - LOW_SURROGATE_START
                SUPPLEMENTARY_START + (high shl 10) + low
            } else {
                first.code
            }
            val source = text.substring(index, index + if (hasSurrogatePair) 2 else 1)
            val encoded = Cp932MappingTable.encodedValue(codePoint)
            if (encoded == null) {
                diagnostics += UnsupportedGlyph(
                    codePoint = codePoint,
                    source = source,
                    severity = if (policy == UnsupportedGlyphPolicy.Fail) {
                        DiagnosticSeverity.Error
                    } else {
                        DiagnosticSeverity.Warning
                    },
                    path = null,
                )
                if (policy == UnsupportedGlyphPolicy.Replace) {
                    bytes += REPLACEMENT_BYTE
                }
            } else if (encoded <= SINGLE_BYTE_MAX) {
                bytes += encoded.toByte()
            } else {
                bytes += (encoded ushr 8).toByte()
                bytes += encoded.toByte()
            }
            index += if (hasSurrogatePair) 2 else 1
        }

        return EncodedText(
            bytes = ByteString.copyFrom(bytes.toByteArray()),
            diagnostics = diagnostics.toPersistentList(),
        )
    }

    override fun measure(text: String, policy: UnsupportedGlyphPolicy): TextMeasurement {
        val encoded = encode(text, policy)
        return TextMeasurement(
            columns = encoded.bytes.size,
            diagnostics = encoded.diagnostics,
        )
    }

    private const val HIGH_SURROGATE_START: Int = 0xD800
    private const val LOW_SURROGATE_START: Int = 0xDC00
    private const val SUPPLEMENTARY_START: Int = 0x10000
    private const val SINGLE_BYTE_MAX: Int = 0xFF
    private const val REPLACEMENT_BYTE: Byte = 0x3F
}

// TODO(Phase 2): 実行環境のNetwork Data Transfer制限が解除されたら、Unicode公式
// CP932.TXTをthird_party/unicodeへchecksum付きで固定し、GenerateCp932Tableを公式入力で
// 再実行してJDK bootstrap由来の表を置き換える。Codecの実行は既にcommonMain内で決定的である。

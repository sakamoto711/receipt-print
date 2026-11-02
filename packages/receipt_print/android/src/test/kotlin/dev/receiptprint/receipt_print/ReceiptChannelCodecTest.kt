package dev.receiptprint.receipt_print

import dev.receiptprint.receipt_print.codec.ReceiptChannelCodec
import org.junit.Assert.*
import org.junit.Test
import receiptdsl.engine.*

class ReceiptChannelCodecTest {
    @Test fun inlineRunsKeepTheirExistingInverseMargins() {
        val normal = mapOf("widthScale" to 1, "heightScale" to 2,
            "emphasized" to false, "inverted" to false, "align" to "Left")
        val job = ReceiptChannelCodec.printJob(mapOf(
            "direction" to "Normal", "startActions" to emptyList<String>(),
            "tickets" to listOf(mapOf("cut" to "Partial", "children" to listOf(
                mapOf("type" to "inline", "align" to "Left", "runs" to listOf(
                    mapOf("value" to "□ ALPHA" + " ".repeat(19), "style" to normal),
                    mapOf("value" to " 1点 ", "style" to (normal + ("inverted" to true))),
                )),
            ))),
        ))
        val result = ReceiptEngine().compile(job, PrinterProfileDto(PrinterModelDto.EpsonTmM30, columnsPerLine = 32))
        assertTrue(result.diagnostics.toString(), result.isSuccess)
    }

    private val style = mapOf(
        "widthScale" to 2, "heightScale" to 3, "emphasized" to true,
        "inverted" to true, "align" to "Right",
    )
    private val expectedStyle = TextStyleDto(2, 3, true, true, TextAlignDto.Right)

    @Test fun everyFieldSurvivesTheChannelDecoder() {
        val job = ReceiptChannelCodec.printJob(mapOf(
            "direction" to "Reverse", "startActions" to listOf("PlaySound"),
            "tickets" to listOf(mapOf("cut" to "NoMargin", "children" to listOf(
                mapOf("type" to "column", "style" to style, "children" to listOf(
                    mapOf("type" to "text", "value" to "日本語", "style" to style,
                        "wrap" to "Character", "preserveNewLines" to false),
                    mapOf("type" to "twoColumn",
                        "leading" to mapOf("value" to "A", "style" to style),
                        "trailing" to mapOf("value" to "B", "style" to style),
                        "widthColumns" to 32L, "leadingReservedColumns" to 12,
                        "trailingReservedColumns" to 8, "overflow" to "Clip"),
                    mapOf("type" to "rule", "thickness" to 2),
                    mapOf("type" to "qrCode", "data" to "qr", "cellSize" to 4,
                        "errorCorrection" to "High", "align" to "Left"),
                    mapOf("type" to "feed", "lines" to 3),
                )),
            ))),
        ))
        val expected = PrintJobDto(listOf(TicketDto(PrintNodeListDto(listOf(
            ColumnDto(PrintNodeListDto(listOf(
                TextDto("日本語", expectedStyle, WrapModeDto.Character, false),
                TwoColumnDto(TextRunDto("A", expectedStyle), TextRunDto("B", expectedStyle),
                    32, 12, 8, TwoColumnOverflowDto.Clip),
                RuleDto(2), QrCodeDto("qr", 4, QrErrorCorrectionDto.High, TextAlignDto.Left), FeedDto(3),
            )), expectedStyle),
        )), CutModeDto.NoMargin)), PrintDirectionDto.Reverse, listOf(JobActionDto.PlaySound))
        assertEquals(expected, job)
        val printer = ReceiptChannelCodec.printerProfile(mapOf(
            "model" to "StarFvp10", "columnsPerLine" to 32, "compatibilityMode" to "Compatibility",
            "soundEnabled" to false, "optionalExternalBuzzerEnabled" to true,
        ))
        assertEquals(PrinterProfileDto(PrinterModelDto.StarFvp10, 32,
            CompatibilityModeDto.Compatibility, false, true), printer)
        val engine = ReceiptEngine()
        val actualResponse = engine.compile(job, printer)
        val directResponse = engine.compile(expected, printer)
        assertArrayEquals(directResponse.bytes, actualResponse.bytes)
        assertEquals(directResponse.diagnostics, actualResponse.diagnostics)
    }

    @Test fun oversizedIntegersAreRejectedRatherThanTruncated() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptChannelCodec.feed(mapOf("lines" to 2147483648L))
        }
    }

    @Test fun unknownEnumsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptChannelCodec.textStyle(style + ("align" to "Unknown"))
        }
    }
}

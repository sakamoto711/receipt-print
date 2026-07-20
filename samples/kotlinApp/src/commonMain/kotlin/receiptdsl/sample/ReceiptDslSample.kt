package receiptdsl.sample

import receiptdsl.engine.ColumnDto
import receiptdsl.engine.CutModeDto
import receiptdsl.engine.PrintJobDto
import receiptdsl.engine.PrintNodeListDto
import receiptdsl.engine.PrinterModelDto
import receiptdsl.engine.PrinterProfileDto
import receiptdsl.engine.QrCodeDto
import receiptdsl.engine.ReceiptEngine
import receiptdsl.engine.RuleDto
import receiptdsl.engine.TextAlignDto
import receiptdsl.engine.TextDto
import receiptdsl.engine.TextRunDto
import receiptdsl.engine.TextStyleDto
import receiptdsl.engine.TicketDto
import receiptdsl.engine.TwoColumnDto

public data class SampleOutput(
    val printerCommands: ByteArray,
    val receiptLine: String,
)

public fun createReceiptSample(): SampleOutput {
    val job = PrintJobDto(
        tickets = listOf(
            TicketDto(
                children = PrintNodeListDto(
                    listOf(
                        TextDto(
                            value = "ご注文伝票",
                            style = TextStyleDto(
                                widthScale = 2,
                                heightScale = 2,
                                align = TextAlignDto.Center,
                            ),
                        ),
                        RuleDto(),
                        TwoColumnDto(
                            leading = TextRunDto("ブレンドコーヒー × 2"),
                            trailing = TextRunDto("¥1,000"),
                        ),
                        ColumnDto(
                            children = PrintNodeListDto(
                                listOf(QrCodeDto("https://example.invalid/orders/123")),
                            ),
                        ),
                    ),
                ),
                cut = CutModeDto.Partial,
            ),
        ),
    )
    val profile = PrinterProfileDto(
        model = PrinterModelDto.EpsonTmM30,
        columnsPerLine = 48,
    )
    val engine = ReceiptEngine()
    val compiled = engine.compile(job, profile)
    val preview = engine.preview(job, profile)

    return SampleOutput(
        printerCommands = requireNotNull(compiled.bytes) {
            compiled.diagnostics.joinToString { it.message }
        },
        receiptLine = requireNotNull(preview.receiptLine) {
            preview.diagnostics.joinToString { it.message }
        },
    )
}

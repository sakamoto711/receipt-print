package receiptdsl.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class ReceiptEngineTest {
    private val engine = ReceiptEngine()

    @Test
    public fun genericTreeCanBePreviewedAndCompiled() {
        val job = PrintJobDto(
            tickets = listOf(
                TicketDto(
                    children = PrintNodeListDto(
                        listOf(
                            TextDto(
                                value = "注文 #12",
                                style = TextStyleDto(
                                    emphasized = true,
                                    align = TextAlignDto.Center,
                                ),
                            ),
                            TwoColumnDto(
                                leading = TextRunDto("コーヒー"),
                                trailing = TextRunDto("2"),
                            ),
                            ColumnDto(
                                children = PrintNodeListDto(
                                    listOf(
                                        RuleDto(),
                                        QrCodeDto("https://example.com/order/12"),
                                        FeedDto(),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            startActions = listOf(JobActionDto.PlaySound),
        )
        val printer = PrinterProfileDto(PrinterModelDto.EpsonTmM30)

        val preview = engine.preview(job, printer)
        val compiled = engine.compile(job, printer)

        assertTrue(preview.isSuccess)
        assertNotNull(preview.receiptLine)
        assertTrue(preview.receiptLine.contains("注文 #12"))
        assertTrue(preview.receiptLine.contains("コーヒー | 2"))
        assertTrue(preview.receiptLine.contains("{code:"))
        assertTrue(compiled.isSuccess)
        assertTrue(requireNotNull(compiled.bytes).isNotEmpty())
    }

    @Test
    public fun everySupportedPrinterModelResolvesToAnEncoder() {
        val job = PrintJobDto(
            tickets = listOf(
                TicketDto(PrintNodeListDto(listOf(TextDto("A"))), cut = CutModeDto.None),
            ),
        )

        PrinterModelDto.entries.forEach { model ->
            val response = engine.compile(job, PrinterProfileDto(model))
            assertTrue(response.isSuccess, "$model: ${response.diagnostics}")
            assertFalse(requireNotNull(response.bytes).isEmpty(), model.name)
        }
    }

    @Test
    public fun invalidNestedNodeReportsItsTreePathForCompileAndPreview() {
        val job = PrintJobDto(
            tickets = listOf(
                TicketDto(
                    PrintNodeListDto(
                        listOf(
                            TextDto("valid"),
                            ColumnDto(PrintNodeListDto(listOf(FeedDto(lines = 0)))),
                        ),
                    ),
                ),
            ),
        )
        val printer = PrinterProfileDto(PrinterModelDto.StarMcPrint3)

        val compiled = engine.compile(job, printer)
        val preview = engine.preview(job, printer)

        assertNull(compiled.bytes)
        assertNull(preview.receiptLine)
        assertEquals(listOf(1, 0), compiled.diagnostics.single().childIndexes)
        assertEquals(listOf(1, 0), preview.diagnostics.single().childIndexes)
    }

    @Test
    public fun previewAndCompileValidateAgainstTheSamePrinterProfile() {
        val job = PrintJobDto(
            tickets = listOf(
                TicketDto(PrintNodeListDto(listOf(TextDto("reverse"))), cut = CutModeDto.None),
            ),
            direction = PrintDirectionDto.Reverse,
        )
        val printer = PrinterProfileDto(PrinterModelDto.EpsonTmM30)

        val compiled = engine.compile(job, printer)
        val preview = engine.preview(job, printer)

        assertFalse(compiled.isSuccess)
        assertFalse(preview.isSuccess)
        assertEquals("UNSUPPORTED_FEATURE", compiled.diagnostics.single().code)
        assertEquals("UNSUPPORTED_FEATURE", preview.diagnostics.single().code)
    }

    @Test
    public fun compileResponseDoesNotExposeItsStoredByteArray() {
        val response = engine.compile(
            PrintJobDto(
                tickets = listOf(
                    TicketDto(PrintNodeListDto(listOf(TextDto("A"))), cut = CutModeDto.None),
                ),
            ),
            PrinterProfileDto(PrinterModelDto.EpsonTmM30),
        )
        val original = requireNotNull(response.bytes)
        val expected = original.copyOf()

        original.fill(0)

        assertContentEquals(expected, response.bytes)
    }
}

package receiptdsl.validation

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import receiptdsl.diagnostic.DiagnosticSeverity
import receiptdsl.diagnostic.InvalidLayout
import receiptdsl.diagnostic.NodePath
import receiptdsl.diagnostic.UnsupportedFeature
import receiptdsl.diagnostic.ValueClamped
import receiptdsl.model.Column
import receiptdsl.model.CommandSet
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterCapabilities
import receiptdsl.model.PrinterProfile
import receiptdsl.model.QrCode
import receiptdsl.model.ReverseMode
import receiptdsl.model.Rule
import receiptdsl.model.SoundMode
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.Ticket
import receiptdsl.model.TwoColumnLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class PrintJobValidatorTest {
    private val validator = DefaultPrintJobValidator()

    @Test
    public fun acceptsValidJob() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        children = persistentListOf(
                            TextBlock("本文"),
                            Rule(),
                            QrCode("qr"),
                            Feed(),
                        ),
                    ),
                ),
            ),
        )

        val result = validator.validate(job, profile())

        assertTrue(result.isSuccess)
        assertEquals(job, result.value)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    public fun collectsProfileAndEmptyJobErrors() {
        val invalidProfile = profile(
            id = " ",
            columnsPerLine = 0,
            maxWidthScale = 0,
            maxHeightScale = 0,
        )

        val result = validator.validate(
            job = PrintJob(tickets = persistentListOf()),
            profile = invalidProfile,
        )

        assertFalse(result.isSuccess)
        assertNull(result.value)
        assertEquals(5, result.diagnostics.size)
        assertTrue(result.diagnostics.all { it is InvalidLayout })
    }

    @Test
    public fun collectsAllNodeErrorsWithPaths() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        children = persistentListOf(
                            TextBlock("invalid scale", style = TextStyle(widthScale = 0)),
                            Rule(thickness = 0),
                            QrCode(data = "", cellSize = 0),
                            Feed(lines = 0),
                            TwoColumnLine(
                                leading = TextRun("leading"),
                                widthColumns = 49,
                                leadingReservedColumns = -1,
                                trailingReservedColumns = -2,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = validator.validate(job, profile())

        assertFalse(result.isSuccess)
        assertNull(result.value)
        assertEquals(8, result.diagnostics.size)
        assertEquals(
            NodePath(ticketIndex = 0, childIndexes = persistentListOf(1)),
            result.diagnostics.first { it.message.contains("Rule.thickness") }.path,
        )
        assertEquals(
            NodePath(ticketIndex = 0, childIndexes = persistentListOf(4)),
            result.diagnostics.first { it.message.contains("leadingReservedColumns") }.path,
        )
    }

    @Test
    public fun strictModeRejectsUnsupportedFeaturesAndOversizedStyle() {
        val job = unsupportedFeatureJob()

        val result = validator.validate(
            job = job,
            profile = restrictedProfile(CompatibilityMode.Strict),
        )

        assertFalse(result.isSuccess)
        assertNull(result.value)
        assertEquals(7, result.diagnostics.size)
        assertEquals(6, result.diagnostics.filterIsInstance<UnsupportedFeature>().size)
        assertEquals(1, result.diagnostics.filterIsInstance<InvalidLayout>().size)
        assertTrue(result.diagnostics.all { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    public fun compatibilityModeKeepsJobAndReportsFallbackWarnings() {
        val job = unsupportedFeatureJob()

        val result = validator.validate(
            job = job,
            profile = restrictedProfile(CompatibilityMode.Compatibility),
        )

        assertTrue(result.isSuccess)
        assertNotNull(result.value)
        assertEquals(7, result.diagnostics.size)
        assertEquals(6, result.diagnostics.filterIsInstance<UnsupportedFeature>().size)
        assertEquals(
            ValueClamped(
                property = "widthScale",
                requested = 7,
                actual = 6,
                path = NodePath(ticketIndex = 0),
            ),
            result.diagnostics.filterIsInstance<ValueClamped>().single(),
        )
        assertTrue(result.diagnostics.all { it.severity == DiagnosticSeverity.Warning })
    }

    @Test
    public fun inheritedColumnStyleDoesNotReduceAvailableWidthTwice() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        children = persistentListOf(
                            Column(
                                children = persistentListOf(
                                    TwoColumnLine(
                                        leading = TextRun("leading"),
                                        widthColumns = 24,
                                    ),
                                ),
                            ),
                        ),
                        style = TextStyle(widthScale = 2),
                    ),
                ),
            ),
        )

        val result = validator.validate(job, profile(columnsPerLine = 48))

        assertTrue(result.isSuccess)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    public fun rejectsReservedColumnsThatCannotFitTargetWidth() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = TwoColumnLine(
                        leading = TextRun("leading"),
                        trailing = TextRun("trailing"),
                        widthColumns = 10,
                        leadingReservedColumns = 7,
                        trailingReservedColumns = 4,
                    ),
                ),
            ),
        )

        val result = validator.validate(job, profile())

        assertFalse(result.isSuccess)
        assertEquals(1, result.diagnostics.size)
        assertTrue(result.diagnostics.single().message.contains("reserved columns"))
    }

    @Test
    public fun rejectsReservedColumnSumWithoutIntegerOverflow() {
        val job = PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = TwoColumnLine(
                        leading = TextRun("leading"),
                        trailing = TextRun("trailing"),
                        widthColumns = Int.MAX_VALUE,
                        leadingReservedColumns = Int.MAX_VALUE,
                        trailingReservedColumns = Int.MAX_VALUE,
                    ),
                ),
            ),
        )

        val result = validator.validate(job, profile(columnsPerLine = Int.MAX_VALUE))

        assertFalse(result.isSuccess)
        assertEquals(1, result.diagnostics.size)
        assertTrue(result.diagnostics.single().message.contains("reserved columns"))
    }

    private fun unsupportedFeatureJob(): PrintJob =
        PrintJob(
            tickets = persistentListOf(
                Ticket(
                    content = Column(
                        children = persistentListOf(
                            TextBlock("本文"),
                            QrCode("qr"),
                        ),
                        style = TextStyle(
                            widthScale = 7,
                            emphasized = true,
                            inverted = true,
                        ),
                    ),
                    cut = CutMode.NoMargin,
                ),
            ),
            direction = PrintDirection.Reverse,
            startActions = persistentListOf(JobAction.PlaySound),
        )

    private fun restrictedProfile(mode: CompatibilityMode): PrinterProfile =
        profile(
            mode = mode,
            supportedCuts = persistentSetOf(CutMode.Partial),
            supportsQrCode = false,
            supportsEmphasis = false,
            supportsInverse = false,
            reverseMode = ReverseMode.Unsupported,
            soundMode = SoundMode.Unsupported,
        )

    private fun profile(
        id: String = "test-profile",
        columnsPerLine: Int = 48,
        maxWidthScale: Int = 6,
        maxHeightScale: Int = 6,
        mode: CompatibilityMode = CompatibilityMode.Strict,
        supportedCuts: Set<CutMode> = persistentSetOf(
            CutMode.Full,
            CutMode.Partial,
            CutMode.NoMargin,
        ),
        supportsQrCode: Boolean = true,
        supportsEmphasis: Boolean = true,
        supportsInverse: Boolean = true,
        reverseMode: ReverseMode = ReverseMode.NativePageReverse,
        soundMode: SoundMode = SoundMode.NativeBuzzer,
    ): PrinterProfile =
        PrinterProfile(
            id = id,
            commandSet = CommandSet.EscPos,
            columnsPerLine = columnsPerLine,
            capabilities = PrinterCapabilities(
                maxWidthScale = maxWidthScale,
                maxHeightScale = maxHeightScale,
                supportedCuts = supportedCuts.toPersistentSet(),
                supportsQrCode = supportsQrCode,
                supportsEmphasis = supportsEmphasis,
                supportsInverse = supportsInverse,
                reverseMode = reverseMode,
                soundMode = soundMode,
            ),
            compatibilityMode = mode,
        )
}

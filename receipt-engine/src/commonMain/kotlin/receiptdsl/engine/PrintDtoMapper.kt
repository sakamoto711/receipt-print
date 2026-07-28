package receiptdsl.engine

import kotlinx.collections.immutable.toPersistentList
import receiptdsl.model.BlockNode
import receiptdsl.model.Column
import receiptdsl.model.CompatibilityMode
import receiptdsl.model.CutMode
import receiptdsl.model.Feed
import receiptdsl.model.JobAction
import receiptdsl.model.PrintDirection
import receiptdsl.model.PrintJob
import receiptdsl.model.PrinterProfile
import receiptdsl.model.PrinterProfiles
import receiptdsl.model.QrCode
import receiptdsl.model.QrErrorCorrection
import receiptdsl.model.Rule
import receiptdsl.model.TextAlign
import receiptdsl.model.TextBlock
import receiptdsl.model.TextRun
import receiptdsl.model.TextStyle
import receiptdsl.model.Ticket
import receiptdsl.model.TwoColumnLine
import receiptdsl.model.TwoColumnOverflow
import receiptdsl.model.WrapMode

internal fun PrintJobDto.toPrintJob(): PrintJob = PrintJob(
    tickets = tickets.map(TicketDto::toTicket).toPersistentList(),
    direction = direction.toPrintDirection(),
    startActions = startActions.map(JobActionDto::toJobAction).toPersistentList(),
)

private fun TicketDto.toTicket(): Ticket = Ticket(
    content = Column(children = children.toBlockNodes()),
    cut = cut.toCutMode(),
)

private fun PrintNodeListDto.toBlockNodes() =
    nodes.map(PrintNodeDto::toBlockNode).toPersistentList()

private fun PrintNodeDto.toBlockNode(): BlockNode = when (this) {
    is ColumnDto -> Column(
        children = children.toBlockNodes(),
        style = style?.toTextStyle(),
    )
    is TextDto -> TextBlock(
        text = value,
        style = style.toTextStyle(),
        wrap = wrap.toWrapMode(),
        preserveNewLines = preserveNewLines,
    )
    is TwoColumnDto -> TwoColumnLine(
        leading = leading.toTextRun(),
        trailing = trailing?.toTextRun(),
        widthColumns = widthColumns,
        leadingReservedColumns = leadingReservedColumns,
        trailingReservedColumns = trailingReservedColumns,
        overflow = overflow.toTwoColumnOverflow(),
    )
    is RuleDto -> Rule(thickness)
    is QrCodeDto -> QrCode(
        data = data,
        cellSize = cellSize,
        errorCorrection = errorCorrection.toQrErrorCorrection(),
        align = align.toTextAlign(),
    )
    is FeedDto -> Feed(lines)
}

private fun TextRunDto.toTextRun(): TextRun = TextRun(
    text = value,
    style = style.toTextStyle(),
)

private fun TextStyleDto.toTextStyle(): TextStyle = TextStyle(
    widthScale = widthScale,
    heightScale = heightScale,
    emphasized = emphasized,
    inverted = inverted,
    align = align.toTextAlign(),
)

internal fun PrinterProfileDto.toPrinterProfile(): PrinterProfile {
    val compatibilityMode = compatibilityMode.toCompatibilityMode()
    return when (model) {
        PrinterModelDto.StarFvp10 -> PrinterProfiles.fvp10(columnsPerLine, compatibilityMode, soundEnabled)
        PrinterModelDto.EpsonTmT90Kp -> PrinterProfiles.tmT90Kp(columnsPerLine, compatibilityMode, soundEnabled)
        PrinterModelDto.EpsonTmM30 -> PrinterProfiles.tmM30(
            columnsPerLine = columnsPerLine,
            compatibilityMode = compatibilityMode,
            soundEnabled = soundEnabled,
            optionalExternalBuzzerEnabled = optionalExternalBuzzerEnabled,
        )
        PrinterModelDto.StarTsp650II -> PrinterProfiles.tsp650II(columnsPerLine, compatibilityMode, soundEnabled)
        PrinterModelDto.StarMcPrint3 -> PrinterProfiles.mcPrint3(columnsPerLine, compatibilityMode, soundEnabled)
        PrinterModelDto.SiiRpF10 -> PrinterProfiles.rpF10(columnsPerLine, compatibilityMode, soundEnabled)
    }
}

private fun PrintDirectionDto.toPrintDirection(): PrintDirection = when (this) {
    PrintDirectionDto.Normal -> PrintDirection.Normal
    PrintDirectionDto.Reverse -> PrintDirection.Reverse
}

private fun JobActionDto.toJobAction(): JobAction = when (this) {
    JobActionDto.PlaySound -> JobAction.PlaySound
}

private fun CutModeDto.toCutMode(): CutMode = when (this) {
    CutModeDto.None -> CutMode.None
    CutModeDto.Full -> CutMode.Full
    CutModeDto.Partial -> CutMode.Partial
    CutModeDto.NoMargin -> CutMode.NoMargin
}

private fun TextAlignDto.toTextAlign(): TextAlign = when (this) {
    TextAlignDto.Left -> TextAlign.Left
    TextAlignDto.Center -> TextAlign.Center
    TextAlignDto.Right -> TextAlign.Right
}

private fun WrapModeDto.toWrapMode(): WrapMode = when (this) {
    WrapModeDto.None -> WrapMode.None
    WrapModeDto.Character -> WrapMode.Character
    WrapModeDto.WordOrCharacter -> WrapMode.WordOrCharacter
}

private fun TwoColumnOverflowDto.toTwoColumnOverflow(): TwoColumnOverflow = when (this) {
    TwoColumnOverflowDto.WrapLeadingThenTrailing -> TwoColumnOverflow.WrapLeadingThenTrailing
    TwoColumnOverflowDto.TrailingOnNextLine -> TwoColumnOverflow.TrailingOnNextLine
    TwoColumnOverflowDto.Clip -> TwoColumnOverflow.Clip
    TwoColumnOverflowDto.Fail -> TwoColumnOverflow.Fail
}

private fun QrErrorCorrectionDto.toQrErrorCorrection(): QrErrorCorrection = when (this) {
    QrErrorCorrectionDto.Low -> QrErrorCorrection.Low
    QrErrorCorrectionDto.Medium -> QrErrorCorrection.Medium
    QrErrorCorrectionDto.Quartile -> QrErrorCorrection.Quartile
    QrErrorCorrectionDto.High -> QrErrorCorrection.High
}

private fun CompatibilityModeDto.toCompatibilityMode(): CompatibilityMode = when (this) {
    CompatibilityModeDto.Strict -> CompatibilityMode.Strict
    CompatibilityModeDto.Compatibility -> CompatibilityMode.Compatibility
}

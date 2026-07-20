package receiptdsl.engine

import kotlin.jvm.JvmInline

public data class PrintJobDto(
    val tickets: List<TicketDto>,
    val direction: PrintDirectionDto = PrintDirectionDto.Normal,
    val startActions: List<JobActionDto> = emptyList(),
)

public data class TicketDto(
    val children: PrintNodeListDto,
    val cut: CutModeDto = CutModeDto.Partial,
)

@JvmInline
public value class PrintNodeListDto(
    public val nodes: List<PrintNodeDto>,
)

public sealed interface PrintNodeDto

public data class ColumnDto(
    val children: PrintNodeListDto,
    val style: TextStyleDto? = null,
) : PrintNodeDto

public data class TextDto(
    val value: String,
    val style: TextStyleDto = TextStyleDto(),
    val wrap: WrapModeDto = WrapModeDto.WordOrCharacter,
    val preserveNewLines: Boolean = true,
) : PrintNodeDto

public data class TwoColumnDto(
    val leading: TextRunDto,
    val trailing: TextRunDto? = null,
    val widthColumns: Int? = null,
    val leadingReservedColumns: Int? = null,
    val trailingReservedColumns: Int? = null,
    val overflow: TwoColumnOverflowDto = TwoColumnOverflowDto.WrapLeadingThenTrailing,
) : PrintNodeDto

public data class RuleDto(
    val thickness: Int = 1,
) : PrintNodeDto

public data class QrCodeDto(
    val data: String,
    val cellSize: Int = 6,
    val errorCorrection: QrErrorCorrectionDto = QrErrorCorrectionDto.Medium,
    val align: TextAlignDto = TextAlignDto.Center,
) : PrintNodeDto

public data class FeedDto(
    val lines: Int = 1,
) : PrintNodeDto

public data class TextRunDto(
    val value: String,
    val style: TextStyleDto = TextStyleDto(),
)

public data class TextStyleDto(
    val widthScale: Int = 1,
    val heightScale: Int = 1,
    val emphasized: Boolean = false,
    val inverted: Boolean = false,
    val align: TextAlignDto = TextAlignDto.Left,
)

public enum class PrintDirectionDto {
    Normal,
    Reverse,
}

public enum class JobActionDto {
    PlaySound,
}

public enum class CutModeDto {
    None,
    Full,
    Partial,
    NoMargin,
}

public enum class TextAlignDto {
    Left,
    Center,
    Right,
}

public enum class WrapModeDto {
    None,
    Character,
    WordOrCharacter,
}

public enum class TwoColumnOverflowDto {
    WrapLeadingThenTrailing,
    TrailingOnNextLine,
    Clip,
    Fail,
}

public enum class QrErrorCorrectionDto {
    Low,
    Medium,
    Quartile,
    High,
}

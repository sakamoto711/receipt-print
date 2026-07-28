package receiptdsl.engine

public data class PrinterProfileDto(
    val model: PrinterModelDto,
    val columnsPerLine: Int = 48,
    val compatibilityMode: CompatibilityModeDto = CompatibilityModeDto.Strict,
    val soundEnabled: Boolean = true,
    val optionalExternalBuzzerEnabled: Boolean = false,
)

public enum class PrinterModelDto {
    StarFvp10,
    EpsonTmT90Kp,
    EpsonTmM30,
    StarTsp650II,
    StarMcPrint3,
    SiiRpF10,
}

public enum class CompatibilityModeDto {
    Strict,
    Compatibility,
}

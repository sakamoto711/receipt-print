package receiptdsl.model

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

public data class PrinterProfile(
    val id: String,
    val model: PrinterModel = PrinterModel.Custom,
    val commandSet: CommandSet,
    val columnsPerLine: Int,
    val textEncoding: TextEncoding = TextEncoding.Cp932,
    val capabilities: PrinterCapabilities,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
    val soundEnabled: Boolean = true,
)

public enum class PrinterModel {
    Custom,
    StarFvp10,
    EpsonTmT90Kp,
    EpsonTmM30,
    StarTsp650II,
    StarMcPrint3,
    SiiRpF10,
}

public enum class CommandSet {
    EscPos,
    StarPrnt,
    StarLineMode,
}

public enum class TextEncoding {
    Cp932,
}

public enum class CompatibilityMode {
    Strict,
    Compatibility,
}

public data class PrinterCapabilities(
    val maxWidthScale: Int,
    val maxHeightScale: Int,
    val supportedCuts: PersistentSet<CutMode>,
    val supportsQrCode: Boolean,
    val supportsEmphasis: Boolean,
    val supportsInverse: Boolean,
    val reverseMode: ReverseMode,
    val soundMode: SoundMode,
    val maxQrCellSize: Int = 255,
    val compatibilityQrCellSize: Int? = null,
    val printableWidthDots: Int = DEFAULT_PRINTABLE_WIDTH_DOTS,
)

public enum class ReverseMode {
    NativePageReverse,
    ReorderLinesAndReverseGlyphs,
    Unsupported,
}

public enum class SoundMode {
    NativeBuzzer,
    ExternalBuzzer,
    Unsupported,
}

public object PrinterProfiles {
    public fun fvp10(
        columnsPerLine: Int = DEFAULT_COLUMNS_PER_LINE,
        compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
        soundEnabled: Boolean = true,
    ): PrinterProfile = starLineProfile(
        id = "star-fvp10",
        model = PrinterModel.StarFvp10,
        columnsPerLine = columnsPerLine,
        compatibilityMode = compatibilityMode,
        soundMode = SoundMode.NativeBuzzer,
        soundEnabled = soundEnabled,
    )

    public fun tmT90Kp(
        columnsPerLine: Int = DEFAULT_COLUMNS_PER_LINE,
        compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
        soundEnabled: Boolean = true,
    ): PrinterProfile = escPosProfile(
        id = "epson-tm-t90kp",
        model = PrinterModel.EpsonTmT90Kp,
        columnsPerLine = columnsPerLine,
        compatibilityMode = compatibilityMode,
        reverseMode = ReverseMode.ReorderLinesAndReverseGlyphs,
        soundMode = SoundMode.NativeBuzzer,
        soundEnabled = soundEnabled,
    )

    public fun tmM30(
        columnsPerLine: Int = DEFAULT_COLUMNS_PER_LINE,
        compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
        soundEnabled: Boolean = true,
        optionalExternalBuzzerEnabled: Boolean = false,
    ): PrinterProfile = escPosProfile(
        id = "epson-tm-m30",
        model = PrinterModel.EpsonTmM30,
        columnsPerLine = columnsPerLine,
        compatibilityMode = compatibilityMode,
        reverseMode = ReverseMode.Unsupported,
        soundMode = if (optionalExternalBuzzerEnabled) SoundMode.ExternalBuzzer else SoundMode.Unsupported,
        soundEnabled = soundEnabled,
    )

    public fun tsp650II(
        columnsPerLine: Int = DEFAULT_COLUMNS_PER_LINE,
        compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
        soundEnabled: Boolean = true,
    ): PrinterProfile = starLineProfile(
        id = "star-tsp650ii",
        model = PrinterModel.StarTsp650II,
        columnsPerLine = columnsPerLine,
        compatibilityMode = compatibilityMode,
        soundMode = SoundMode.ExternalBuzzer,
        soundEnabled = soundEnabled,
    )

    public fun mcPrint3(
        columnsPerLine: Int = DEFAULT_COLUMNS_PER_LINE,
        compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
        soundEnabled: Boolean = true,
    ): PrinterProfile = PrinterProfile(
        id = "star-mc-print3",
        model = PrinterModel.StarMcPrint3,
        commandSet = CommandSet.StarPrnt,
        columnsPerLine = columnsPerLine,
        capabilities = starCapabilities(SoundMode.ExternalBuzzer),
        compatibilityMode = compatibilityMode,
        soundEnabled = soundEnabled,
    )

    public fun rpF10(
        columnsPerLine: Int = DEFAULT_COLUMNS_PER_LINE,
        compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
        soundEnabled: Boolean = true,
    ): PrinterProfile = escPosProfile(
        id = "sii-rp-f10",
        model = PrinterModel.SiiRpF10,
        columnsPerLine = columnsPerLine,
        compatibilityMode = compatibilityMode,
        reverseMode = ReverseMode.ReorderLinesAndReverseGlyphs,
        soundMode = SoundMode.NativeBuzzer,
        soundEnabled = soundEnabled,
    )

    private fun escPosProfile(
        id: String,
        model: PrinterModel,
        columnsPerLine: Int,
        compatibilityMode: CompatibilityMode,
        reverseMode: ReverseMode,
        soundMode: SoundMode,
        soundEnabled: Boolean,
    ): PrinterProfile = PrinterProfile(
        id = id,
        model = model,
        commandSet = CommandSet.EscPos,
        columnsPerLine = columnsPerLine,
        capabilities = PrinterCapabilities(
            maxWidthScale = 8,
            maxHeightScale = 8,
            supportedCuts = if (model == PrinterModel.EpsonTmT90Kp) {
                persistentSetOf(CutMode.Full, CutMode.Partial, CutMode.NoMargin)
            } else {
                persistentSetOf(CutMode.Full, CutMode.Partial)
            },
            supportsQrCode = true,
            supportsEmphasis = true,
            supportsInverse = true,
            reverseMode = reverseMode,
            soundMode = soundMode,
            maxQrCellSize = 16,
            compatibilityQrCellSize = 6,
        ),
        compatibilityMode = compatibilityMode,
        soundEnabled = soundEnabled,
    )

    private fun starLineProfile(
        id: String,
        model: PrinterModel,
        columnsPerLine: Int,
        compatibilityMode: CompatibilityMode,
        soundMode: SoundMode,
        soundEnabled: Boolean,
    ): PrinterProfile = PrinterProfile(
        id = id,
        model = model,
        commandSet = CommandSet.StarLineMode,
        columnsPerLine = columnsPerLine,
        capabilities = starCapabilities(soundMode),
        compatibilityMode = compatibilityMode,
        soundEnabled = soundEnabled,
    )

    private fun starCapabilities(soundMode: SoundMode): PrinterCapabilities =
        PrinterCapabilities(
            maxWidthScale = 6,
            maxHeightScale = 6,
            supportedCuts = persistentSetOf(CutMode.Full, CutMode.Partial),
            supportsQrCode = true,
            supportsEmphasis = true,
            supportsInverse = true,
            reverseMode = ReverseMode.NativePageReverse,
            soundMode = soundMode,
            maxQrCellSize = 8,
            compatibilityQrCellSize = 6,
        )

    private const val DEFAULT_COLUMNS_PER_LINE: Int = 48
}

private const val DEFAULT_PRINTABLE_WIDTH_DOTS: Int = 576

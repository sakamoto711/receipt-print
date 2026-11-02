/// Input models corresponding to the public Kotlin receipt-engine DTOs.
library;

enum PrintDirectionDto {
  normal('Normal'),
  reverse('Reverse');

  const PrintDirectionDto(this.wireValue);
  final String wireValue;
}

enum JobActionDto {
  playSound('PlaySound');

  const JobActionDto(this.wireValue);
  final String wireValue;
}

enum CutModeDto {
  none('None'),
  full('Full'),
  partial('Partial'),
  noMargin('NoMargin');

  const CutModeDto(this.wireValue);
  final String wireValue;
}

enum TextAlignDto {
  left('Left'),
  center('Center'),
  right('Right');

  const TextAlignDto(this.wireValue);
  final String wireValue;
}

enum WrapModeDto {
  none('None'),
  character('Character'),
  wordOrCharacter('WordOrCharacter');

  const WrapModeDto(this.wireValue);
  final String wireValue;
}

enum TwoColumnOverflowDto {
  wrapLeadingThenTrailing('WrapLeadingThenTrailing'),
  trailingOnNextLine('TrailingOnNextLine'),
  clip('Clip'),
  fail('Fail');

  const TwoColumnOverflowDto(this.wireValue);
  final String wireValue;
}

enum QrErrorCorrectionDto {
  low('Low'),
  medium('Medium'),
  quartile('Quartile'),
  high('High');

  const QrErrorCorrectionDto(this.wireValue);
  final String wireValue;
}

enum PrinterModelDto {
  starFvp10('StarFvp10'),
  epsonTmT90Kp('EpsonTmT90Kp'),
  epsonTmM30('EpsonTmM30'),
  starTsp650II('StarTsp650II'),
  starMcPrint3('StarMcPrint3'),
  siiRpF10('SiiRpF10');

  const PrinterModelDto(this.wireValue);
  final String wireValue;
}

enum CompatibilityModeDto {
  strict('Strict'),
  compatibility('Compatibility');

  const CompatibilityModeDto(this.wireValue);
  final String wireValue;
}

sealed class PrintNodeDto {
  const PrintNodeDto();
  Map<String, Object?> toMap();
}

final class PrintJobDto {
  const PrintJobDto({
    required this.tickets,
    this.direction = PrintDirectionDto.normal,
    this.startActions = const [],
  });

  final List<TicketDto> tickets;
  final PrintDirectionDto direction;
  final List<JobActionDto> startActions;

  Map<String, Object?> toMap() => {
    'tickets': tickets.map((value) => value.toMap()).toList(),
    'direction': direction.wireValue,
    'startActions': startActions.map((value) => value.wireValue).toList(),
  };
}

final class TicketDto {
  const TicketDto({required this.children, this.cut = CutModeDto.partial});

  final List<PrintNodeDto> children;
  final CutModeDto cut;

  Map<String, Object?> toMap() => {
    'children': children.map((value) => value.toMap()).toList(),
    'cut': cut.wireValue,
  };
}

final class ColumnDto extends PrintNodeDto {
  const ColumnDto({required this.children, this.style});

  final List<PrintNodeDto> children;
  final TextStyleDto? style;

  @override
  Map<String, Object?> toMap() => {
    'type': 'column',
    'children': children.map((value) => value.toMap()).toList(),
    'style': style?.toMap(),
  };
}

final class TextDto extends PrintNodeDto {
  const TextDto({
    required this.value,
    this.style = const TextStyleDto(),
    this.wrap = WrapModeDto.wordOrCharacter,
    this.preserveNewLines = true,
  });

  final String value;
  final TextStyleDto style;
  final WrapModeDto wrap;
  final bool preserveNewLines;

  @override
  Map<String, Object?> toMap() => {
    'type': 'text',
    'value': value,
    'style': style.toMap(),
    'wrap': wrap.wireValue,
    'preserveNewLines': preserveNewLines,
  };
}

final class TwoColumnDto extends PrintNodeDto {
  const TwoColumnDto({
    required this.leading,
    this.trailing,
    this.widthColumns,
    this.leadingReservedColumns,
    this.trailingReservedColumns,
    this.overflow = TwoColumnOverflowDto.wrapLeadingThenTrailing,
  });

  final TextRunDto leading;
  final TextRunDto? trailing;
  final int? widthColumns;
  final int? leadingReservedColumns;
  final int? trailingReservedColumns;
  final TwoColumnOverflowDto overflow;

  @override
  Map<String, Object?> toMap() => {
    'type': 'twoColumn',
    'leading': leading.toMap(),
    'trailing': trailing?.toMap(),
    'widthColumns': widthColumns,
    'leadingReservedColumns': leadingReservedColumns,
    'trailingReservedColumns': trailingReservedColumns,
    'overflow': overflow.wireValue,
  };
}

final class RuleDto extends PrintNodeDto {
  const RuleDto({this.thickness = 1});

  final int thickness;

  @override
  Map<String, Object?> toMap() => {'type': 'rule', 'thickness': thickness};
}

final class QrCodeDto extends PrintNodeDto {
  const QrCodeDto({
    required this.data,
    this.cellSize = 6,
    this.errorCorrection = QrErrorCorrectionDto.medium,
    this.align = TextAlignDto.center,
  });

  final String data;
  final int cellSize;
  final QrErrorCorrectionDto errorCorrection;
  final TextAlignDto align;

  @override
  Map<String, Object?> toMap() => {
    'type': 'qrCode',
    'data': data,
    'cellSize': cellSize,
    'errorCorrection': errorCorrection.wireValue,
    'align': align.wireValue,
  };
}

final class FeedDto extends PrintNodeDto {
  const FeedDto({this.lines = 1});

  final int lines;

  @override
  Map<String, Object?> toMap() => {'type': 'feed', 'lines': lines};
}

final class TextRunDto {
  const TextRunDto({required this.value, this.style = const TextStyleDto()});

  final String value;
  final TextStyleDto style;

  Map<String, Object?> toMap() => {'value': value, 'style': style.toMap()};
}

final class TextStyleDto {
  const TextStyleDto({
    this.widthScale = 1,
    this.heightScale = 1,
    this.emphasized = false,
    this.inverted = false,
    this.align = TextAlignDto.left,
  });

  final int widthScale;
  final int heightScale;
  final bool emphasized;
  final bool inverted;
  final TextAlignDto align;

  Map<String, Object?> toMap() => {
    'widthScale': widthScale,
    'heightScale': heightScale,
    'emphasized': emphasized,
    'inverted': inverted,
    'align': align.wireValue,
  };
}

final class PrinterProfileDto {
  const PrinterProfileDto({
    required this.model,
    this.columnsPerLine = 48,
    this.compatibilityMode = CompatibilityModeDto.strict,
    this.soundEnabled = true,
    this.optionalExternalBuzzerEnabled = false,
  });

  final PrinterModelDto model;
  final int columnsPerLine;
  final CompatibilityModeDto compatibilityMode;
  final bool soundEnabled;
  final bool optionalExternalBuzzerEnabled;

  Map<String, Object?> toMap() => {
    'model': model.wireValue,
    'columnsPerLine': columnsPerLine,
    'compatibilityMode': compatibilityMode.wireValue,
    'soundEnabled': soundEnabled,
    'optionalExternalBuzzerEnabled': optionalExternalBuzzerEnabled,
  };
}

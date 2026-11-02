import 'dart:typed_data';

enum DiagnosticSeverityDto { warning, error }

final class DiagnosticDto {
  DiagnosticDto.fromMap(Map<Object?, Object?> map)
    : code = map['code'] as String,
      severity = switch (map['severity']) {
        'Warning' => DiagnosticSeverityDto.warning,
        'Error' => DiagnosticSeverityDto.error,
        _ => throw const FormatException('Unknown diagnostic severity'),
      },
      message = map['message'] as String,
      ticketIndex = map['ticketIndex'] as int?,
      childIndexes = List<int>.unmodifiable(
        (map['childIndexes'] as List).cast<int>(),
      );

  final String code;
  final DiagnosticSeverityDto severity;
  final String message;
  final int? ticketIndex;
  final List<int> childIndexes;
}

List<DiagnosticDto> _diagnostics(Map<Object?, Object?> map) =>
    List<DiagnosticDto>.unmodifiable(
      (map['diagnostics'] as List).map(
        (value) => DiagnosticDto.fromMap(value as Map<Object?, Object?>),
      ),
    );

final class CompileResponseDto {
  CompileResponseDto.fromMap(Map<Object?, Object?> map)
    : bytes = map['bytes'] as Uint8List?,
      diagnostics = _diagnostics(map);

  final Uint8List? bytes;
  final List<DiagnosticDto> diagnostics;
  bool get isSuccess =>
      bytes != null &&
      !diagnostics.any(
        (value) => value.severity == DiagnosticSeverityDto.error,
      );
}

final class PreviewResponseDto {
  PreviewResponseDto.fromMap(Map<Object?, Object?> map)
    : receiptLine = map['receiptLine'] as String?,
      diagnostics = _diagnostics(map);

  final String? receiptLine;
  final List<DiagnosticDto> diagnostics;
  bool get isSuccess =>
      receiptLine != null &&
      !diagnostics.any(
        (value) => value.severity == DiagnosticSeverityDto.error,
      );
}

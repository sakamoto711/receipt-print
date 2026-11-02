import 'package:flutter/services.dart';

import 'src/models.dart';
import 'src/responses.dart';

export 'src/models.dart';
export 'src/responses.dart';
export 'src/preview_view.dart';

/// Converts print trees; the caller owns printer connections and transmission.
final class ReceiptPrint {
  static const _channel = MethodChannel('dev.receiptprint/receipt_print');

  Future<int> measureText(String text) async => (await _channel.invokeMethod<int>('measureText', {
        'protocolVersion': 1,
        'text': text,
      }))!;

  /// Renders ReceiptLine to SVG locally on iOS. Does not access a printer or network.
  Future<String> renderSvg(String receiptLine, {required int columns}) async => (await _channel.invokeMethod<String>('renderSvg', {
        'protocolVersion': 1,
        'receiptLine': receiptLine,
        'columns': columns,
      }))!;

  Future<CompileResponseDto> compile({
    required PrintJobDto job,
    required PrinterProfileDto printer,
  }) async {
    final response = await _invoke('compile', job, printer);
    return CompileResponseDto.fromMap(response);
  }

  Future<PreviewResponseDto> preview({
    required PrintJobDto job,
    required PrinterProfileDto printer,
  }) async {
    final response = await _invoke('preview', job, printer);
    return PreviewResponseDto.fromMap(response);
  }

  Future<Map<Object?, Object?>> _invoke(
    String method,
    PrintJobDto job,
    PrinterProfileDto printer,
  ) async {
    final response = await _channel.invokeMapMethod<Object?, Object?>(method, {
      'protocolVersion': 1,
      'job': job.toMap(),
      'printer': printer.toMap(),
    });
    if (response == null) {
      throw const FormatException('receipt_print returned a null response');
    }
    return response;
  }
}

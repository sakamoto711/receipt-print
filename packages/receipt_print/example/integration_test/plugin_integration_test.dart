import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:receipt_print/receipt_print.dart';
import 'package:receipt_print_example/main.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final engine = ReceiptPrint();

  testWidgets('all node kinds cross the real native channel', (tester) async {
    final compiled = await engine.compile(
      job: sampleJob,
      printer: samplePrinter,
    );
    final preview = await engine.preview(
      job: sampleJob,
      printer: samplePrinter,
    );
    expect(
      compiled.isSuccess,
      isTrue,
      reason: compiled.diagnostics.map((d) => d.message).join(),
    );
    expect(compiled.bytes, isNotEmpty);
    expect(compiled.bytes!.any((byte) => byte > 127), isTrue);
    expect(preview.isSuccess, isTrue);
    expect(preview.receiptLine, contains('注文 #12'));
    expect(preview.receiptLine, contains('コーヒー | 2'));
    expect(preview.receiptLine, contains('{code:'));
  });

  testWidgets('all six printers compile a receipt', (tester) async {
    const job = PrintJobDto(
      tickets: [
        TicketDto(
          children: [TextDto(value: 'A')],
          cut: CutModeDto.none,
        ),
      ],
    );
    for (final model in PrinterModelDto.values) {
      final response = await engine.compile(
        job: job,
        printer: PrinterProfileDto(model: model),
      );
      expect(response.isSuccess, isTrue, reason: model.name);
    }
  });

  testWidgets('nested validation errors preserve their positions', (
    tester,
  ) async {
    const job = PrintJobDto(
      tickets: [
        TicketDto(
          children: [
            TextDto(value: 'valid'),
            ColumnDto(children: [FeedDto(lines: 0)]),
          ],
        ),
      ],
    );
    final compiled = await engine.compile(job: job, printer: samplePrinter);
    final preview = await engine.preview(job: job, printer: samplePrinter);
    expect(compiled.isSuccess, isFalse);
    expect(compiled.bytes, isNull);
    expect(preview.receiptLine, isNull);
    for (final diagnostic in [
      compiled.diagnostics.single,
      preview.diagnostics.single,
    ]) {
      expect(diagnostic.severity, DiagnosticSeverityDto.error);
      expect(diagnostic.ticketIndex, 0);
      expect(diagnostic.childIndexes, [1, 0]);
    }
  });

  testWidgets('start actions and printer flags reach the engine', (
    tester,
  ) async {
    const job = PrintJobDto(
      tickets: [
        TicketDto(
          children: [TextDto(value: 'sound')],
          cut: CutModeDto.none,
        ),
      ],
      startActions: [JobActionDto.playSound],
    );
    final unconfigured = await engine.compile(job: job, printer: samplePrinter);
    expect(unconfigured.isSuccess, isFalse);
    final configured = await engine.compile(
      job: job,
      printer: const PrinterProfileDto(
        model: PrinterModelDto.epsonTmM30,
        optionalExternalBuzzerEnabled: true,
      ),
    );
    expect(configured.isSuccess, isTrue);
    final disabled = await engine.compile(
      job: job,
      printer: const PrinterProfileDto(
        model: PrinterModelDto.epsonTmM30,
        soundEnabled: false,
      ),
    );
    expect(disabled.isSuccess, isTrue);
    expect(disabled.diagnostics.single.severity, DiagnosticSeverityDto.warning);
  });

  testWidgets('unsupported glyphs are returned as diagnostics', (tester) async {
    final response = await engine.compile(
      job: const PrintJobDto(
        tickets: [
          TicketDto(
            children: [TextDto(value: 'A🙂B')],
            cut: CutModeDto.none,
          ),
        ],
      ),
      printer: samplePrinter,
    );
    expect(response.bytes, isNull);
    expect(response.diagnostics.single.code, 'UNSUPPORTED_GLYPH');
  });

  testWidgets('malformed requests and unknown methods fail explicitly', (
    tester,
  ) async {
    const channel = MethodChannel('dev.receiptprint/receipt_print');
    await expectLater(
      channel.invokeMethod<void>('compile', {'protocolVersion': 99}),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'invalid_arguments',
        ),
      ),
    );
    await expectLater(
      channel.invokeMethod<void>('unknown'),
      throwsA(isA<MissingPluginException>()),
    );
  });
}

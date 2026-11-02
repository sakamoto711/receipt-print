import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:receipt_print/receipt_print.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.receiptprint/receipt_print');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final engine = ReceiptPrint();
  const job = PrintJobDto(
    tickets: [
      TicketDto(
        children: [
          ColumnDto(
            children: [
              TextDto(value: '日本語', style: TextStyleDto(emphasized: true)),
              TwoColumnDto(
                leading: TextRunDto(value: 'item'),
                trailing: TextRunDto(value: '100'),
              ),
              RuleDto(),
              QrCodeDto(data: 'qr'),
              FeedDto(),
            ],
          ),
        ],
      ),
    ],
    startActions: [JobActionDto.playSound],
  );
  const printer = PrinterProfileDto(model: PrinterModelDto.epsonTmM30);

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'compile sends codec-compatible typed trees and preserves unsigned bytes',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'compile');
        final arguments = call.arguments as Map;
        expect(arguments['protocolVersion'], 1);
        final mappedJob = arguments['job'] as Map;
        expect(mappedJob['startActions'], ['PlaySound']);
        final ticket = (mappedJob['tickets'] as List).single as Map;
        final column = (ticket['children'] as List).single as Map;
        final children = column['children'] as List;
        expect(children.map((value) => (value as Map)['type']), [
          'text',
          'twoColumn',
          'rule',
          'qrCode',
          'feed',
        ]);
        expect(((children.first as Map)['style'] as Map)['emphasized'], isTrue);
        expect(arguments['printer'], printer.toMap());
        return {
          'bytes': Uint8List.fromList([0, 127, 128, 255]),
          'diagnostics': [],
        };
      });
      final response = await engine.compile(job: job, printer: printer);
      expect(response.isSuccess, isTrue);
      expect(response.bytes, [0, 127, 128, 255]);
    },
  );

  test(
    'preview preserves warnings and nullable diagnostic positions',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'preview');
        return {
          'receiptLine': '日本語',
          'diagnostics': [
            {
              'code': 'PREVIEW_APPROXIMATION',
              'severity': 'Warning',
              'message': 'approximation',
              'ticketIndex': null,
              'childIndexes': [],
            },
          ],
        };
      });
      final response = await engine.preview(job: job, printer: printer);
      expect(response.isSuccess, isTrue);
      expect(response.receiptLine, '日本語');
      expect(response.diagnostics.single.ticketIndex, isNull);
      expect(
        response.diagnostics.single.severity,
        DiagnosticSeverityDto.warning,
      );
    },
  );

  test('validation failure preserves the tree path and null output', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => {
        'bytes': null,
        'diagnostics': [
          {
            'code': 'INVALID_VALUE',
            'severity': 'Error',
            'message': 'invalid feed',
            'ticketIndex': 1,
            'childIndexes': [2, 0],
          },
        ],
      },
    );
    final response = await engine.compile(job: job, printer: printer);
    expect(response.isSuccess, isFalse);
    expect(response.bytes, isNull);
    expect(response.diagnostics.single.ticketIndex, 1);
    expect(response.diagnostics.single.childIndexes, [2, 0]);
  });

  test('native errors propagate as PlatformException', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(
        code: 'invalid_arguments',
        message: 'invalid request',
      ),
    );
    await expectLater(
      engine.compile(job: job, printer: printer),
      throwsA(isA<PlatformException>()),
    );
  });

  test('malformed null responses fail explicitly', () async {
    messenger.setMockMethodCallHandler(channel, (_) async => null);
    await expectLater(
      engine.compile(job: job, printer: printer),
      throwsA(isA<FormatException>()),
    );
  });
}

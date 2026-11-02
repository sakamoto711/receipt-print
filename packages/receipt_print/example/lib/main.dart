import 'package:flutter/material.dart';
import 'package:receipt_print/receipt_print.dart';

void main() => runApp(const ReceiptSampleApp());

const sampleJob = PrintJobDto(
  tickets: [
    TicketDto(
      children: [
        TextDto(value: '注文 #12', style: TextStyleDto(emphasized: true)),
        TwoColumnDto(
          leading: TextRunDto(value: 'コーヒー'),
          trailing: TextRunDto(value: '2'),
        ),
        ColumnDto(
          children: [
            RuleDto(),
            QrCodeDto(data: 'https://example.com/order/12'),
            FeedDto(),
          ],
        ),
      ],
    ),
  ],
);
const samplePrinter = PrinterProfileDto(model: PrinterModelDto.epsonTmM30);

class ReceiptSampleApp extends StatefulWidget {
  const ReceiptSampleApp({super.key});

  @override
  State<ReceiptSampleApp> createState() => _ReceiptSampleAppState();
}

class _ReceiptSampleAppState extends State<ReceiptSampleApp> {
  String _output = '変換するボタンでコマンドとプレビューを生成します。';
  bool _busy = false;

  Future<void> _convert() async {
    setState(() => _busy = true);
    String output;
    try {
      final engine = ReceiptPrint();
      final compiled = await engine.compile(
        job: sampleJob,
        printer: samplePrinter,
      );
      final preview = await engine.preview(
        job: sampleJob,
        printer: samplePrinter,
      );
      output =
          'コマンド: ${compiled.bytes?.length ?? 0} bytes\n'
          '${preview.receiptLine ?? "プレビューなし"}\n'
          '${[...compiled.diagnostics, ...preview.diagnostics].map((d) => "${d.code}: ${d.message}").join("\n")}';
    } catch (error) {
      output = '変換に失敗しました: $error';
    }
    if (!mounted) return;
    setState(() {
      _output = output;
      _busy = false;
    });
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(
      appBar: AppBar(title: const Text('receipt-print')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          FilledButton(
            onPressed: _busy ? null : _convert,
            child: const Text('変換する'),
          ),
          SelectableText(_output),
        ],
      ),
    ),
  );
}

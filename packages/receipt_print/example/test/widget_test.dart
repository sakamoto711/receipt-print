import 'package:flutter_test/flutter_test.dart';
import 'package:receipt_print_example/main.dart';

void main() {
  testWidgets('shows the conversion sample', (tester) async {
    await tester.pumpWidget(const ReceiptSampleApp());
    expect(find.text('変換する'), findsOneWidget);
  });
}

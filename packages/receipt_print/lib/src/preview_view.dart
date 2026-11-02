import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Renders the generated SVG with native SVG text/filter support and local scrolling.
/// The view does not load scripts or remote resources.
class ReceiptPreviewView extends StatelessWidget {
  final String svg;
  const ReceiptPreviewView({super.key, required this.svg});
  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.iOS) {
      return const Center(child: Text('Receipt preview requires iOS.'));
    }
    return UiKitView(
      key: ValueKey(svg),
      viewType: 'receipt_print/preview',
      gestureRecognizers: {Factory<OneSequenceGestureRecognizer>(() => EagerGestureRecognizer())},
      creationParams: {'svg': svg},
      creationParamsCodec: const StandardMessageCodec(),
    );
  }
}

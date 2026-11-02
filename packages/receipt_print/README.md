# receipt_print

汎用印刷ツリーからサーマルプリンター向けコマンドと ReceiptLine プレビューを生成する Flutter プラグインです。
変換処理は、このリポジトリの Kotlin Multiplatform `ReceiptEngine` を利用します。
プリンターへの接続、送信、再試行は利用側で実装します。

## 対応環境

- Flutter 3.24.2 以降、Dart 3.5.2 以降（example の検証用SDKはリポジトリの `.tool-versions` を使用）
- Android API 24 以降、JDK 17 以降、Kotlin 2.4.10 以降
- iOS 15 以降、arm64 の実機と Apple Silicon Simulator

Android の検証用 example は AGP 9.0.1 と Kotlin 2.4.10 を使用します。
Flutter の iOS 連携は CocoaPods と Swift Package Manager の定義を含みます。
Web、macOS、Windows、Linux と Intel Mac の iOS Simulator は対象外です。
iOS アプリの Runner ターゲットの Minimum Deployments を 15.0 以上に設定してください。
CocoaPods を使うアプリでは、`Podfile` にも `platform :ios, '15.0'` を指定します。

## 利用準備

リポジトリのルートで実行します。
iOS 向け配布物の生成には macOS と Xcode が必要です。

```shell
./gradlew prepareFlutterPlugin
```

Android だけを使う場合は `prepareFlutterAndroid`、iOS だけを使う場合は `prepareFlutterIos` を実行できます。
生成する Maven リポジトリと XCFramework は Git 管理対象外です。
KMP のコードを変更した場合は、同じタスクで再生成してください。
Maven の配布物には SNAPSHOT バージョンを使用しているため、Android 側が以前の内容を使う場合はアプリの Gradle で `--refresh-dependencies` を指定してください。

利用側の `pubspec.yaml` に追加し、`flutter pub get` を実行します。
パスは利用側プロジェクトからの相対パスです。

```yaml
dependencies:
  receipt_print:
    path: ../receipt-print/packages/receipt_print
```

現在はローカルパッケージとして利用します。
Git 依存として取得する場合も、取得先にネイティブ配布物を準備する必要があります。
`publish_to: none` としており、公開レジストリへの配布設定は含みません。

Android の依存解決でプロジェクト側のリポジトリを禁止している場合は、利用側の `settings.gradle.kts` の `dependencyResolutionManagement.repositories` に、このパッケージの `android/maven` を追加してください。
`receipt-kmp` グループの JVM ライブラリをこの場所から解決します。
`FAIL_ON_PROJECT_REPOS` を使う場合は、利用側の `gradle.properties` に `receiptPrintConfigureRepositories=false` も指定し、プラグインによるリポジトリの自動追加を無効にしてください。

## 使用例

```dart
import 'package:receipt_print/receipt_print.dart';

final receipt = ReceiptPrint();
const job = PrintJobDto(tickets: [
  TicketDto(children: [
    TextDto(value: '注文 #12', style: TextStyleDto(emphasized: true)),
    TwoColumnDto(
      leading: TextRunDto(value: 'コーヒー'),
      trailing: TextRunDto(value: '2'),
    ),
    RuleDto(),
    QrCodeDto(data: 'https://example.com/order/12'),
    FeedDto(),
  ]),
]);
const printer = PrinterProfileDto(model: PrinterModelDto.epsonTmM30);

final compiled = await receipt.compile(job: job, printer: printer);
if (compiled.isSuccess) {
  final bytes = compiled.bytes!; // Uint8List。利用側でプリンターへ送信する。
  print('コマンド長: ${bytes.length}');
}
for (final diagnostic in compiled.diagnostics) {
  print('${diagnostic.code}: ${diagnostic.message}');
}

final preview = await receipt.preview(job: job, printer: printer);
print(preview.receiptLine);
```

`ColumnDto` でツリーを入れ子にできます。
公開 DTO のフィールドと既定値は KMP に対応し、子ノードの配列には Dart の `List<PrintNodeDto>` を使います。
`PrinterModelDto` の 6 機種に対応します。
Dart のモデルで渡す整数は、Kotlin の `Int` と同じ符号付き 32 ビット整数の範囲です。

変換が成功すると `isSuccess` が `true` になります。
警告だけの場合は、出力と診断の両方を返します。
検証エラーの場合は `bytes` または `receiptLine` が `null` になり、`diagnostics` の `ticketIndex` と `childIndexes` から該当ノードを特定できます。
プレビューには ReceiptLine の表現上の近似を示す警告が含まれる場合があります。
Channel 引数の不正など、呼び出し自体の失敗は `PlatformException` として通知します。

## 構成

```text
Flutter → Dart API → MethodChannel → Kotlin / Swift → ReceiptEngine
Android ネイティブ → Kotlin → ReceiptEngine
 iOS ネイティブ    → Swift  → ReceiptEngine
```

Dart は入力モデルと結果モデル、および Channel 呼び出しを担当します。
検証、レイアウト、コマンド生成を Dart に重複実装しません。
Android と iOS のネイティブプロジェクトは、既存の KMP 配布物と XCFramework を直接利用できます。

## 検証

このディレクトリで Dart の解析とテストを実行します。

```shell
flutter pub get
flutter analyze
flutter test
cd example
flutter test
flutter build apk --debug
cd android
./gradlew :receipt_print:testDebugUnitTest
```

iOS Simulator で実際の MethodChannel と KMP を通すテストを実行できます。
Android 端末またはエミュレーターでも、同じテストを実行できます。

```shell
cd packages/receipt_print/example
flutter test integration_test/plugin_integration_test.dart -d <device-id>
```

iOS 側の DTO 変換と不正引数のテストは、`example` で次のコマンドを実行します。
結合テストの実行後は `--config-only` で通常のアプリを対象にした設定へ戻してから実行します。

```shell
flutter build ios --simulator --debug --config-only
cd ios
xcodebuild test -project Runner.xcodeproj -scheme Runner \
  -destination 'platform=iOS Simulator,id=<device-id>' \
  -parallel-testing-enabled NO -only-testing:RunnerTests
```

サンプルアプリは `example` ディレクトリで `flutter run` を実行すると起動します。

## 紙面プレビュー

`ReceiptPrint.measureText(text)` は互換モードと同じCP932幅を返します。
`ReceiptPrint.renderSvg(receiptLine, columns: 48)` はiOS上のJavaScriptCoreでSVGを生成します。
ReceiptLine 4.0.1の `lib/receiptline.js` を改行コードだけLFにそろえ、ライセンスとともに同梱します。ネットワークへ接続しません。
`ReceiptPreviewView(svg: svg)` を高さ制約のある場所へ配置すると、SVGの文字・QR・フィルターを画面内に描画します。
WebViewのスクリプト・外部リソース・外部リンク遷移は無効です。AndroidのSVG描画は未対応です。

`InlineLineDto` は、各 `TextRunDto` を余白なしに並べる整形済み1行です。
取消数量など、既に反転用の余白を含む行を自動カラム調整なしに表現できます。

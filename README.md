# receipt-print

`receipt-print` は、呼び出し元が構築した汎用印刷ツリーをサーマルプリンター向けコマンド、または ReceiptLine プレビューへ変換する Kotlin Multiplatform ライブラリです。

注文、キッチン伝票、会計、再印刷などの業務概念は持ちません。呼び出し元が業務データを `PrintJobDto` へ変換し、このライブラリはツリーの検証、CP932 レイアウト、出力形式への変換だけを担当します。プリンターとの接続、送信、再試行、キュー管理も呼び出し元の責務です。

## 公開モデル

```text
PrintJobDto
└── tickets: List<TicketDto>
    ├── children: PrintNodeListDto  // value class
    │   ├── TextDto
    │   ├── TwoColumnDto
    │   ├── RuleDto
    │   ├── QrCodeDto
    │   ├── FeedDto
    │   └── ColumnDto
    │       └── children: PrintNodeListDto
    └── cut: CutModeDto
```

`ReceiptEngine.compile(job, printer)` は機種別コマンドの `ByteArray` を返し、`ReceiptEngine.preview(job, printer)` は同じツリーとプリンタープロファイルから ReceiptLine を返します。エラーと近似表現は、ツリー上の位置を含む `DiagnosticDto` として返します。

`PrinterProfileDto` で指定するのは機種、1 行の桁数、互換モード、サウンドの許可です。TM-m30 でサウンドを使う場合は、外付けブザーを設定済みであることも明示します。対応コマンドセットとその他のプリンター能力は選択した機種からライブラリ内で解決します。

## モジュール

- `receipt-core`: 不変 AST、Kotlin DSL、検証、CP932 レイアウト、コンパイラ
- `receipt-command-escpos`: TM-T90KP、TM-m30、RP-F10 のコマンド変換
- `receipt-command-star`: FVP10、TSP650II、mC-Print3 のコマンド変換
- `receipt-receiptline`: ReceiptLine プレビュー変換
- `receipt-engine`: 汎用 DTO、機種解決、コマンド・プレビュー Facade、XCFramework
- `samples/kotlinApp`: Kotlin から汎用 DTO を利用する例
- `samples/iosApp`: `ReceiptPrint.xcframework` を Swift から利用する例

詳しい境界と変換規則は[アーキテクチャ文書](doc/receipt_dsl_kmp_architecture.md)を参照してください。

## ビルドと検証

JDK 17 以降が必要です。iOS の検証には macOS、Xcode、Apple Silicon Simulator toolchain、SVG Golden の検証には Node.js が必要です。

```shell
./gradlew build
./gradlew engineCheck
```

`engineCheck` は ABI、JVM / iOS Simulator テスト、プリンターコマンド Fixture、ReceiptLine SVG、Kotlin / Swift サンプルを検証します。

Maven publication は次のコマンドで `build/maven-repository` へ生成できます。

```shell
./gradlew publishAllToBuildRepository
```

Swift 向け XCFramework は次のコマンドで `receipt-engine/build/XCFrameworks/debug/ReceiptPrint.xcframework` へ生成されます。

```shell
./gradlew :receipt-engine:assembleReceiptPrintDebugXCFramework
./gradlew swiftSampleCheck
```

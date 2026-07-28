# receipt-print アーキテクチャ

## 1. 責務

`receipt-print` は、汎用印刷ツリーを検証・レイアウトし、次のどちらかへ決定的に変換するライブラリである。

- 対応サーマルプリンターへ渡すコマンドバイト列
- 画面表示や Golden 検証に使う ReceiptLine

業務データから印刷ツリーを作る処理は呼び出し元が担う。このライブラリは注文伝票やキッチン伝票といった帳票種別、商品、注文イベント、店舗設定を知らない。また、接続、送信、状態監視、再試行、キュー、履歴、再印刷、送信先の振り分けも対象外とする。

```text
呼び出し元の業務データ
        │ 呼び出し元が変換
        ▼
    PrintJobDto
        │
        ├── ReceiptEngine.preview ──→ ReceiptLine + DiagnosticDto
        │
        └── ReceiptEngine.compile ──→ Printer Command ByteArray + DiagnosticDto
                                      │
                                      └── 呼び出し元が送信
```

## 2. 公開構文木

公開境界は特定の帳票形式に依存しない。

```kotlin
PrintJobDto(
    tickets = listOf(
        TicketDto(
            children = PrintNodeListDto(
                listOf(
                    TextDto("title"),
                    TwoColumnDto(TextRunDto("item"), TextRunDto("100")),
                    ColumnDto(
                        PrintNodeListDto(
                            listOf(RuleDto(), QrCodeDto("payload")),
                        ),
                    ),
                ),
            ),
        ),
    ),
)
```

`PrintNodeListDto` は `List<PrintNodeDto>` を直接公開せずに意味のある境界として扱う value class である。`TicketDto` と `ColumnDto` の子要素は常にこの型で受け取る。

`PrintNodeDto` は次のノードを持つ。

- `TextDto`: 文字列、スタイル、折り返し、新行保持
- `TwoColumnDto`: 左右の文字列と予約幅、溢れ方針
- `RuleDto`: 罫線
- `QrCodeDto`: データ、セルサイズ、誤り訂正、配置
- `FeedDto`: 紙送り
- `ColumnDto`: スタイルを継承できる再帰コンテナ

`TicketDto` は同じ印刷ジョブ内でカット単位を表す。`header`、`body`、`footer` のような意味は持たず、必要なら呼び出し元が `ColumnDto` の並びとして表現する。

## 3. プリンタープロファイル

公開 `PrinterProfileDto` は次の入力だけを受ける。

- `model`: 対応する 6 機種の列挙値
- `columnsPerLine`: 論理桁数
- `compatibilityMode`: `Strict` または `Compatibility`
- `soundEnabled`: 開始アクションのサウンドを許可するか
- `optionalExternalBuzzerEnabled`: TM-m30 の外付けブザーを設定済みか

コマンドセット、対応カット、最大文字倍率、QR 対応、反転方式、サウンド方式などの capability は `model` と外付けブザー設定から内部で解決する。呼び出し元が矛盾した capability を組み立てることはできない。TM-m30 の外付けブザー設定は既定で無効とし、有効な場合だけ `PlaySound` をブザー専用コマンドへ変換する。

`Strict` は表現できない指定をエラーにする標準モードである。`Compatibility` は古いプリンターコマンドの制約に合わせ、丸めや代替を警告付きで許可するための明示的モードである。

## 4. 変換パイプライン

```text
Interop DTO
  → immutable core PrintJob
  → profile validation
  → CP932 encoding and logical-column layout
  → ESC/POS or Star command encoder
```

プレビューは同じ `PrintJob` と実プリンタープロファイルで検証した後、ReceiptLine へ変換する。ReceiptLine が正確に表現できない reverse、cut、明示幅、非対称倍率などは `PREVIEW_APPROXIMATION` 警告を返す。

コマンド変換にエラーがあれば `bytes` は `null`、プレビュー変換にエラーがあれば `receiptLine` は `null` になる。警告だけなら値と警告の両方を返す。`DiagnosticDto` は `ticketIndex` と再帰的な `childIndexes` を持ち、呼び出し元が問題のノードを特定できる。

## 5. 内部モジュール境界

```text
receipt-engine
├── receipt-core
├── receipt-command-escpos ──→ receipt-core
├── receipt-command-star ────→ receipt-core
└── receipt-receiptline ─────→ receipt-core
```

`receipt-engine` だけが機種からエンコーダを選択し、DTO と core AST を相互に隔離する。個別コマンドモジュールと ReceiptLine モジュールは業務語彙を持たない。

## 6. 不変条件

- 同じ `PrintJobDto` と `PrinterProfileDto` は同じ結果を返す。
- 入力 DTO から変換した core AST は不変である。
- 返却する `ByteArray` は防御的コピーとし、呼び出し側の変更が後続取得値へ影響しない。
- 文字幅は CP932 の実エンコード結果に基づいて計算する。
- 全 Diagnostic は安定した code、severity、message、可能ならノード位置を持つ。
- プリンターコマンド Fixture は外部リポジトリや外部ランタイムなしで検証できる。

## 7. 配布境界

Kotlin 利用者には各 KMP publication を提供する。Swift 利用者には `receipt-engine` が静的 `ReceiptPrint.xcframework` を提供する。どちらの配布物にも TCP クライアントや業務帳票モデルは含めない。

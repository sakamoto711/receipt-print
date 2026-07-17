# Kotlin Multiplatform Receipt DSL / 印刷エンジン設計

## 1. この文書の目的

この文書は、サーマルプリンター向けの帳票をKotlin DSLで宣言し、不変な構文木からレイアウト、プリンターコマンド、ReceiptLineプレビューを生成する独立パッケージの実装仕様である。

同時に、`waiter-terminal`の現行伝票印刷で利用している機能を棚卸しし、新パッケージで互換性を担保する場所を明示する。

この文書だけを入力としてAIが段階的に実装できる粒度を目標とする。ただし、プリンター固有のバイト列については本リポジトリの既存実装と特性テストを正とし、文書へバイト値を重複転記しない。

## 2. 確定事項と既定値

現時点では以下を設計上の既定値とする。

- 実装言語はKotlinとする。
- AndroidとiOSで共通利用できるKotlin Multiplatformライブラリとする。
- Kotlin利用者には型安全なKotlin DSLを提供する。
- DSLはAPIの本体ではなく、不変な`PrintJob`を構築するsyntax sugarとする。
- Swift利用者にはDSLを直接公開せず、Swift向けFacadeまたは不変DTOを公開する。
- 実機印刷は文字・装飾・QRコード・カットなどのネイティブプリンターコマンドを生成する。帳票全体の画像印刷は標準経路にしない。
- ReceiptLineは主にSVG/PNGプレビュー用の出力先とし、KMP CoreはReceiptLine Markdownの生成までを担当する。
- `waiter-terminal`の現行印字結果との互換性を移行時の必須条件とする。
- TCP接続、キュー、履歴、プリンター振り分けなどは帳票構文木と分離する。
- 現行の業務テンプレートは汎用DSL Coreに混ぜず、`waiter-compat`モジュールへ置く。
- 未確定事項は実装を停止させず、後述の「保留中の設計判断」の既定値を採用する。

## 3. 目標

### 3.1 機能目標

- 帳票をKotlin DSLで宣言できる。
- DSLから生成される構文木は完全に不変である。
- 同じ構文木から複数機種向けのコマンドを生成できる。
- 同じ構文木からReceiptLine Markdownを生成できる。
- プリンターI/Oなしでレイアウトとコマンドをテストできる。
- プリンター機種差を`PrinterProfile`と`CommandEncoder`へ隔離できる。
- スタイル変更はスコープを持ち、子要素の装飾状態が兄弟要素へ漏れない。
- 未対応文字や未対応機能を黙って壊さず、診断情報として返せる。
- Android、iOS、JVMテストから利用できる。
- `waiter-terminal`の現行注文伝票、キッチン伝票、フリーテキスト印刷を表現できる。

### 3.2 品質目標

- LayoutとEncoderは純粋関数として実行できる。
- 同じ入力、設定、プロファイルから常に同じ出力を返す。
- Layoutとコマンド生成にCoroutineや非同期APIを要求しない。
- プラットフォーム標準の文字コード実装差に依存しない。
- 互換性テストでは既存Dart実装と生成バイト列を比較できる。
- 新しい帳票テンプレートはSocketやプリンター機種を知らずに記述できる。

## 4. 非目標

初期リリースでは以下を対象外とする。

- Compose Multiplatformによる画面UI。
- Flutter Widget互換API。
- 帳票全体をRaster Imageへ変換して送る標準印刷経路。
- Bluetooth、USB、Serial接続。現行互換の対象接続はTCP 9100のみ。
- 会計伝票。Domain上には種別があるが、現行キューでは未対応である。
- 任意座標へ描画する汎用2D Canvas。
- HTML/CSS互換レイアウト。
- ReceiptLine JavaScript RuntimeのAndroid/iOSへの内蔵。
- アプリ固有のRepository、印刷履歴画面、検索UIのライブラリ移植。

## 5. 現行実装の全体像

### 5.1 現行処理フロー

```text
Platform印刷イベント / 店舗印刷API / Terminal内HTTP API
    ↓
Slip生成・保存
    ↓
Print待機レコード生成
    ↓
プリンター別Queue
    ↓
PrintOrderSlip / PrintKitchenTicket
    ├─ データ形式とイベントを判定
    ├─ 帳票をList<PrinterCommand>へ直接変換
    ├─ TCP 9100へ接続
    ├─ 印刷前ステータス確認
    └─ コマンド送信
    ↓
成功 / 迂回 / 失敗 / 自動再試行 / 履歴
```

主な実装は以下に存在する。

- 注文伝票: `lib/application/print/commands/print_order_slip/print_order_slip.dart`
- キッチン伝票: `lib/application/print/commands/print_kitchen_ticket/print_kitchen_ticket.dart`
- 店舗印刷API向けAdapter: `lib/application/print/commands/print_kitchen_ticket/store_print.dart`
- Internal API変換: `lib/application/print/commands/print_order_slip/internal_api_print.dart`、`print_kitchen_ticket/internal_api_print.dart`
- 共通組版関数: `lib/application/print/commands/print_mixin/print_mixin.dart`
- Printer Utils: `packages/flutter-printer-utils/lib/`
- Queue: `lib/application/print/print_queue_manager.dart`
- 履歴・再印刷: `lib/application/print/commands/print_history_mixin.dart`
- 印刷イベントからの振り分け: `lib/domain/print_event/record_slip_collection.dart`

### 5.2 現行の入力経路

| 入力経路 | Content型 | 印刷経路 | 主な特徴 |
| --- | --- | --- | --- |
| Platform印刷イベント | `TableInfoContent` | `waiter-terminal`内の組版処理 | TableInfoを直接参照する |
| 店舗印刷API / Table Order系イベント | `StorePrintApiContent` | `flutter-printer-utils`内のWaiter向け組版処理 | appName、任意ヘッダー、任意フッター、価格Optionを持つ |
| Terminal内HTTP API | `InternalApiContent` | 一度`TableInfo`へ変換してからアプリ内組版処理 | 入力モデルに無いTableInfo項目へ仮値を設定している |

新設計では3経路を直接Layoutへ渡してはならない。経路ごとのNormalizerが共通の印刷用ViewDataへ変換し、Templateは入力元を知らない構造にする。

ただし移行中は、現行の入力元依存の差異を消さずに`LegacyBehaviorProfile`として再現する。

### 5.3 対応イベント

注文伝票とキッチン伝票は以下の4イベントを扱う。

| 意味 | TableInfo / Internal API | Store Print API |
| --- | --- | --- |
| 追加注文 | `ordered` | `storePrintApiOrdered` / `addOrder` |
| 新規注文 | `newOrdered` | `storePrintApiNewOrdered` / `newOrder` |
| 数量変更 | `amountChanged` | `storePrintApiAmountChanged` / `changeQuantity` |
| 注文キャンセル | `orderCanceled` | `storePrintApiOrderCanceled` / `cancelOrder` |

イベントは帳票テンプレートを丸ごと3種類に分けず、`OrderEvent`として正規化し、タイトル、符号、反転、QR、サウンドなどのPolicy差へ変換する。

```kotlin
enum class OrderEvent {
    NewOrder,
    AddOrder,
    ChangeQuantity,
    CancelOrder,
}
```

### 5.4 対応プリンター

現行実装は以下の6機種を扱う。

| ModelNumber | 機種 | Command系統 | 現行Factory |
| --- | --- | --- | --- |
| `1` | Star FVP10 | Star Line Mode | `Fvp10CommandFactory` |
| `4` | Epson TM-T90KP | ESC/POS派生 | `TmT90kpCommandFactory` |
| `5` | Epson TM-m30 | ESC/POS派生 | `TmM30CommandFactory` |
| `7` | Star TSP650II | Star Line Mode | `Tsp650Ver2CommandFactory` |
| `8` | Star mC-Print3 | StarPRNT | `McPrint3CommandFactory` |
| `10` | Seiko Instruments RP-F10 | ESC/POS派生 | `RpF10CommandFactory` |

全機種とも現行コードではTCP port 9100へ接続する。接続タイムアウトは10秒である。

### 5.5 現行PrinterCommandの機能

`PrinterCommandFactory`が持つ機能は以下である。

- 初期化
- CP932テキスト
- CR / LF
- Full Cut / Partial Cut / No Margin Cut
- 左寄せ / 中央寄せ / 右寄せ
- 文字幅・文字高の倍率
- 強調開始 / 終了
- 白黒反転開始 / 終了
- 横罫線
- サウンド
- QRコード
- CP932エンコード後の文字幅計測
- 上下反転印刷

現行実装上の差異も互換対象として記録する。

- ESC/POS系の文字倍率は1〜8へ丸める。
- Star系の文字倍率は1〜6へ丸める。
- 文字列は1文字ずつCP932へ変換し、変換不能文字は警告して除外する。
- 表示幅はCP932のバイト長で計測する。
- TM-T90KPとRP-F10は行単位の並べ替えとReverse Modeを組み合わせて上下反転する。
- TM-m30は上下反転非対応で、現行実装は入力コマンドを変更せず返す。
- StarPRNT / Star Line Modeは機種コマンドでPage Reverseを指定する。
- TM-T90KPのNo Margin CutはCut ReservationとLFを使う。
- Star系ではNo Margin CutがFull Cutへ写像される。
- 基底ESC/POSのCutは種別を区別しない。TM-m30とRP-F10も現状はこの挙動を継承する。
- 横罫線は文字の`-`ではなくBit Image Commandで生成する。
- QRコードは画像化せず、各Command SetのネイティブQRコマンドを使用する。
- サウンドコマンドは機種ごとに異なる。TM-m30はDrawer Kick Connector Pulseを利用する。

現行Status Parserは機種応答から以下を識別する。

- Unknown / Other。
- Offline。
- Cover Open。
- Paper End。
- Paper Near End。低レベルStatusには存在するが、共通`PrinterErrorType`では独立Errorになっていない。
- Hard Error。
- Epson系のWaiting Online、Paper Endによる停止。

StarPRNTとStar Line Modeは可変長Status Header / Bodyを検証し、ESC/POSはRealtime Statusの各Byteを検証する。RP-F10は専用2Byte Statusを利用する。Status Request Byte列、応答長、Timeout、ParserもTransport互換Fixtureへ含める。

新パッケージはこれらを`PrinterCapabilities`と機種別Encoderへ明示的に移す。

## 6. 現行注文伝票の機能一覧

### 6.1 基本構造

TableInfo / Internal API経路の注文伝票は以下の順序で出力する。

```text
Text State初期化
Header
Rule
Printable Items
Rule
Price Summary
Optional QR Code
LF
Partial Cut
```

印刷対象明細が0件ならコマンドを送信しない。

Store Print API経路ではHeaderにアプリ名・任意ヘッダーが入り、Price Summary後に任意Footerが追加される。

### 6.2 注文伝票Header

TableInfo / Internal API経路は以下を印字する。

- 追加注文: `追加注文`を中央、2倍、反転で表示する。
- 新規注文: イベントタイトルを表示しない。
- 数量変更: `数量変更`を中央、2倍、反転で表示する。
- キャンセル: `注文キャンセル`を中央、2倍、反転で表示する。
- テーブルカテゴリとテーブル名を`カテゴリ : テーブル名`形式で2倍表示する。
- 左側へスタッフ名、右側へ注文日時を表示する。
- 明細が無い場合はOrderHeaderの最終注文日時とスタッフを利用する。
- 人数を右寄せで表示する。

Store Print API経路は以下を印字する。

- アプリ名を左寄せ、反転、前後スペース付きで表示する。
- イベントタイトルを表示する。
- 任意Header Titleを中央、2倍で表示する。改行を保持する。
- 任意Header Contentsを通常サイズで表示する。改行を保持する。

### 6.3 注文伝票明細

以下の明細種別を扱う。

- 通常メニュー
- プラン
- プラン内メニュー
- 簡易コース
- コース親
- コース内メニュー
- トッピング
- 不明種別

TableInfo / Internal API経路の挙動は以下である。

- 親メニューは設定されたItem Markとメニュー名を表示する。
- お好みオーダー名があればDrill Down Mark付きで表示する。
- 注文メモがあれば丸括弧で表示する。
- 販売単価、数量、明細合計を表示する。
- キャンセル明細では数量と金額へ負号を付ける。
- 明細値引、割引、値引クーポン、割引クーポン、無料クーポンを表示する。
- トッピングを親明細へ紐付けて表示する。
- コース内メニューを親コースへ紐付けて表示する。
- コース内メニューの金額が0の場合は金額欄を省略する。
- `itemPrintDivision`の注文伝票Bitが立つ明細だけを印字する。
- コース親自体が印字対象外でも、対象のコース内メニューがあれば親と子を印字する。
- 親が存在するトッピングとコース内メニューは親側で印字し、明細ループでは重複印字しない。
- 親が存在しないトッピングまたはコース内メニューはエラーログ後、単品として印字する。
- 不明な明細種別はエラーログ後、単品として印字する。

Store Print API経路ではさらに以下を扱う。

- 複数のお好みオーダー。
- 親、コース内メニュー、トッピングそれぞれの任意単価・数量・小計。
- 明細単位の任意価格Option。
- 親とコース内メニューのNote。
- 親がキャンセルなら子コースとトッピングも負号を付ける。
- Store Print API経路では入力済みItemsをすべて印字し、TableInfo経路の`itemPrintDivision`判定は行わない。

### 6.4 注文伝票金額欄

TableInfo / Internal API経路は以下を扱う。

- 追加注文時の今回注文小計。
- 追加注文時の注文済小計。
- 小計。
- 値引。
- 割引率と割引額。
- 値引クーポン。
- 割引クーポン。
- 無料クーポン。
- 小計または合計に対する時間帯値引、割引、値増、割増。
- 人数を考慮したテーブルチャージ。
- サービスチャージ率と金額。
- 外税。負の税額は0として表示する。
- 端数処理。
- 合計金額。設定により2倍表示、通常時は強調表示する。
- 設定により内消費税を表示する。
- 合計点数。
- 店舗設定と人数により割り勘金額を表示する。

Store Print API経路は以下を扱う。

- 任意小計。
- 任意小計OptionのKey / Value。
- 任意合計金額。
- 任意合計金額OptionのKey / Value。
- 任意合計点数。
- 任意Footer TitleとFooter Contents。

### 6.5 注文伝票QRコード

TableInfo / Internal API経路では以下の条件でQRコードを印字する。

- 新規注文イベントである。
- QRコード印字設定がONである。
- QR Formatが空ではない。
- Format展開後の文字列が空ではない。

Formatでは以下のPlaceholderを置換する。

- `{serverUrl}`
- `{contractId}`
- `{storeId}`
- `{uuid}`
- `{orderHeaderId}`

QRコードは中央寄せでネイティブQRコマンドを利用する。Store Print API経路では現状QRコードを出力しない。

## 7. 現行キッチン伝票の機能一覧

### 7.1 基本構造

キッチン伝票は設定によって1印刷Job内に複数の物理Ticketを持つ。

- 注文単位印刷ON: 全明細に対してHeaderを1回出し、末尾でCutする。
- 注文単位印刷OFF: 明細ごとにHeaderを出し、明細間でCutする。
- Store Print API経路ではFooterも物理Ticket単位で挿入する。
- 最終Cutは常にLF + Partial Cutである。
- 明細間CutはNo Margin設定によりNo Margin CutまたはPartial Cutになる。
- 印刷対象明細が0件ならコマンドを送信しない。

したがってDSLのRootは単一の`Header / Body / Footer`ではなく、複数`Ticket`を持つ`PrintJob`とする必要がある。

### 7.2 キッチン伝票Header

TableInfo / Internal API経路は以下を印字する。

- 追加注文: イベントタイトルを表示しない。
- 新規注文: `新規注文`、または設定された初回注文タイトルを中央、2倍、反転で表示する。
- 数量変更: `数量変更`を中央、2倍、反転で表示する。
- キャンセル: `注文キャンセル`を中央、2倍、反転で表示する。
- テーブルカテゴリとテーブル名を2倍表示する。
- スタッフ名と注文日時を左右へ表示する。
- 日時文字は設定により縦2倍にする。現行では`twoTimes`は通常サイズ扱いで、`twoHeight`だけ縦2倍になる。
- 注文明細番号と人数を左右へ表示する。
- 明細単位印刷時は対象明細の注文明細番号を表示する。
- 印字対象外のコース親と印字対象の子を組み合わせる場合、最初の子の注文明細番号を利用する。

Store Print API経路は以下を印字する。

- アプリ名を反転表示する。
- イベントタイトルを表示する。
- 存在する場合だけ注文明細番号を表示する。
- 任意Header Title / Header Contentsを表示する。
- 任意Footer Title / Footer Contentsを表示する。

### 7.3 キッチン伝票明細

- キッチン用メニュー名があれば優先し、無ければ通常メニュー名を表示する。
- Item Markと数量の`点`表記を表示する。
- キャンセル明細では数量側だけを白黒反転する。
- お好みオーダーをDrill Down Mark付きで表示する。
- 注文メモを丸括弧で表示する。
- 簡易コース内容を改行分割し、Prix Fixe Mark付きで表示する。
- トッピングをTopping Mark付きで表示する。
- コース内メニューをCourse Mark付きで表示する。
- `itemPrintDivision`のキッチン伝票Bitが立つ明細だけを印字する。
- コース親、コース内メニュー、トッピングの親子関係を維持する。
- 親が無い子要素はログ後に単品印字する。
- 注文単位印刷ONではコース親と対象の子を同一Ticketへ印字する。
- 注文単位印刷OFFではコース内メニューごとに親情報と対象の子1件を印字する。
- 設定により同一Item ID / Drill Down IDのコース親と子の数量を合算する。

Store Print API経路では以下の差異がある。

- 複数のお好みオーダーを表示する。
- 親、コース内メニュー、トッピングのNoteを扱う。
- 現行パッケージ側ではコース数量合算に未対応である。
- 親のキャンセル状態を子コースとトッピングへ引き継ぐ。

Internal API経路には以下の現行制約がある。

- 複数の`customOrderContents`のうち先頭1件だけをTableInfoの`itemDrillDownName`へ変換する。
- トッピングの`itemPrintDivision`は親依存として固定値255へ変換する。

### 7.4 上下反転とサウンド

- キッチン上下反転設定がONならJob全体へReverse Printを適用する。
- TM-m30では現行実装上Reverse Printが無効である。
- 新規注文と追加注文の通常生成経路では、端末設定とプリンター設定の両方がONの場合にサウンドを先頭へ追加する。
- 数量変更とキャンセルではサウンドを鳴らさない。
- Reverse Print後に先頭へサウンドを挿入する。

## 8. 現行フリーテキスト印刷

`PrintFreeText`は以下の機能を持つ。

- IP AddressとModel Numberを直接指定する。
- 6機種へTCP 9100で接続する。
- 文字倍率、強調、反転、寄せを初期状態へ戻す。
- 入力文字列の改行を維持して各行をText + LFとして送る。
- 自動折り返しは行わない。
- 最後にLF + Partial Cutを出す。
- 印刷前のプリンターステータスを確認する。

新パッケージでは`TextBlock(wrap = WrapMode.None, preserveNewLines = true)`で表現する。

## 9. 現行設定の互換一覧

| 設定 | 現行効果 | 新設計上の配置 |
| --- | --- | --- |
| `printerHallOrderReceipt` | 注文伝票自体を生成するか | waiter-terminal Adapter |
| `printerKitchenOrderPrint` | キッチン伝票自体を生成するか | waiter-terminal Adapter |
| `printerHallOrderPrintCharacterSizeType` | 注文伝票明細の文字倍率 | `OrderSlipPolicy` |
| `printerKitchenPrintCharacterSizeType` | キッチン明細の文字倍率 | `KitchenTicketPolicy` |
| `printerKitchenPrintDateTimeCharacterSizeType` | 日時の文字高 | `KitchenTicketPolicy` |
| `printerKitchenItemMark` | 親メニュー接頭辞 | Template Policy |
| `printerKitchenDrillDownMark` | お好みオーダー接頭辞 | Template Policy |
| `printerKitchenToppingMark` | トッピング接頭辞 | Template Policy |
| `printerKitchenCourseMark` | コース内メニュー接頭辞 | Template Policy |
| `printerKitchenPrixFixeMark` | 簡易コース内容 / Note接頭辞 | Template Policy |
| `printerKitchenChangeTitleFirstOrder` | 新規注文タイトルを変更するか | `KitchenTicketPolicy` |
| `printerKitchenTitleFirstOrder` | 新規注文タイトル | `KitchenTicketPolicy` |
| `printerKitchenPrintPerOrder` | 1Job単位か明細単位か | Ticket分割Policy |
| `printerKitchenNoSpace` | 明細間Cut種別 | Ticket分割Policy |
| `printerKitchenTotalingForCourse` | コース数量合算 | Normalizer / Template Policy |
| `printerKitchenReversePrint` | Job全体の上下反転 | `PrintJob.direction` |
| `printerKitchenOrderSound` | キッチン注文時の鳴動 | `PrintJob.startActions` |
| Printerの`enabledSound` | 鳴動を許可するか | `PrinterProfile` / Job Policy |
| `printerHallCheckoutPrice2x` | 合計金額を2倍表示 | `OrderSlipPolicy` |
| `printerHallCheckoutTaxIncluded` | 内消費税を表示 | `OrderSlipPolicy` |
| `printerHallQrCode` | 新規注文QRを表示 | `OrderSlipPolicy` |
| `printerHallQrCodeFormat` | QR内容Template | Normalizer / `OrderSlipPolicy` |
| Storeの`billDivide` | 割り勘金額を表示 | `OrderSlipPolicy` |

## 10. 現行プリンター振り分け

プリンター振り分けは汎用帳票DSLではなく、`waiter-terminal`のApplication Adapter責務とする。ただし現行互換テストの対象とする。

### 10.1 注文伝票

- 対象テーブルに注文伝票用プリンターグループがあれば最優先する。
- 無ければ店舗のホールプリンターを利用する。
- Main PrinterとSub Printerの両方へ伝票を作成できる。
- 店舗印刷API経路も店舗のMain / Sub Printerへ作成する。

### 10.2 キッチン伝票

- 親メニューのMain Printer IDとSub Printer IDへ振り分ける。
- コース内メニューが親と異なるプリンターを持つ場合、そのプリンターにも振り分ける。
- プリンターごとに明細をフィルターする。
- 対象のコース内メニューだけが存在する場合、印字対象外状態のコース親を補完する。
- トッピングは親明細と同じプリンター側へ補完する。

## 11. 現行印刷ライフサイクル

以下は帳票構文木の責務ではないが、現行機能として維持する必要がある。

- プリンターごとに独立したQueueを持つ。
- Queueは1秒間隔で待機印刷を確認する。
- 同一プリンターでは逐次印刷する。
- 各注文伝票 / キッチン伝票の直接実行は最大3回試行し、試行間隔は1秒である。
- 印刷失敗後の自動再試行可能時刻は既定で1分後である。
- 印刷前にプリンターステータスを確認する。
- 印刷後ステータス確認は現状未実装である。
- 失敗時にプリンター設定の迂回先へ1回だけ迂回できる。
- 迂回後に同じ迂回元を持つ後続待機印刷も迂回先へ更新する。
- 成功、失敗、中止の状態を管理する。
- 成功、失敗、中止から再印刷できる。待機中は再印刷不可である。
- 失敗から同一プリンターへRetryする場合は既存レコードを待機へ戻す。
- 別プリンターへの再印刷は新しいSlipとPrint待機レコードを作る。
- 受付から1時間以上経過した新規印刷は中止として登録する。
- Domain Eventで登録、成功、失敗、更新を通知する。
- 会計伝票はQueue実行未対応である。

新パッケージでは以下へ分離する。

- `receipt-core`: 帳票構文木とLayout。
- `receipt-command-*`: コマンド生成。
- `receipt-transport-tcp`: TCP接続とステータス通信。初期Core完成後に実装する。
- `waiter-terminal`: Queue、履歴、再印刷、迂回、業務上のプリンター振り分け。

## 12. 目標アーキテクチャ

```text
Source DTO
    ↓ Normalizer
Canonical ViewData
    ↓ Template written by Kotlin DSL
Immutable PrintJob
    ↓ Validator
Validated PrintJob ───────────────→ ReceiptLine Exporter → ReceiptLine Markdown → SVG / PNG
    ↓ LayoutEngine + PrinterProfile
LaidOutJob
    ├─ ESC/POS Encoder ─────→ ByteString
    ├─ StarPRNT Encoder ────→ ByteString
    └─ Star Line Encoder ───→ ByteString

ByteString → defensive-copy ByteArray at the host boundary
    ↓ optional receipt-transport-tcp
Printer
```

### 12.1 依存方向

```text
receipt-core
├── model ← dsl
├── model ← layout
└── model + layout ← command IR

receipt-command-escpos   ──depends on──→ receipt-core
receipt-command-star     ──depends on──→ receipt-core
receipt-receiptline      ──depends on──→ receipt-core
waiter-compat            ──depends on──→ receipt-core + Encoder + Exporter
```

実装初期はGradle Moduleを過剰に分けず、以下の5 Moduleを推奨する。

```text
:receipt-core
:receipt-command-escpos
:receipt-command-star
:receipt-receiptline
:waiter-compat
```

`receipt-core`内はPackageで`model`、`dsl`、`layout`、`command`を分離する。APIが安定してからGradle Moduleを追加分割する。

### 12.2 初期Repository構成

実装AIはまず次の構成を作る。Package名の`receiptdsl`は公開Group ID決定時に一括置換できる仮名とする。

```text
receipt-kmp/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── receipt-core/
│   └── src/
│       ├── commonMain/kotlin/receiptdsl/
│       │   ├── model/
│       │   ├── dsl/
│       │   ├── layout/
│       │   ├── command/
│       │   ├── encoding/
│       │   └── diagnostic/
│       └── commonTest/kotlin/receiptdsl/
├── receipt-command-escpos/
│   └── src/
│       ├── commonMain/kotlin/receiptdsl/escpos/
│       └── commonTest/kotlin/receiptdsl/escpos/
├── receipt-command-star/
│   └── src/
│       ├── commonMain/kotlin/receiptdsl/star/
│       └── commonTest/kotlin/receiptdsl/star/
├── receipt-receiptline/
│   └── src/
│       ├── commonMain/kotlin/receiptdsl/receiptline/
│       └── commonTest/kotlin/receiptdsl/receiptline/
├── waiter-compat/
│   └── src/
│       ├── commonMain/kotlin/receiptdsl/waiter/
│       └── commonTest/kotlin/receiptdsl/waiter/
├── samples/
│   ├── androidApp/
│   └── iosApp/
└── fixtures/
    ├── canonical/
    ├── commands/
    └── previews/
```

`receipt-core`だけはPrinter Vendor、Socket、Android、Foundation、Flutterへ依存させない。各Encoderも通信を行わず、決定的なCommand列を返す。Sample AppのみがPlatform APIと接続する。

## 13. Coreデータモデル

### 13.1 Rootモデル

```kotlin
data class PrintJob(
    val tickets: PersistentList<Ticket>,
    val direction: PrintDirection = PrintDirection.Normal,
    val startActions: PersistentList<JobAction> = persistentListOf(),
)

data class Ticket(
    val content: BlockNode,
    val cut: CutMode = CutMode.Partial,
)

enum class PrintDirection {
    Normal,
    Reverse,
}

sealed interface JobAction {
    data object PlaySound : JobAction
}

enum class CutMode {
    None,
    Full,
    Partial,
    NoMargin,
}
```

`PrintJob`は送信単位、`Ticket`は物理的なカット単位を表す。Cutを単なるContent Nodeにせず、物理Ticketの属性にすることで、明細単位印刷と最終Cutを明示する。

必要なら`Ticket`間に異なるCutを持てる。現行キッチン伝票の「明細間No Margin、末尾Partial」を表現するため、最後のTicketだけ`Partial`にする。

### 13.2 Nodeモデル

初期実装で必要なNodeは以下とする。

```kotlin
sealed interface ReceiptNode

sealed interface BlockNode : ReceiptNode

data class Column(
    val children: PersistentList<BlockNode>,
    val style: TextStyle? = null,
) : BlockNode

data class TextBlock(
    val text: String,
    val style: TextStyle = TextStyle.Default,
    val wrap: WrapMode = WrapMode.WordOrCharacter,
    val preserveNewLines: Boolean = true,
) : BlockNode

data class TwoColumnLine(
    val leading: TextRun,
    val trailing: TextRun? = null,
    val widthColumns: Int? = null,
    val leadingReservedColumns: Int? = null,
    val trailingReservedColumns: Int? = null,
    val overflow: TwoColumnOverflow = TwoColumnOverflow.WrapLeadingThenTrailing,
) : BlockNode

data class Rule(
    val thickness: Int = 1,
) : BlockNode

data class QrCode(
    val data: String,
    val cellSize: Int = 6,
    val errorCorrection: QrErrorCorrection = QrErrorCorrection.Medium,
    val align: TextAlign = TextAlign.Center,
) : BlockNode

data class Feed(
    val lines: Int = 1,
) : BlockNode

data class TextRun(
    val text: String,
    val style: TextStyle = TextStyle.Default,
)
```

Header、Body、Footer、Item、Price SummaryはCore Nodeにしない。これらは業務Templateが返すComposite関数とする。

ここでいう不変性はComposeの`@Immutable` Annotationへ依存する意味ではない。すべてのPropertyを`val`とし、Core ASTのCollectionには`kotlinx.collections.immutable`の`PersistentList` / `PersistentSet`を利用する。BuilderのMutable Collectionは`build()`時に`toPersistentList()`等で切り離す。

```kotlin
fun ColumnScope.orderHeader(data: OrderHeaderViewData, policy: OrderSlipPolicy)
fun ColumnScope.orderItems(items: List<OrderItemViewData>, policy: OrderSlipPolicy)
fun ColumnScope.priceSummary(summary: PriceSummaryViewData, policy: OrderSlipPolicy)
```

### 13.3 Style

```kotlin
data class TextStyle(
    val widthScale: Int = 1,
    val heightScale: Int = 1,
    val emphasized: Boolean = false,
    val inverted: Boolean = false,
    val align: TextAlign = TextAlign.Left,
) {
    companion object {
        val Default = TextStyle()
    }
}

enum class TextAlign {
    Left,
    Center,
    Right,
}
```

Styleは親から継承する。子で変更したStyleは子の終了時に親Styleへ戻る。EncoderがStyle差分をCommandへ変換し、Templateが手動でReset Commandを追加しない構造にする。

### 13.4 Wrapと改行

```kotlin
enum class WrapMode {
    None,
    Character,
    WordOrCharacter,
}

enum class TwoColumnOverflow {
    WrapLeadingThenTrailing,
    TrailingOnNextLine,
    Clip,
    Fail,
}
```

現行互換Profileでは日本語を含むため`Character`相当を利用し、CP932変換後の長さを基準に折り返す。

## 14. Kotlin DSL仕様

### 14.1 DSL Marker

```kotlin
@DslMarker
annotation class ReceiptDsl
```

すべてのScopeへ`@ReceiptDsl`を付与し、外側Scopeを誤って操作できないようにする。

### 14.2 Public DSL

```kotlin
fun printJob(block: PrintJobScope.() -> Unit): PrintJob

@ReceiptDsl
class PrintJobScope internal constructor() {
    var direction: PrintDirection
    fun playSound()
    fun ticket(
        cut: CutMode = CutMode.Partial,
        block: ColumnScope.() -> Unit,
    )
}

@ReceiptDsl
class ColumnScope internal constructor() {
    fun text(
        value: String,
        style: TextStyle = TextStyle.Default,
        wrap: WrapMode = WrapMode.Character,
    )

    fun twoColumn(
        leading: String,
        trailing: String? = null,
        leadingStyle: TextStyle = TextStyle.Default,
        trailingStyle: TextStyle = TextStyle.Default,
        widthColumns: Int? = null,
        leadingReservedColumns: Int? = null,
        trailingReservedColumns: Int? = null,
    )

    fun rule(thickness: Int = 1)
    fun qrCode(data: String, cellSize: Int = 6)
    fun feed(lines: Int = 1)
    fun column(style: TextStyle? = null, block: ColumnScope.() -> Unit)
}
```

Builder内部は`MutableList`を利用してよい。`build()`時に`toPersistentList()`へ変換し、返却後にBuilderから変更できないようにする。

### 14.3 使用例

```kotlin
val job = printJob {
    direction = if (policy.reverse) {
        PrintDirection.Reverse
    } else {
        PrintDirection.Normal
    }

    if (policy.playSound) {
        playSound()
    }

    ticket(cut = CutMode.Partial) {
        if (data.title != null) {
            text(
                data.title,
                style = TextStyle(
                    widthScale = 2,
                    heightScale = 2,
                    inverted = true,
                    align = TextAlign.Center,
                ),
            )
        }

        text(data.tableName, style = tableStyle)
        twoColumn("スタッフ:${data.staffName}", data.orderedAt)
        rule()

        data.items.forEach { item ->
            orderItem(item, policy)
        }

        rule()
        priceSummary(data.summary, policy)

        data.qrCode?.let { qrCode(it) }
    }
}
```

条件分岐と繰り返しはKotlin標準構文を利用し、DSL独自の`IfNode`や`ForEachNode`は初期実装では作らない。構文木には評価済みのNodeだけを格納する。

## 15. PrinterProfileとCapabilities

```kotlin
data class PrinterProfile(
    val id: String,
    val commandSet: CommandSet,
    val columnsPerLine: Int,
    val textEncoding: TextEncoding = TextEncoding.Cp932,
    val capabilities: PrinterCapabilities,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.Strict,
)

enum class CommandSet {
    EscPos,
    StarPrnt,
    StarLineMode,
}

data class PrinterCapabilities(
    val maxWidthScale: Int,
    val maxHeightScale: Int,
    val supportedCuts: PersistentSet<CutMode>,
    val supportsQrCode: Boolean,
    val supportsEmphasis: Boolean,
    val supportsInverse: Boolean,
    val reverseMode: ReverseMode,
    val soundMode: SoundMode,
)

enum class ReverseMode {
    NativePageReverse,
    ReorderLinesAndReverseGlyphs,
    Unsupported,
}
```

`LegacyWaiter`互換モードでは、未対応Reverseを無視する、倍率を範囲へ丸める、変換不能文字を除外するなど現行挙動を再現する。

`Strict`モードでは未対応機能を`CompilationError`にする。

## 16. 文字コードと文字幅

### 16.1 CP932 Codec

Kotlin/JVMの`Charset`を`commonMain`から直接利用してはならない。AndroidとKotlin/Nativeで同じ結果を得るため、以下のInterfaceをCoreへ置く。

```kotlin
interface TextCodec {
    fun encode(text: String, policy: UnsupportedGlyphPolicy): EncodedText
    fun measure(text: String, policy: UnsupportedGlyphPolicy): Int
}

data class EncodedText(
    val bytes: ByteString,
    val diagnostics: PersistentList<Diagnostic>,
)

enum class UnsupportedGlyphPolicy {
    Drop,
    Replace,
    Fail,
}
```

初期実装ではCP932 Mapping Tableを共通Resourceとして持ち、Unicode Code PointからByte列へ決定論的に変換する。

現行互換Profileは`Drop`を利用し、警告Diagnosticを返す。厳格Profileは`Fail`を既定値とする。

Kotlinの`ByteArray`はMutableかつ標準の`equals`が内容比較ではないため、不変な中間モデルへ直接保持しない。Coreには防御的Copyと内容Equalityを持つ`ByteString`を定義する。

```kotlin
class ByteString private constructor(
    private val value: ByteArray,
) {
    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ByteString && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        fun copyFrom(value: ByteArray): ByteString =
            ByteString(value.copyOf())
    }
}
```

### 16.2 Column幅

- `columnsPerLine`は現行の`charsPerLine`と同じ論理単位とする。
- 通常文字の占有幅はCP932エンコード後のバイト数とする。
- `widthScale = n`の場合、利用可能な論理列数は`floor(columnsPerLine / n)`とする。
- 改行文字はText Codecへ渡す前に分割する。
- 空文字だけのTextBlockは既定では何も出力しない。
- 明示的な空行は`Feed(1)`で表す。

### 16.3 TwoColumn互換アルゴリズム

`LegacyWaiter`モードでは現行`createCaptionAndContentText`相当を以下の手順で再現する。

1. Node指定幅、またはPrinter全幅を対象幅とする。
2. Leading / Trailingの装飾用前後スペースをStyle適用前に確定する。
3. LeadingとTrailingをCP932で計測する。
4. Reserved幅未指定なら各文字列の実幅を利用する。
5. Leadingを対象幅で折り返す。
6. Leadingの最後の行とTrailingが同一行へ収まれば、Trailingが右端へ揃うよう半角スペースを挿入する。
7. 収まらなければTrailingを次の行へ送り、右端へ揃える。
8. Trailingが空ならLeading後にLFを1つ出す。
9. Trailing自体が対象幅を超える場合もCharacter単位で折り返す。

このアルゴリズムは独立Unit Testを用意する。

## 17. コンパイルPipeline

### Pass 1: Build

Kotlin DSLから不変な`PrintJob`を生成する。この段階ではプリンターを参照しない。

### Pass 2: Validate

以下を検証する。

- `tickets`が空でない。
- `columnsPerLine > 0`。
- 文字倍率が1以上。
- `Rule.thickness`が1以上。
- `QrCode.data`が空でない。
- Reserved幅が負でない。
- 子幅が親幅を超えない。
- PrinterProfileが要求機能をサポートする。

Validatorは全エラーを収集して返し、最初の1件だけで終了しない。

### Pass 3: Layout

```kotlin
interface LayoutEngine {
    fun layout(
        job: PrintJob,
        profile: PrinterProfile,
    ): LayoutResult
}

data class LayoutResult(
    val job: LaidOutJob?,
    val diagnostics: PersistentList<Diagnostic>,
)
```

Layout結果はTicketと行の境界を失わないPrinter非依存構造とする。

```kotlin
data class LaidOutJob(
    val tickets: PersistentList<LaidOutTicket>,
    val direction: PrintDirection,
    val startActions: PersistentList<JobAction>,
)

data class LaidOutTicket(
    val blocks: PersistentList<LaidOutBlock>,
    val cut: CutMode,
)

sealed interface LaidOutBlock

data class LaidOutTextLine(
    val operations: PersistentList<LineOp>,
) : LaidOutBlock

data class LaidOutRule(
    val thickness: Int,
) : LaidOutBlock

data class LaidOutQrCode(
    val data: ByteString,
    val cellSize: Int,
    val errorCorrection: QrErrorCorrection,
    val align: TextAlign,
) : LaidOutBlock

data class LaidOutFeed(
    val lines: Int,
) : LaidOutBlock

sealed interface LineOp

data class SetStyle(val style: TextStyle) : LineOp
data class TextBytes(val source: String, val bytes: ByteString) : LineOp
```

空の`LaidOutTextLine`は明示された空行を表す。Cutは`LaidOutTicket`、Soundは`LaidOutJob.startActions`に保持し、内容中の任意位置へ紛れ込ませない。Reverse PrintのEncoder実装が安全にBlock順と行順を操作できるよう、単なる平坦なByteArrayにはしない。

### Pass 4: Encode

```kotlin
interface CommandEncoder {
    val supportedCommandSet: CommandSet

    fun encode(
        job: LaidOutJob,
        profile: PrinterProfile,
    ): EncodeResult
}

data class EncodeResult(
    val bytes: ByteString?,
    val diagnostics: PersistentList<Diagnostic>,
)
```

Encoderは現在Styleと次Styleの差分だけをCommand化する。Ticket開始時には明示的に既定Styleへ初期化する。

### Pass 5: Send

Core内部の標準戻り値は`ByteString`とし、送信境界で防御的Copyした`ByteArray`へ変換する。送信は別ModuleまたはHost Applicationが担当する。

## 18. Encoder実装要件

### 18.1 共通要件

- 既存DartのCommand定数を移植元とする。
- 既存実装と同一Fixtureに対してByte単位で比較する。
- TextはLayout済みCP932 `ByteString`を利用し、Encoder内で再エンコードしない。
- QRはネイティブコマンドを使う。
- Ruleは現行互換ではBit Image Commandを使う。
- Cut、Reverse、SoundはPrinterProfileの機種Capabilityへ従う。
- 未対応機能はProfileに従いError、Warning + Fallback、またはIgnoreとする。

### 18.2 ESC/POS

対象機種はTM-T90KP、TM-m30、RP-F10である。

実装項目:

- Align
- Character Size 1〜8
- Emphasis
- Inverse
- LF / CR
- Cut
- Bit Image Rule
- Native QR
- Model別Sound
- TM-T90KP / RP-F10のReverse
- Model別Printer Status request / response parserはTransport Moduleへ分離する

### 18.3 StarPRNT

対象機種はmC-Print3である。

実装項目:

- Init
- Align
- Character Size 1〜6
- Emphasis
- Inverse
- Cut
- Bit Image Rule
- Native QR
- External Device Sound
- Page Reverse
- StarPRNT Status parser

### 18.4 Star Line Mode

対象機種はFVP10、TSP650IIである。

実装項目:

- Init
- Align
- Character Size 1〜6
- Emphasis
- Inverse
- Cut
- Bit Image Rule
- Native QR
- FVP10 NV Sound
- TSP650II External Buzzer
- Page Reverse
- Star Line Mode Status parser

## 19. ReceiptLine Exporter

### 19.1 役割

`receipt-receiptline`は`PrintJob`または`LaidOutJob`からReceiptLine Markdownを生成する。

初期実装では`PrintJob`から生成し、ReceiptLine自身にプレビュー用Layoutを任せる。そのためプレビューは印刷Byte列の完全なWYSIWYGではなく、意味的な近似である。

```kotlin
interface ReceiptLineExporter {
    fun export(
        job: PrintJob,
        profile: PreviewProfile,
    ): ReceiptLineResult
}
```

### 19.2 Mapping

| Core Node | ReceiptLine |
| --- | --- |
| `TextBlock` | Text行 + Alignment Property |
| `TwoColumnLine` | `leading | trailing`形式 |
| `Rule` | `---` |
| `QrCode` | `{code:...;option:qrcode,...}` |
| `Feed` | 空行 |
| `Ticket.cut` | Cut PropertyまたはPreview上の区切り |
| Emphasized | ReceiptLineの強調記法 |
| Width / Height Scale | ReceiptLineの文字倍率記法 |
| Inverted | 近似可能ならProperty、不可ならWarning |

ReceiptLineへ表現できない機種固有機能はDiagnosticへ記録する。

### 19.3 実行環境

- KMPはReceiptLine MarkdownのStringを返す。
- SVG / PNG生成はNode.jsまたはBrowserのReceiptLine / Receipt.jsへ任せる。
- Android/iOSのCoreへJavaScript Runtimeを組み込まない。
- README画像、DSL Playground、Golden Preview、デバッグ画面で利用する。
- ReceiptLineのRaster Print経路は標準実機印刷に利用しない。

ReceiptLine公式実装はPrinter Command出力と画像出力の両方を持つが、本設計ではコマンド生成の正本を自前Encoderとする。

## 20. waiter-compatモデル

### 20.1 Canonical ViewData

`TableInfo`を新パッケージの共通モデルにしてはならない。必要な印字情報だけを持つViewDataを定義する。

```kotlin
data class OrderSlipViewData(
    val event: OrderEvent,
    val appName: String?,
    val title: String?,
    val table: TableViewData?,
    val staffName: String?,
    val orderedAt: String?,
    val numberOfGuests: Int?,
    val headerTitle: String?,
    val headerContents: String?,
    val items: PersistentList<OrderItemViewData>,
    val summary: OrderPriceSummaryViewData,
    val qrCode: String?,
    val footerTitle: String?,
    val footerContents: String?,
)

data class KitchenTicketViewData(
    val event: OrderEvent,
    val appName: String?,
    val title: String?,
    val table: TableViewData?,
    val staffName: String?,
    val orderedAt: String?,
    val numberOfGuests: Int?,
    val headerTitle: String?,
    val headerContents: String?,
    val items: PersistentList<KitchenItemViewData>,
    val footerTitle: String?,
    val footerContents: String?,
)
```

明細は親子を平坦なID参照で保持せず、Normalizer段階でTreeへする。

```kotlin
data class OrderItemViewData(
    val id: String,
    val kind: ItemKind,
    val displayName: String,
    val kitchenName: String?,
    val quantity: Int?,
    val unitPrice: Long?,
    val subtotal: Long?,
    val canceled: Boolean,
    val printableOnOrderSlip: Boolean,
    val printableOnKitchenTicket: Boolean,
    val customOrders: PersistentList<String>,
    val memo: String?,
    val prixFixeLines: PersistentList<String>,
    val discounts: PersistentList<PriceAdjustmentViewData>,
    val toppings: PersistentList<OrderItemViewData>,
    val courseItems: PersistentList<OrderItemViewData>,
)
```

親が存在しない子要素はNormalizerが`OrphanItemPolicy`に従ってRootへ昇格し、Diagnosticを残す。Template内で親検索を繰り返さない。

### 20.2 Normalizer

以下を別実装にする。

```kotlin
interface OrderSlipNormalizer<S> {
    fun normalize(source: S, policy: LegacyBehaviorProfile): NormalizeResult<OrderSlipViewData>
}

class TableInfoOrderSlipNormalizer
class StorePrintOrderSlipNormalizer
class InternalApiOrderSlipNormalizer
class TableInfoKitchenTicketNormalizer
class StorePrintKitchenTicketNormalizer
class InternalApiKitchenTicketNormalizer
```

実際のDart Domain ModelをKMPへ直接依存させない。移行時はJSON FixtureまたはInterop DTOを入力とする。

### 20.3 Policy

```kotlin
data class OrderSlipPolicy(
    val itemStyle: TextStyle,
    val itemMark: String,
    val drillDownMark: String,
    val toppingMark: String,
    val courseMark: String,
    val showTotalDoubleSize: Boolean,
    val showIncludedTax: Boolean,
    val showQrCode: Boolean,
    val billDivide: Boolean,
)

data class KitchenTicketPolicy(
    val itemStyle: TextStyle,
    val dateTimeStyle: TextStyle,
    val itemMark: String,
    val drillDownMark: String,
    val toppingMark: String,
    val courseMark: String,
    val prixFixeMark: String,
    val firstOrderTitle: String,
    val printPerOrder: Boolean,
    val noMarginBetweenItems: Boolean,
    val totalCourses: Boolean,
    val reverse: Boolean,
    val playSound: Boolean,
)
```

## 21. Android / iOS API

### 21.1 Kotlin API

Kotlin利用者はDSLとCompilerを直接利用できる。

```kotlin
val document = printJob { /* ... */ }
val layout = ReceiptCompiler.layout(document, profile)
val encoded = ReceiptCompiler.encode(layout.requireJob(), profile)
```

### 21.2 Swift API

Kotlin receiver lambda DSLをSwift APIとして公開しない。Swiftでは高水準Facadeを利用する。

```kotlin
class ReceiptEngine {
    fun compile(
        request: ReceiptRequestDto,
        profile: PrinterProfileDto,
    ): CompileResponseDto

    fun exportReceiptLine(
        request: ReceiptRequestDto,
        previewProfile: PreviewProfileDto,
    ): ReceiptLineResponseDto
}
```

Swift向けDTOでは以下を避ける。

- Kotlin Function Type。
- 深いsealed class階層をそのまま公開すること。
- Default Argumentだけに依存したAPI。
- Kotlin固有Collectionの変更操作。
- Exceptionだけで結果を通知すること。

結果はByteArray、String、Diagnostic DTOを含む値として返す。Swift側で必要な`Data`変換は薄いFacadeで提供する。

### 21.3 配布

- Android / JVM: Maven Artifact。
- iOS Device: `iosArm64`。
- iOS Simulator: `iosSimulatorArm64`。
- iOS配布: XCFramework。必要に応じてSwift Package Manager用Binary Targetを用意する。
- Version CatalogでKotlin、Gradle Plugin、Test LibraryのVersionを一元管理する。
- Public APIへBinary Compatibility Validationを導入する。

### 21.4 Flutterから利用する場合

KMP LibraryがFlutterへ直接公開されるわけではない。必要になった時点で、AndroidはKotlin、iOSはSwiftの薄いFlutter Pluginを追加し、Pigeonで生成した型安全なChannelから`ReceiptEngine`を呼ぶ。

Channel越しにKotlin DSLやAST全体を運ばず、`ReceiptRequestDto` / `PrinterProfileDto`を渡して、Command Byte列またはPreview文字列を受け取る。これによりKMP本体はFlutter非依存のまま保てる。

## 22. エラーとDiagnostic

```kotlin
enum class DiagnosticSeverity {
    Warning,
    Error,
}

data class NodePath(
    val ticketIndex: Int? = null,
    val childIndexes: PersistentList<Int> = persistentListOf(),
)

sealed interface Diagnostic {
    val code: String
    val severity: DiagnosticSeverity
    val message: String
    val path: NodePath?
}

sealed interface CompilationIssue : Diagnostic

data class UnsupportedGlyph(
    val codePoint: Int,
    val source: String,
    override val severity: DiagnosticSeverity,
    override val path: NodePath?,
    override val code: String = "UNSUPPORTED_GLYPH",
    override val message: String = "Code point U+${codePoint.toString(16)} cannot be encoded",
) : CompilationIssue

data class UnsupportedFeature(
    val feature: String,
    val profileId: String,
    override val severity: DiagnosticSeverity,
    override val path: NodePath?,
    override val code: String = "UNSUPPORTED_FEATURE",
    override val message: String = "$feature is unsupported by $profileId",
) : CompilationIssue

data class InvalidLayout(
    val reason: String,
    override val path: NodePath?,
    override val code: String = "INVALID_LAYOUT",
    override val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    override val message: String = reason,
) : CompilationIssue

data class ValueClamped(
    val property: String,
    val requested: Int,
    val actual: Int,
    override val path: NodePath?,
    override val code: String = "VALUE_CLAMPED",
    override val severity: DiagnosticSeverity = DiagnosticSeverity.Warning,
    override val message: String = "$property was clamped from $requested to $actual",
) : CompilationIssue

data class CompilationResult<T : Any>(
    val value: T?,
    val diagnostics: PersistentList<Diagnostic>,
) {
    val isSuccess: Boolean
        get() = value != null && diagnostics.none { it.severity == DiagnosticSeverity.Error }
}
```

`OrphanItemPromoted`と`PreviewApproximation`も同じInterfaceで定義し、業務互換ModuleとPreview Moduleに置く。CoreにWaiter固有語彙を持ち込まない。

ルール:

- Recover可能な問題はWarningとして結果と一緒に返す。
- Byte列が不正になる問題はErrorとして出力を返さない。
- Legacy互換で無視する場合もDiagnosticは残す。
- DiagnosticはNode Pathを持ち、どのTicket / Node / Itemで起きたか追跡できる。
- Loggerへ直接書かず、HostがDiagnosticをログへ変換する。

## 23. テスト戦略

現行実装には帳票レイアウトとCommand Byte列の実質的なUnit Testが存在しない。移行前に特性テストを追加し、現在の出力をFixture化する必要がある。

### 23.1 Core Unit Test

- DSLから期待する不変Treeが生成される。
- Builder変更がBuild済みTreeへ影響しない。
- Style継承と復元。
- CP932 Encode / Measure。
- 変換不能文字のDrop / Replace / Fail。
- Character単位の折り返し。
- TwoColumnの同一行配置。
- TwoColumnのTrailing次行送り。
- 文字倍率による有効幅変化。
- 明示改行と空行。
- Ticket境界とCut。
- Validationが複数Errorを収集する。

### 23.2 Encoder Unit Test

- 各Commandの既知Byte Snapshot。
- 文字倍率の上限下限。
- Align、Emphasis、Inverseの開始と復元。
- Full / Partial / No Margin Cut。
- QRコード。
- Rule。
- Sound。
- Reverse。
- 空Job。
- Unsupported CapabilityのStrict / Legacy挙動。

### 23.3 waiter-compat特性テスト

最低限、以下のFixtureを作る。

イベント:

- New Order。
- Add Order。
- Change Quantity。
- Cancel Order。

入力経路:

- TableInfo。
- Store Print API。
- Internal API。

明細:

- 通常メニュー。
- 値引、割引、各クーポン。
- トッピング。
- コース親と複数子。
- 親無しトッピング。
- 親無しコース子。
- 簡易コース内容。
- 複数お好みオーダー。
- 複数行Memo。
- キャンセル明細。
- 印刷対象外明細。
- 対象の子だけを持つコース。

注文金額:

- 今回注文小計 / 注文済小計。
- 時間帯料金4種と適用位置2種。
- テーブルチャージ。
- サービスチャージ。
- 内税 / 外税。
- 丸め。
- 2倍合計。
- 割り勘。
- 小計 / 合計Option。
- QR Placeholder展開。

キッチン設定:

- 注文単位印刷ON / OFF。
- 明細間No Margin Cut。
- コース合算ON / OFF。
- Reverse ON / OFF。
- Sound ON / OFF。
- 初回タイトル変更。
- 日時縦2倍。

機種:

- FVP10。
- TM-T90KP。
- TM-m30。
- TSP650II。
- mC-Print3。
- RP-F10。

全組合せの直積は作らず、共通Layout Fixtureと機種固有Command Fixtureを分離する。業務Fixtureは代表Profile 1つでLayoutを比較し、機種Fixtureは小さなCommand Jobで全機種を比較する。

### 23.4 比較レベル

1. Canonical ViewData比較。
2. Immutable Tree比較。
3. LaidOutJob比較。
4. ByteArray比較。
5. ReceiptLine Markdown Snapshot。
6. ReceiptLine SVG Golden。これは見た目確認用で、実機Byte互換の判定には使わない。

## 24. 実装手順

AIは以下の順序を守る。後続Phaseを先に実装しない。

### Phase 0: 現行特性の固定

1. Dart側のCommand生成をI/Oから分離できるTest Harnessを作る。
2. 6機種のCommand FactoryをFake Socketなしで呼べるようにする。
3. 注文伝票、キッチン伝票、フリーテキストの代表Fixtureを作る。
4. 生成Commandの型列とByteArrayをFixtureへ保存する。
5. 現行の既知不具合を「互換維持」か「修正」に分類する。

完了条件:

- 実機なしで現行Byte列を再生成できる。
- Fixtureの由来と対象機種が記録されている。

### Phase 1: KMP ScaffoldとCore Tree

1. KMP Projectを作る。
2. `PrintJob`、`Ticket`、Node、Styleを実装する。
3. DSL MarkerとBuilderを実装する。
4. 不変性Testを追加する。
5. JVM、iOS SimulatorのTestをCIへ追加する。

完了条件:

- サンプルDSLから期待するTreeが生成される。
- Public APIにDart / Flutter依存が無い。

### Phase 2: Text CodecとLayout

1. CP932 Codecを実装する。
2. Diagnosticを実装する。
3. TextBlock Layoutを実装する。
4. TwoColumn Layoutを実装する。
5. Style Scopeを実装する。
6. Ticket、Cut、Rule、QR、SoundのLayout Opを実装する。

完了条件:

- 現行PrintMixin相当のUnit Testをすべて通す。
- Platformごとの文字幅差が無い。

### Phase 3: Command Encoder

1. ESC/POS共通Encoderを実装する。
2. TM-T90KP、TM-m30、RP-F10 Profile差を実装する。
3. Star Line Mode Encoderを実装する。
4. FVP10、TSP650II Profile差を実装する。
5. StarPRNT EncoderとmC-Print3差を実装する。
6. Reverse、Sound、Cut、QR、Ruleを実装する。
7. Dart FixtureとのByte比較Testを追加する。

完了条件:

- 6機種の代表FixtureでByte互換が取れる。
- 差異がある場合は承認済み差分として文書化される。

### Phase 4: ReceiptLine Exporter

1. Text、TwoColumn、Rule、QR、FeedをMappingする。
2. StyleをReceiptLineへMappingする。
3. 表現不能機能をDiagnostic化する。
4. Node.js Test ToolでSVG Goldenを生成する。
5. DSL Playground用の最小Sampleを作る。

完了条件:

- 同じ`PrintJob`からByteArrayとReceiptLine Markdownを生成できる。
- プレビュー生成がCoreの実機Encoderへ依存しない。

### Phase 5: waiter-compat NormalizerとTemplate

1. Canonical ViewDataを実装する。
2. 3入力経路のNormalizerを実装する。
3. Order Slip Templateを実装する。
4. Kitchen Ticket Templateを実装する。
5. Free Text Templateを実装する。
6. 全設定をPolicyへMappingする。
7. 現行FixtureとTree / Layout / Byteを比較する。

完了条件:

- 本文の現行機能一覧に未対応項目が無い。
- Source別の現行差異をLegacy Profileで再現できる。

### Phase 6: Android / iOS統合

1. Maven Artifactを生成する。
2. XCFrameworkを生成する。
3. Kotlin SampleからDSLを利用する。
4. Swift SampleからFacadeを利用する。
5. ByteArray / Data変換とDiagnostic表示を確認する。

完了条件:

- AndroidとiOSで同一Fixtureから同一Byte列を生成できる。

### Phase 7: TCP Transport

1. `PrinterConnection` Interfaceを定義する。
2. Android / iOS TCP Socket Adapterを実装する。
3. 10秒Timeout、Open、Flush、Closeを実装する。
4. 3 Command SetのStatus Parserを移植する。
5. 印刷前Status Checkを実装する。
6. Retry Policyを外部注入できるようにする。

Statusの共通結果は少なくとも`Unknown`、`Offline`、`CoverOpen`、`PaperNearEnd`、`PaperEnd`、`HardError`を表現する。現行共通Errorへ集約する際の優先順位もFixtureで固定する。

Queue、履歴、迂回はこのModuleへ含めず、Host Applicationが担当する。

## 25. 受け入れ条件

新パッケージの初回互換リリースは以下をすべて満たすこと。

- Kotlin DSLから不変な`PrintJob`を構築できる。
- DSL Builderの内部状態がBuild済みJobへ漏れない。
- AndroidとiOSで同じJobから同じByteArrayを生成できる。
- 6機種のProfileが存在する。
- 現行PrinterCommandFactoryの全機能を表現できる。
- 注文伝票の4イベントを生成できる。
- キッチン伝票の4イベントを生成できる。
- 3入力経路の現行差異をLegacy Profileで再現できる。
- 明細、コース、トッピング、お好みオーダー、Memo、価格調整を表現できる。
- 注文伝票金額欄の全項目を表現できる。
- キッチンの明細単位Cut、コース合算、Reverse、Soundを表現できる。
- フリーテキスト印刷を表現できる。
- QRコードを画像化せずネイティブCommandで出力できる。
- ReceiptLine Markdownを生成できる。
- ReceiptLineプレビューが実機コマンドの正本になっていない。
- Strict ModeとLegacy Modeが存在する。
- 未対応文字、未対応機能、値のClampをDiagnosticとして取得できる。
- 既存Dart FixtureとのByte比較Testがある。
- JVMとiOS SimulatorでCore Testが通る。
- Public APIにFlutter依存が無い。

## 26. 既知の現行挙動と移行時判断

以下は実装前に特性テストで固定し、互換維持または修正を判断する。判断されるまでは`LegacyWaiter`で現行挙動を維持する。

| 項目 | 現行挙動 | 既定判断 |
| --- | --- | --- |
| 注文伝票の`twoTimes` | Widthは常に1で、`twoTimes`でも実質通常倍率になる | Legacyで維持、Strictで2x2を許可 |
| キッチン日時の`twoTimes` | `twoHeight`だけ縦2倍で、`twoTimes`は通常 | Legacyで維持 |
| Internal APIのお好みオーダー | 先頭1件だけ利用する | Legacyで維持、Canonicalでは全件保持 |
| TM-m30 Reverse | 設定ONでも何もしない | Warning付きIgnore |
| ESC/POS Cut種別 | 一部機種でFull / Partial / NoMarginを区別しない | Legacyで維持 |
| Star No Margin Cut | Full Cutへ写像する | Legacyで維持 |
| 変換不能文字 | 警告して文字を削除する | LegacyはDrop、StrictはFail |
| RP-F10 Reverse内のLF判定 | 既存コードに型比較のFIXMEがあり常に期待通り判定されない可能性がある | Fixtureで現行Byteを固定後に判断 |
| Store Printのコース合算 | 未対応 | Legacyで未対応、Canonicalで後から対応可能 |
| Store Print QR | 出力しない | Legacyで維持 |
| 印刷後Status Check | 未実装 | 初回互換範囲外 |

## 27. 保留中の設計判断

以下は追加回答が無くても既定値で着手できる。

| 判断 | 既定値 |
| --- | --- |
| Package名 / Group ID | 仮名`receipt-kmp`、公開時に変更可能にする |
| 最低Android SDK | Sample統合先が要求する値に合わせ、CoreはAndroid API非依存にする |
| iOS最低Version | Sample統合先に合わせ、CoreはFoundation最小依存にする |
| Persistent Collection Library | `kotlinx.collections.immutable`をCore ASTで使用する |
| 金額型 | `Long`。通貨FormatはTemplate Policyが担当する |
| Locale | 初回は日本語 / 円のLegacy Template。CoreはLocale非依存 |
| ReceiptLine Runtime | Node.js開発Toolのみ |
| Transport同梱 | Coreリリース後の別Module |
| Swift任意帳票Builder | 初回は高水準Facade。需要が出たらSwift DSLを別途実装する |
| Serialization | Interop DTOを優先。保存 / Remote用途が出たらkotlinx.serializationを追加する |

## 28. 調査対象ファイル

主に以下を調査して本書へ反映した。

- `lib/application/print/commands/print_order_slip/print_order_slip.dart`
- `lib/application/print/commands/print_order_slip/internal_api_print.dart`
- `lib/application/print/commands/print_kitchen_ticket/print_kitchen_ticket.dart`
- `lib/application/print/commands/print_kitchen_ticket/internal_api_print.dart`
- `lib/application/print/commands/print_kitchen_ticket/store_print.dart`
- `lib/application/print/commands/print_free_text/print_free_text.dart`
- `lib/application/print/commands/print_mixin/print_mixin.dart`
- `lib/application/print/print_queue_manager.dart`
- `lib/application/print/commands/print_history_mixin.dart`
- `lib/domain/print/print.dart`
- `lib/domain/print/print_impl.dart`
- `lib/domain/print_event/record_slip_collection.dart`
- `lib/domain/slip/value_objects/content.dart`
- `lib/infrastructure/web_server/api/action/slip_print_api/`
- `doc/openapi/models/slip-print/`
- `packages/flutter-printer-utils/lib/printer/`
- `packages/flutter-printer-utils/lib/utils/waiter/`

## 29. 実装開始時の最初の作業

最初の実装担当AIはKMP Projectを作り始める前に、Phase 0の特性テスト作成方法を提案し、以下を成果物として提出すること。

1. 代表Fixture一覧。
2. 現行DartからI/OなしでCommandを取得するTest Harness設計。
3. Byte Fixtureの保存形式。
4. 既知不具合の互換判断一覧。
5. Phase 1で作るKMP Directory Tree。

Phase 0が承認されるまで、既存印刷実装の置換や新KMP Encoderの本実装を開始しない。

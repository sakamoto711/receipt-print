# receipt-print

Kotlin Multiplatform の共有ライブラリです。現在は次のターゲットを構成しています。

- JVM（Java 17）
- iOS ARM64（実機）
- iOS Simulator ARM64（Apple Silicon）

## 必要な環境

- JDK 17 以降
- iOS 向けビルドには macOS と Xcode

## ビルドとテスト

```shell
./gradlew build
```

JVM の共通テストだけを実行する場合:

```shell
./gradlew jvmTest
```

iOS 向け XCFramework を生成する場合:

```shell
./gradlew assembleReceiptPrintDebugXCFramework
```

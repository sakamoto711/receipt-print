// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "receipt_print",
    platforms: [.iOS("15.0")],
    products: [.library(name: "receipt-print", targets: ["receipt_print"])],
    dependencies: [.package(name: "FlutterFramework", path: "../FlutterFramework")],
    targets: [
        .binaryTarget(name: "ReceiptPrint", path: "../Frameworks/ReceiptPrint.xcframework"),
        .target(
            name: "receipt_print",
            dependencies: [
                "ReceiptPrint",
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ]
        )
    ]
)

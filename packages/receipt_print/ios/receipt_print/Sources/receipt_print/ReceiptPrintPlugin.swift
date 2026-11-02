import Flutter
import Foundation
import ReceiptPrint

public class ReceiptPrintPlugin: NSObject, FlutterPlugin {
    private let queue = DispatchQueue(label: "dev.receiptprint/receipt_print")

    public static func register(with registrar: FlutterPluginRegistrar) {
        registrar.register(ReceiptPreviewFactory(messenger: registrar.messenger()), withId: "receipt_print/preview")
        let channel = FlutterMethodChannel(
            name: "dev.receiptprint/receipt_print", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(ReceiptPrintPlugin(), channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard ["compile", "preview", "measureText", "renderSvg"].contains(call.method) else {
            result(FlutterMethodNotImplemented)
            return
        }
        queue.async {
            do {
                let arguments = try ReceiptChannelCodec.object(call.arguments)
                guard let version = arguments["protocolVersion"] as? Int, version == 1 else {
                    throw ReceiptChannelError.invalidArguments("Unsupported protocol version")
                }
                if call.method == "measureText" {
                    let width = ReceiptEngine().measureText(text: try ReceiptChannelCodec.string(arguments["text"]))
                    DispatchQueue.main.async { result(width) }
                    return
                }
                if call.method == "renderSvg" {
                    let bundle: Bundle
                    #if SWIFT_PACKAGE
                    bundle = Bundle.module
                    #else
                    bundle = Bundle(for: ReceiptPrintPlugin.self)
                    #endif
                    guard let url = bundle.url(forResource: "receiptline", withExtension: "js")
                        ?? Bundle.main.url(forResource: "receiptline", withExtension: "js") else {
                        throw ReceiptChannelError.invalidArguments("Missing bundled ReceiptLine renderer")
                    }
                    let svg = try ReceiptSvgRenderer.render(ReceiptChannelCodec.string(arguments["receiptLine"]),
                        columns: Int(ReceiptChannelCodec.int(arguments["columns"])), scriptURL: url)
                    DispatchQueue.main.async { result(svg) }
                    return
                }
                let job = try ReceiptChannelCodec.printJob(arguments["job"])
                let printer = try ReceiptChannelCodec.printerProfile(arguments["printer"])
                let engine = ReceiptEngine()
                var payload: [String: Any]
                if call.method == "compile" {
                    let response = engine.compile(job: job, printer: printer)
                    var bytes: Any = NSNull()
                    if let value = response.bytes {
                        var data = Data(capacity: Int(value.size))
                        for index in 0..<value.size {
                            data.append(UInt8(bitPattern: value.get(index: index)))
                        }
                        bytes = FlutterStandardTypedData(bytes: data)
                    }
                    payload = ["bytes": bytes,
                               "diagnostics": ReceiptChannelCodec.diagnostics(response.diagnostics)]
                } else {
                    let response = engine.preview(job: job, printer: printer)
                    payload = ["receiptLine": response.receiptLine as Any? ?? NSNull(),
                               "diagnostics": ReceiptChannelCodec.diagnostics(response.diagnostics)]
                }
                DispatchQueue.main.async { result(payload) }
            } catch {
                let error = FlutterError(code: "invalid_arguments",
                                         message: String(describing: error), details: nil)
                DispatchQueue.main.async { result(error) }
            }
        }
    }
}

import Flutter
import Foundation
import ReceiptPrint

public class ReceiptPrintPlugin: NSObject, FlutterPlugin {
    private let queue = DispatchQueue(label: "dev.receiptprint/receipt_print")

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: "dev.receiptprint/receipt_print", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(ReceiptPrintPlugin(), channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard call.method == "compile" || call.method == "preview" else {
            result(FlutterMethodNotImplemented)
            return
        }
        queue.async {
            do {
                let arguments = try ReceiptChannelCodec.object(call.arguments)
                guard let version = arguments["protocolVersion"] as? Int, version == 1 else {
                    throw ReceiptChannelError.invalidArguments("Unsupported protocol version")
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

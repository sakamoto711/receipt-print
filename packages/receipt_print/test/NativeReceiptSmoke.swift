import Foundation
import ReceiptPrint

@main
struct NativeReceiptSmoke {
    static func main() throws {
        let input = URL(fileURLWithPath: CommandLine.arguments[1])
        let renderer = URL(fileURLWithPath: CommandLine.arguments[2])
        let output = URL(fileURLWithPath: CommandLine.arguments[3])
        let requests = try JSONSerialization.jsonObject(with: Data(contentsOf: input)) as! [[String: Any]]
        let engine = ReceiptEngine()
        let responses = try requests.map { args -> [String: Any] in
            if let text = args["measure"] as? String { return ["columns": engine.measureText(text: text)] }
            let job = try ReceiptChannelCodec.printJob(args["job"])
            let profile = try ReceiptChannelCodec.printerProfile(args["printer"])
            let compiled = engine.compile(job: job, printer: profile)
            let preview = engine.preview(job: job, printer: profile)
            guard compiled.isSuccess, preview.isSuccess, let bytes = compiled.bytes, let text = preview.receiptLine else {
                throw ReceiptChannelError.invalidArguments("Native compile/preview failed: \(compiled.diagnostics) \(preview.diagnostics)")
            }
            var data = Data()
            for index in 0..<bytes.size { data.append(UInt8(bitPattern: bytes.get(index: index))) }
            let svg = try ReceiptSvgRenderer.render(text, columns: Int(profile.columnsPerLine), scriptURL: renderer)
            return ["bytes": data.base64EncodedString(), "svg": svg, "receiptLine": text,
                "diagnostics": ReceiptChannelCodec.diagnostics(preview.diagnostics)]
        }
        try JSONSerialization.data(withJSONObject: responses, options: [.sortedKeys]).write(to: output)
        print("Native receipt smoke passed: \(responses.count) requests")
    }
}

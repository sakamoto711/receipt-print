import Foundation
import JavaScriptCore

// A fresh context keeps rendering isolated; receipt text is passed as a JS argument, never evaluated.
enum ReceiptSvgRenderer {
    static func render(_ text: String, columns: Int, scriptURL: URL) throws -> String {
        guard let context = JSContext() else {
            throw ReceiptChannelError.invalidArguments("Unable to initialize JavaScriptCore")
        }
        context.setObject(UUID().uuidString, forKeyedSubscript: "receiptSvgId" as NSString)
        // ReceiptLine uses Blob URLs only to generate an SVG filter ID. JavaScriptCore
        // has no DOM, so supply this ID operation and the UTF-8 encoder needed by QR.
        context.evaluateScript("""
          var window = this;
          var Blob = function() {};
          var URL = {createObjectURL: function() {return 'blob:' + receiptSvgId;}, revokeObjectURL: function() {}};
          var TextEncoder = function() {};
          TextEncoder.prototype.encode = function(value) {
            var utf8 = unescape(encodeURIComponent(value));
            var bytes = new Uint8Array(utf8.length);
            for (var i = 0; i < utf8.length; i++) bytes[i] = utf8.charCodeAt(i);
            return bytes;
          };
          """)
        context.evaluateScript(try String(contentsOf: scriptURL, encoding: .utf8))
        guard context.exception == nil else { throw ReceiptChannelError.invalidArguments("ReceiptLine initialization failed") }
        let options: [String: Any] = ["cpl": columns, "encoding": "cp932"]
        let svg = context.objectForKeyedSubscript("receiptline")?
            .invokeMethod("transform", withArguments: [text, options])?.toString()
        guard context.exception == nil, let svg = svg, svg.contains("<svg") else {
            throw ReceiptChannelError.invalidArguments("ReceiptLine SVG generation failed: \(context.exception?.toString() ?? "no SVG")")
        }
        // ReceiptLine emits a Google Fonts @import by default. Use local fonts
        // so exported SVGs are self-contained and do not request remote resources.
        return svg.replacingOccurrences(of: "(?s)<style[^>]*>.*?</style>", with: "", options: .regularExpression)
    }
}

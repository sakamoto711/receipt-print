import Flutter
import Foundation
import XCTest
@testable import receipt_print

class RunnerTests: XCTestCase {
    func testUnknownMethodsAreNotImplemented() {
        let plugin = ReceiptPrintPlugin()
        plugin.handle(FlutterMethodCall(methodName: "unknown", arguments: nil)) { result in
            XCTAssertTrue((result as AnyObject?) === (FlutterMethodNotImplemented as AnyObject))
        }
    }

    func testMalformedRequestsReturnAnError() {
        let done = expectation(description: "error response")
        ReceiptPrintPlugin().handle(
            FlutterMethodCall(methodName: "compile", arguments: ["protocolVersion": 99])
        ) { result in
            XCTAssertEqual((result as? FlutterError)?.code, "invalid_arguments")
            done.fulfill()
        }
        waitForExpectations(timeout: 5)
    }

    func testNondefaultStylesAndWidthsSurviveDecoding() throws {
        let style: [String: Any] = ["widthScale": 2, "heightScale": 3,
            "emphasized": true, "inverted": true, "align": "Right"]
        let text = try ReceiptChannelCodec.text(["value": "日本語", "style": style,
            "wrap": "Character", "preserveNewLines": false])
        XCTAssertEqual(text.style.widthScale, 2)
        XCTAssertEqual(text.style.heightScale, 3)
        XCTAssertTrue(text.style.emphasized)
        XCTAssertTrue(text.style.inverted)
        XCTAssertEqual(text.style.align.name, "Right")
        XCTAssertEqual(text.wrap.name, "Character")
        XCTAssertFalse(text.preserveNewLines)
        let columns = try ReceiptChannelCodec.twoColumn([
            "leading": ["value": "A", "style": style],
            "trailing": ["value": "B", "style": style],
            "widthColumns": 32, "leadingReservedColumns": 12,
            "trailingReservedColumns": 8, "overflow": "Clip"])
        XCTAssertEqual(columns.widthColumns?.intValue, 32)
        XCTAssertEqual(columns.leadingReservedColumns?.intValue, 12)
        XCTAssertEqual(columns.trailingReservedColumns?.intValue, 8)
        XCTAssertEqual(columns.overflow.name, "Clip")
        XCTAssertEqual(columns.trailing?.value, "B")
        XCTAssertThrowsError(try ReceiptChannelCodec.feed(["lines": Int64(2147483648)]))
        XCTAssertThrowsError(try ReceiptChannelCodec.feed(["lines": true]))
    }
}

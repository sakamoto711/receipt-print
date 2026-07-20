import Foundation
import ReceiptPrint

enum ReceiptEngineSampleError: Error {
    case compilationFailed([DiagnosticDto])
}

func compileGenericTreeSample() throws -> Data {
    let style = TextStyleDto(
        widthScale: 1,
        heightScale: 1,
        emphasized: false,
        inverted: false,
        align: .left
    )
    let text = TextDto(
        value: "キッチンへお願いします",
        style: style,
        wrap: .wordorcharacter,
        preserveNewLines: true
    )
    let ticket = TicketDto(children: [text], cut: .partial)
    let job = PrintJobDto(tickets: [ticket], direction: .normal, startActions: [])
    let printer = PrinterProfileDto(
        model: .epsontmm30,
        columnsPerLine: 48,
        compatibilityMode: .strict,
        soundEnabled: true
    )
    let response = ReceiptEngine().compile(job: job, printer: printer)

    guard response.isSuccess, let bytes = response.bytes else {
        throw ReceiptEngineSampleError.compilationFailed(response.diagnostics)
    }

    var data = Data(capacity: Int(bytes.size))
    for index in 0..<bytes.size {
        data.append(UInt8(bitPattern: bytes.get(index: index)))
    }
    return data
}

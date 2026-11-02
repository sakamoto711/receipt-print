import Foundation
import CoreFoundation
import ReceiptPrint

enum ReceiptChannelError: Error {
    case invalidArguments(String)
}

enum ReceiptChannelCodec {
    private static func printDirectionDto(_ value: Any?) throws -> PrintDirectionDto {
        switch try string(value) {
        case "Normal": return .normal
        case "Reverse": return .reverse
        default: throw ReceiptChannelError.invalidArguments("Unknown PrintDirectionDto value")
        }
    }

    private static func jobActionDto(_ value: Any?) throws -> JobActionDto {
        switch try string(value) {
        case "PlaySound": return .playsound
        default: throw ReceiptChannelError.invalidArguments("Unknown JobActionDto value")
        }
    }

    private static func cutModeDto(_ value: Any?) throws -> CutModeDto {
        switch try string(value) {
        case "None": return .none
        case "Full": return .full
        case "Partial": return .partial
        case "NoMargin": return .nomargin
        default: throw ReceiptChannelError.invalidArguments("Unknown CutModeDto value")
        }
    }

    private static func textAlignDto(_ value: Any?) throws -> TextAlignDto {
        switch try string(value) {
        case "Left": return .left
        case "Center": return .center
        case "Right": return .right
        default: throw ReceiptChannelError.invalidArguments("Unknown TextAlignDto value")
        }
    }

    private static func wrapModeDto(_ value: Any?) throws -> WrapModeDto {
        switch try string(value) {
        case "None": return .none
        case "Character": return .character
        case "WordOrCharacter": return .wordorcharacter
        default: throw ReceiptChannelError.invalidArguments("Unknown WrapModeDto value")
        }
    }

    private static func twoColumnOverflowDto(_ value: Any?) throws -> TwoColumnOverflowDto {
        switch try string(value) {
        case "WrapLeadingThenTrailing": return .wrapleadingthentrailing
        case "TrailingOnNextLine": return .trailingonnextline
        case "Clip": return .clip
        case "Fail": return .fail
        default: throw ReceiptChannelError.invalidArguments("Unknown TwoColumnOverflowDto value")
        }
    }

    private static func qrErrorCorrectionDto(_ value: Any?) throws -> QrErrorCorrectionDto {
        switch try string(value) {
        case "Low": return .low
        case "Medium": return .medium
        case "Quartile": return .quartile
        case "High": return .high
        default: throw ReceiptChannelError.invalidArguments("Unknown QrErrorCorrectionDto value")
        }
    }

    private static func printerModelDto(_ value: Any?) throws -> PrinterModelDto {
        switch try string(value) {
        case "StarFvp10": return .starfvp10
        case "EpsonTmT90Kp": return .epsontmt90kp
        case "EpsonTmM30": return .epsontmm30
        case "StarTsp650II": return .startsp650ii
        case "StarMcPrint3": return .starmcprint3
        case "SiiRpF10": return .siirpf10
        default: throw ReceiptChannelError.invalidArguments("Unknown PrinterModelDto value")
        }
    }

    private static func compatibilityModeDto(_ value: Any?) throws -> CompatibilityModeDto {
        switch try string(value) {
        case "Strict": return .strict
        case "Compatibility": return .compatibility
        default: throw ReceiptChannelError.invalidArguments("Unknown CompatibilityModeDto value")
        }
    }

    static func printJob(_ value: Any?) throws -> PrintJobDto {
        let map = try object(value)
        return PrintJobDto(
            tickets: try list(map["tickets"]).map { try ticket($0) },
            direction: try printDirectionDto(map["direction"]),
            startActions: try list(map["startActions"]).map { try jobActionDto($0) }
        )
    }

    static func ticket(_ value: Any?) throws -> TicketDto {
        let map = try object(value)
        return TicketDto(
            children: try list(map["children"]).map { try node($0) },
            cut: try cutModeDto(map["cut"])
        )
    }

    static func column(_ value: Any?) throws -> ColumnDto {
        let map = try object(value)
        return ColumnDto(
            children: try list(map["children"]).map { try node($0) },
            style: try optional(map["style"]) { try textStyle($0) }
        )
    }

    static func text(_ value: Any?) throws -> TextDto {
        let map = try object(value)
        return TextDto(
            value: try string(map["value"]),
            style: try textStyle(map["style"]),
            wrap: try wrapModeDto(map["wrap"]),
            preserveNewLines: try boolean(map["preserveNewLines"])
        )
    }

    static func twoColumn(_ value: Any?) throws -> TwoColumnDto {
        let map = try object(value)
        return TwoColumnDto(
            leading: try textRun(map["leading"]),
            trailing: try optional(map["trailing"]) { try textRun($0) },
            widthColumns: try optional(map["widthColumns"]) { KotlinInt(value: try int($0)) },
            leadingReservedColumns: try optional(map["leadingReservedColumns"]) { KotlinInt(value: try int($0)) },
            trailingReservedColumns: try optional(map["trailingReservedColumns"]) { KotlinInt(value: try int($0)) },
            overflow: try twoColumnOverflowDto(map["overflow"])
        )
    }

    static func rule(_ value: Any?) throws -> RuleDto {
        let map = try object(value)
        return RuleDto(
            thickness: try int(map["thickness"])
        )
    }

    static func qrCode(_ value: Any?) throws -> QrCodeDto {
        let map = try object(value)
        return QrCodeDto(
            data: try string(map["data"]),
            cellSize: try int(map["cellSize"]),
            errorCorrection: try qrErrorCorrectionDto(map["errorCorrection"]),
            align: try textAlignDto(map["align"])
        )
    }

    static func feed(_ value: Any?) throws -> FeedDto {
        let map = try object(value)
        return FeedDto(
            lines: try int(map["lines"])
        )
    }

    static func textRun(_ value: Any?) throws -> TextRunDto {
        let map = try object(value)
        return TextRunDto(
            value: try string(map["value"]),
            style: try textStyle(map["style"])
        )
    }

    static func textStyle(_ value: Any?) throws -> TextStyleDto {
        let map = try object(value)
        return TextStyleDto(
            widthScale: try int(map["widthScale"]),
            heightScale: try int(map["heightScale"]),
            emphasized: try boolean(map["emphasized"]),
            inverted: try boolean(map["inverted"]),
            align: try textAlignDto(map["align"])
        )
    }

    static func printerProfile(_ value: Any?) throws -> PrinterProfileDto {
        let map = try object(value)
        return PrinterProfileDto(
            model: try printerModelDto(map["model"]),
            columnsPerLine: try int(map["columnsPerLine"]),
            compatibilityMode: try compatibilityModeDto(map["compatibilityMode"]),
            soundEnabled: try boolean(map["soundEnabled"]),
            optionalExternalBuzzerEnabled: try boolean(map["optionalExternalBuzzerEnabled"])
        )
    }

    private static func node(_ value: Any?) throws -> PrintNodeDto {
        let map = try object(value)
        switch try string(map["type"]) {
        case "inline": return InlineLineDto(runs: try list(map["runs"]).map { try textRun($0) },
            align: try textAlignDto(map["align"]))
        case "text": return try text(map)
        case "column": return try column(map)
        case "twoColumn": return try twoColumn(map)
        case "rule": return try rule(map)
        case "qrCode": return try qrCode(map)
        case "feed": return try feed(map)
        default: throw ReceiptChannelError.invalidArguments("Unknown print node type")
        }
    }

    static func diagnostics(_ values: [DiagnosticDto]) -> [[String: Any]] {
        values.map { value in
            ["code": value.code, "severity": value.severity.name, "message": value.message,
             "ticketIndex": value.ticketIndex.map { $0.intValue as Any } ?? NSNull(),
             "childIndexes": value.childIndexes.map { $0.intValue }]
        }
    }

    static func object(_ value: Any?) throws -> [String: Any] {
        guard let value = value as? [String: Any] else {
            throw ReceiptChannelError.invalidArguments("Expected an object")
        }
        return value
    }
    private static func list(_ value: Any?) throws -> [Any] {
        guard let value = value as? [Any] else {
            throw ReceiptChannelError.invalidArguments("Expected a list")
        }
        return value
    }
    static func string(_ value: Any?) throws -> String {
        guard let value = value as? String else {
            throw ReceiptChannelError.invalidArguments("Expected a string")
        }
        return value
    }
    private static func boolean(_ value: Any?) throws -> Bool {
        guard let value = value as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else {
            throw ReceiptChannelError.invalidArguments("Expected a boolean")
        }
        return value.boolValue
    }
    static func int(_ value: Any?) throws -> Int32 {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
              let result = Int32(exactly: value.doubleValue) else {
            throw ReceiptChannelError.invalidArguments("Expected an Int32 integer")
        }
        return result
    }
    private static func optional<T>(_ value: Any?, _ decode: (Any) throws -> T) rethrows -> T? {
        guard let value = value, !(value is NSNull) else { return nil }
        return try decode(value)
    }
}

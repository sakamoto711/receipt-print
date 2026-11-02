package dev.receiptprint.receipt_print.codec

import receiptdsl.engine.*

internal object ReceiptChannelCodec {
    fun printJob(value: Any?): PrintJobDto {
        val map = obj(value)
        return PrintJobDto(
            tickets = list(map["tickets"]).map { ticket(it) },
            direction = enum<PrintDirectionDto>(map["direction"]),
            startActions = list(map["startActions"]).map { enum<JobActionDto>(it) },
        )
    }

    fun ticket(value: Any?): TicketDto {
        val map = obj(value)
        return TicketDto(
            children = PrintNodeListDto(list(map["children"]).map(::node)),
            cut = enum<CutModeDto>(map["cut"]),
        )
    }

    fun column(value: Any?): ColumnDto {
        val map = obj(value)
        return ColumnDto(
            children = PrintNodeListDto(list(map["children"]).map(::node)),
            style = map["style"]?.let { textStyle(it) },
        )
    }

    fun text(value: Any?): TextDto {
        val map = obj(value)
        return TextDto(
            value = string(map["value"]),
            style = textStyle(map["style"]),
            wrap = enum<WrapModeDto>(map["wrap"]),
            preserveNewLines = boolean(map["preserveNewLines"]),
        )
    }

    fun twoColumn(value: Any?): TwoColumnDto {
        val map = obj(value)
        return TwoColumnDto(
            leading = textRun(map["leading"]),
            trailing = map["trailing"]?.let { textRun(it) },
            widthColumns = map["widthColumns"]?.let { int(it) },
            leadingReservedColumns = map["leadingReservedColumns"]?.let { int(it) },
            trailingReservedColumns = map["trailingReservedColumns"]?.let { int(it) },
            overflow = enum<TwoColumnOverflowDto>(map["overflow"]),
        )
    }

    fun rule(value: Any?): RuleDto {
        val map = obj(value)
        return RuleDto(
            thickness = int(map["thickness"]),
        )
    }

    fun qrCode(value: Any?): QrCodeDto {
        val map = obj(value)
        return QrCodeDto(
            data = string(map["data"]),
            cellSize = int(map["cellSize"]),
            errorCorrection = enum<QrErrorCorrectionDto>(map["errorCorrection"]),
            align = enum<TextAlignDto>(map["align"]),
        )
    }

    fun feed(value: Any?): FeedDto {
        val map = obj(value)
        return FeedDto(
            lines = int(map["lines"]),
        )
    }

    fun textRun(value: Any?): TextRunDto {
        val map = obj(value)
        return TextRunDto(
            value = string(map["value"]),
            style = textStyle(map["style"]),
        )
    }

    fun textStyle(value: Any?): TextStyleDto {
        val map = obj(value)
        return TextStyleDto(
            widthScale = int(map["widthScale"]),
            heightScale = int(map["heightScale"]),
            emphasized = boolean(map["emphasized"]),
            inverted = boolean(map["inverted"]),
            align = enum<TextAlignDto>(map["align"]),
        )
    }

    fun printerProfile(value: Any?): PrinterProfileDto {
        val map = obj(value)
        return PrinterProfileDto(
            model = enum<PrinterModelDto>(map["model"]),
            columnsPerLine = int(map["columnsPerLine"]),
            compatibilityMode = enum<CompatibilityModeDto>(map["compatibilityMode"]),
            soundEnabled = boolean(map["soundEnabled"]),
            optionalExternalBuzzerEnabled = boolean(map["optionalExternalBuzzerEnabled"]),
        )
    }

    private fun node(value: Any?): PrintNodeDto {
        val map = obj(value)
        return when (string(map["type"])) {
            "text" -> text(map)
            "column" -> column(map)
            "twoColumn" -> twoColumn(map)
            "rule" -> rule(map)
            "qrCode" -> qrCode(map)
            "feed" -> feed(map)
            else -> throw IllegalArgumentException("Unknown print node type")
        }
    }

    fun diagnostics(values: List<DiagnosticDto>): List<Map<String, Any?>> = values.map {
        mapOf(
            "code" to it.code, "severity" to it.severity.name, "message" to it.message,
            "ticketIndex" to it.ticketIndex, "childIndexes" to it.childIndexes,
        )
    }

    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *>
        ?: throw IllegalArgumentException("Expected an object")
    private fun list(value: Any?): List<*> = value as? List<*>
        ?: throw IllegalArgumentException("Expected a list")
    private fun string(value: Any?): String = value as? String
        ?: throw IllegalArgumentException("Expected a string")
    private fun boolean(value: Any?): Boolean = value as? Boolean
        ?: throw IllegalArgumentException("Expected a boolean")
    private fun int(value: Any?): Int {
        val number = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("Expected an integer")
        }
        require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer is outside the Int32 range" }
        return number.toInt()
    }
    private inline fun <reified T : Enum<T>> enum(value: Any?): T = enumValueOf(string(value))
}

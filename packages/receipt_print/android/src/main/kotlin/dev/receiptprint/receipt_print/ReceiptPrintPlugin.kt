package dev.receiptprint.receipt_print

import dev.receiptprint.receipt_print.codec.ReceiptChannelCodec
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import receiptdsl.engine.ReceiptEngine

class ReceiptPrintPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(
            binding.binaryMessenger,
            "dev.receiptprint/receipt_print",
            StandardMethodCodec.INSTANCE,
            binding.binaryMessenger.makeBackgroundTaskQueue(),
        ).also { it.setMethodCallHandler(this) }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "compile" && call.method != "preview") {
            result.notImplemented()
            return
        }
        try {
            val arguments = call.arguments as? Map<*, *>
                ?: throw IllegalArgumentException("Expected an object")
            require(arguments["protocolVersion"] == 1) { "Unsupported protocol version" }
            val job = ReceiptChannelCodec.printJob(arguments["job"])
            val printer = ReceiptChannelCodec.printerProfile(arguments["printer"])
            val engine = ReceiptEngine()
            if (call.method == "compile") {
                val response = engine.compile(job, printer)
                result.success(mapOf(
                    "bytes" to response.bytes,
                    "diagnostics" to ReceiptChannelCodec.diagnostics(response.diagnostics),
                ))
            } else {
                val response = engine.preview(job, printer)
                result.success(mapOf(
                    "receiptLine" to response.receiptLine,
                    "diagnostics" to ReceiptChannelCodec.diagnostics(response.diagnostics),
                ))
            }
        } catch (error: IllegalArgumentException) {
            result.error("invalid_arguments", error.message, null)
        } catch (error: Exception) {
            result.error("engine_error", error.message, null)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}

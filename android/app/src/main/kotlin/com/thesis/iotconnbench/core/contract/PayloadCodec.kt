package com.thesis.iotconnbench.core.contract

import com.thesis.iotconnbench.core.domain.LogicalPayload
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.util.Json

/**
 * Configurable mapping between the common logical workload and the bytes the ESP32 firmware
 * expects on BLE and MQTT (spec §4.3).
 *
 * The payload format is NOT assumed. Every template and field name is a PLACEHOLDER (empty by
 * default) to be copied from the firmware contract. docs/DEVICE_CONTRACT.md contains a *proposed*
 * format that the firmware author may adopt; it is not applied automatically.
 *
 * Template placeholders:
 *   {op_id}  unique operation id (must be echoed by the device in its acknowledgement)
 *   {op}     READ_STATE / WRITE_STATE / SUBSCRIBE_STATE
 *   {value}  logical value (writes); empty for reads
 *   {pad}    filler characters added so the encoded message reaches the requested payload size
 *
 * Acknowledgements are parsed as JSON objects. The ack matches a request when
 * ack[ackOpIdField] == op_id. If [ackStatusField] is set, success requires
 * ack[ackStatusField] == [ackOkValue].
 */
data class CodecConfig(
    val writeTemplate: String = "",
    val readTemplate: String = "",
    val ackOpIdField: String = "",
    val ackStatusField: String = "",
    val ackOkValue: String = "",
    val ackValueField: String = "",
    val padChar: Char = 'x',
) {
    fun missingFields(prefix: String): List<String> = buildList {
        if (writeTemplate.isBlank()) add("$prefix.writeTemplate")
        if (readTemplate.isBlank()) add("$prefix.readTemplate")
        if (ackOpIdField.isBlank()) add("$prefix.ackOpIdField")
        if (writeTemplate.isNotBlank() && !writeTemplate.contains("{op_id}")) add("$prefix.writeTemplate must contain {op_id}")
        if (readTemplate.isNotBlank() && !readTemplate.contains("{op_id}")) add("$prefix.readTemplate must contain {op_id}")
        if (ackStatusField.isNotBlank() && ackOkValue.isBlank()) add("$prefix.ackOkValue")
    }

    fun metadata(prefix: String) = mapOf(
        "$prefix.writeTemplate" to writeTemplate,
        "$prefix.readTemplate" to readTemplate,
        "$prefix.ackOpIdField" to ackOpIdField,
        "$prefix.ackStatusField" to ackStatusField,
        "$prefix.ackOkValue" to ackOkValue,
        "$prefix.ackValueField" to ackValueField,
    )
}

/** A decoded device-level acknowledgement. */
data class DeviceAck(
    val operationId: String?,
    val ok: Boolean,
    val value: Long?,
    val statusText: String?,
    val raw: String,
)

class CodecException(msg: String) : Exception(msg)

class TemplatePayloadCodec(private val cfg: CodecConfig) {

    /** Encodes a request. Throws [CodecException] if the target size is smaller than the minimal message. */
    fun encode(opId: String, type: OperationType, payload: LogicalPayload): ByteArray {
        val template = when (type) {
            OperationType.WRITE_STATE -> cfg.writeTemplate
            OperationType.READ_STATE, OperationType.SUBSCRIBE_STATE -> cfg.readTemplate
        }
        if (type == OperationType.WRITE_STATE && payload.value == null) {
            throw CodecException("WRITE_STATE requires a value")
        }
        val base = template
            .replace("{op_id}", opId)
            .replace("{op}", type.name)
            .replace("{value}", payload.value?.toString() ?: "")
        val withoutPad = base.replace("{pad}", "")
        val minSize = withoutPad.toByteArray(Charsets.UTF_8).size
        val target = payload.targetSizeBytes
        val text = if (target == null) {
            withoutPad
        } else {
            if (target < minSize) throw CodecException("Requested payload size $target B < minimal message $minSize B")
            if (!template.contains("{pad}")) {
                if (target != minSize) throw CodecException("Template has no {pad}; cannot reach $target B (minimal $minSize B)")
                withoutPad
            } else {
                base.replace("{pad}", cfg.padChar.toString().repeat(target - minSize))
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Decodes an acknowledgement. Returns null when the bytes are not a parseable JSON object. */
    fun decodeAck(bytes: ByteArray): DeviceAck? {
        val raw = bytes.toString(Charsets.UTF_8)
        val obj = try { Json.parseObject(raw) } catch (e: Exception) { return null }
        val opId = obj[cfg.ackOpIdField]?.toString()
        val status = if (cfg.ackStatusField.isBlank()) null else obj[cfg.ackStatusField]?.toString()
        val ok = if (cfg.ackStatusField.isBlank()) true else status == cfg.ackOkValue
        val value = if (cfg.ackValueField.isBlank()) null else when (val v = obj[cfg.ackValueField]) {
            is Long -> v
            is Double -> v.toLong()
            is Boolean -> if (v) 1L else 0L
            is String -> v.toLongOrNull() ?: when (v.lowercase()) { "true", "on" -> 1L; "false", "off" -> 0L; else -> null }
            else -> null
        }
        return DeviceAck(opId, ok, value, status, raw)
    }
}

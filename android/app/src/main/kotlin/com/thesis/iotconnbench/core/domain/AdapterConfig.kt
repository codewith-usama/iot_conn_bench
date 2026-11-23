package com.thesis.iotconnbench.core.domain

import com.thesis.iotconnbench.core.contract.CodecConfig

/**
 * Typed device-contract configuration (spec §2.4, Appendix A.C).
 *
 * No UUID, topic, endpoint, cluster or attribute value is hard-coded anywhere in the app.
 * Every field defaults to an empty/null PLACEHOLDER and [missingFields] reports what is still
 * required from the ESP32 firmware contract. Adapters refuse to initialize while anything is
 * missing, so an unconfigured path can never produce a fabricated result.
 */
sealed interface AdapterConfig {
    val protocol: ProtocolType
    fun missingFields(): List<String>

    /** Non-secret settings that belong in experiment metadata. */
    fun metadata(): Map<String, String>
}

enum class BleReadMode {
    /** Native GATT read of [BleAdapterConfig.stateCharUuid]; completion = onCharacteristicRead. */
    GATT_READ,
    /** Read request written to the command characteristic; completion = correlated notification. */
    COMMAND,
}

data class BleAdapterConfig(
    val serviceUuid: String = "",
    val commandCharUuid: String = "",
    /** Characteristic on which the device notifies/indicates application acknowledgements. */
    val responseCharUuid: String = "",
    /** Optional; required only for BleReadMode.GATT_READ. */
    val stateCharUuid: String = "",
    val writeWithResponse: Boolean = true,
    val requestedMtu: Int = 247,
    val scanTimeoutMs: Long = 8_000,
    val connectTimeoutMs: Long = 15_000,
    val readMode: BleReadMode = BleReadMode.COMMAND,
    /** Optional advertised-name filter in addition to the service UUID filter. */
    val deviceNameFilter: String = "",
    val codec: CodecConfig = CodecConfig(),
) : AdapterConfig {
    override val protocol get() = ProtocolType.BLE

    override fun missingFields(): List<String> = buildList {
        if (serviceUuid.isBlank()) add("ble.serviceUuid")
        if (commandCharUuid.isBlank()) add("ble.commandCharUuid")
        if (responseCharUuid.isBlank()) add("ble.responseCharUuid")
        if (readMode == BleReadMode.GATT_READ && stateCharUuid.isBlank()) add("ble.stateCharUuid")
        addAll(codec.missingFields("ble.codec"))
        listOf(serviceUuid, commandCharUuid, responseCharUuid, stateCharUuid)
            .filter { it.isNotBlank() && !UUID_RE.matches(it) }
            .forEach { add("ble.invalidUuid:$it") }
    }

    override fun metadata() = mapOf(
        "ble.serviceUuid" to serviceUuid,
        "ble.commandCharUuid" to commandCharUuid,
        "ble.responseCharUuid" to responseCharUuid,
        "ble.stateCharUuid" to stateCharUuid,
        "ble.writeWithResponse" to writeWithResponse.toString(),
        "ble.requestedMtu" to requestedMtu.toString(),
        "ble.readMode" to readMode.name,
    ) + codec.metadata("ble.codec")

    companion object {
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

data class MqttAdapterConfig(
    val host: String = "",
    val port: Int = 1883,
    val useTls: Boolean = false,
    val clientId: String = "",
    val keepAliveSeconds: Int = 60,
    val cleanStart: Boolean = true,
    val sessionExpirySeconds: Long = 0,
    val commandTopic: String = "",
    val ackTopic: String = "",
    val stateTopic: String = "",
    /** 0, 1 or 2. Recorded with every run. */
    val qos: Int = 1,
    val username: String = "",
    /** True when a password is held in the Keystore-backed secret store (never in this object). */
    val hasStoredPassword: Boolean = false,
    /** Explicit opt-in for unauthenticated, non-TLS lab brokers (spec §5.2). */
    val allowInsecureLab: Boolean = false,
    /** Also send MQTT 5 Response Topic + Correlation Data with each command. */
    val useMqtt5RequestResponse: Boolean = true,
    val codec: CodecConfig = CodecConfig(),
) : AdapterConfig {
    override val protocol get() = ProtocolType.MQTT_V5

    override fun missingFields(): List<String> = buildList {
        if (host.isBlank()) add("mqtt.host")
        if (port !in 1..65535) add("mqtt.port")
        if (clientId.isBlank()) add("mqtt.clientId")
        if (commandTopic.isBlank()) add("mqtt.commandTopic")
        if (ackTopic.isBlank()) add("mqtt.ackTopic")
        if (qos !in 0..2) add("mqtt.qos")
        if (!useTls && !allowInsecureLab) add("mqtt.tlsOrExplicitInsecureLabOptIn")
        addAll(codec.missingFields("mqtt.codec"))
    }

    override fun metadata() = mapOf(
        "mqtt.host" to host,
        "mqtt.port" to port.toString(),
        "mqtt.tls" to useTls.toString(),
        "mqtt.clientId" to clientId,
        "mqtt.keepAliveSeconds" to keepAliveSeconds.toString(),
        "mqtt.cleanStart" to cleanStart.toString(),
        "mqtt.sessionExpirySeconds" to sessionExpirySeconds.toString(),
        "mqtt.commandTopic" to commandTopic,
        "mqtt.ackTopic" to ackTopic,
        "mqtt.stateTopic" to stateTopic,
        "mqtt.qos" to qos.toString(),
        "mqtt.authenticated" to (username.isNotBlank()).toString(),
        "mqtt.requestResponse" to useMqtt5RequestResponse.toString(),
    ) + codec.metadata("mqtt.codec")
}

enum class MatterWriteMode {
    /** Write the configured attribute with the logical value. */
    WRITE_ATTRIBUTE,
    /** Invoke OnOff On (value != 0) / Off (value == 0). Requires clusterId = 0x0006. */
    INVOKE_ONOFF,
}

enum class MatterValueType { BOOLEAN, UINT8, UINT16, UINT32, INT16, INT32 }

data class MatterAdapterConfig(
    /** Operational node id assigned at commissioning (decimal). 0 = not commissioned yet. */
    val nodeId: Long = 0,
    val endpointId: Int = -1,
    val clusterId: Long = -1,
    val attributeId: Long = -1,
    val valueType: MatterValueType = MatterValueType.BOOLEAN,
    val writeMode: MatterWriteMode = MatterWriteMode.WRITE_ATTRIBUTE,
    /** Device-side firmware info reported by the operator; read back from BasicInformation if possible. */
    val interactionTimeoutMs: Long = 10_000,
    val subscribeMinIntervalS: Int = 0,
    val subscribeMaxIntervalS: Int = 10,
) : AdapterConfig {
    override val protocol get() = ProtocolType.MATTER

    override fun missingFields(): List<String> = buildList {
        if (nodeId <= 0) add("matter.nodeId(commission first)")
        if (endpointId < 0) add("matter.endpointId")
        if (clusterId < 0) add("matter.clusterId")
        if (attributeId < 0) add("matter.attributeId")
        if (writeMode == MatterWriteMode.INVOKE_ONOFF && clusterId != 0x0006L) {
            add("matter.clusterId must be 0x0006 for INVOKE_ONOFF")
        }
    }

    override fun metadata() = mapOf(
        "matter.nodeId" to nodeId.toString(),
        "matter.endpointId" to endpointId.toString(),
        "matter.clusterId" to "0x" + clusterId.toString(16),
        "matter.attributeId" to "0x" + attributeId.toString(16),
        "matter.valueType" to valueType.name,
        "matter.writeMode" to writeMode.name,
        "matter.interactionTimeoutMs" to interactionTimeoutMs.toString(),
    )
}

package com.thesis.iotconnbench.core.mapping

import com.thesis.iotconnbench.core.bench.ExperimentConfig
import com.thesis.iotconnbench.core.bench.OperationRecord
import com.thesis.iotconnbench.core.bench.OrderStrategy
import com.thesis.iotconnbench.core.bench.RunInfo
import com.thesis.iotconnbench.core.contract.CodecConfig
import com.thesis.iotconnbench.core.domain.AdapterCapabilities
import com.thesis.iotconnbench.core.domain.BleAdapterConfig
import com.thesis.iotconnbench.core.domain.BleReadMode
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.domain.MatterAdapterConfig
import com.thesis.iotconnbench.core.domain.MatterValueType
import com.thesis.iotconnbench.core.domain.MatterWriteMode
import com.thesis.iotconnbench.core.domain.MqttAdapterConfig
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.export.Exporter
import com.thesis.iotconnbench.core.metrics.ProtocolSummary
import com.thesis.iotconnbench.core.select.SelectionMode

/**
 * Typed <-> map conversion for the Flutter platform channel (StandardMessageCodec) and for the
 * JSON blobs stored in SQLite. Keys are the contract shared with lib/api/models.dart.
 */
object Mappers {
    private fun Map<String, Any?>.str(k: String, d: String = "") = (this[k] as? String) ?: d
    private fun Map<String, Any?>.bool(k: String, d: Boolean) = (this[k] as? Boolean) ?: d
    private fun Map<String, Any?>.long(k: String, d: Long) = (this[k] as? Number)?.toLong() ?: d
    private fun Map<String, Any?>.int(k: String, d: Int) = (this[k] as? Number)?.toInt() ?: d
    private fun Map<String, Any?>.intOrNull(k: String) = (this[k] as? Number)?.toInt()
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(k: String) = (this[k] as? Map<String, Any?>) ?: emptyMap()
    private fun Map<String, Any?>.strList(k: String) = (this[k] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    fun codecFromMap(m: Map<String, Any?>) = CodecConfig(
        writeTemplate = m.str("writeTemplate"),
        readTemplate = m.str("readTemplate"),
        ackOpIdField = m.str("ackOpIdField"),
        ackStatusField = m.str("ackStatusField"),
        ackOkValue = m.str("ackOkValue"),
        ackValueField = m.str("ackValueField"),
    )

    fun codecToMap(c: CodecConfig) = mapOf(
        "writeTemplate" to c.writeTemplate, "readTemplate" to c.readTemplate, "ackOpIdField" to c.ackOpIdField,
        "ackStatusField" to c.ackStatusField, "ackOkValue" to c.ackOkValue, "ackValueField" to c.ackValueField,
    )

    fun bleFromMap(m: Map<String, Any?>) = BleAdapterConfig(
        serviceUuid = m.str("serviceUuid").trim(),
        commandCharUuid = m.str("commandCharUuid").trim(),
        responseCharUuid = m.str("responseCharUuid").trim(),
        stateCharUuid = m.str("stateCharUuid").trim(),
        writeWithResponse = m.bool("writeWithResponse", true),
        requestedMtu = m.int("requestedMtu", 247),
        scanTimeoutMs = m.long("scanTimeoutMs", 8_000),
        connectTimeoutMs = m.long("connectTimeoutMs", 15_000),
        readMode = runCatching { BleReadMode.valueOf(m.str("readMode", "COMMAND")) }.getOrDefault(BleReadMode.COMMAND),
        deviceNameFilter = m.str("deviceNameFilter"),
        codec = codecFromMap(m.map("codec")),
    )

    fun bleToMap(c: BleAdapterConfig) = mapOf(
        "serviceUuid" to c.serviceUuid, "commandCharUuid" to c.commandCharUuid, "responseCharUuid" to c.responseCharUuid,
        "stateCharUuid" to c.stateCharUuid, "writeWithResponse" to c.writeWithResponse, "requestedMtu" to c.requestedMtu,
        "scanTimeoutMs" to c.scanTimeoutMs, "connectTimeoutMs" to c.connectTimeoutMs, "readMode" to c.readMode.name,
        "deviceNameFilter" to c.deviceNameFilter, "codec" to codecToMap(c.codec),
    )

    fun mqttFromMap(m: Map<String, Any?>, hasStoredPassword: Boolean) = MqttAdapterConfig(
        host = m.str("host").trim(),
        port = m.int("port", 1883),
        useTls = m.bool("useTls", false),
        clientId = m.str("clientId").trim(),
        keepAliveSeconds = m.int("keepAliveSeconds", 60),
        cleanStart = m.bool("cleanStart", true),
        sessionExpirySeconds = m.long("sessionExpirySeconds", 0),
        commandTopic = m.str("commandTopic").trim(),
        ackTopic = m.str("ackTopic").trim(),
        stateTopic = m.str("stateTopic").trim(),
        qos = m.int("qos", 1),
        username = m.str("username"),
        hasStoredPassword = hasStoredPassword,
        allowInsecureLab = m.bool("allowInsecureLab", false),
        useMqtt5RequestResponse = m.bool("useMqtt5RequestResponse", true),
        codec = codecFromMap(m.map("codec")),
    )

    fun mqttToMap(c: MqttAdapterConfig) = mapOf(
        "host" to c.host, "port" to c.port, "useTls" to c.useTls, "clientId" to c.clientId,
        "keepAliveSeconds" to c.keepAliveSeconds, "cleanStart" to c.cleanStart, "sessionExpirySeconds" to c.sessionExpirySeconds,
        "commandTopic" to c.commandTopic, "ackTopic" to c.ackTopic, "stateTopic" to c.stateTopic, "qos" to c.qos,
        "username" to c.username, "hasStoredPassword" to c.hasStoredPassword, "allowInsecureLab" to c.allowInsecureLab,
        "useMqtt5RequestResponse" to c.useMqtt5RequestResponse, "codec" to codecToMap(c.codec),
    )

    fun matterFromMap(m: Map<String, Any?>) = MatterAdapterConfig(
        nodeId = m.long("nodeId", 0),
        endpointId = m.int("endpointId", -1),
        clusterId = m.long("clusterId", -1),
        attributeId = m.long("attributeId", -1),
        valueType = runCatching { MatterValueType.valueOf(m.str("valueType", "BOOLEAN")) }.getOrDefault(MatterValueType.BOOLEAN),
        writeMode = runCatching { MatterWriteMode.valueOf(m.str("writeMode", "WRITE_ATTRIBUTE")) }.getOrDefault(MatterWriteMode.WRITE_ATTRIBUTE),
        interactionTimeoutMs = m.long("interactionTimeoutMs", 10_000),
    )

    fun matterToMap(c: MatterAdapterConfig) = mapOf(
        "nodeId" to c.nodeId, "endpointId" to c.endpointId, "clusterId" to c.clusterId, "attributeId" to c.attributeId,
        "valueType" to c.valueType.name, "writeMode" to c.writeMode.name, "interactionTimeoutMs" to c.interactionTimeoutMs,
    )

    fun configFromMap(m: Map<String, Any?>) = ExperimentConfig(
        name = m.str("name"),
        protocols = m.strList("protocols").map { ProtocolType.valueOf(it) },
        operation = OperationType.valueOf(m.str("operation", "WRITE_STATE")),
        writeValues = (m["writeValues"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: listOf(1, 0),
        payloadSizeBytes = m.intOrNull("payloadSizeBytes"),
        warmupCount = m.int("warmupCount", 10),
        measuredCount = m.int("measuredCount", 100),
        timeoutMs = m.long("timeoutMs", 5_000),
        interOperationDelayMs = m.long("interOperationDelayMs", 200),
        order = OrderStrategy.valueOf(m.str("order", "FIXED")),
        randomSeed = m.long("randomSeed", 42),
        conditionLabels = m.strList("conditionLabels"),
        operatorNotes = m.str("operatorNotes"),
        selectionMode = SelectionMode.valueOf(m.str("selectionMode", "FIXED")),
        multipathDeclared = m.bool("multipathDeclared", false),
        reconnectBetweenOps = m.bool("reconnectBetweenOps", false),
    )

    fun configToMap(c: ExperimentConfig): Map<String, Any?> = mapOf(
        "name" to c.name, "protocols" to c.protocols.map { it.name }, "operation" to c.operation.name,
        "writeValues" to c.writeValues, "payloadSizeBytes" to c.payloadSizeBytes, "warmupCount" to c.warmupCount,
        "measuredCount" to c.measuredCount, "timeoutMs" to c.timeoutMs, "interOperationDelayMs" to c.interOperationDelayMs,
        "order" to c.order.name, "randomSeed" to c.randomSeed, "conditionLabels" to c.conditionLabels,
        "operatorNotes" to c.operatorNotes, "selectionMode" to c.selectionMode.name,
        "multipathDeclared" to c.multipathDeclared, "reconnectBetweenOps" to c.reconnectBetweenOps,
    )

    fun deviceToMap(d: DeviceDescriptor) = mapOf(
        "logicalId" to d.logicalId, "displayName" to d.displayName, "address" to d.address,
        "protocols" to d.supportedProtocols.map { it.name }, "rssiDbm" to d.rssiDbm,
        "hardwareIdentity" to d.hardwareIdentity, "firmwareVersion" to d.firmwareVersion,
    )

    fun deviceFromMap(m: Map<String, Any?>) = DeviceDescriptor(
        logicalId = m.str("logicalId"),
        displayName = m.str("displayName"),
        supportedProtocols = m.strList("protocols").map { ProtocolType.valueOf(it) }.toSet(),
        address = m["address"] as? String,
        hardwareIdentity = m["hardwareIdentity"] as? String,
        firmwareVersion = m["firmwareVersion"] as? String,
        rssiDbm = m.intOrNull("rssiDbm"),
    )

    fun capabilitiesToMap(c: AdapterCapabilities) = mapOf(
        "protocol" to c.protocol.name, "executionMode" to c.executionMode.name,
        "supportedOperations" to c.supportedOperations.map { it.name }, "supportsSubscriptions" to c.supportsSubscriptions,
        "supportsPayloadPadding" to c.supportsPayloadPadding, "requiresCommissioning" to c.requiresCommissioning,
        "knownLimitations" to c.knownLimitations, "adapterVersion" to c.adapterVersion,
    )

    fun recordToMap(r: OperationRecord) = mapOf(
        "operationId" to r.operationId, "runId" to r.runId, "sequence" to r.sequence, "protocol" to r.protocol.name,
        "operationType" to r.operationType.name, "warmup" to r.warmup, "success" to r.success,
        "errorCategory" to r.errorCategory.name, "errorDetail" to r.errorDetail, "tStartNs" to r.tStartNs,
        "tEndNs" to r.tEndNs, "rttNs" to r.rttNs, "requestedValue" to r.requestedValue, "responseValue" to r.responseValue,
        "executionMode" to r.executionMode.name, "selectionReason" to r.selectionReason,
    )

    fun runToMap(r: RunInfo) = mapOf(
        "runId" to r.runId, "sessionId" to r.sessionId, "config" to configToMap(r.config), "status" to r.status.name,
        "statusDetail" to r.statusDetail, "startedWallMs" to r.startedWallMs, "endedWallMs" to r.endedWallMs,
        "adapterMetadata" to Exporter.redact(r.adapterMetadata),
    )

    fun summaryToMap(s: ProtocolSummary) = Exporter.summaryMap(s)

    fun eventToMap(e: LifecycleEvent) = mapOf(
        "protocol" to e.protocol?.name, "kind" to e.kind.name, "tNs" to e.tNs, "wallMs" to e.wallTimeMs,
        "durationNs" to e.durationNs, "detail" to e.detail, "attributes" to Exporter.redact(e.attributes), "runId" to e.runId,
    )
}

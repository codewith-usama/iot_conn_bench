package com.thesis.iotconnbench.platform.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling
import com.thesis.iotconnbench.core.contract.AckCorrelator
import com.thesis.iotconnbench.core.contract.CodecException
import com.thesis.iotconnbench.core.contract.TemplatePayloadCodec
import com.thesis.iotconnbench.core.domain.AdapterCapabilities
import com.thesis.iotconnbench.core.domain.AdapterConfig
import com.thesis.iotconnbench.core.domain.AdapterEvents
import com.thesis.iotconnbench.core.domain.AdapterException
import com.thesis.iotconnbench.core.domain.ConnectReport
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.ConnectivityAdapter
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.MqttAdapterConfig
import com.thesis.iotconnbench.core.domain.OperationRequest
import com.thesis.iotconnbench.core.domain.OperationResult
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MQTT 5.0 adapter on the HiveMQ MQTT Client (com.hivemq:hivemq-mqtt-client), which implements
 * MQTT 5.0 natively (`useMqttVersion5()`); a broker that only speaks 3.1.1 answers with
 * reason code UNSUPPORTED_PROTOCOL_VERSION and the connect fails, so a successful connect is
 * evidence that MQTT 5.0 was negotiated. The CONNACK reason code is recorded.
 *
 * Completion boundary: the device's application-level acknowledgement published on ackTopic and
 * correlated by op id (or MQTT 5 Correlation Data). The broker PUBACK/PUBREC only proves the
 * broker accepted the command; it is recorded as diagnostic `mqtt.broker_ack_ns` and never ends
 * an operation.
 *
 * Automatic reconnect is deliberately NOT enabled: reconnection happens only through an explicit
 * connect() call (operator action or runner `reconnectBetweenOps`), each recorded as an event.
 */
class MqttV5Adapter(
    private val clock: MonotonicClock,
    private val events: AdapterEvents,
    private val passwordProvider: () -> String?,
) : ConnectivityAdapter {

    override val protocol = ProtocolType.MQTT_V5
    override val capabilities = AdapterCapabilities(
        protocol = ProtocolType.MQTT_V5,
        executionMode = ExecutionMode.HARDWARE,
        supportedOperations = setOf(OperationType.READ_STATE, OperationType.WRITE_STATE),
        supportsSubscriptions = true,
        supportsPayloadPadding = true,
        requiresCommissioning = false,
        knownLimitations = listOf(
            "RTT includes phone->broker->ESP32->broker->phone; broker host/location is part of the path",
            "Retained messages on the ack topic are ignored (and recorded) to avoid stale acks",
        ),
        adapterVersion = "mqtt5-hivemq-1.0.0",
    )

    private val state = MutableStateFlow(ConnectionState.IDLE)
    override fun observeState(): StateFlow<ConnectionState> = state.asStateFlow()

    private var cfg: MqttAdapterConfig? = null
    private var codec: TemplatePayloadCodec? = null
    private var correlator: AckCorrelator? = null
    private var client: Mqtt5AsyncClient? = null
    private val sessionDiag = linkedMapOf<String, String>()
    @Volatile private var everConnected = false

    override fun configMetadata(): Map<String, String> = (cfg?.metadata() ?: emptyMap()) + sessionDiag

    override suspend fun initialize(config: AdapterConfig) {
        val c = config as? MqttAdapterConfig ?: throw AdapterException(ErrorCategory.INTERNAL, "Not an MQTT config")
        val missing = c.missingFields()
        if (missing.isNotEmpty()) throw AdapterException(ErrorCategory.NOT_CONFIGURED, "MQTT config incomplete: $missing")
        runCatching { client?.disconnect() }
        cfg = c
        codec = TemplatePayloadCodec(c.codec).also { correlator = AckCorrelator(it) }
        var b = MqttClient.builder()
            .useMqttVersion5()
            .identifier(c.clientId)
            .serverHost(c.host)
            .serverPort(c.port)
        if (c.useTls) b = b.sslWithDefaultConfig()
        b = b.addDisconnectedListener { ctx ->
            val t = clock.nowNs()
            val wasConnected = state.value == ConnectionState.CONNECTED
            state.value = ConnectionState.DISCONNECTED
            correlator?.failAll(ErrorCategory.DISCONNECTED_IN_FLIGHT, "MQTT disconnected: ${ctx.cause.message}", t)
            if (wasConnected) {
                events.emit(LifecycleKind.DISCONNECTED, "source=${ctx.source} cause=${ctx.cause.javaClass.simpleName}: ${ctx.cause.message}")
            }
        }
        client = b.buildAsync()
        state.value = ConnectionState.IDLE
        events.emit(LifecycleKind.INITIALIZED, "MQTT 5 client for ${c.host}:${c.port} tls=${c.useTls}")
    }

    /** MQTT has no device discovery; the logical device is defined by the configured topics. */
    override suspend fun discover(timeoutMs: Long): List<DeviceDescriptor> {
        val c = cfg ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "MQTT not configured")
        return listOf(DeviceDescriptor(c.commandTopic, "MQTT ${c.commandTopic}", setOf(ProtocolType.MQTT_V5), address = "${c.host}:${c.port}"))
    }

    override suspend fun connect(device: DeviceDescriptor): ConnectReport {
        val c = cfg ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "MQTT not configured")
        val cl = client ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "MQTT client not built")
        val phases = linkedMapOf<String, Long>()
        state.value = if (everConnected) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        events.emit(LifecycleKind.CONNECT_STARTED, "${c.host}:${c.port}")
        val t0 = clock.nowNs()
        val connAck: Mqtt5ConnAck = try {
            var cb = cl.connectWith()
                .cleanStart(c.cleanStart)
                .keepAlive(c.keepAliveSeconds)
                .sessionExpiryInterval(c.sessionExpirySeconds)
            if (c.username.isNotBlank()) {
                val pw = passwordProvider()
                cb = if (pw != null) {
                    cb.simpleAuth().username(c.username).password(pw.toByteArray(Charsets.UTF_8)).applySimpleAuth()
                } else {
                    cb.simpleAuth().username(c.username).applySimpleAuth()
                }
            }
            withTimeout(15_000) { cb.send().await() }
        } catch (e: Exception) {
            state.value = ConnectionState.ERROR
            val reason = (e.cause ?: e).toString()
            events.emit(LifecycleKind.ERROR, "connect failed: $reason")
            throw AdapterException(ErrorCategory.PROTOCOL_ERROR, "MQTT connect failed: $reason", e)
        }
        val tConn = clock.nowNs()
        phases["mqtt.connect_ns"] = tConn - t0
        sessionDiag["mqtt.negotiatedVersion"] = cl.config.mqttVersion.name
        sessionDiag["mqtt.connackReason"] = connAck.reasonCode.name
        sessionDiag["mqtt.sessionPresent"] = connAck.isSessionPresent.toString()
        connAck.serverKeepAlive.ifPresent { sessionDiag["mqtt.serverKeepAlive"] = it.toString() }
        connAck.assignedClientIdentifier.ifPresent { sessionDiag["mqtt.assignedClientId"] = it.toString() }
        sessionDiag["mqtt.serverMaximumQos"] = connAck.restrictions.maximumQos.name
        if (connAck.reasonCode != Mqtt5ConnAckReasonCode.SUCCESS) {
            state.value = ConnectionState.ERROR
            throw AdapterException(ErrorCategory.PROTOCOL_ERROR, "CONNACK ${connAck.reasonCode}")
        }
        if (c.qos > connAck.restrictions.maximumQos.code) {
            events.emit(LifecycleKind.ERROR, "broker maximum QoS ${connAck.restrictions.maximumQos} < configured ${c.qos}")
        }

        val tS = clock.nowNs()
        try {
            val subAck = withTimeout(10_000) {
                cl.subscribeWith()
                    .topicFilter(c.ackTopic)
                    .qos(qosOf(c.qos))
                    .retainHandling(Mqtt5RetainHandling.DO_NOT_SEND)
                    .callback { p -> onAck(p) }
                    .send()
                    .await()
            }
            sessionDiag["mqtt.ackSubscribeReason"] = subAck.reasonCodes.joinToString { it.name }
            if (subAck.reasonCodes.any { it.isError }) throw IllegalStateException("SUBACK ${subAck.reasonCodes}")
            if (c.stateTopic.isNotBlank()) {
                withTimeout(10_000) {
                    cl.subscribeWith().topicFilter(c.stateTopic).qos(qosOf(c.qos))
                        .callback { p -> events.emit(LifecycleKind.STATE_REPORT, p.payloadAsBytes.toString(Charsets.UTF_8).take(200), attrs = mapOf("mqtt.topic" to p.topic.toString(), "mqtt.retain" to p.isRetain.toString())) }
                        .send().await()
                }
            }
        } catch (e: Exception) {
            state.value = ConnectionState.ERROR
            events.emit(LifecycleKind.ERROR, "subscribe failed: ${e.message}")
            runCatching { cl.disconnect() }
            throw AdapterException(ErrorCategory.PROTOCOL_ERROR, "MQTT subscribe failed: ${e.message}", e)
        }
        phases["mqtt.subscribe_ns"] = clock.nowNs() - tS
        phases["mqtt.total_setup_ns"] = clock.nowNs() - t0
        val reconnect = everConnected
        everConnected = true
        state.value = ConnectionState.CONNECTED
        events.emit(
            if (reconnect) LifecycleKind.RECONNECTED else LifecycleKind.CONNECTED,
            "MQTT ${sessionDiag["mqtt.negotiatedVersion"]} ${connAck.reasonCode}",
            phases["mqtt.total_setup_ns"],
            phases.mapValues { it.value.toString() } + sessionDiag,
        )
        return ConnectReport(phases, sessionDiag.toMap())
    }

    private fun onAck(p: Mqtt5Publish) {
        val t = clock.nowNs() // stamped first: t_end candidate
        if (p.isRetain) {
            events.emit(LifecycleKind.RETAINED_IGNORED, "retained message on ${p.topic}")
            return
        }
        val hint = p.correlationData.orElse(null)?.let { bb ->
            val dup = bb.duplicate(); ByteArray(dup.remaining()).also { dup.get(it) }.toString(Charsets.UTF_8)
        }
        val bytes = p.payloadAsBytes
        val (disp, _) = correlator?.onIncoming(bytes, t, hint) ?: return
        when (disp) {
            AckCorrelator.Disposition.DUPLICATE -> events.emit(LifecycleKind.DUPLICATE_RESPONSE, bytes.toString(Charsets.UTF_8).take(200))
            AckCorrelator.Disposition.UNMATCHED, AckCorrelator.Disposition.UNPARSEABLE ->
                events.emit(LifecycleKind.UNMATCHED_RESPONSE, bytes.toString(Charsets.UTF_8).take(200))
            AckCorrelator.Disposition.MATCHED -> Unit
        }
    }

    override suspend fun disconnect() {
        val cl = client ?: return
        state.value = ConnectionState.DISCONNECTING
        runCatching { withTimeoutOrNull(5_000) { cl.disconnect().await() } }
        state.value = ConnectionState.DISCONNECTED
        events.emit(LifecycleKind.DISCONNECTED, "operator disconnect")
    }

    override suspend fun execute(request: OperationRequest): OperationResult {
        val c = cfg ?: return OperationResult.failure(request, ErrorCategory.NOT_CONFIGURED, clock.nowNs(), clock.nowNs(), "MQTT not configured")
        val cl = client
        if (cl == null || state.value != ConnectionState.CONNECTED) {
            val t = clock.nowNs()
            return OperationResult.failure(request, ErrorCategory.NOT_CONNECTED, t, t, "state=${state.value}")
        }
        val bytes = try {
            codec!!.encode(request.operationId, request.type, request.payload)
        } catch (e: CodecException) {
            val t = clock.nowNs()
            return OperationResult.failure(request, ErrorCategory.VALIDATION_FAILED, t, t, e.message)
        }
        val diag = java.util.concurrent.ConcurrentHashMap(mapOf("mqtt.qos" to c.qos.toString(), "mqtt.commandTopic" to c.commandTopic))
        val corr = correlator!!
        val ackDeferred = corr.register(request.operationId)

        var pb = cl.publishWith().topic(c.commandTopic).qos(qosOf(c.qos)).payload(bytes)
        if (c.useMqtt5RequestResponse) {
            pb = pb.responseTopic(c.ackTopic).correlationData(request.operationId.toByteArray(Charsets.UTF_8))
        }
        val t0 = clock.nowNs()
        val future = pb.send()
        future.whenComplete { r: Mqtt5PublishResult?, err: Throwable? ->
            val tb = clock.nowNs()
            diag["mqtt.broker_ack_ns"] = (tb - t0).toString()
            if (err != null) diag["mqtt.publish_error"] = err.toString()
            r?.error?.ifPresent { diag["mqtt.publish_error"] = it.toString() }
            (r as? Mqtt5PublishResult.Mqtt5Qos1Result)?.let { diag["mqtt.puback_reason"] = it.pubAck.reasonCode.name }
            (r as? Mqtt5PublishResult.Mqtt5Qos2Result)?.let { diag["mqtt.pubrec_reason"] = it.pubRec.reasonCode.name }
        }

        val outcome = corr.await(request.operationId, ackDeferred, request.timeoutMs)
        // A publish that the broker rejected can never be acknowledged by the device.
        val pubErr = diag["mqtt.publish_error"]
        return when (outcome) {
            null -> OperationResult.failure(
                request, if (pubErr != null) ErrorCategory.PROTOCOL_ERROR else ErrorCategory.TIMEOUT,
                t0, clock.nowNs(), pubErr ?: "no device acknowledgement on ${c.ackTopic}", diag.toMap(),
            )
            is AckCorrelator.Outcome.Failed -> OperationResult.failure(request, outcome.category, t0, outcome.tDetectedNs, outcome.detail, diag.toMap())
            is AckCorrelator.Outcome.Acked -> if (outcome.ack.ok) {
                OperationResult(request.operationId, protocol, true, ErrorCategory.NONE, t0, outcome.tReceivedNs,
                    responseValue = outcome.ack.value, requestBytes = bytes.size, responseBytes = outcome.responseBytes,
                    diagnostics = diag.toMap())
            } else {
                OperationResult.failure(request, ErrorCategory.DEVICE_REJECTED, t0, outcome.tReceivedNs,
                    "device status=${outcome.ack.statusText}", diag.toMap())
            }
        }
    }

    override suspend fun dispose() {
        runCatching { client?.disconnect() }
        client = null
        state.value = ConnectionState.IDLE
    }

    private fun qosOf(q: Int): MqttQos = MqttQos.fromCode(q) ?: MqttQos.AT_LEAST_ONCE
}

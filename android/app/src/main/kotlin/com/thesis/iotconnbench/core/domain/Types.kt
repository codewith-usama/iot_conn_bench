package com.thesis.iotconnbench.core.domain

/*
 * Protocol-independent domain model (spec §4.1).
 *
 * Everything in the `core` package is plain Kotlin/JVM with no Android imports so it can be
 * unit-tested on the JVM and reasoned about independently of the radio stacks.
 */

enum class ProtocolType { BLE, MQTT_V5, MATTER }

enum class ConnectionState {
    IDLE, DISCOVERING, CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED, RECONNECTING, ERROR,
}

enum class OperationType { READ_STATE, WRITE_STATE, SUBSCRIBE_STATE }

/** Failure taxonomy recorded with every unsuccessful operation. NONE only for successes. */
enum class ErrorCategory {
    NONE,
    TIMEOUT,
    NOT_CONNECTED,
    DISCONNECTED_IN_FLIGHT,
    PROTOCOL_ERROR,
    DEVICE_REJECTED,
    VALIDATION_FAILED,
    UNSUPPORTED,
    NOT_CONFIGURED,
    PERMISSION_DENIED,
    UNAVAILABLE,
    CANCELLED,
    INTERNAL,
}

/**
 * HARDWARE: the adapter talks to a real radio/network stack.
 * SIMULATED: a test double. Simulated adapters exist only in the unit-test source set; the app
 * ships none. The field is still exported so any record produced by a double is unmistakable.
 */
enum class ExecutionMode { HARDWARE, SIMULATED }

data class DeviceDescriptor(
    /** Logical device identity shared across protocol paths (operator-assigned). */
    val logicalId: String,
    val displayName: String,
    val supportedProtocols: Set<ProtocolType>,
    /** Protocol-specific locator: BLE MAC address, MQTT client/topic root, Matter node id. */
    val address: String? = null,
    val hardwareIdentity: String? = null,
    val firmwareVersion: String? = null,
    val rssiDbm: Int? = null,
)

/** The logical payload of the common workload (spec §4.3). */
data class LogicalPayload(
    /** State value to write; null for reads. */
    val value: Long? = null,
    /**
     * Target encoded size in bytes for padded text protocols (BLE/MQTT). Null = no padding.
     * Matter attributes have fixed data-model types, so this is ignored there (documented).
     */
    val targetSizeBytes: Int? = null,
)

data class OperationRequest(
    val operationId: String,
    val logicalDeviceId: String,
    val type: OperationType,
    val payload: LogicalPayload,
    val protocol: ProtocolType,
    val timeoutMs: Long,
    val sessionId: String,
    val runId: String,
    val sequence: Int,
    val warmup: Boolean,
)

data class OperationResult(
    val operationId: String,
    val protocol: ProtocolType,
    val success: Boolean,
    val errorCategory: ErrorCategory,
    /** Monotonic ns, taken immediately before dispatch. */
    val tStartNs: Long,
    /** Monotonic ns, taken when the matching device-level response arrived (or failure detected). */
    val tEndNs: Long,
    val responseValue: Long? = null,
    val errorDetail: String? = null,
    val requestBytes: Int? = null,
    val responseBytes: Int? = null,
    /** Protocol-specific diagnostics (GATT status, MQTT reason codes, Matter status, ...). */
    val diagnostics: Map<String, String> = emptyMap(),
) {
    /** Android-observed application-level RTT. Null for failures (never a zero-latency sample). */
    val rttNs: Long? get() = if (success) tEndNs - tStartNs else null

    companion object {
        fun failure(
            req: OperationRequest,
            category: ErrorCategory,
            tStart: Long,
            tEnd: Long,
            detail: String?,
            diagnostics: Map<String, String> = emptyMap(),
        ) = OperationResult(
            operationId = req.operationId, protocol = req.protocol, success = false,
            errorCategory = category, tStartNs = tStart, tEndNs = tEnd,
            errorDetail = detail, diagnostics = diagnostics,
        )
    }
}

data class AdapterCapabilities(
    val protocol: ProtocolType,
    val executionMode: ExecutionMode,
    val supportedOperations: Set<OperationType>,
    val supportsSubscriptions: Boolean,
    val supportsPayloadPadding: Boolean,
    val requiresCommissioning: Boolean,
    /** Human-readable, exported with the session. */
    val knownLimitations: List<String>,
    val adapterVersion: String,
)

/** Durations of connection-setup phases; kept separate from steady-state RTT (spec §2.7). */
data class ConnectReport(
    val phasesNs: Map<String, Long>,
    val diagnostics: Map<String, String> = emptyMap(),
)

class AdapterException(val category: ErrorCategory, message: String, cause: Throwable? = null) :
    Exception(message, cause)

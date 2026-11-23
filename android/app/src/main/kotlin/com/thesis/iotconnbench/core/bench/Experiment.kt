package com.thesis.iotconnbench.core.bench

import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.select.SelectionMode

enum class OrderStrategy {
    /** All ops of protocol A, then all of B, ... (blocked design). */
    FIXED,
    /** A, B, C, A, B, C, ... */
    ROUND_ROBIN,
    /** Each round is a seeded random permutation of the protocols. */
    SEEDED_RANDOM,
}

/** Spec §4.1 ExperimentConfig. Pure value object; snapshotted into every run. */
data class ExperimentConfig(
    val name: String = "",
    val protocols: List<ProtocolType>,
    val operation: OperationType = OperationType.WRITE_STATE,
    /** Values written cyclically (e.g. [1, 0] toggles a LED) so each write changes state. */
    val writeValues: List<Long> = listOf(1, 0),
    /** Target encoded payload size for BLE/MQTT text payloads; null = natural size. */
    val payloadSizeBytes: Int? = null,
    val warmupCount: Int = 10,
    val measuredCount: Int = 100,
    val timeoutMs: Long = 5_000,
    val interOperationDelayMs: Long = 200,
    val order: OrderStrategy = OrderStrategy.FIXED,
    val randomSeed: Long = 42,
    val conditionLabels: List<String> = listOf("baseline"),
    val operatorNotes: String = "",
    val selectionMode: SelectionMode = SelectionMode.FIXED,
    /** Required for any multi-path policy: operator asserts all paths reach the same logical device. */
    val multipathDeclared: Boolean = false,
    /** If true, the runner may explicitly reconnect a DISCONNECTED path between ops; each attempt is recorded. */
    val reconnectBetweenOps: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (protocols.isEmpty()) add("Select at least one protocol")
        if (protocols.toSet().size != protocols.size) add("Duplicate protocol in selection")
        if (measuredCount <= 0) add("measuredCount must be > 0")
        if (warmupCount < 0) add("warmupCount must be >= 0")
        if (timeoutMs <= 0) add("timeoutMs must be > 0")
        if (interOperationDelayMs < 0) add("interOperationDelayMs must be >= 0")
        if (operation == OperationType.WRITE_STATE && writeValues.isEmpty()) add("writeValues required for WRITE_STATE")
        if (operation == OperationType.SUBSCRIBE_STATE) add("SUBSCRIBE_STATE is not a request/response workload; use READ/WRITE (subscriptions are recorded as events)")
        if (payloadSizeBytes != null && payloadSizeBytes <= 0) add("payloadSizeBytes must be > 0")
        if (selectionMode.isMultiPath && !multipathDeclared) {
            add("Multi-path selection requires multipathDeclared=true (same logical device on all paths)")
        }
        if (selectionMode.isMultiPath && protocols.size < 2) add("Multi-path selection requires >= 2 protocols")
        if (selectionMode == SelectionMode.MODEL_BASED_SELECTOR) add("MODEL_BASED_SELECTOR is disabled: no trained policy has been supplied")
        if (selectionMode == SelectionMode.ORACLE) add("ORACLE is an offline analysis reference and cannot run online")
    }
}

/** Spec §4.1/§6.4 session metadata. A session may contain several independent runs. */
data class ExperimentSession(
    val sessionId: String,
    val startedWallMs: Long,
    val startedMonoNs: Long,
    val metadata: Map<String, String>,
    var endedWallMs: Long? = null,
)

data class RunInfo(
    val runId: String,
    val sessionId: String,
    val config: ExperimentConfig,
    val startedWallMs: Long,
    val startedMonoNs: Long,
    /** Non-secret adapter + capability metadata captured at run start. */
    val adapterMetadata: Map<String, String>,
    var endedWallMs: Long? = null,
    var endedMonoNs: Long? = null,
    var status: RunStatus = RunStatus.RUNNING,
    var statusDetail: String = "",
)

enum class RunStatus { RUNNING, COMPLETED, CANCELLED, FAILED }

/** One raw per-operation observation (one CSV row). */
data class OperationRecord(
    val sessionId: String,
    val runId: String,
    val operationId: String,
    val sequence: Int,
    val protocol: ProtocolType,
    val operationType: OperationType,
    val warmup: Boolean,
    val success: Boolean,
    val errorCategory: ErrorCategory,
    val errorDetail: String?,
    val tStartNs: Long,
    val tEndNs: Long,
    val rttNs: Long?,
    val requestedValue: Long?,
    val responseValue: Long?,
    val requestBytes: Int?,
    val responseBytes: Int?,
    val payloadSizeTarget: Int?,
    val executionMode: ExecutionMode,
    val conditionLabels: List<String>,
    val selectionMode: SelectionMode,
    val selectionReason: String?,
    val diagnostics: Map<String, String>,
)

/** Persistence abstraction implemented by SQLite on Android and in-memory in tests. */
interface RecordSink {
    fun saveSession(session: ExperimentSession)
    fun saveRun(run: RunInfo)
    fun record(op: OperationRecord)
    fun event(e: LifecycleEvent)
    fun updateRun(run: RunInfo)
}

class InMemoryRecordSink : RecordSink {
    val sessions = mutableListOf<ExperimentSession>()
    val runs = mutableListOf<RunInfo>()
    val records = mutableListOf<OperationRecord>()
    val events = mutableListOf<LifecycleEvent>()
    @Synchronized override fun saveSession(session: ExperimentSession) { sessions += session }
    @Synchronized override fun saveRun(run: RunInfo) { runs += run }
    @Synchronized override fun record(op: OperationRecord) { records += op }
    @Synchronized override fun event(e: LifecycleEvent) { events += e }
    @Synchronized override fun updateRun(run: RunInfo) {
        val i = runs.indexOfFirst { it.runId == run.runId }
        if (i >= 0) runs[i] = run else runs += run
    }
}

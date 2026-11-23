package com.thesis.iotconnbench.core.bench

import com.thesis.iotconnbench.core.domain.AdapterRegistry
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.EventSink
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.LogicalPayload
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.OperationRequest
import com.thesis.iotconnbench.core.domain.OperationResult
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.domain.WallClock
import com.thesis.iotconnbench.core.select.PathState
import com.thesis.iotconnbench.core.select.PolicyFactory
import com.thesis.iotconnbench.core.select.SelectionContext
import com.thesis.iotconnbench.core.select.SelectionMode
import com.thesis.iotconnbench.core.select.SelectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Routes lifecycle events to persistence + live listeners, tagging the active session/run. */
class EventRouter(private val sink: RecordSink) : EventSink {
    @Volatile var sessionId: String? = null
    @Volatile var runId: String? = null
    private val listeners = CopyOnWriteArrayList<(LifecycleEvent) -> Unit>()

    fun addListener(l: (LifecycleEvent) -> Unit) { listeners += l }
    fun removeListener(l: (LifecycleEvent) -> Unit) { listeners -= l }

    override fun emit(event: LifecycleEvent) {
        val tagged = event.copy(sessionId = event.sessionId ?: sessionId, runId = event.runId ?: runId)
        if (tagged.sessionId != null) sink.event(tagged)
        listeners.forEach { runCatching { it(tagged) } }
    }
}

data class ProtocolCounts(var attempts: Int = 0, var success: Int = 0, var failed: Int = 0, var timeouts: Int = 0)

data class RunProgress(
    val runId: String,
    val completed: Int,
    val total: Int,
    val currentProtocol: ProtocolType?,
    val currentWarmup: Boolean,
    val last: OperationRecord?,
    val counts: Map<ProtocolType, ProtocolCounts>,
)

/**
 * Native benchmark engine (spec §6). Sequential execution only. All timestamps come from the
 * injected monotonic clock; the adapter takes t_start/t_end, the runner adds a guard timeout.
 */
class BenchmarkRunner(
    private val registry: AdapterRegistry,
    private val clock: MonotonicClock,
    private val wall: WallClock,
    private val sink: RecordSink,
    private val events: EventRouter,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    /** Extra time the runner waits beyond the adapter timeout before declaring a guard timeout. */
    private val guardMarginMs: Long = 1_000,
) {
    suspend fun run(
        session: ExperimentSession,
        config: ExperimentConfig,
        devices: Map<ProtocolType, DeviceDescriptor>,
        policyOverride: SelectionPolicy? = null,
        onProgress: (RunProgress) -> Unit = {},
    ): RunInfo {
        val problems = config.validate().toMutableList()
        if (policyOverride != null && config.selectionMode == SelectionMode.MODEL_BASED_SELECTOR) {
            problems.remove("MODEL_BASED_SELECTOR is disabled: no trained policy has been supplied")
        }
        config.protocols.filter { registry[it] == null }.forEach { problems += "No adapter for $it" }
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val adapterMeta = buildMap {
            for (p in config.protocols) {
                val a = registry.require(p)
                putAll(a.configMetadata())
                put("adapter.$p.version", a.capabilities.adapterVersion)
                put("adapter.$p.executionMode", a.capabilities.executionMode.name)
                put("adapter.$p.limitations", a.capabilities.knownLimitations.joinToString(" | "))
                devices[p]?.let { d ->
                    put("device.$p.logicalId", d.logicalId)
                    put("device.$p.address", d.address ?: "")
                    put("device.$p.firmware", d.firmwareVersion ?: "")
                }
            }
        }
        val run = RunInfo(idGen(), session.sessionId, config, wall.nowMs(), clock.nowNs(), adapterMeta)
        sink.saveRun(run)
        events.sessionId = session.sessionId
        events.runId = run.runId
        emit(LifecycleKind.RUN_STARTED, null, "run ${run.runId}")

        val counts = config.protocols.associateWith { ProtocolCounts() }
        val stats = config.protocols.associateWith { PathStats() }
        val perPath = config.warmupCount + config.measuredCount
        val total = if (config.selectionMode == SelectionMode.FIXED) perPath * config.protocols.size else perPath
        var completed = 0
        var writeIndex = 0

        fun nextValue(): Long? =
            if (config.operation == OperationType.WRITE_STATE) config.writeValues[writeIndex++ % config.writeValues.size] else null

        suspend fun one(protocol: ProtocolType, seq: Int, warmup: Boolean, reason: String?): OperationRecord {
            val rec = executeOne(session, run, config, devices, protocol, seq, warmup, nextValue(), reason)
            val c = counts.getValue(protocol)
            if (!warmup) {
                c.attempts++
                if (rec.success) c.success++ else c.failed++
                if (rec.errorCategory == ErrorCategory.TIMEOUT) c.timeouts++
            }
            stats.getValue(protocol).update(rec)
            completed++
            onProgress(RunProgress(run.runId, completed, total, protocol, warmup, rec, counts))
            return rec
        }

        try {
            if (config.selectionMode == SelectionMode.FIXED) {
                val schedule = ScheduleBuilder.build(config)
                schedule.forEachIndexed { i, op ->
                    one(op.protocol, i, op.warmup, null)
                    if (i < schedule.lastIndex && config.interOperationDelayMs > 0) delay(config.interOperationDelayMs)
                }
            } else {
                val policy = policyOverride ?: PolicyFactory.create(config.selectionMode, config.protocols)
                var previous: ProtocolType? = null
                var lastFailureEndNs: Long? = null
                val n = config.warmupCount + config.measuredCount
                for (i in 0 until n) {
                    val warmup = i < config.warmupCount
                    val ctx = SelectionContext(
                        sequence = i,
                        paths = config.protocols.map { p ->
                            val s = stats.getValue(p)
                            PathState(p, registry.require(p).observeState().value, s.lastSuccess, s.lastRtt, s.consecutiveFailures)
                        },
                        previousSelection = previous,
                        nowNs = clock.nowNs(),
                    )
                    val decision = policy.select(ctx)
                    emit(
                        LifecycleKind.SELECTION_DECISION, decision.selected, decision.reason,
                        attrs = decision.features + mapOf("sequence" to i.toString(), "policy" to policy.mode.name),
                    )
                    val target = decision.selected ?: previous ?: config.protocols.first()
                    if (previous != null && decision.selected != null && decision.selected != previous) {
                        emit(
                            LifecycleKind.PATH_SWITCH, decision.selected, "$previous->${decision.selected}",
                            durationNs = lastFailureEndNs?.let { decision.decisionTimeNs - it },
                        )
                    }
                    val rec = if (decision.selected == null) {
                        val now = clock.nowNs()
                        val r = record(
                            session, run, config, protocol = target, seq = i, warmup = warmup, value = nextValue(),
                            result = OperationResult(
                                idGen(), target, false, ErrorCategory.NOT_CONNECTED, now, now,
                                errorDetail = "no connected path available",
                            ),
                            reason = decision.reason,
                        )
                        completed++
                        onProgress(RunProgress(run.runId, completed, total, target, warmup, r, counts))
                        r
                    } else {
                        one(target, i, warmup, decision.reason)
                    }
                    policy.onOutcome(rec.protocol, rec.success, rec.rttNs)
                    if (!rec.success) lastFailureEndNs = rec.tEndNs
                    if (rec.success) lastFailureEndNs = null
                    previous = decision.selected ?: previous
                    if (i < n - 1 && config.interOperationDelayMs > 0) delay(config.interOperationDelayMs)
                }
            }
            run.status = RunStatus.COMPLETED
        } catch (e: CancellationException) {
            run.status = RunStatus.CANCELLED
            run.statusDetail = "cancelled by operator"
            throw e
        } catch (e: Exception) {
            run.status = RunStatus.FAILED
            run.statusDetail = e.toString()
            throw e
        } finally {
            withContext(NonCancellable) {
                run.endedMonoNs = clock.nowNs()
                run.endedWallMs = wall.nowMs()
                sink.updateRun(run)
                emit(
                    if (run.status == RunStatus.CANCELLED) LifecycleKind.RUN_CANCELLED else LifecycleKind.RUN_FINISHED,
                    null, "${run.status} ${run.statusDetail}".trim(),
                )
                events.runId = null
            }
        }
        return run
    }

    private suspend fun executeOne(
        session: ExperimentSession,
        run: RunInfo,
        config: ExperimentConfig,
        devices: Map<ProtocolType, DeviceDescriptor>,
        protocol: ProtocolType,
        seq: Int,
        warmup: Boolean,
        value: Long?,
        reason: String?,
    ): OperationRecord {
        val adapter = registry.require(protocol)
        val opId = idGen()

        if (adapter.observeState().value != ConnectionState.CONNECTED && config.reconnectBetweenOps) {
            val device = devices[protocol]
            if (device != null) {
                val t0 = clock.nowNs()
                emit(LifecycleKind.RECONNECT_STARTED, protocol, "explicit runner reconnect before seq $seq")
                val ok = try {
                    adapter.connect(device); true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(LifecycleKind.ERROR, protocol, "reconnect failed: ${e.message}"); false
                }
                if (ok) emit(LifecycleKind.RECONNECTED, protocol, "runner reconnect", durationNs = clock.nowNs() - t0)
            }
        }
        if (adapter.observeState().value != ConnectionState.CONNECTED) {
            val now = clock.nowNs()
            return record(
                session, run, config, protocol, seq, warmup, value,
                OperationResult(opId, protocol, false, ErrorCategory.NOT_CONNECTED, now, now,
                    errorDetail = "adapter state ${adapter.observeState().value}"),
                reason,
            )
        }

        val req = OperationRequest(
            operationId = opId,
            logicalDeviceId = devices[protocol]?.logicalId ?: "unknown",
            type = config.operation,
            payload = LogicalPayload(value, config.payloadSizeBytes),
            protocol = protocol,
            timeoutMs = config.timeoutMs,
            sessionId = session.sessionId,
            runId = run.runId,
            sequence = seq,
            warmup = warmup,
        )
        val guardStart = clock.nowNs()
        val result: OperationResult = try {
            withTimeoutOrNull(config.timeoutMs + guardMarginMs) { adapter.execute(req) }
                ?: OperationResult.failure(req, ErrorCategory.TIMEOUT, guardStart, clock.nowNs(), "runner guard timeout (adapter did not return)")
        } catch (e: CancellationException) {
            val r = OperationResult.failure(req, ErrorCategory.CANCELLED, guardStart, clock.nowNs(), "cancelled in flight")
            withContext(NonCancellable) { record(session, run, config, protocol, seq, warmup, value, r, reason) }
            throw e
        } catch (e: Exception) {
            OperationResult.failure(req, ErrorCategory.INTERNAL, guardStart, clock.nowNs(), e.toString())
        }
        return record(session, run, config, protocol, seq, warmup, value, validate(req, result), reason)
    }

    /** Generic semantic validation applied to every adapter's result. */
    private fun validate(req: OperationRequest, r: OperationResult): OperationResult {
        if (r.operationId != req.operationId || r.protocol != req.protocol) {
            return r.copy(success = false, errorCategory = ErrorCategory.INTERNAL,
                errorDetail = "adapter returned mismatched op id/protocol")
        }
        if (r.success && r.tEndNs < r.tStartNs) {
            return r.copy(success = false, errorCategory = ErrorCategory.INTERNAL, errorDetail = "negative RTT")
        }
        if (r.success && req.type == OperationType.WRITE_STATE && r.responseValue != null &&
            req.payload.value != null && r.responseValue != req.payload.value
        ) {
            return r.copy(success = false, errorCategory = ErrorCategory.VALIDATION_FAILED,
                errorDetail = "device reported ${r.responseValue}, expected ${req.payload.value}")
        }
        return r
    }

    private fun record(
        session: ExperimentSession,
        run: RunInfo,
        config: ExperimentConfig,
        protocol: ProtocolType,
        seq: Int,
        warmup: Boolean,
        value: Long?,
        result: OperationResult,
        reason: String?,
    ): OperationRecord {
        val rec = OperationRecord(
            sessionId = session.sessionId,
            runId = run.runId,
            operationId = result.operationId,
            sequence = seq,
            protocol = protocol,
            operationType = config.operation,
            warmup = warmup,
            success = result.success,
            errorCategory = result.errorCategory,
            errorDetail = result.errorDetail,
            tStartNs = result.tStartNs,
            tEndNs = result.tEndNs,
            rttNs = result.rttNs,
            requestedValue = value,
            responseValue = result.responseValue,
            requestBytes = result.requestBytes,
            responseBytes = result.responseBytes,
            payloadSizeTarget = config.payloadSizeBytes,
            executionMode = registry.require(protocol).capabilities.executionMode,
            conditionLabels = config.conditionLabels,
            selectionMode = config.selectionMode,
            selectionReason = reason,
            diagnostics = result.diagnostics,
        )
        sink.record(rec)
        return rec
    }

    private fun emit(
        kind: LifecycleKind,
        protocol: ProtocolType?,
        detail: String,
        durationNs: Long? = null,
        attrs: Map<String, String> = emptyMap(),
    ) = events.emit(LifecycleEvent(protocol, kind, clock.nowNs(), wall.nowMs(), durationNs, detail, attrs))

    private class PathStats {
        var lastSuccess: Boolean? = null
        var lastRtt: Long? = null
        var consecutiveFailures = 0
        fun update(r: OperationRecord) {
            lastSuccess = r.success
            if (r.success) { lastRtt = r.rttNs; consecutiveFailures = 0 } else consecutiveFailures++
        }
    }
}

package com.thesis.iotconnbench.core.export

import com.thesis.iotconnbench.core.bench.ExperimentSession
import com.thesis.iotconnbench.core.bench.OperationRecord
import com.thesis.iotconnbench.core.bench.RunInfo
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.metrics.LatencySummary
import com.thesis.iotconnbench.core.metrics.Metrics
import com.thesis.iotconnbench.core.metrics.ProtocolSummary
import com.thesis.iotconnbench.core.util.Json

/**
 * Versioned exports (spec §7). See docs/EXPORT_SCHEMA.md. Bump [SCHEMA_VERSION] on any change
 * to column order/meaning or JSON structure.
 *
 * Secrets are never part of the inputs (configs carry no passwords/pairing codes), and
 * [redact] additionally strips any key that looks secret as defence in depth.
 */
object Exporter {
    const val SCHEMA_VERSION = "1.0.0"

    val CSV_COLUMNS = listOf(
        "schema_version", "session_id", "run_id", "operation_id", "sequence", "protocol",
        "operation_type", "is_warmup", "success", "error_category", "error_detail",
        "t_start_ns", "t_end_ns", "rtt_ns", "requested_value", "response_value",
        "request_bytes", "response_bytes", "payload_size_target", "execution_mode",
        "condition_labels", "selection_mode", "selection_reason", "diagnostics_json",
    )

    private val SECRET_KEY = Regex(
        "(?i)(password|passwd|secret|token|passcode|setup.?pin|setup.?code|pairing.?code|credential|psk|fabric.?key|(^|[._])(pin|ipk|noc)($|[._]))",
    )

    fun redact(m: Map<String, String>): Map<String, String> =
        m.mapValues { (k, v) -> if (SECRET_KEY.containsMatchIn(k)) "[REDACTED]" else v }

    fun csv(records: List<OperationRecord>): String {
        val sb = StringBuilder()
        sb.append(CSV_COLUMNS.joinToString(",")).append('\n')
        for (r in records.sortedWith(compareBy({ it.runId }, { it.sequence }))) {
            val row = listOf(
                SCHEMA_VERSION, r.sessionId, r.runId, r.operationId, r.sequence.toString(), r.protocol.name,
                r.operationType.name, r.warmup.toString(), r.success.toString(), r.errorCategory.name,
                r.errorDetail ?: "", r.tStartNs.toString(), r.tEndNs.toString(), r.rttNs?.toString() ?: "",
                r.requestedValue?.toString() ?: "", r.responseValue?.toString() ?: "",
                r.requestBytes?.toString() ?: "", r.responseBytes?.toString() ?: "",
                r.payloadSizeTarget?.toString() ?: "", r.executionMode.name,
                r.conditionLabels.joinToString(";"), r.selectionMode.name, r.selectionReason ?: "",
                Json.write(redact(r.diagnostics)),
            )
            sb.append(row.joinToString(",") { csvField(it) }).append('\n')
        }
        return sb.toString()
    }

    /** RFC 4180 quoting. */
    fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun json(
        session: ExperimentSession,
        runs: List<RunInfo>,
        records: List<OperationRecord>,
        events: List<LifecycleEvent>,
        exportedAtWallMs: Long,
        csvFileName: String?,
    ): String {
        val doc = linkedMapOf<String, Any?>(
            "schema_version" to SCHEMA_VERSION,
            "exported_at_wall_ms" to exportedAtWallMs,
            "timing" to mapOf(
                "clock" to "android.os.SystemClock.elapsedRealtimeNanos (monotonic, includes deep sleep)",
                "rtt_definition" to "Android-observed application-level round trip: t_end (matching device-level response received) - t_start (immediately before dispatch). Not one-way latency.",
                "percentile_method" to Metrics.PERCENTILE_METHOD,
                "throughput_window" to "successes / (max t_end - min t_start) of the protocol's measured ops",
                "throughput_busy" to "successes / sum(t_end - t_start) of the protocol's measured ops",
            ),
            "records_csv_file" to csvFileName,
            "session" to linkedMapOf(
                "session_id" to session.sessionId,
                "started_wall_ms" to session.startedWallMs,
                "started_mono_ns" to session.startedMonoNs,
                "ended_wall_ms" to session.endedWallMs,
                "metadata" to redact(session.metadata),
            ),
            "runs" to runs.map { run ->
                linkedMapOf(
                    "run_id" to run.runId,
                    "status" to run.status.name,
                    "status_detail" to run.statusDetail,
                    "started_wall_ms" to run.startedWallMs,
                    "ended_wall_ms" to run.endedWallMs,
                    "started_mono_ns" to run.startedMonoNs,
                    "ended_mono_ns" to run.endedMonoNs,
                    "config" to configMap(run),
                    "adapter_metadata" to redact(run.adapterMetadata),
                    "summary" to Metrics.summarize(run.runId, records, events).map { summaryMap(it) },
                    "operation_count" to records.count { it.runId == run.runId },
                )
            },
            "lifecycle_events" to events.map { e ->
                linkedMapOf(
                    "run_id" to e.runId,
                    "protocol" to e.protocol?.name,
                    "kind" to e.kind.name,
                    "t_ns" to e.tNs,
                    "wall_ms" to e.wallTimeMs,
                    "duration_ns" to e.durationNs,
                    "detail" to e.detail,
                    "attributes" to redact(e.attributes),
                )
            },
        )
        return Json.write(doc)
    }

    fun configMap(run: RunInfo): Map<String, Any?> {
        val c = run.config
        return linkedMapOf(
            "name" to c.name,
            "protocols" to c.protocols.map { it.name },
            "operation" to c.operation.name,
            "write_values" to c.writeValues,
            "payload_size_bytes" to c.payloadSizeBytes,
            "warmup_count" to c.warmupCount,
            "measured_count" to c.measuredCount,
            "timeout_ms" to c.timeoutMs,
            "inter_operation_delay_ms" to c.interOperationDelayMs,
            "order" to c.order.name,
            "random_seed" to c.randomSeed,
            "condition_labels" to c.conditionLabels,
            "operator_notes" to c.operatorNotes,
            "selection_mode" to c.selectionMode.name,
            "multipath_declared" to c.multipathDeclared,
            "reconnect_between_ops" to c.reconnectBetweenOps,
        )
    }

    fun summaryMap(s: ProtocolSummary): Map<String, Any?> = linkedMapOf(
        "protocol" to s.protocol.name,
        "attempts" to s.attempts,
        "successes" to s.successes,
        "failures" to s.failures,
        "timeouts" to s.timeouts,
        "success_rate" to s.successRate,
        "failure_rate" to s.failureRate,
        "failures_by_category" to s.failuresByCategory.mapKeys { it.key.name },
        "latency_ms" to latencyMap(s.latency),
        "throughput_window_ops_per_s" to s.throughputWindowOpsPerS,
        "throughput_busy_ops_per_s" to s.throughputBusyOpsPerS,
        "reconnect_count" to s.reconnectCount,
        "mean_reconnect_ms" to s.meanReconnectMs,
        "path_switch_count" to s.pathSwitchCount,
        "mean_path_switch_ms" to s.meanPathSwitchMs,
        "warmup_excluded" to s.warmupExcluded,
    )

    fun latencyMap(l: LatencySummary): Map<String, Any?> = linkedMapOf(
        "n" to l.n, "min" to l.minMs, "mean" to l.meanMs, "std_dev" to l.stdDevMs,
        "p50" to l.p50Ms, "p90" to l.p90Ms, "p95" to l.p95Ms, "p99" to l.p99Ms, "max" to l.maxMs,
    )
}

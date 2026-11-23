package com.thesis.iotconnbench.core.metrics

import com.thesis.iotconnbench.core.bench.OperationRecord
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.ProtocolType
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Summary statistics (spec §6.3).
 *
 * - Only measured (non-warm-up) records of one run + protocol are summarised.
 * - Latency statistics use successful operations only; failures are counted, never treated as
 *   zero-latency samples.
 * - Percentiles: NEAREST-RANK method. For sorted samples x[1..n] and p in (0,100],
 *   rank = ceil(p/100 * n), Pp = x[rank]. Always returns an observed sample (no interpolation).
 * - Throughput (two definitions, both exported):
 *     window: successes / (max t_end - min t_start) over the protocol's measured ops. In
 *             interleaved orders this window includes the other protocols' ops and delays.
 *     busy:   successes / sum(t_end - t_start) over all the protocol's measured attempts,
 *             i.e. back-to-back sequential capacity excluding inter-operation delay.
 */
data class LatencySummary(
    val n: Int,
    val minMs: Double?,
    val meanMs: Double?,
    val stdDevMs: Double?,
    val p50Ms: Double?,
    val p90Ms: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val maxMs: Double?,
)

data class ProtocolSummary(
    val runId: String,
    val protocol: ProtocolType,
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val timeouts: Int,
    val successRate: Double,
    val failureRate: Double,
    val failuresByCategory: Map<ErrorCategory, Int>,
    val latency: LatencySummary,
    val throughputWindowOpsPerS: Double?,
    val throughputBusyOpsPerS: Double?,
    val reconnectCount: Int,
    val meanReconnectMs: Double?,
    val pathSwitchCount: Int,
    val meanPathSwitchMs: Double?,
    val warmupExcluded: Int,
)

object Metrics {
    const val PERCENTILE_METHOD = "nearest-rank: rank=ceil(p/100*n), value=sorted[rank-1]"

    fun percentileNearestRank(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        require(p > 0 && p <= 100) { "p must be in (0,100]" }
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun latency(rttsNs: List<Long>): LatencySummary {
        if (rttsNs.isEmpty()) return LatencySummary(0, null, null, null, null, null, null, null, null)
        val s = rttsNs.sorted()
        val mean = s.average()
        val sd = if (s.size > 1) sqrt(s.sumOf { (it - mean) * (it - mean) } / (s.size - 1)) else 0.0
        fun ms(ns: Long?) = ns?.let { it / 1e6 }
        return LatencySummary(
            n = s.size,
            minMs = ms(s.first()),
            meanMs = mean / 1e6,
            stdDevMs = sd / 1e6,
            p50Ms = ms(percentileNearestRank(s, 50.0)),
            p90Ms = ms(percentileNearestRank(s, 90.0)),
            p95Ms = ms(percentileNearestRank(s, 95.0)),
            p99Ms = ms(percentileNearestRank(s, 99.0)),
            maxMs = ms(s.last()),
        )
    }

    fun summarize(
        runId: String,
        records: List<OperationRecord>,
        events: List<LifecycleEvent> = emptyList(),
    ): List<ProtocolSummary> {
        val runRecords = records.filter { it.runId == runId }
        return runRecords.map { it.protocol }.distinct().sorted().map { p ->
            val all = runRecords.filter { it.protocol == p }
            val measured = all.filter { !it.warmup }
            val ok = measured.filter { it.success }
            val fail = measured.filter { !it.success }
            val windowNs = if (measured.isEmpty()) 0L else measured.maxOf { it.tEndNs } - measured.minOf { it.tStartNs }
            val busyNs = measured.sumOf { it.tEndNs - it.tStartNs }
            val evs = events.filter { it.runId == runId && it.protocol == p }
            val reconnects = evs.filter { it.kind == LifecycleKind.RECONNECTED && it.durationNs != null }
            val switches = evs.filter { it.kind == LifecycleKind.PATH_SWITCH }
            val switchDur = switches.mapNotNull { it.durationNs }
            ProtocolSummary(
                runId = runId,
                protocol = p,
                attempts = measured.size,
                successes = ok.size,
                failures = fail.size,
                timeouts = fail.count { it.errorCategory == ErrorCategory.TIMEOUT },
                successRate = if (measured.isEmpty()) 0.0 else ok.size.toDouble() / measured.size,
                failureRate = if (measured.isEmpty()) 0.0 else fail.size.toDouble() / measured.size,
                failuresByCategory = fail.groupingBy { it.errorCategory }.eachCount(),
                latency = latency(ok.mapNotNull { it.rttNs }),
                throughputWindowOpsPerS = if (windowNs > 0) ok.size / (windowNs / 1e9) else null,
                throughputBusyOpsPerS = if (busyNs > 0) ok.size / (busyNs / 1e9) else null,
                reconnectCount = reconnects.size,
                meanReconnectMs = reconnects.mapNotNull { it.durationNs }.takeIf { it.isNotEmpty() }?.average()?.div(1e6),
                pathSwitchCount = switches.size,
                meanPathSwitchMs = switchDur.takeIf { it.isNotEmpty() }?.average()?.div(1e6),
                warmupExcluded = all.size - measured.size,
            )
        }
    }
}

package com.thesis.iotconnbench.core.select

import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.ProtocolType

/**
 * Runtime path-selection extension point (spec §9).
 *
 *  - FIXED: the configured protocol list is executed by the schedule (no online decisions).
 *  - NAIVE_FAILOVER: use the first path in priority order whose adapter is CONNECTED; after a
 *    failure on the current path, move to the next path for subsequent operations. The failed
 *    operation is NOT retried on the new path (no hidden retries; spec §6.2).
 *  - MODEL_BASED_SELECTOR: interface only. Disabled until a real, documented policy trained on
 *    measured data is supplied via [ModelBasedSelector].
 *  - ORACLE: offline reference only (see analysis tooling); cannot be constructed for online use.
 */
enum class SelectionMode(val isMultiPath: Boolean) {
    FIXED(false),
    NAIVE_FAILOVER(true),
    MODEL_BASED_SELECTOR(true),
    ORACLE(true),
}

data class PathState(
    val protocol: ProtocolType,
    val connection: ConnectionState,
    val lastSuccess: Boolean?,
    val lastRttNs: Long?,
    val consecutiveFailures: Int,
)

data class SelectionContext(
    val sequence: Int,
    val paths: List<PathState>,
    val previousSelection: ProtocolType?,
    val nowNs: Long,
)

data class SelectionDecision(
    val selected: ProtocolType?,
    val reason: String,
    /** Feature values used for the decision; exported verbatim. */
    val features: Map<String, String>,
    val decisionTimeNs: Long,
)

interface SelectionPolicy {
    val mode: SelectionMode
    fun select(ctx: SelectionContext): SelectionDecision
    /** Outcome feedback after the op completes. */
    fun onOutcome(protocol: ProtocolType, success: Boolean, rttNs: Long?) {}
}

class FixedPolicy(private val protocol: ProtocolType) : SelectionPolicy {
    override val mode = SelectionMode.FIXED
    override fun select(ctx: SelectionContext) =
        SelectionDecision(protocol, "fixed", emptyMap(), ctx.nowNs)
}

class NaiveFailoverPolicy(private val priority: List<ProtocolType>) : SelectionPolicy {
    override val mode = SelectionMode.NAIVE_FAILOVER
    private var current: ProtocolType? = null
    private var lastFailed: ProtocolType? = null

    override fun select(ctx: SelectionContext): SelectionDecision {
        val byProto = ctx.paths.associateBy { it.protocol }
        val features = ctx.paths.associate { "state.${it.protocol}" to it.connection.name } +
            ctx.paths.associate { "consecutiveFailures.${it.protocol}" to it.consecutiveFailures.toString() }
        val cur = current
        if (cur != null && cur != lastFailed && byProto[cur]?.connection == ConnectionState.CONNECTED) {
            return SelectionDecision(cur, "stay:current_path_healthy", features, ctx.nowNs)
        }
        // Rotate starting after the failed path so we do not bounce back immediately.
        val start = lastFailed?.let { priority.indexOf(it) + 1 } ?: 0
        val rotation = priority.indices.map { priority[(start + it) % priority.size] }
        val next = rotation.firstOrNull { it != lastFailed && byProto[it]?.connection == ConnectionState.CONNECTED }
            ?: rotation.firstOrNull { byProto[it]?.connection == ConnectionState.CONNECTED }
        val reason = when {
            next == null -> "no_connected_path"
            cur == null -> "initial:first_connected_in_priority"
            next != cur -> "failover:${cur}->${next}"
            else -> "stay:only_connected_path"
        }
        current = next
        lastFailed = null
        return SelectionDecision(next, reason, features, ctx.nowNs)
    }

    override fun onOutcome(protocol: ProtocolType, success: Boolean, rttNs: Long?) {
        if (!success) lastFailed = protocol
    }
}

/**
 * Extension point for a future model-based selector. A concrete implementation must supply a
 * documented model with provenance; the runner refuses MODEL_BASED_SELECTOR until then.
 */
interface ModelBasedSelector : SelectionPolicy {
    /** Identifier + hash of the trained model artifact, exported with every decision. */
    val modelProvenance: String
}

object PolicyFactory {
    fun create(mode: SelectionMode, protocols: List<ProtocolType>, model: ModelBasedSelector? = null): SelectionPolicy =
        when (mode) {
            SelectionMode.FIXED -> error("FIXED mode is executed by the schedule, not a policy")
            SelectionMode.NAIVE_FAILOVER -> NaiveFailoverPolicy(protocols)
            SelectionMode.MODEL_BASED_SELECTOR -> model
                ?: throw IllegalStateException("MODEL_BASED_SELECTOR disabled: no trained policy supplied")
            SelectionMode.ORACLE -> throw IllegalStateException("ORACLE is offline-only and cannot be deployed online")
        }
}

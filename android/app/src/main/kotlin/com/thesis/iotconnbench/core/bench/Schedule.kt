package com.thesis.iotconnbench.core.bench

import com.thesis.iotconnbench.core.domain.ProtocolType
import kotlin.random.Random

data class ScheduledOp(val protocol: ProtocolType, val warmup: Boolean, val indexWithinProtocol: Int)

/**
 * Builds the deterministic execution order (spec §6.2). All warm-up operations precede all
 * measured operations, so warm-up never interleaves with the measured window.
 */
object ScheduleBuilder {
    fun build(cfg: ExperimentConfig): List<ScheduledOp> =
        phase(cfg, warmup = true, count = cfg.warmupCount, rnd = Random(cfg.randomSeed)) +
            phase(cfg, warmup = false, count = cfg.measuredCount, rnd = Random(cfg.randomSeed + 1))

    private fun phase(cfg: ExperimentConfig, warmup: Boolean, count: Int, rnd: Random): List<ScheduledOp> {
        val out = ArrayList<ScheduledOp>()
        when (cfg.order) {
            OrderStrategy.FIXED -> for (p in cfg.protocols) for (i in 0 until count) out += ScheduledOp(p, warmup, i)
            OrderStrategy.ROUND_ROBIN -> for (i in 0 until count) for (p in cfg.protocols) out += ScheduledOp(p, warmup, i)
            OrderStrategy.SEEDED_RANDOM -> for (i in 0 until count) {
                for (p in cfg.protocols.shuffled(rnd)) out += ScheduledOp(p, warmup, i)
            }
        }
        return out
    }
}

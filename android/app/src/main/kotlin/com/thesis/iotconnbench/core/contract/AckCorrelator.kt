package com.thesis.iotconnbench.core.contract

import com.thesis.iotconnbench.core.domain.ErrorCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Correlates asynchronous device-level acknowledgements (BLE notifications, MQTT ack-topic
 * messages) with in-flight operations by operation id. Shared by the BLE and MQTT adapters.
 *
 * The receive timestamp must be taken by the caller at callback entry (before decoding) and is
 * used as t_end, so JSON parsing cost is not attributed to the network path.
 */
class AckCorrelator(
    private val codec: TemplatePayloadCodec,
    private val recentCapacity: Int = 512,
) {
    sealed interface Outcome {
        data class Acked(val ack: DeviceAck, val tReceivedNs: Long, val responseBytes: Int) : Outcome
        data class Failed(val category: ErrorCategory, val detail: String, val tDetectedNs: Long) : Outcome
    }

    enum class Disposition { MATCHED, DUPLICATE, UNMATCHED, UNPARSEABLE }

    private val lock = Any()
    private val pending = HashMap<String, CompletableDeferred<Outcome>>()
    private val recentlyCompleted = object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > recentCapacity
    }

    var duplicateCount = 0L; private set
    var unmatchedCount = 0L; private set
    var unparseableCount = 0L; private set

    /** Register before dispatching so a very fast ack cannot be missed. */
    fun register(opId: String): CompletableDeferred<Outcome> = synchronized(lock) {
        require(!pending.containsKey(opId)) { "Duplicate in-flight operation id $opId" }
        CompletableDeferred<Outcome>().also { pending[opId] = it }
    }

    fun unregister(opId: String) = synchronized(lock) { pending.remove(opId) }

    /**
     * @param correlationHint optional transport-level id (e.g. MQTT 5 Correlation Data) used when
     *   the payload carries no op id field.
     */
    fun onIncoming(bytes: ByteArray, tReceivedNs: Long, correlationHint: String? = null): Pair<Disposition, DeviceAck?> {
        val ack = codec.decodeAck(bytes)
        if (ack == null && correlationHint == null) {
            synchronized(lock) { unparseableCount++ }
            return Disposition.UNPARSEABLE to null
        }
        val effective = ack ?: DeviceAck(null, false, null, "unparseable", bytes.toString(Charsets.UTF_8))
        val key = effective.operationId ?: correlationHint ?: run {
            synchronized(lock) { unmatchedCount++ }
            return Disposition.UNMATCHED to effective
        }
        synchronized(lock) {
            val d = pending.remove(key)
            if (d != null) {
                recentlyCompleted[key] = Unit
                d.complete(Outcome.Acked(effective, tReceivedNs, bytes.size))
                return Disposition.MATCHED to effective
            }
            return if (recentlyCompleted.containsKey(key)) {
                duplicateCount++
                Disposition.DUPLICATE to effective
            } else {
                unmatchedCount++
                Disposition.UNMATCHED to effective
            }
        }
    }

    /** Fails every in-flight op, e.g. on link loss. */
    fun failAll(category: ErrorCategory, detail: String, tDetectedNs: Long) {
        val toFail = synchronized(lock) {
            val l = pending.entries.toList()
            pending.clear()
            l.forEach { recentlyCompleted[it.key] = Unit }
            l
        }
        toFail.forEach { it.value.complete(Outcome.Failed(category, detail, tDetectedNs)) }
    }

    fun inFlight(): Int = synchronized(lock) { pending.size }

    /**
     * Awaits the outcome for [opId]. On timeout returns null and unregisters the op, so a late ack
     * is later counted as DUPLICATE (it is not silently accepted).
     */
    suspend fun await(opId: String, deferred: CompletableDeferred<Outcome>, timeoutMs: Long): Outcome? {
        val r = withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { deferred.await() }
        if (r == null) {
            synchronized(lock) {
                if (pending.remove(opId) != null) recentlyCompleted[opId] = Unit
            }
        }
        return r
    }
}

package com.thesis.iotconnbench.core.domain

/** Connection/lifecycle/diagnostic events, persisted alongside operation records (spec §7). */
enum class LifecycleKind {
    INITIALIZED,
    SCAN_STARTED,
    SCAN_COMPLETED,
    CONNECT_STARTED,
    CONNECTED,
    SERVICES_DISCOVERED,
    MTU_NEGOTIATED,
    NOTIFICATIONS_ENABLED,
    DISCONNECTED,
    RECONNECT_STARTED,
    RECONNECTED,
    COMMISSIONING_STARTED,
    COMMISSIONED,
    COMMISSIONING_FAILED,
    SUBSCRIPTION_ESTABLISHED,
    SUBSCRIPTION_LOST,
    STATE_REPORT,
    UNMATCHED_RESPONSE,
    DUPLICATE_RESPONSE,
    RETAINED_IGNORED,
    STALE_CALLBACK,
    SELECTION_DECISION,
    PATH_SWITCH,
    RUN_STARTED,
    RUN_FINISHED,
    RUN_CANCELLED,
    ERROR,
    OPERATOR_NOTE,
}

data class LifecycleEvent(
    val protocol: ProtocolType?,
    val kind: LifecycleKind,
    val tNs: Long,
    val wallTimeMs: Long,
    val durationNs: Long? = null,
    val detail: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val runId: String? = null,
)

fun interface EventSink {
    fun emit(event: LifecycleEvent)
}

/** Helper bound to one protocol so adapters can emit events tersely. */
class AdapterEvents(
    private val protocol: ProtocolType,
    private val sink: EventSink,
    private val clock: MonotonicClock,
    private val wall: WallClock,
) {
    fun emit(kind: LifecycleKind, detail: String = "", durationNs: Long? = null, attrs: Map<String, String> = emptyMap()) {
        sink.emit(LifecycleEvent(protocol, kind, clock.nowNs(), wall.nowMs(), durationNs, detail, attrs))
    }
}

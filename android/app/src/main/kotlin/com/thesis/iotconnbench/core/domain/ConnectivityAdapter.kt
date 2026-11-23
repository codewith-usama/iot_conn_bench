package com.thesis.iotconnbench.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Common adapter contract (spec §4.2). The benchmark runner depends only on this interface.
 *
 * Contract for implementers:
 *  - [execute] must take `tStartNs` from the injected [MonotonicClock] immediately before handing
 *    the request to the protocol stack, and `tEndNs` when the *device-level* acknowledgement /
 *    response is received. Broker or link-layer acknowledgements must never complete an op.
 *  - [execute] must never retry internally and never throw for ordinary failures; it returns an
 *    [OperationResult] with an [ErrorCategory]. It must respect [OperationRequest.timeoutMs].
 *  - No hidden reconnects: an adapter reports DISCONNECTED and waits for an explicit [connect].
 */
interface ConnectivityAdapter {
    val protocol: ProtocolType
    val capabilities: AdapterCapabilities

    /** Validates and applies configuration. Throws [AdapterException] NOT_CONFIGURED/UNAVAILABLE. */
    suspend fun initialize(config: AdapterConfig)

    suspend fun discover(timeoutMs: Long): List<DeviceDescriptor>

    suspend fun connect(device: DeviceDescriptor): ConnectReport

    suspend fun disconnect()

    suspend fun execute(request: OperationRequest): OperationResult

    fun observeState(): StateFlow<ConnectionState>

    /** Non-secret description of the active configuration, for experiment metadata. */
    fun configMetadata(): Map<String, String>

    suspend fun dispose()
}

class AdapterRegistry {
    private val adapters = linkedMapOf<ProtocolType, ConnectivityAdapter>()

    fun register(adapter: ConnectivityAdapter) {
        adapters[adapter.protocol] = adapter
    }

    operator fun get(protocol: ProtocolType): ConnectivityAdapter? = adapters[protocol]

    fun require(protocol: ProtocolType): ConnectivityAdapter =
        adapters[protocol] ?: throw AdapterException(ErrorCategory.UNAVAILABLE, "No adapter registered for $protocol")

    fun all(): List<ConnectivityAdapter> = adapters.values.toList()

    fun capabilities(): List<AdapterCapabilities> = adapters.values.map { it.capabilities }
}

/** Single source of monotonic time for all measurements. Android: SystemClock.elapsedRealtimeNanos(). */
fun interface MonotonicClock {
    fun nowNs(): Long
}

/** Wall clock is used only for human-readable metadata, never for intervals. */
fun interface WallClock {
    fun nowMs(): Long
}

object JvmMonotonicClock : MonotonicClock {
    override fun nowNs(): Long = System.nanoTime()
}

object SystemWallClock : WallClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

package com.thesis.iotconnbench.core

import com.thesis.iotconnbench.core.domain.AdapterCapabilities
import com.thesis.iotconnbench.core.domain.AdapterConfig
import com.thesis.iotconnbench.core.domain.ConnectReport
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.ConnectivityAdapter
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.OperationRequest
import com.thesis.iotconnbench.core.domain.OperationResult
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.domain.WallClock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Deterministic virtual clock for unit tests. */
class FakeClock(var now: Long = 1_000_000_000L) : MonotonicClock, WallClock {
    override fun nowNs() = now
    override fun nowMs() = now / 1_000_000
    fun advanceMs(ms: Long) { now += ms * 1_000_000 }
}

/** What the fake should do for each successive execute() call. */
sealed interface FakeStep {
    data class Ok(val latencyMs: Long, val echoValue: Long? = null) : FakeStep
    data class Fail(val category: ErrorCategory, val latencyMs: Long = 0) : FakeStep
    data object Hang : FakeStep
    data object Disconnect : FakeStep
}

/**
 * SIMULATED adapter for unit tests ONLY. It lives in the test source set and is never packaged
 * into the app; its capabilities declare ExecutionMode.SIMULATED.
 */
class FakeAdapter(
    override val protocol: ProtocolType,
    private val clock: FakeClock,
    private val steps: ArrayDeque<FakeStep> = ArrayDeque(),
    private val defaultStep: FakeStep = FakeStep.Ok(10),
) : ConnectivityAdapter {
    val state = MutableStateFlow(ConnectionState.CONNECTED)
    val executed = mutableListOf<OperationRequest>()
    var connectCalls = 0

    override val capabilities = AdapterCapabilities(
        protocol, ExecutionMode.SIMULATED, setOf(OperationType.READ_STATE, OperationType.WRITE_STATE),
        supportsSubscriptions = false, supportsPayloadPadding = true, requiresCommissioning = false,
        knownLimitations = listOf("unit-test double"), adapterVersion = "fake-1",
    )

    fun enqueue(vararg s: FakeStep) { steps.addAll(s) }

    override suspend fun initialize(config: AdapterConfig) {}
    override suspend fun discover(timeoutMs: Long) = listOf(DeviceDescriptor("dev1", "fake", setOf(protocol)))
    override suspend fun connect(device: DeviceDescriptor): ConnectReport {
        connectCalls++
        clock.advanceMs(50)
        state.value = ConnectionState.CONNECTED
        return ConnectReport(mapOf("connect" to 50_000_000L))
    }
    override suspend fun disconnect() { state.value = ConnectionState.DISCONNECTED }
    override fun observeState(): StateFlow<ConnectionState> = state
    override fun configMetadata() = mapOf("fake.$protocol" to "1")
    override suspend fun dispose() {}

    override suspend fun execute(request: OperationRequest): OperationResult {
        executed += request
        val t0 = clock.nowNs()
        return when (val s = steps.removeFirstOrNull() ?: defaultStep) {
            is FakeStep.Ok -> {
                clock.advanceMs(s.latencyMs)
                OperationResult(request.operationId, protocol, true, ErrorCategory.NONE, t0, clock.nowNs(),
                    responseValue = s.echoValue ?: request.payload.value)
            }
            is FakeStep.Fail -> {
                clock.advanceMs(s.latencyMs)
                OperationResult.failure(request, s.category, t0, clock.nowNs(), "injected")
            }
            FakeStep.Hang -> awaitCancellation()
            FakeStep.Disconnect -> {
                state.value = ConnectionState.DISCONNECTED
                OperationResult.failure(request, ErrorCategory.DISCONNECTED_IN_FLIGHT, t0, clock.nowNs(), "link lost")
            }
        }
    }
}

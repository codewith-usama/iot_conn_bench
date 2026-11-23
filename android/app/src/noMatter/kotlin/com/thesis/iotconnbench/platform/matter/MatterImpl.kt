package com.thesis.iotconnbench.platform.matter

import android.content.Context
import com.thesis.iotconnbench.core.domain.AdapterCapabilities
import com.thesis.iotconnbench.core.domain.AdapterConfig
import com.thesis.iotconnbench.core.domain.AdapterEvents
import com.thesis.iotconnbench.core.domain.AdapterException
import com.thesis.iotconnbench.core.domain.ConnectReport
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.OperationRequest
import com.thesis.iotconnbench.core.domain.OperationResult
import com.thesis.iotconnbench.core.domain.ProtocolType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Build variant WITHOUT the Matter SDK (`-PenableMatter=false`). This is NOT a Matter
 * implementation: every call fails with UNAVAILABLE so no Matter result can ever be fabricated.
 */
object MatterImpl {
    fun create(context: Context, clock: MonotonicClock, events: AdapterEvents): MatterControl = Unavailable(clock)

    private class Unavailable(private val clock: MonotonicClock) : MatterControl {
        private val reason = "App built with -PenableMatter=false: no Android Matter controller is linked"
        override val protocol = ProtocolType.MATTER
        override val capabilities = AdapterCapabilities(
            ProtocolType.MATTER, ExecutionMode.HARDWARE, emptySet(), false, false, true,
            listOf(reason), "matter-unavailable",
        )
        private val state = MutableStateFlow(ConnectionState.ERROR)
        override fun observeState(): StateFlow<ConnectionState> = state
        override fun configMetadata() = mapOf("matter.controller" to "unavailable")
        override fun controllerStatus() = mapOf("available" to false, "reason" to reason)
        override suspend fun initialize(config: AdapterConfig) = throw AdapterException(ErrorCategory.UNAVAILABLE, reason)
        override suspend fun discover(timeoutMs: Long): List<DeviceDescriptor> = throw AdapterException(ErrorCategory.UNAVAILABLE, reason)
        override suspend fun connect(device: DeviceDescriptor): ConnectReport = throw AdapterException(ErrorCategory.UNAVAILABLE, reason)
        override suspend fun disconnect() {}
        override suspend fun execute(request: OperationRequest): OperationResult {
            val t = clock.nowNs()
            return OperationResult.failure(request, ErrorCategory.UNAVAILABLE, t, t, reason)
        }
        override suspend fun commissionOnNetwork(params: OnNetworkCommissioning) = CommissioningResult(false, params.nodeId, emptyMap(), reason)
        override suspend fun commissionBleWifi(params: BleWifiCommissioning) = CommissioningResult(false, params.nodeId, emptyMap(), reason)
        override suspend fun dispose() {}
    }
}

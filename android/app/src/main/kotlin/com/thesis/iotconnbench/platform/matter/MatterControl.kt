package com.thesis.iotconnbench.platform.matter

import android.content.Context
import com.thesis.iotconnbench.core.domain.AdapterEvents
import com.thesis.iotconnbench.core.domain.ConnectivityAdapter
import com.thesis.iotconnbench.core.domain.MonotonicClock

/**
 * Matter-specific controls beyond the common adapter contract: controller lifecycle and
 * commissioning. Commissioning is always an explicit user action (spec §5.3) and its timings are
 * recorded separately from steady-state interaction RTT.
 *
 * Two implementations exist, selected at build time by the Gradle property `enableMatter`:
 *   src/matter/   real controller on the connectedhomeip Android controller (CHIPController JNI)
 *   src/noMatter/ reports UNAVAILABLE for every call (used only if the Matter SDK cannot be built)
 */
interface MatterControl : ConnectivityAdapter {
    /** Human-readable readiness of the controller, shown on the dashboard. */
    fun controllerStatus(): Map<String, Any?>

    /** Commission a device that is already on the IP network (e.g. after an Open Commissioning Window). */
    suspend fun commissionOnNetwork(params: OnNetworkCommissioning): CommissioningResult

    /** Commission over BLE and provision the device onto Wi-Fi (Matter-over-Wi-Fi). */
    suspend fun commissionBleWifi(params: BleWifiCommissioning): CommissioningResult
}

data class OnNetworkCommissioning(
    val nodeId: Long,
    val ipAddress: String,
    val port: Int,
    val discriminator: Int,
    /** Setup passcode; transient, never stored or logged. */
    val setupPinCode: Long,
)

data class BleWifiCommissioning(
    val nodeId: Long,
    val discriminator: Int,
    val setupPinCode: Long,
    val ssid: String,
    /** Transient; read from SecretStore by the caller, never stored in config or logged. */
    val wifiPassword: String,
    val scanTimeoutMs: Long = 20_000,
)

data class CommissioningResult(
    val success: Boolean,
    val nodeId: Long,
    val phasesNs: Map<String, Long>,
    val detail: String,
    val errorCode: Long? = null,
)

/** Implemented in src/matter or src/noMatter. */
object MatterFactory {
    fun create(context: Context, clock: MonotonicClock, events: AdapterEvents): MatterControl =
        MatterImpl.create(context, clock, events)
}

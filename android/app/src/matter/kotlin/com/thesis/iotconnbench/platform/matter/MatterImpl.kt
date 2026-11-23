package com.thesis.iotconnbench.platform.matter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import chip.devicecontroller.ChipClusters
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.ControllerParams
import chip.devicecontroller.GetConnectedDeviceCallbackJni
import chip.devicecontroller.NetworkCredentials
import chip.platform.AndroidBleManager
import chip.platform.AndroidChipPlatform
import chip.platform.BleCallback
import chip.platform.ChipMdnsCallbackImpl
import chip.platform.DiagnosticDataProviderImpl
import chip.platform.NsdManagerServiceBrowser
import chip.platform.NsdManagerServiceResolver
import chip.platform.PreferencesConfigurationManager
import chip.platform.PreferencesKeyValueStoreManager
import com.thesis.iotconnbench.core.domain.AdapterCapabilities
import com.thesis.iotconnbench.core.domain.AdapterConfig
import com.thesis.iotconnbench.core.domain.AdapterEvents
import com.thesis.iotconnbench.core.domain.AdapterException
import com.thesis.iotconnbench.core.domain.ConnectReport
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.MatterAdapterConfig
import com.thesis.iotconnbench.core.domain.MatterWriteMode
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.OperationRequest
import com.thesis.iotconnbench.core.domain.OperationResult
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.platform.Permissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Real Android Matter controller on the connectedhomeip Java/JNI controller
 * (chip.devicecontroller.ChipDeviceController), packaged by Google as
 * com.google.matter:matter-android-demo-sdk:1.0.
 *
 * API surface used is restricted to calls whose signatures are identical in the connectedhomeip
 * v1.0 and v1.1 Android controller (the demo AAR predates v1.2) and that Google's own sample app
 * uses with this AAR: ControllerParams.newBuilder, setCompletionListener, pairDeviceWithAddress,
 * pairDevice(BluetoothGatt,...), getConnectedDevicePointer, and the generated ChipClusters.OnOffCluster.
 *
 * Common workload mapping (spec §4.3) — OnOff cluster only:
 *   READ_STATE  -> ReadRequest OnOff.OnOff (0x0006/0x0000); completion = attribute report (onSuccess).
 *   WRITE_STATE -> Invoke OnOff.On / OnOff.Off; completion = InvokeResponse with SUCCESS status,
 *                  i.e. the device's data model accepted and executed the command.
 * Generic attribute write / arbitrary clusters need the newer interaction-model Java API and are
 * rejected as UNSUPPORTED rather than approximated.
 *
 * Fabric: ControllerParams.newBuilder() generates operational credentials for the controller's
 * process lifetime. After an app restart the node must be commissioned again (documented
 * limitation; see docs/MATTER.md).
 */
object MatterImpl {
    fun create(context: Context, clock: MonotonicClock, events: AdapterEvents): MatterControl =
        ChipMatterAdapter(context.applicationContext, clock, events)
}

@SuppressLint("MissingPermission")
private class ChipMatterAdapter(
    private val context: Context,
    private val clock: MonotonicClock,
    private val events: AdapterEvents,
) : MatterControl {

    override val protocol = ProtocolType.MATTER
    override val capabilities = AdapterCapabilities(
        protocol = ProtocolType.MATTER,
        executionMode = ExecutionMode.HARDWARE,
        supportedOperations = setOf(OperationType.READ_STATE, OperationType.WRITE_STATE),
        supportsSubscriptions = false,
        supportsPayloadPadding = false,
        requiresCommissioning = true,
        knownLimitations = listOf(
            "Workload limited to OnOff cluster (read OnOff attribute / invoke On, Off)",
            "Payload size is fixed by the Matter data model (padding not applicable)",
            "Controller fabric is per app process: re-commission after app restart",
            "Controller SDK: com.google.matter:matter-android-demo-sdk:1.0 (development/example use only)",
        ),
        adapterVersion = "matter-chip-1.0.0",
    )

    private val state = MutableStateFlow(ConnectionState.IDLE)
    override fun observeState(): StateFlow<ConnectionState> = state.asStateFlow()

    private var cfg: MatterAdapterConfig? = null
    private var bleManager: AndroidBleManager? = null
    private var controller: ChipDeviceController? = null
    private var initError: String? = null
    private val opMutex = Mutex()
    private val commissionedThisProcess = mutableSetOf<Long>()

    // Commissioning state shared with the completion listener (one commissioning at a time).
    @Volatile private var commissioning: CommissioningTracker? = null

    private class CommissioningTracker(val nodeId: Long, val t0: Long) {
        val phases = linkedMapOf<String, Long>()
        val done = CompletableDeferred<Pair<Boolean, String>>()
        var errorCode: Long? = null
    }

    // ---------- controller lifecycle ----------
    @Synchronized
    private fun controller(): ChipDeviceController {
        controller?.let { return it }
        initError?.let { throw AdapterException(ErrorCategory.UNAVAILABLE, it) }
        try {
            ChipDeviceController.loadJni()
            val ble = AndroidBleManager()
            AndroidChipPlatform(
                ble,
                PreferencesKeyValueStoreManager(context),
                PreferencesConfigurationManager(context),
                NsdManagerServiceResolver(context),
                NsdManagerServiceBrowser(context),
                ChipMdnsCallbackImpl(),
                DiagnosticDataProviderImpl(context),
            )
            val c = ChipDeviceController(ControllerParams.newBuilder().setControllerVendorId(TEST_VENDOR_ID).build())
            c.setCompletionListener(listener)
            bleManager = ble
            controller = c
            events.emit(LifecycleKind.INITIALIZED, "Matter controller started (vendor 0x${TEST_VENDOR_ID.toString(16)})")
            return c
        } catch (t: Throwable) {
            initError = "Matter controller init failed: $t"
            events.emit(LifecycleKind.ERROR, initError!!)
            throw AdapterException(ErrorCategory.UNAVAILABLE, initError!!, t)
        }
    }

    private val listener = object : ChipDeviceController.CompletionListener {
        private fun phase(name: String) {
            val c = commissioning ?: return
            c.phases[name] = clock.nowNs() - c.t0
        }
        override fun onConnectDeviceComplete() = phase("connect_device_complete_ns")
        override fun onStatusUpdate(status: Int) = phase("status_$status")
        override fun onPairingComplete(errorCode: Int) {
            phase("pase_complete_ns")
            if (errorCode != 0) commissioning?.let { it.errorCode = errorCode.toLong(); it.done.complete(false to "PASE failed, code $errorCode") }
        }
        override fun onPairingDeleted(errorCode: Int) {}
        override fun onCommissioningComplete(nodeId: Long, errorCode: Int) {
            phase("commissioning_complete_ns")
            val c = commissioning ?: return
            c.errorCode = errorCode.toLong()
            c.done.complete((errorCode == 0) to (if (errorCode == 0) "commissioned" else "commissioning failed, code $errorCode"))
        }
        override fun onReadCommissioningInfo(vendorId: Int, productId: Int, wifiEndpointId: Int, threadEndpointId: Int) {
            phase("read_commissioning_info_ns")
            events.emit(LifecycleKind.STATE_REPORT, "commissioning info vid=$vendorId pid=$productId wifiEp=$wifiEndpointId")
        }
        override fun onCommissioningStatusUpdate(nodeId: Long, stage: String, errorCode: Int) {
            phase("stage_${stage}_ns")
            if (errorCode != 0) events.emit(LifecycleKind.ERROR, "commissioning stage $stage error $errorCode")
        }
        override fun onNotifyChipConnectionClosed() {}
        override fun onCloseBleComplete() {}
        override fun onError(error: Throwable) {
            commissioning?.done?.complete(false to "controller error: $error")
            events.emit(LifecycleKind.ERROR, "controller error: ${error.message}")
        }
        override fun onOpCSRGenerationComplete(csr: ByteArray) {}
    }

    override fun controllerStatus(): Map<String, Any?> = mapOf(
        "available" to (initError == null),
        "sdk" to "com.google.matter:matter-android-demo-sdk:1.0",
        "controllerStarted" to (controller != null),
        "initError" to initError,
        "commissionedThisProcess" to commissionedThisProcess.toList(),
        "fabricNote" to "Controller credentials are per app process; re-commission after restart",
    )

    override fun configMetadata(): Map<String, String> = (cfg?.metadata() ?: emptyMap()) + mapOf(
        "matter.controllerSdk" to "com.google.matter:matter-android-demo-sdk:1.0",
        "matter.transport" to "Wi-Fi (IPv6/UDP operational)",
    )

    override suspend fun initialize(config: AdapterConfig) {
        val c = config as? MatterAdapterConfig ?: throw AdapterException(ErrorCategory.INTERNAL, "Not a Matter config")
        val missing = c.missingFields()
        if (missing.isNotEmpty()) throw AdapterException(ErrorCategory.NOT_CONFIGURED, "Matter config incomplete: $missing")
        if (c.clusterId != ONOFF_CLUSTER || c.attributeId != ONOFF_ATTRIBUTE || c.writeMode != MatterWriteMode.INVOKE_ONOFF) {
            throw AdapterException(
                ErrorCategory.UNSUPPORTED,
                "This controller build supports the OnOff workload only: clusterId=6, attributeId=0, writeMode=INVOKE_ONOFF " +
                    "(configured cluster=${c.clusterId} attribute=${c.attributeId} mode=${c.writeMode})",
            )
        }
        cfg = c
        controller()
    }

    override suspend fun discover(timeoutMs: Long): List<DeviceDescriptor> {
        val c = cfg ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "Matter not configured / commissioned")
        return listOf(DeviceDescriptor("matter-node-${c.nodeId}", "Matter node ${c.nodeId}", setOf(ProtocolType.MATTER), address = c.nodeId.toString()))
    }

    /** Establishes (or reuses) the CASE session; timed as a setup phase, never as operation RTT. */
    override suspend fun connect(device: DeviceDescriptor): ConnectReport {
        val c = cfg ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "Matter not configured")
        state.value = ConnectionState.CONNECTING
        events.emit(LifecycleKind.CONNECT_STARTED, "node ${c.nodeId}")
        val t0 = clock.nowNs()
        val ptr = devicePointer(c.nodeId, c.interactionTimeoutMs)
        if (ptr == null) {
            state.value = ConnectionState.ERROR
            val hint = if (c.nodeId !in commissionedThisProcess) " (node not commissioned by this app process: re-commission)" else ""
            throw AdapterException(ErrorCategory.NOT_CONNECTED, "Could not establish CASE session with node ${c.nodeId}$hint")
        }
        val d = clock.nowNs() - t0
        state.value = ConnectionState.CONNECTED
        events.emit(LifecycleKind.CONNECTED, "CASE session to node ${c.nodeId}", d, mapOf("matter.case_ns" to d.toString()))
        return ConnectReport(mapOf("matter.case_session_ns" to d), mapOf("matter.nodeId" to c.nodeId.toString()))
    }

    private suspend fun devicePointer(nodeId: Long, timeoutMs: Long): Long? {
        val ctrl = controller()
        val d = CompletableDeferred<Long?>()
        ctrl.getConnectedDevicePointer(nodeId, object : GetConnectedDeviceCallbackJni.GetConnectedDeviceCallback {
            override fun onDeviceConnected(devicePointer: Long) { d.complete(devicePointer) }
            override fun onConnectionFailure(nodeId: Long, error: Exception) {
                events.emit(LifecycleKind.ERROR, "node $nodeId unreachable: ${error.message}")
                d.complete(null)
            }
        })
        return withTimeoutOrNull(timeoutMs) { d.await() }
    }

    override suspend fun disconnect() {
        state.value = ConnectionState.DISCONNECTED
        events.emit(LifecycleKind.DISCONNECTED, "operator disconnect (CASE session left to controller)")
    }

    override suspend fun execute(request: OperationRequest): OperationResult = opMutex.withLock {
        val c = cfg ?: return@withLock OperationResult.failure(request, ErrorCategory.NOT_CONFIGURED, clock.nowNs(), clock.nowNs(), "Matter not configured")
        if (state.value != ConnectionState.CONNECTED) {
            val t = clock.nowNs()
            return@withLock OperationResult.failure(request, ErrorCategory.NOT_CONNECTED, t, t, "state=${state.value}")
        }
        // Session lookup is outside the measured interval; its duration is a diagnostic.
        val tp = clock.nowNs()
        val ptr = devicePointer(c.nodeId, c.interactionTimeoutMs)
        val ptrNs = clock.nowNs() - tp
        val diag = linkedMapOf(
            "matter.nodeId" to c.nodeId.toString(), "matter.endpoint" to c.endpointId.toString(),
            "matter.cluster" to "0x0006", "matter.get_device_ptr_ns" to ptrNs.toString(),
        )
        if (ptr == null) {
            state.value = ConnectionState.DISCONNECTED
            val t = clock.nowNs()
            return@withLock OperationResult.failure(request, ErrorCategory.NOT_CONNECTED, t, t, "node unreachable", diag)
        }
        val cluster = ChipClusters.OnOffCluster(ptr, c.endpointId)
        val timeout = minOf(request.timeoutMs, c.interactionTimeoutMs)
        when (request.type) {
            OperationType.READ_STATE -> {
                diag["matter.interaction"] = "READ OnOff.OnOff (0x0000)"
                val d = CompletableDeferred<Result<Pair<Boolean, Long>>>()
                val t0 = clock.nowNs()
                cluster.readOnOffAttribute(object : ChipClusters.BooleanAttributeCallback {
                    override fun onSuccess(value: Boolean) { d.complete(Result.success(value to clock.nowNs())) }
                    override fun onError(error: Exception) { d.complete(Result.failure(error)) }
                })
                finish(request, t0, timeout, d, diag) { (v, tEnd) -> (if (v) 1L else 0L) to tEnd }
            }
            OperationType.WRITE_STATE -> {
                val on = (request.payload.value ?: 0L) != 0L
                diag["matter.interaction"] = if (on) "INVOKE OnOff.On (0x01)" else "INVOKE OnOff.Off (0x00)"
                val d = CompletableDeferred<Result<Pair<Unit, Long>>>()
                val cb = object : ChipClusters.DefaultClusterCallback {
                    override fun onSuccess() { d.complete(Result.success(Unit to clock.nowNs())) }
                    override fun onError(error: Exception) { d.complete(Result.failure(error)) }
                }
                val t0 = clock.nowNs()
                if (on) cluster.on(cb) else cluster.off(cb)
                // Invoke response carries no attribute value; responseValue stays null (no fabricated echo).
                finish(request, t0, timeout, d, diag) { (_, tEnd) -> null to tEnd }
            }
            OperationType.SUBSCRIBE_STATE -> {
                val t = clock.nowNs()
                OperationResult.failure(request, ErrorCategory.UNSUPPORTED, t, t, "subscriptions not part of the measured workload", diag)
            }
        }
    }

    private suspend fun <T> finish(
        req: OperationRequest,
        t0: Long,
        timeoutMs: Long,
        d: CompletableDeferred<Result<T>>,
        diag: Map<String, String>,
        map: (T) -> Pair<Long?, Long>,
    ): OperationResult {
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
            ?: return OperationResult.failure(req, ErrorCategory.TIMEOUT, t0, clock.nowNs(), "interaction timeout", diag)
        return r.fold(
            onSuccess = { v ->
                val (value, tEnd) = map(v)
                OperationResult(req.operationId, protocol, true, ErrorCategory.NONE, t0, tEnd, responseValue = value, diagnostics = diag)
            },
            onFailure = { e ->
                val msg = e.message ?: e.toString()
                val cat = if (msg.contains("timeout", ignoreCase = true)) ErrorCategory.TIMEOUT else ErrorCategory.DEVICE_REJECTED
                OperationResult.failure(req, cat, t0, clock.nowNs(), msg, diag + ("matter.error" to msg))
            },
        )
    }

    // ---------- commissioning ----------
    override suspend fun commissionOnNetwork(params: OnNetworkCommissioning): CommissioningResult {
        val ctrl = controller()
        val tr = startCommissioning(params.nodeId)
        events.emit(LifecycleKind.COMMISSIONING_STARTED, "on-network ${params.ipAddress}:${params.port} node ${params.nodeId}")
        try {
            ctrl.pairDeviceWithAddress(params.nodeId, params.ipAddress, params.port, params.discriminator, params.setupPinCode, null)
        } catch (e: Exception) {
            tr.done.complete(false to "pairDeviceWithAddress threw: $e")
        }
        return awaitCommissioning(tr, 120_000)
    }

    override suspend fun commissionBleWifi(params: BleWifiCommissioning): CommissioningResult {
        if (!Permissions.bleGranted(context)) throw AdapterException(ErrorCategory.PERMISSION_DENIED, "Bluetooth permissions not granted")
        val ctrl = controller()
        val ble = bleManager!!
        val tr = startCommissioning(params.nodeId)
        events.emit(LifecycleKind.COMMISSIONING_STARTED, "BLE->Wi-Fi discriminator ${params.discriminator} node ${params.nodeId}")
        val device = scanForCommissionable(params.discriminator, params.scanTimeoutMs)
        if (device == null) {
            commissioning = null
            events.emit(LifecycleKind.COMMISSIONING_FAILED, "no commissionable device with discriminator ${params.discriminator}")
            return CommissioningResult(false, params.nodeId, tr.phases, "No BLE advertisement for discriminator ${params.discriminator}")
        }
        tr.phases["ble_scan_ns"] = clock.nowNs() - tr.t0
        val gatt = connectForCommissioning(device, ble) ?: run {
            commissioning = null
            return CommissioningResult(false, params.nodeId, tr.phases, "BLE GATT connection for commissioning failed")
        }
        tr.phases["ble_gatt_ready_ns"] = clock.nowNs() - tr.t0
        val connId = ble.addConnection(gatt)
        ble.setBleCallback(object : BleCallback {
            override fun onCloseBleComplete(connId: Int) { runCatching { gatt.close() } }
            override fun onNotifyChipConnectionClosed(connId: Int) { runCatching { gatt.close() } }
        })
        val creds = NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(params.ssid, params.wifiPassword))
        try {
            ctrl.pairDevice(gatt, connId, params.nodeId, params.setupPinCode, creds)
        } catch (e: Exception) {
            tr.done.complete(false to "pairDevice threw: $e")
        }
        return awaitCommissioning(tr, 180_000)
    }

    private fun startCommissioning(nodeId: Long): CommissioningTracker {
        check(commissioning == null) { "Commissioning already in progress" }
        return CommissioningTracker(nodeId, clock.nowNs()).also { commissioning = it }
    }

    private suspend fun awaitCommissioning(tr: CommissioningTracker, timeoutMs: Long): CommissioningResult {
        val (ok, detail) = withTimeoutOrNull(timeoutMs) { tr.done.await() } ?: (false to "commissioning timeout")
        commissioning = null
        val total = clock.nowNs() - tr.t0
        val phases = tr.phases + ("total_ns" to total)
        if (ok) {
            commissionedThisProcess += tr.nodeId
            events.emit(LifecycleKind.COMMISSIONED, "node ${tr.nodeId}", total, phases.mapValues { it.value.toString() })
        } else {
            events.emit(LifecycleKind.COMMISSIONING_FAILED, detail, total, phases.mapValues { it.value.toString() })
        }
        return CommissioningResult(ok, tr.nodeId, phases, detail, tr.errorCode)
    }

    private suspend fun scanForCommissionable(discriminator: Int, timeoutMs: Long): BluetoothDevice? {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: throw AdapterException(ErrorCategory.UNAVAILABLE, "BLE scanner unavailable (Bluetooth off?)")
        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(ParcelUuid(MATTER_BLE_SERVICE)) ?: return
                if (data.size < 3) return
                // Matter BLE advertisement service data: [0]=opcode, [1..2]=12-bit discriminator (LE) | adv version.
                val disc = ((data[2].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                if (disc == discriminator) found.complete(result.device)
            }
            override fun onScanFailed(errorCode: Int) { found.complete(null) }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MATTER_BLE_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        val d = withTimeoutOrNull(timeoutMs) { found.await() }
        runCatching { scanner.stopScan(cb) }
        return d
    }

    /** Connects GATT and forwards every callback to the CHIP BLE manager, as CHIPTool does. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private suspend fun connectForCommissioning(device: BluetoothDevice, ble: AndroidBleManager): BluetoothGatt? {
        val ready = CompletableDeferred<BluetoothGatt?>()
        val wrapped: BluetoothGattCallback = ble.callback
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                wrapped.onConnectionStateChange(gatt, status, newState)
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) gatt.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) ready.complete(null)
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                wrapped.onServicesDiscovered(gatt, status)
                gatt.requestMtu(247)
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                wrapped.onMtuChanged(gatt, mtu, status)
                ready.complete(gatt)
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
                wrapped.onCharacteristicChanged(gatt, characteristic)
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                wrapped.onCharacteristicRead(gatt, characteristic, status)
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                wrapped.onCharacteristicWrite(gatt, characteristic, status)
            override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
                wrapped.onDescriptorRead(gatt, descriptor, status)
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
                wrapped.onDescriptorWrite(gatt, descriptor, status)
            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) = wrapped.onReadRemoteRssi(gatt, rssi, status)
            override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) = wrapped.onReliableWriteCompleted(gatt, status)
        }
        val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val r = withTimeoutOrNull(20_000) { ready.await() }
        if (r == null) runCatching { gatt.close() }
        return r
    }

    override suspend fun dispose() {
        state.value = ConnectionState.IDLE
    }

    companion object {
        /** Matter CHIPoBLE service UUID (0xFFF6), from the Matter core specification. */
        val MATTER_BLE_SERVICE: UUID = UUID.fromString("0000FFF6-0000-1000-8000-00805F9B34FB")
        /** Matter test vendor id (0xFFF1) reserved for development. */
        const val TEST_VENDOR_ID = 0xFFF1
        /** Matter spec constants for the OnOff cluster/attribute (not device-specific). */
        const val ONOFF_CLUSTER = 0x0006L
        const val ONOFF_ATTRIBUTE = 0x0000L
    }
}

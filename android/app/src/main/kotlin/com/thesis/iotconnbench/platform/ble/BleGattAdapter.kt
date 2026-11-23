package com.thesis.iotconnbench.platform.ble

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
import android.os.Build
import android.os.ParcelUuid
import com.thesis.iotconnbench.core.contract.AckCorrelator
import com.thesis.iotconnbench.core.contract.CodecException
import com.thesis.iotconnbench.core.contract.TemplatePayloadCodec
import com.thesis.iotconnbench.core.domain.AdapterCapabilities
import com.thesis.iotconnbench.core.domain.AdapterConfig
import com.thesis.iotconnbench.core.domain.AdapterEvents
import com.thesis.iotconnbench.core.domain.AdapterException
import com.thesis.iotconnbench.core.domain.BleAdapterConfig
import com.thesis.iotconnbench.core.domain.BleReadMode
import com.thesis.iotconnbench.core.domain.ConnectReport
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.ConnectivityAdapter
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.OperationRequest
import com.thesis.iotconnbench.core.domain.OperationResult
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.platform.Permissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE/GATT adapter on the Android framework Bluetooth APIs (no third-party BLE library).
 *
 * Workload mapping (configurable, see BleAdapterConfig):
 *  WRITE_STATE: write encoded command to commandChar -> completion = correlated notification /
 *               indication on responseChar (device-level ack). The GATT write callback is only a
 *               link-layer acknowledgement and is recorded as diagnostic `gatt_write_cb_ns`.
 *  READ_STATE:  COMMAND mode as above, or GATT_READ mode: completion = onCharacteristicRead
 *               (value served by the device's GATT server; no op id echo, single outstanding op).
 *
 * Android's GATT client allows one outstanding operation at a time, so all GATT requests are
 * serialised with [gattMutex]. Callbacks from a previous connection generation are ignored and
 * logged as STALE_CALLBACK.
 */
@SuppressLint("MissingPermission") // every entry point checks Permissions.bleGranted first
class BleGattAdapter(
    private val context: Context,
    private val clock: MonotonicClock,
    private val events: AdapterEvents,
) : ConnectivityAdapter {

    override val protocol = ProtocolType.BLE
    override val capabilities = AdapterCapabilities(
        protocol = ProtocolType.BLE,
        executionMode = ExecutionMode.HARDWARE,
        supportedOperations = setOf(OperationType.READ_STATE, OperationType.WRITE_STATE),
        supportsSubscriptions = true,
        supportsPayloadPadding = true,
        requiresCommissioning = false,
        knownLimitations = listOf(
            "Payloads > ATT_MTU-3 use Android long writes (write-with-response only)",
            "Connection interval is chosen by the Android stack and is not controlled by the app",
            "GATT_READ mode has no op-id echo; correlation relies on GATT serialisation",
        ),
        adapterVersion = "ble-gatt-1.0.0",
    )

    private val state = MutableStateFlow(ConnectionState.IDLE)
    override fun observeState(): StateFlow<ConnectionState> = state.asStateFlow()

    private var cfg: BleAdapterConfig? = null
    private var codec: TemplatePayloadCodec? = null
    private var correlator: AckCorrelator? = null

    private val gattMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var generation = 0
    @Volatile private var pending: CompletableDeferred<GattEvent>? = null
    @Volatile private var connectWaiter: CompletableDeferred<GattEvent.ConnectionChanged>? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    @Volatile var negotiatedMtu: Int = 23; private set
    private var lastDevice: DeviceDescriptor? = null

    private sealed interface GattEvent {
        val tNs: Long
        data class ConnectionChanged(val status: Int, val newState: Int, override val tNs: Long) : GattEvent
        data class ServicesDiscovered(val status: Int, override val tNs: Long) : GattEvent
        data class MtuChanged(val mtu: Int, val status: Int, override val tNs: Long) : GattEvent
        data class DescriptorWritten(val status: Int, override val tNs: Long) : GattEvent
        data class CharWritten(val status: Int, override val tNs: Long) : GattEvent
        data class CharRead(val value: ByteArray, val status: Int, override val tNs: Long) : GattEvent
        data class RssiRead(val rssi: Int, val status: Int, override val tNs: Long) : GattEvent
        data class LinkLost(val status: Int, override val tNs: Long) : GattEvent
    }

    override fun configMetadata(): Map<String, String> =
        (cfg?.metadata() ?: emptyMap()) + mapOf("ble.negotiatedMtu" to negotiatedMtu.toString())

    override suspend fun initialize(config: AdapterConfig) {
        val c = config as? BleAdapterConfig ?: throw AdapterException(ErrorCategory.INTERNAL, "Not a BLE config")
        val missing = c.missingFields()
        if (missing.isNotEmpty()) throw AdapterException(ErrorCategory.NOT_CONFIGURED, "BLE contract incomplete: $missing")
        cfg = c
        codec = TemplatePayloadCodec(c.codec).also { correlator = AckCorrelator(it) }
        events.emit(LifecycleKind.INITIALIZED, "BLE configured")
    }

    private fun requireReady(): BleAdapterConfig {
        val c = cfg ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "BLE not configured")
        if (!Permissions.bleGranted(context)) throw AdapterException(ErrorCategory.PERMISSION_DENIED, "Bluetooth permissions not granted")
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw AdapterException(ErrorCategory.UNAVAILABLE, "No Bluetooth adapter on this phone")
        if (!bt.isEnabled) throw AdapterException(ErrorCategory.UNAVAILABLE, "Bluetooth is disabled")
        return c
    }

    override suspend fun discover(timeoutMs: Long): List<DeviceDescriptor> {
        val c = requireReady()
        val scanner = context.getSystemService(BluetoothManager::class.java)!!.adapter.bluetoothLeScanner
            ?: throw AdapterException(ErrorCategory.UNAVAILABLE, "BLE scanner unavailable")
        val found = LinkedHashMap<String, DeviceDescriptor>()
        val failed = CompletableDeferred<Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                if (c.deviceNameFilter.isNotBlank() && !name.contains(c.deviceNameFilter)) return
                synchronized(found) {
                    found[result.device.address] = DeviceDescriptor(
                        logicalId = result.device.address,
                        displayName = name.ifBlank { result.device.address },
                        supportedProtocols = setOf(ProtocolType.BLE),
                        address = result.device.address,
                        rssiDbm = result.rssi,
                    )
                }
            }
            override fun onScanFailed(errorCode: Int) { failed.complete(errorCode) }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(c.serviceUuid))).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val t0 = clock.nowNs()
        state.value = ConnectionState.DISCOVERING
        events.emit(LifecycleKind.SCAN_STARTED, "filter service=${c.serviceUuid}")
        scanner.startScan(filters, settings, cb)
        val err = withTimeoutOrNull(if (timeoutMs > 0) timeoutMs else c.scanTimeoutMs) { failed.await() }
        runCatching { scanner.stopScan(cb) }
        val dur = clock.nowNs() - t0
        state.value = if (gatt != null) ConnectionState.CONNECTED else ConnectionState.IDLE
        if (err != null) {
            events.emit(LifecycleKind.ERROR, "scan failed code=$err", dur)
            throw AdapterException(ErrorCategory.PROTOCOL_ERROR, "BLE scan failed, code $err")
        }
        val list = synchronized(found) { found.values.toList() }
        events.emit(LifecycleKind.SCAN_COMPLETED, "${list.size} device(s)", dur, mapOf("ble.scan_ns" to dur.toString()))
        return list
    }

    override suspend fun connect(device: DeviceDescriptor): ConnectReport {
        val c = requireReady()
        val address = device.address ?: throw AdapterException(ErrorCategory.NOT_CONFIGURED, "BLE device has no address")
        closeGatt()
        val bt = context.getSystemService(BluetoothManager::class.java)!!.adapter
        val remote: BluetoothDevice = bt.getRemoteDevice(address)
        val phases = linkedMapOf<String, Long>()
        val diag = linkedMapOf("ble.address" to address)
        state.value = if (lastDevice != null) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        events.emit(LifecycleKind.CONNECT_STARTED, address)
        val gen = ++generation
        val t0 = clock.nowNs()
        val waiter = CompletableDeferred<GattEvent.ConnectionChanged>().also { connectWaiter = it }
        val g = remote.connectGatt(context, false, Callback(gen), BluetoothDevice.TRANSPORT_LE)
            ?: fail("connectGatt returned null")
        gatt = g
        val cc = withTimeoutOrNull(c.connectTimeoutMs) { waiter.await() }
        if (cc == null || cc.newState != BluetoothProfile.STATE_CONNECTED) {
            closeGatt()
            state.value = ConnectionState.ERROR
            fail(if (cc == null) "connect timeout" else "connect failed status=${cc.status}", if (cc == null) ErrorCategory.TIMEOUT else ErrorCategory.PROTOCOL_ERROR)
        }
        phases["ble.connect_ns"] = cc.tNs - t0

        val tS = clock.nowNs()
        val sd = gattOp(c.connectTimeoutMs) { it.discoverServices() } as GattEvent.ServicesDiscovered
        if (sd.status != BluetoothGatt.GATT_SUCCESS) fail("service discovery status=${sd.status}")
        phases["ble.service_discovery_ns"] = sd.tNs - tS
        val service = g.getService(UUID.fromString(c.serviceUuid)) ?: fail("service ${c.serviceUuid} not found", ErrorCategory.NOT_CONFIGURED)
        commandChar = service.getCharacteristic(UUID.fromString(c.commandCharUuid)) ?: fail("command characteristic not found", ErrorCategory.NOT_CONFIGURED)
        responseChar = service.getCharacteristic(UUID.fromString(c.responseCharUuid)) ?: fail("response characteristic not found", ErrorCategory.NOT_CONFIGURED)
        stateChar = c.stateCharUuid.takeIf { it.isNotBlank() }?.let { service.getCharacteristic(UUID.fromString(it)) }
        if (c.readMode == BleReadMode.GATT_READ && stateChar == null) fail("state characteristic not found", ErrorCategory.NOT_CONFIGURED)
        events.emit(LifecycleKind.SERVICES_DISCOVERED, "service found", phases["ble.service_discovery_ns"])

        val tM = clock.nowNs()
        val mtu = gattOp(5_000) { it.requestMtu(c.requestedMtu) } as GattEvent.MtuChanged
        negotiatedMtu = if (mtu.status == BluetoothGatt.GATT_SUCCESS) mtu.mtu else 23
        phases["ble.mtu_ns"] = mtu.tNs - tM
        diag["ble.negotiatedMtu"] = negotiatedMtu.toString()
        events.emit(LifecycleKind.MTU_NEGOTIATED, "mtu=$negotiatedMtu status=${mtu.status}", phases["ble.mtu_ns"])

        val rc = responseChar!!
        val tN = clock.nowNs()
        if (!g.setCharacteristicNotification(rc, true)) fail("setCharacteristicNotification rejected")
        val cccd = rc.getDescriptor(CCCD_UUID) ?: fail("response characteristic has no CCCD (0x2902)", ErrorCategory.NOT_CONFIGURED)
        val useIndication = rc.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
            rc.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (useIndication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val dw = gattOp(5_000) { writeDescriptor(it, cccd, value) } as GattEvent.DescriptorWritten
        if (dw.status != BluetoothGatt.GATT_SUCCESS) fail("CCCD write status=${dw.status}")
        phases["ble.cccd_ns"] = dw.tNs - tN
        diag["ble.ackTransport"] = if (useIndication) "indication" else "notification"
        events.emit(LifecycleKind.NOTIFICATIONS_ENABLED, diag["ble.ackTransport"]!!, phases["ble.cccd_ns"])

        val rssi = gattOp(3_000) { it.readRemoteRssi() } as GattEvent.RssiRead
        if (rssi.status == BluetoothGatt.GATT_SUCCESS) diag["ble.rssiDbm"] = rssi.rssi.toString()

        phases["ble.total_setup_ns"] = clock.nowNs() - t0
        lastDevice = device
        state.value = ConnectionState.CONNECTED
        events.emit(LifecycleKind.CONNECTED, address, phases["ble.total_setup_ns"], phases.mapValues { it.value.toString() } + diag)
        return ConnectReport(phases, diag)
    }

    override suspend fun disconnect() {
        state.value = ConnectionState.DISCONNECTING
        gatt?.disconnect()
        delay(200)
        closeGatt()
        state.value = ConnectionState.DISCONNECTED
        events.emit(LifecycleKind.DISCONNECTED, "operator disconnect")
    }

    override suspend fun execute(request: OperationRequest): OperationResult {
        val c = cfg ?: return OperationResult.failure(request, ErrorCategory.NOT_CONFIGURED, clock.nowNs(), clock.nowNs(), "BLE not configured")
        val g = gatt
        if (state.value != ConnectionState.CONNECTED || g == null) {
            val t = clock.nowNs()
            return OperationResult.failure(request, ErrorCategory.NOT_CONNECTED, t, t, "state=${state.value}")
        }
        return if (request.type == OperationType.READ_STATE && c.readMode == BleReadMode.GATT_READ) gattRead(request)
        else commandOp(request, c)
    }

    private suspend fun gattRead(req: OperationRequest): OperationResult {
        val sc = stateChar!!
        return gattMutex.withLock {
            val d = CompletableDeferred<GattEvent>().also { pending = it }
            val t0 = clock.nowNs()
            if (gatt?.readCharacteristic(sc) != true) {
                pending = null
                return@withLock OperationResult.failure(req, ErrorCategory.PROTOCOL_ERROR, t0, clock.nowNs(), "readCharacteristic rejected")
            }
            when (val ev = withTimeoutOrNull(req.timeoutMs) { d.await() }) {
                null -> { pending = null; OperationResult.failure(req, ErrorCategory.TIMEOUT, t0, clock.nowNs(), "GATT read timeout") }
                is GattEvent.CharRead -> if (ev.status == BluetoothGatt.GATT_SUCCESS) {
                    val ack = codec!!.decodeAck(ev.value)
                    val v = ack?.value ?: ev.value.toString(Charsets.UTF_8).trim().toLongOrNull()
                    OperationResult(req.operationId, protocol, true, ErrorCategory.NONE, t0, ev.tNs, responseValue = v,
                        responseBytes = ev.value.size, diagnostics = mapOf("ble.raw_hex" to ev.value.toHex(), "ble.mtu" to negotiatedMtu.toString()))
                } else {
                    OperationResult.failure(req, ErrorCategory.DEVICE_REJECTED, t0, ev.tNs, "GATT read status=${ev.status}", mapOf("ble.gatt_status" to ev.status.toString()))
                }
                is GattEvent.LinkLost -> OperationResult.failure(req, ErrorCategory.DISCONNECTED_IN_FLIGHT, t0, ev.tNs, "link lost status=${ev.status}")
                else -> OperationResult.failure(req, ErrorCategory.INTERNAL, t0, clock.nowNs(), "unexpected GATT event $ev")
            }
        }
    }

    private suspend fun commandOp(req: OperationRequest, c: BleAdapterConfig): OperationResult {
        val bytes = try {
            codec!!.encode(req.operationId, req.type, req.payload)
        } catch (e: CodecException) {
            val t = clock.nowNs()
            return OperationResult.failure(req, ErrorCategory.VALIDATION_FAILED, t, t, e.message)
        }
        val maxSingle = negotiatedMtu - 3
        if (bytes.size > maxSingle && !c.writeWithResponse) {
            val t = clock.nowNs()
            return OperationResult.failure(req, ErrorCategory.VALIDATION_FAILED, t, t, "${bytes.size} B > ATT_MTU-3 ($maxSingle) without write-with-response")
        }
        val corr = correlator!!
        val ackDeferred = corr.register(req.operationId)
        val diag = linkedMapOf(
            "ble.mtu" to negotiatedMtu.toString(),
            "ble.write_type" to if (c.writeWithResponse) "with_response" else "without_response",
            "ble.long_write" to (bytes.size > maxSingle).toString(),
        )
        var t0 = 0L
        val writeFailure: OperationResult? = gattMutex.withLock {
            val d = CompletableDeferred<GattEvent>().also { pending = it }
            t0 = clock.nowNs()
            val accepted = writeCharacteristic(gatt!!, commandChar!!, bytes, c.writeWithResponse)
            if (!accepted) {
                pending = null
                return@withLock OperationResult.failure(req, ErrorCategory.PROTOCOL_ERROR, t0, clock.nowNs(), "writeCharacteristic rejected by stack", diag)
            }
            if (!c.writeWithResponse) { pending = null; return@withLock null }
            when (val ev = withTimeoutOrNull(req.timeoutMs) { d.await() }) {
                null -> { pending = null; OperationResult.failure(req, ErrorCategory.TIMEOUT, t0, clock.nowNs(), "GATT write callback timeout", diag) }
                is GattEvent.CharWritten -> {
                    diag["ble.gatt_write_cb_ns"] = (ev.tNs - t0).toString()
                    diag["ble.gatt_status"] = ev.status.toString()
                    if (ev.status != BluetoothGatt.GATT_SUCCESS) {
                        OperationResult.failure(req, ErrorCategory.DEVICE_REJECTED, t0, ev.tNs, "GATT write status=${ev.status}", diag)
                    } else null
                }
                is GattEvent.LinkLost -> OperationResult.failure(req, ErrorCategory.DISCONNECTED_IN_FLIGHT, t0, ev.tNs, "link lost", diag)
                else -> OperationResult.failure(req, ErrorCategory.INTERNAL, t0, clock.nowNs(), "unexpected GATT event", diag)
            }
        }
        if (writeFailure != null) { corr.unregister(req.operationId); return writeFailure }

        val remaining = req.timeoutMs - (clock.nowNs() - t0) / 1_000_000
        return when (val o = corr.await(req.operationId, ackDeferred, remaining)) {
            null -> OperationResult.failure(req, ErrorCategory.TIMEOUT, t0, clock.nowNs(), "no device acknowledgement", diag)
            is AckCorrelator.Outcome.Failed -> OperationResult.failure(req, o.category, t0, o.tDetectedNs, o.detail, diag)
            is AckCorrelator.Outcome.Acked -> if (o.ack.ok) {
                OperationResult(req.operationId, protocol, true, ErrorCategory.NONE, t0, o.tReceivedNs, o.ack.value,
                    requestBytes = bytes.size, responseBytes = o.responseBytes, diagnostics = diag)
            } else {
                OperationResult.failure(req, ErrorCategory.DEVICE_REJECTED, t0, o.tReceivedNs, "device status=${o.ack.statusText}", diag)
            }
        }
    }

    private suspend fun gattOp(timeoutMs: Long, start: (BluetoothGatt) -> Boolean): GattEvent = gattMutex.withLock {
        val g = gatt ?: fail("no GATT connection", ErrorCategory.NOT_CONNECTED)
        val d = CompletableDeferred<GattEvent>().also { pending = it }
        if (!start(g)) { pending = null; fail("GATT request rejected by stack") }
        val ev = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        when (ev) {
            null -> fail("GATT operation timeout", ErrorCategory.TIMEOUT)
            is GattEvent.LinkLost -> fail("link lost during setup (status=${ev.status})", ErrorCategory.DISCONNECTED_IN_FLIGHT)
            else -> ev
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean): Boolean {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, type) == BluetoothGatt.GATT_SUCCESS // BluetoothStatusCodes.SUCCESS == 0
        } else {
            ch.writeType = type
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        generation++
        runCatching { g.close() }
        pending?.complete(GattEvent.LinkLost(-1, clock.nowNs()))
        pending = null
        correlator?.failAll(ErrorCategory.DISCONNECTED_IN_FLIGHT, "GATT closed", clock.nowNs())
    }

    override suspend fun dispose() {
        closeGatt()
        state.value = ConnectionState.IDLE
    }

    private fun fail(msg: String, cat: ErrorCategory = ErrorCategory.PROTOCOL_ERROR): Nothing {
        events.emit(LifecycleKind.ERROR, msg)
        throw AdapterException(cat, msg)
    }

    private inner class Callback(private val gen: Int) : BluetoothGattCallback() {
        private fun current(g: BluetoothGatt, what: String): Boolean {
            if (gen == generation && g === gatt) return true
            events.emit(LifecycleKind.STALE_CALLBACK, "$what from generation $gen (current $generation)")
            return false
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val t = clock.nowNs()
            if (!current(g, "onConnectionStateChange")) { if (newState == BluetoothProfile.STATE_DISCONNECTED) g.close(); return }
            val ev = GattEvent.ConnectionChanged(status, newState, t)
            connectWaiter?.let { if (!it.isCompleted) { it.complete(ev); if (newState == BluetoothProfile.STATE_CONNECTED) return } }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state.value = ConnectionState.DISCONNECTED
                pending?.complete(GattEvent.LinkLost(status, t))
                correlator?.failAll(ErrorCategory.DISCONNECTED_IN_FLIGHT, "BLE link lost (status=$status)", t)
                events.emit(LifecycleKind.DISCONNECTED, "link lost status=$status", attrs = mapOf("ble.gatt_status" to status.toString()))
                gatt = null
                generation++
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val t = clock.nowNs()
            if (current(g, "onServicesDiscovered")) pending?.complete(GattEvent.ServicesDiscovered(status, t))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            val t = clock.nowNs()
            if (current(g, "onMtuChanged")) pending?.complete(GattEvent.MtuChanged(mtu, status, t))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val t = clock.nowNs()
            if (current(g, "onDescriptorWrite")) pending?.complete(GattEvent.DescriptorWritten(status, t))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val t = clock.nowNs()
            if (current(g, "onCharacteristicWrite")) pending?.complete(GattEvent.CharWritten(status, t))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val t = clock.nowNs()
            if (current(g, "onCharacteristicRead")) pending?.complete(GattEvent.CharRead(value, status, t))
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // API 33+ delivers the value-carrying overload; this legacy one is only reached via its default body.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val t = clock.nowNs()
            if (current(g, "onCharacteristicRead")) pending?.complete(GattEvent.CharRead(characteristic.value ?: ByteArray(0), status, t))
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val t = clock.nowNs()
            if (current(g, "onReadRemoteRssi")) pending?.complete(GattEvent.RssiRead(rssi, status, t))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val t = clock.nowNs() // t_end candidate: stamped at callback entry
            onNotification(g, characteristic, value, t)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Defensive: on API 33+ only the value-carrying overload must handle an ack, so a vendor stack
            // that also invoked this legacy overload could not double-count it as DUPLICATE_RESPONSE.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val t = clock.nowNs()
            onNotification(g, characteristic, characteristic.value ?: ByteArray(0), t)
        }

        private fun onNotification(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, t: Long) {
            if (!current(g, "onCharacteristicChanged")) return
            if (ch.uuid != responseChar?.uuid) {
                events.emit(LifecycleKind.STATE_REPORT, "notification on ${ch.uuid}", attrs = mapOf("ble.raw_hex" to value.toHex()))
                return
            }
            val (disp, _) = correlator?.onIncoming(value, t) ?: return
            when (disp) {
                AckCorrelator.Disposition.DUPLICATE -> events.emit(LifecycleKind.DUPLICATE_RESPONSE, value.toString(Charsets.UTF_8).take(200))
                AckCorrelator.Disposition.UNMATCHED -> events.emit(LifecycleKind.UNMATCHED_RESPONSE, value.toString(Charsets.UTF_8).take(200))
                AckCorrelator.Disposition.UNPARSEABLE -> events.emit(LifecycleKind.UNMATCHED_RESPONSE, "unparseable: ${value.toHex().take(200)}")
                AckCorrelator.Disposition.MATCHED -> Unit
            }
        }
    }

    companion object {
        /** Bluetooth SIG standard Client Characteristic Configuration Descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

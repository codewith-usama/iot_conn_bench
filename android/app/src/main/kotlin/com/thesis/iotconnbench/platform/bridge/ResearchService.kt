package com.thesis.iotconnbench.platform.bridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.thesis.iotconnbench.core.bench.BenchmarkRunner
import com.thesis.iotconnbench.core.bench.EventRouter
import com.thesis.iotconnbench.core.bench.ExperimentConfig
import com.thesis.iotconnbench.core.bench.ExperimentSession
import com.thesis.iotconnbench.core.bench.RunInfo
import com.thesis.iotconnbench.core.bench.RunProgress
import com.thesis.iotconnbench.core.domain.AdapterConfig
import com.thesis.iotconnbench.core.domain.AdapterEvents
import com.thesis.iotconnbench.core.domain.AdapterException
import com.thesis.iotconnbench.core.domain.AdapterRegistry
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.export.Exporter
import com.thesis.iotconnbench.core.mapping.Mappers
import com.thesis.iotconnbench.core.metrics.Comparability
import com.thesis.iotconnbench.core.metrics.Metrics
import com.thesis.iotconnbench.core.util.Json
import com.thesis.iotconnbench.platform.AndroidMonotonicClock
import com.thesis.iotconnbench.platform.AndroidWallClock
import com.thesis.iotconnbench.platform.MetadataCollector
import com.thesis.iotconnbench.platform.Permissions
import com.thesis.iotconnbench.platform.SecretStore
import com.thesis.iotconnbench.platform.ble.BleGattAdapter
import com.thesis.iotconnbench.platform.matter.BleWifiCommissioning
import com.thesis.iotconnbench.platform.matter.MatterControl
import com.thesis.iotconnbench.platform.matter.MatterFactory
import com.thesis.iotconnbench.platform.matter.OnNetworkCommissioning
import com.thesis.iotconnbench.platform.mqtt.MqttV5Adapter
import com.thesis.iotconnbench.platform.persistence.BenchDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Owns the research core on Android: adapters, runner, database and exports. Flutter talks to
 * this only through [ResearchChannel] with experiment-level commands; no per-operation calls
 * cross the bridge (spec §2.6, §3.2).
 */
class ResearchService(private val context: Context, private val emit: (Map<String, Any?>) -> Unit) {
    private val clock = AndroidMonotonicClock
    private val wall = AndroidWallClock
    val db = BenchDatabase(context)
    private val router = EventRouter(db)
    private val secrets = SecretStore(context)
    private val prefs = context.getSharedPreferences("config.v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val registry = AdapterRegistry()
    private val ble = BleGattAdapter(context, clock, AdapterEvents(ProtocolType.BLE, router, clock, wall))
    private val mqtt = MqttV5Adapter(clock, AdapterEvents(ProtocolType.MQTT_V5, router, clock, wall)) { secrets.get(SecretStore.MQTT_PASSWORD) }
    private val matter: MatterControl = MatterFactory.create(context, clock, AdapterEvents(ProtocolType.MATTER, router, clock, wall))
    private val runner = BenchmarkRunner(registry, clock, wall, db, router)

    private val devices = mutableMapOf<ProtocolType, DeviceDescriptor>()
    private val initErrors = mutableMapOf<ProtocolType, String>()
    private var session: ExperimentSession? = null
    private var runJob: Job? = null
    @Volatile var running = false; private set

    init {
        registry.register(ble); registry.register(mqtt); registry.register(matter)
        router.addListener { e -> emit(mapOf("type" to "lifecycle", "event" to Mappers.eventToMap(e))) }
        ProtocolType.values().forEach { p ->
            scope.launch { p.adapter().observeState().collect { s -> emit(mapOf("type" to "state", "protocol" to p.name, "state" to s.name)) } }
        }
        scope.launch { ProtocolType.values().forEach { applyConfig(it) } }
        @Suppress("UNCHECKED_CAST")
        prefs.getString("devices", null)?.let { j ->
            (Json.parse(j) as Map<String, Any?>).forEach { (k, v) -> devices[ProtocolType.valueOf(k)] = Mappers.deviceFromMap(v as Map<String, Any?>) }
        }
    }

    private fun ProtocolType.adapter() = registry.require(this)

    // ---------- configuration ----------
    @Suppress("UNCHECKED_CAST")
    fun rawConfig(p: ProtocolType): Map<String, Any?> =
        prefs.getString("cfg.${p.name}", null)?.let { Json.parse(it) as Map<String, Any?> } ?: emptyMap()

    fun typedConfig(p: ProtocolType): AdapterConfig = when (p) {
        ProtocolType.BLE -> Mappers.bleFromMap(rawConfig(p))
        ProtocolType.MQTT_V5 -> Mappers.mqttFromMap(rawConfig(p), secrets.has(SecretStore.MQTT_PASSWORD))
        ProtocolType.MATTER -> Mappers.matterFromMap(rawConfig(p))
    }

    fun configView(p: ProtocolType): Map<String, Any?> {
        val t = typedConfig(p)
        val m = when (t) {
            is com.thesis.iotconnbench.core.domain.BleAdapterConfig -> Mappers.bleToMap(t)
            is com.thesis.iotconnbench.core.domain.MqttAdapterConfig -> Mappers.mqttToMap(t)
            is com.thesis.iotconnbench.core.domain.MatterAdapterConfig -> Mappers.matterToMap(t)
        }
        return mapOf("config" to m, "missing" to t.missingFields(), "initError" to initErrors[p])
    }

    suspend fun saveConfig(p: ProtocolType, cfg: Map<String, Any?>): Map<String, Any?> {
        // Defence in depth: secrets are never accepted into the plain config store.
        val clean = cfg.filterKeys { !it.contains("password", ignoreCase = true) && !it.contains("pin", ignoreCase = true) }
        prefs.edit().putString("cfg.${p.name}", Json.write(clean)).apply()
        applyConfig(p)
        return configView(p)
    }

    private suspend fun applyConfig(p: ProtocolType) {
        val c = typedConfig(p)
        if (c.missingFields().isNotEmpty()) { initErrors[p] = "PLACEHOLDER: ${c.missingFields()}"; return }
        try {
            p.adapter().initialize(c)
            initErrors.remove(p)
        } catch (e: AdapterException) {
            initErrors[p] = "${e.category}: ${e.message}"
        } catch (e: Exception) {
            initErrors[p] = e.toString()
        }
    }

    fun setSecret(name: String, value: String?) {
        require(name == SecretStore.MQTT_PASSWORD || name == SecretStore.WIFI_PASSWORD) { "unknown secret slot" }
        secrets.put(name, value)
        if (name == SecretStore.MQTT_PASSWORD) scope.launch { applyConfig(ProtocolType.MQTT_V5) }
    }

    // ---------- status ----------
    fun status(): Map<String, Any?> {
        val meta = MetadataCollector.collect(context)
        val verified = db.readableDatabase.rawQuery(
            "SELECT protocol, COUNT(*) FROM operations WHERE success=1 AND is_warmup=0 AND execution_mode=? GROUP BY protocol",
            arrayOf(ExecutionMode.HARDWARE.name),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        return mapOf(
            "metadata" to meta,
            "blePermissionsGranted" to Permissions.bleGranted(context),
            "blePermissionsRequired" to Permissions.bleRequired(),
            "hasMqttPassword" to secrets.has(SecretStore.MQTT_PASSWORD),
            "hasWifiPassword" to secrets.has(SecretStore.WIFI_PASSWORD),
            "matter" to matter.controllerStatus(),
            "running" to running,
            "sessionId" to session?.sessionId,
            "adapters" to ProtocolType.values().associate { p ->
                val a = p.adapter()
                val c = typedConfig(p)
                p.name to mapOf(
                    "state" to a.observeState().value.name,
                    "configured" to (c.missingFields().isEmpty() && initErrors[p] == null),
                    "missing" to c.missingFields(),
                    "initError" to initErrors[p],
                    "capabilities" to Mappers.capabilitiesToMap(a.capabilities),
                    "device" to devices[p]?.let { Mappers.deviceToMap(it) },
                    "verifiedHardwareOps" to (verified[p.name] ?: 0L),
                )
            },
        )
    }

    // ---------- device & connection ----------
    suspend fun discover(p: ProtocolType, timeoutMs: Long) = p.adapter().discover(timeoutMs).map { Mappers.deviceToMap(it) }

    fun selectDevice(p: ProtocolType, d: DeviceDescriptor) {
        devices[p] = d
        prefs.edit().putString("devices", Json.write(devices.mapKeys { it.key.name }.mapValues { Mappers.deviceToMap(it.value) })).apply()
    }

    suspend fun connect(p: ProtocolType): Map<String, Any?> {
        val d = devices[p] ?: when (p) {
            ProtocolType.BLE -> throw AdapterException(com.thesis.iotconnbench.core.domain.ErrorCategory.NOT_CONFIGURED, "Scan and select a BLE device first")
            else -> p.adapter().discover(1_000).first().also { selectDevice(p, it) }
        }
        val r = p.adapter().connect(d)
        return mapOf("phasesNs" to r.phasesNs, "diagnostics" to r.diagnostics)
    }

    suspend fun disconnect(p: ProtocolType) = p.adapter().disconnect()

    suspend fun commissionMatter(args: Map<String, Any?>): Map<String, Any?> {
        val mode = args["mode"] as? String ?: "ON_NETWORK"
        val nodeId = (args["nodeId"] as Number).toLong()
        val disc = (args["discriminator"] as Number).toInt()
        val pin = (args["setupPinCode"] as Number).toLong()
        val res = if (mode == "BLE_WIFI") {
            val pw = secrets.get(SecretStore.WIFI_PASSWORD) ?: throw IllegalStateException("Store the Wi-Fi password first (secure storage)")
            matter.commissionBleWifi(BleWifiCommissioning(nodeId, disc, pin, args["ssid"] as String, pw))
        } else {
            matter.commissionOnNetwork(OnNetworkCommissioning(nodeId, args["ipAddress"] as String, (args["port"] as? Number)?.toInt() ?: 5540, disc, pin))
        }
        if (res.success) {
            val m = rawConfig(ProtocolType.MATTER).toMutableMap(); m["nodeId"] = nodeId
            saveConfig(ProtocolType.MATTER, m)
            selectDevice(ProtocolType.MATTER, DeviceDescriptor("matter-node-$nodeId", "Matter node $nodeId", setOf(ProtocolType.MATTER), address = nodeId.toString()))
        }
        return mapOf("success" to res.success, "nodeId" to res.nodeId, "phasesNs" to res.phasesNs, "detail" to res.detail, "errorCode" to res.errorCode)
    }

    // ---------- sessions & runs ----------
    fun startSession(operatorMeta: Map<String, String>): String {
        session?.let { endSession() }
        val s = ExperimentSession(
            sessionId = UUID.randomUUID().toString(),
            startedWallMs = wall.nowMs(),
            startedMonoNs = clock.nowNs(),
            // Operator-entered keys are already namespaced by the UI (device.*, network.*, broker.*, operator.*).
            metadata = MetadataCollector.collect(context) + Exporter.redact(operatorMeta),
        )
        db.saveSession(s)
        session = s
        router.sessionId = s.sessionId
        router.emit(LifecycleEvent(null, LifecycleKind.OPERATOR_NOTE, clock.nowNs(), wall.nowMs(), detail = "session started"))
        return s.sessionId
    }

    fun endSession() {
        val s = session ?: return
        s.endedWallMs = wall.nowMs()
        db.endSession(s.sessionId, s.endedWallMs!!)
        session = null
        router.sessionId = null
    }

    fun startRun(cfgMap: Map<String, Any?>): Map<String, Any?> {
        check(!running) { "A run is already in progress" }
        val s = session ?: throw IllegalStateException("Start a session first")
        val cfg: ExperimentConfig = Mappers.configFromMap(cfgMap)
        val problems = cfg.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        running = true
        runJob = scope.launch {
            emit(mapOf("type" to "runStarted"))
            try {
                val run = runner.run(s, cfg, devices.toMap(), onProgress = { p -> emit(progressMap(p)) })
                emit(mapOf("type" to "runFinished", "run" to Mappers.runToMap(run)))
            } catch (e: Throwable) {
                emit(mapOf("type" to "runFinished", "error" to e.toString()))
            } finally {
                running = false
            }
        }
        return mapOf("accepted" to true)
    }

    fun cancelRun() { runJob?.cancel() }

    private fun progressMap(p: RunProgress) = mapOf(
        "type" to "progress", "runId" to p.runId, "completed" to p.completed, "total" to p.total,
        "protocol" to p.currentProtocol?.name, "warmup" to p.currentWarmup,
        "last" to p.last?.let { Mappers.recordToMap(it) },
        "counts" to p.counts.mapKeys { it.key.name }.mapValues { (_, c) ->
            mapOf("attempts" to c.attempts, "success" to c.success, "failed" to c.failed, "timeouts" to c.timeouts)
        },
    )

    fun sessions() = db.sessions().map { s ->
        mapOf("sessionId" to s.sessionId, "startedWallMs" to s.startedWallMs, "endedWallMs" to s.endedWallMs,
            "runCount" to db.runs(s.sessionId).size, "metadata" to Exporter.redact(s.metadata))
    }

    fun runs(sessionId: String?) = db.runs(sessionId).map { Mappers.runToMap(it) }

    fun runDetails(runId: String): Map<String, Any?> {
        val run = db.run(runId) ?: throw IllegalArgumentException("unknown run")
        val ops = db.operations(runId = runId)
        val evs = db.events(run.sessionId).filter { it.runId == runId }
        return mapOf(
            "run" to Mappers.runToMap(run),
            "summary" to Metrics.summarize(runId, ops, evs).map { Mappers.summaryToMap(it) },
            "percentileMethod" to Metrics.PERCENTILE_METHOD,
            "eventCounts" to evs.groupingBy { it.kind.name }.eachCount(),
        )
    }

    fun records(runId: String, offset: Int, limit: Int) = db.operations(runId = runId, limit = limit, offset = offset).map { Mappers.recordToMap(it) }

    fun events(sessionId: String, limit: Int) = db.events(sessionId, limit).takeLast(limit).map { Mappers.eventToMap(it) }

    fun compare(a: String, b: String): Map<String, Any?> {
        val ra: RunInfo = db.run(a) ?: throw IllegalArgumentException("unknown run $a")
        val rb: RunInfo = db.run(b) ?: throw IllegalArgumentException("unknown run $b")
        val sa = db.session(ra.sessionId)?.metadata ?: emptyMap()
        val sb = db.session(rb.sessionId)?.metadata ?: emptyMap()
        return mapOf("warnings" to Comparability.warnings(ra, sa, rb, sb), "a" to runDetails(a), "b" to runDetails(b))
    }

    // ---------- export ----------
    /** Writes CSV + JSON + summary to app-specific storage and, if given, to a SAF tree the user picked. */
    fun export(sessionId: String, treeUri: Uri?): Map<String, Any?> {
        val s = db.session(sessionId) ?: throw IllegalArgumentException("unknown session")
        val runs = db.runs(sessionId)
        val ops = db.operations(sessionId = sessionId)
        val evs = db.events(sessionId)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(s.startedWallMs))
        val prefix = "iotbench_${stamp}_${sessionId.take(8)}"
        val files = linkedMapOf(
            "$prefix.records.csv" to Exporter.csv(ops),
            "$prefix.session.json" to Exporter.json(s, runs, ops, evs, wall.nowMs(), "$prefix.records.csv"),
            "$prefix.summary.txt" to humanSummary(s, runs, ops, evs),
        )
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        files.forEach { (name, text) -> File(dir, name).writeText(text) }
        val written = mutableListOf<String>()
        if (treeUri != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            files.forEach { (name, text) ->
                val mime = when { name.endsWith(".csv") -> "text/csv"; name.endsWith(".json") -> "application/json"; else -> "text/plain" }
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                    ?: throw IllegalStateException("Could not create $name in the chosen folder")
                context.contentResolver.openOutputStream(doc)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                written += doc.toString()
            }
        }
        return mapOf("appStorageDir" to dir.absolutePath, "files" to files.keys.toList(), "safUris" to written, "schemaVersion" to Exporter.SCHEMA_VERSION)
    }

    private fun humanSummary(s: ExperimentSession, runs: List<RunInfo>, ops: List<com.thesis.iotconnbench.core.bench.OperationRecord>, evs: List<LifecycleEvent>): String =
        buildString {
            appendLine("IoT connectivity benchmark — session ${s.sessionId}")
            appendLine("Schema ${Exporter.SCHEMA_VERSION}; RTT = Android-observed application-level round trip (monotonic clock).")
            appendLine("Percentiles: ${Metrics.PERCENTILE_METHOD}. Failures are excluded from latency statistics.")
            appendLine("Phone: ${s.metadata["phone.manufacturer"]} ${s.metadata["phone.model"]}, Android ${s.metadata["android.release"]} (API ${s.metadata["android.sdkInt"]})")
            for (r in runs) {
                appendLine()
                appendLine("Run ${r.runId} [${r.status}] op=${r.config.operation} labels=${r.config.conditionLabels} order=${r.config.order} n=${r.config.measuredCount} warmup=${r.config.warmupCount}")
                for (sum in Metrics.summarize(r.runId, ops, evs)) {
                    val l = sum.latency
                    appendLine(
                        "  ${sum.protocol}: attempts=${sum.attempts} ok=${sum.successes} fail=${sum.failures} timeouts=${sum.timeouts} " +
                            "success=${"%.3f".format(sum.successRate)} p50=${l.p50Ms?.let { "%.2f".format(it) }}ms " +
                            "p95=${l.p95Ms?.let { "%.2f".format(it) }}ms p99=${l.p99Ms?.let { "%.2f".format(it) }}ms max=${l.maxMs?.let { "%.2f".format(it) }}ms",
                    )
                    val modes = ops.filter { it.runId == r.runId && it.protocol == sum.protocol }.map { it.executionMode }.toSet()
                    if (ExecutionMode.SIMULATED in modes) appendLine("    WARNING: contains SIMULATED records")
                }
            }
        }
}

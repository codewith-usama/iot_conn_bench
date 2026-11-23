package com.thesis.iotconnbench.core

import com.thesis.iotconnbench.core.bench.BenchmarkRunner
import com.thesis.iotconnbench.core.bench.EventRouter
import com.thesis.iotconnbench.core.bench.ExperimentConfig
import com.thesis.iotconnbench.core.bench.ExperimentSession
import com.thesis.iotconnbench.core.bench.InMemoryRecordSink
import com.thesis.iotconnbench.core.bench.RunInfo
import com.thesis.iotconnbench.core.bench.RunStatus
import com.thesis.iotconnbench.core.domain.AdapterRegistry
import com.thesis.iotconnbench.core.domain.ConnectionState
import com.thesis.iotconnbench.core.domain.DeviceDescriptor
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.export.Exporter
import com.thesis.iotconnbench.core.metrics.Comparability
import com.thesis.iotconnbench.core.metrics.Metrics
import com.thesis.iotconnbench.core.select.SelectionMode
import com.thesis.iotconnbench.core.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RunnerTest {
    private lateinit var clock: FakeClock
    private lateinit var sink: InMemoryRecordSink
    private lateinit var registry: AdapterRegistry
    private lateinit var ble: FakeAdapter
    private lateinit var mqtt: FakeAdapter
    private lateinit var runner: BenchmarkRunner
    private var ids = 0
    private val session get() = ExperimentSession("S1", 0, 0, mapOf("phone.model" to "X"))
    private val devices = ProtocolType.values().associateWith { DeviceDescriptor("dev1", "d", setOf(it)) }

    @Before fun setUp() {
        clock = FakeClock()
        sink = InMemoryRecordSink()
        registry = AdapterRegistry()
        ble = FakeAdapter(ProtocolType.BLE, clock)
        mqtt = FakeAdapter(ProtocolType.MQTT_V5, clock)
        registry.register(ble); registry.register(mqtt)
        ids = 0
        runner = BenchmarkRunner(registry, clock, clock, sink, EventRouter(sink), idGen = { "id-${ids++}" }, guardMarginMs = 20)
    }

    private fun cfg(vararg p: ProtocolType) = ExperimentConfig(
        protocols = p.toList(), warmupCount = 2, measuredCount = 10, timeoutMs = 50, interOperationDelayMs = 0,
    )

    @Test fun fixedRunRecordsEveryOperationWithoutRetries() = runBlocking {
        ble.enqueue(FakeStep.Ok(5), FakeStep.Ok(5), FakeStep.Fail(ErrorCategory.TIMEOUT, 50))
        val run = runner.run(session, cfg(ProtocolType.BLE, ProtocolType.MQTT_V5), devices)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(24, sink.records.size)
        assertEquals("one execute per scheduled op (no hidden retries)", 12, ble.executed.size)
        assertEquals(4, sink.records.count { it.warmup })
        val bleMeasured = sink.records.filter { it.protocol == ProtocolType.BLE && !it.warmup }
        assertEquals(1, bleMeasured.count { it.errorCategory == ErrorCategory.TIMEOUT })
        assertTrue(sink.records.all { it.executionMode == ExecutionMode.SIMULATED })
        // write values toggle 1,0,1,0...
        assertEquals(listOf(1L, 0L, 1L), sink.records.take(3).map { it.requestedValue })
        val s = Metrics.summarize(run.runId, sink.records, sink.events).first { it.protocol == ProtocolType.BLE }
        assertEquals(10, s.attempts); assertEquals(9, s.successes); assertEquals(1, s.timeouts)
        assertEquals(10.0, s.latency.p50Ms!!, 1e-9)
        assertEquals(2, s.warmupExcluded)
    }

    @Test fun failuresAreNeverZeroLatencySamples() = runBlocking {
        repeat(12) { ble.enqueue(FakeStep.Fail(ErrorCategory.DEVICE_REJECTED, 1)) }
        val run = runner.run(session, cfg(ProtocolType.BLE), devices)
        val s = Metrics.summarize(run.runId, sink.records).single()
        assertEquals(0, s.latency.n)
        assertEquals(1.0, s.failureRate, 1e-9)
        assertTrue(sink.records.all { it.rttNs == null })
    }

    @Test fun notConnectedIsRecordedWithoutDispatch() = runBlocking {
        ble.state.value = ConnectionState.DISCONNECTED
        val run = runner.run(session, cfg(ProtocolType.BLE), devices)
        assertEquals(0, ble.executed.size)
        assertTrue(sink.records.filter { it.runId == run.runId }.all { it.errorCategory == ErrorCategory.NOT_CONNECTED })
    }

    @Test fun explicitReconnectBetweenOpsIsRecorded() = runBlocking {
        ble.enqueue(FakeStep.Ok(1), FakeStep.Disconnect)
        val run = runner.run(session, cfg(ProtocolType.BLE).copy(reconnectBetweenOps = true), devices)
        assertEquals(1, ble.connectCalls)
        assertEquals(1, sink.records.count { it.errorCategory == ErrorCategory.DISCONNECTED_IN_FLIGHT })
        val s = Metrics.summarize(run.runId, sink.records, sink.events).single()
        assertEquals(1, s.reconnectCount)
        assertEquals(50.0, s.meanReconnectMs!!, 1e-9)
    }

    @Test fun guardTimeoutCatchesHungAdapter() = runBlocking {
        ble.enqueue(FakeStep.Hang)
        val run = runner.run(session, cfg(ProtocolType.BLE).copy(warmupCount = 0, measuredCount = 2), devices)
        val first = sink.records.first { it.runId == run.runId }
        assertEquals(ErrorCategory.TIMEOUT, first.errorCategory)
        assertTrue(first.errorDetail!!.contains("guard"))
    }

    @Test fun validationRejectsWrongEchoedValue() = runBlocking {
        ble.enqueue(FakeStep.Ok(1, echoValue = 99))
        runner.run(session, cfg(ProtocolType.BLE).copy(warmupCount = 0, measuredCount = 1), devices)
        assertEquals(ErrorCategory.VALIDATION_FAILED, sink.records.single().errorCategory)
    }

    @Test fun cancellationRecordsInFlightOperation() = runBlocking {
        ble.enqueue(FakeStep.Ok(1), FakeStep.Hang)
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            runner.run(session, cfg(ProtocolType.BLE).copy(timeoutMs = 60_000), devices)
        }
        while (ble.executed.size < 2) yield()
        job.cancel()
        try { job.await(); fail("expected cancellation") } catch (e: CancellationException) { }
        assertEquals(ErrorCategory.CANCELLED, sink.records.last().errorCategory)
        assertEquals(RunStatus.CANCELLED, sink.runs.single().status)
        assertTrue(sink.events.any { it.kind == LifecycleKind.RUN_CANCELLED })
    }

    @Test fun naiveFailoverSwitchesAfterFailureAndRecordsDecisions() = runBlocking {
        ble.enqueue(FakeStep.Ok(5), FakeStep.Disconnect)
        val c = cfg(ProtocolType.BLE, ProtocolType.MQTT_V5).copy(
            warmupCount = 0, measuredCount = 4, selectionMode = SelectionMode.NAIVE_FAILOVER, multipathDeclared = true,
        )
        val run = runner.run(session, c, devices)
        val protos = sink.records.filter { it.runId == run.runId }.map { it.protocol }
        assertEquals(listOf(ProtocolType.BLE, ProtocolType.BLE, ProtocolType.MQTT_V5, ProtocolType.MQTT_V5), protos)
        assertEquals(4, sink.events.count { it.kind == LifecycleKind.SELECTION_DECISION })
        val sw = sink.events.single { it.kind == LifecycleKind.PATH_SWITCH }
        assertEquals(0L, sw.durationNs)
        assertTrue(sink.records.all { it.selectionReason != null })
        assertEquals(2, ble.executed.size) // failed op not retried on the new path
    }

    @Test fun multipathRequiresDeclarationAndModelOracleAreRefused() = runBlocking {
        val base = cfg(ProtocolType.BLE, ProtocolType.MQTT_V5)
        for (bad in listOf(
            base.copy(selectionMode = SelectionMode.NAIVE_FAILOVER),
            base.copy(selectionMode = SelectionMode.MODEL_BASED_SELECTOR, multipathDeclared = true),
            base.copy(selectionMode = SelectionMode.ORACLE, multipathDeclared = true),
            base.copy(operation = OperationType.SUBSCRIBE_STATE),
        )) {
            try { runner.run(session, bad, devices); fail("expected rejection for ${bad.selectionMode}") } catch (e: IllegalArgumentException) { }
        }
        assertTrue(sink.records.isEmpty())
    }

    @Test fun exportsAreVersionedParseableAndRecomputable() = runBlocking {
        for (i in 1..12) ble.enqueue(FakeStep.Ok(i.toLong()))
        val run = runner.run(session.copy(metadata = mapOf("phone.model" to "X", "mqtt.password" to "hunter2")), cfg(ProtocolType.BLE), devices)
        val csv = Exporter.csv(sink.records)
        val lines = csv.trimEnd().lines()
        assertEquals(Exporter.CSV_COLUMNS.joinToString(","), lines.first())
        assertEquals(13, lines.size)
        // Recompute p95 from the CSV only, independently of Metrics.summarize.
        val idx = Exporter.CSV_COLUMNS.indexOf("rtt_ns")
        val warm = Exporter.CSV_COLUMNS.indexOf("is_warmup")
        val rtts = lines.drop(1).map { splitCsv(it) }.filter { it[warm] == "false" }.map { it[idx].toLong() }.sorted()
        val p95 = rtts[Math.ceil(0.95 * rtts.size).toInt() - 1] / 1e6
        val s = Metrics.summarize(run.runId, sink.records).single()
        assertEquals(p95, s.latency.p95Ms!!, 1e-9)

        val json = Exporter.json(sink.sessions.firstOrNull() ?: session.copy(metadata = mapOf("mqtt.password" to "hunter2")),
            sink.runs, sink.records, sink.events, 0, "records.csv")
        assertFalse(json.contains("hunter2"))
        val doc = Json.parseObject(json)
        assertEquals(Exporter.SCHEMA_VERSION, doc["schema_version"])
        val runs = doc["runs"] as List<*>
        assertEquals(1, runs.size)
    }

    @Test fun csvQuotingIsRfc4180() {
        assertEquals("\"a,b\"", Exporter.csvField("a,b"))
        assertEquals("\"q\"\"x\"", Exporter.csvField("q\"x"))
        assertEquals("plain", Exporter.csvField("plain"))
    }

    @Test fun comparabilityWarnsOnDifferences() {
        val a = RunInfo("a", "S", cfg(ProtocolType.BLE), 0, 0, mapOf("ble.requestedMtu" to "247", "adapter.BLE.executionMode" to "HARDWARE"))
        val b = a.copy(runId = "b", config = a.config.copy(payloadSizeBytes = 64, conditionLabels = listOf("weak_signal")),
            adapterMetadata = mapOf("ble.requestedMtu" to "185", "adapter.BLE.executionMode" to "SIMULATED"))
        val w = Comparability.warnings(a, mapOf("phone.model" to "P1"), b, mapOf("phone.model" to "P2"))
        assertTrue(w.any { it.startsWith("Payload size") })
        assertTrue(w.any { it.startsWith("Condition labels") })
        assertTrue(w.any { it.startsWith("phone.model") })
        assertTrue(w.any { it.contains("ble.requestedMtu") })
        assertTrue(w.any { it.startsWith("Execution mode") })
        assertTrue(Comparability.warnings(a, emptyMap(), a, emptyMap()).isEmpty())
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString(); return out
    }
}

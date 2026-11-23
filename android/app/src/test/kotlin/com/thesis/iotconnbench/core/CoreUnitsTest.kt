package com.thesis.iotconnbench.core

import com.thesis.iotconnbench.core.bench.ExperimentConfig
import com.thesis.iotconnbench.core.bench.OrderStrategy
import com.thesis.iotconnbench.core.bench.ScheduleBuilder
import com.thesis.iotconnbench.core.contract.AckCorrelator
import com.thesis.iotconnbench.core.contract.CodecConfig
import com.thesis.iotconnbench.core.contract.CodecException
import com.thesis.iotconnbench.core.contract.TemplatePayloadCodec
import com.thesis.iotconnbench.core.domain.BleAdapterConfig
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.LogicalPayload
import com.thesis.iotconnbench.core.domain.MatterAdapterConfig
import com.thesis.iotconnbench.core.domain.MatterWriteMode
import com.thesis.iotconnbench.core.domain.MqttAdapterConfig
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.metrics.Metrics
import com.thesis.iotconnbench.core.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Test-only codec configuration. These strings exist only in tests, not in the app. */
val TEST_CODEC = CodecConfig(
    writeTemplate = """{"id":"{op_id}","op":"{op}","v":{value},"pad":"{pad}"}""",
    readTemplate = """{"id":"{op_id}","op":"{op}","pad":"{pad}"}""",
    ackOpIdField = "id",
    ackStatusField = "st",
    ackOkValue = "ok",
    ackValueField = "v",
)

class JsonTest {
    @Test fun roundTripNestedStructures() {
        val doc = mapOf("a" to 1L, "b" to listOf(true, null, 2.5), "s" to "q\"\\\n\u0001é")
        val parsed = Json.parse(Json.write(doc))
        assertEquals(doc, parsed)
    }

    @Test fun rejectsTrailingGarbage() {
        try { Json.parse("{} x"); fail("expected parse error") } catch (e: Json.ParseException) { }
    }

    @Test fun nonFiniteDoublesBecomeNull() {
        assertEquals("[null,null]", Json.write(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }
}

class CodecTest {
    private val codec = TemplatePayloadCodec(TEST_CODEC)

    @Test fun encodesWriteWithExactPaddedSize() {
        val b = codec.encode("op-1", OperationType.WRITE_STATE, LogicalPayload(1, targetSizeBytes = 100))
        assertEquals(100, b.size)
        val obj = Json.parseObject(b.toString(Charsets.UTF_8))
        assertEquals("op-1", obj["id"])
        assertEquals(1L, obj["v"])
    }

    @Test fun naturalSizeWithoutTarget() {
        val b = codec.encode("x", OperationType.READ_STATE, LogicalPayload())
        assertEquals("""{"id":"x","op":"READ_STATE","pad":""}""", b.toString(Charsets.UTF_8))
    }

    @Test fun rejectsTooSmallTarget() {
        try {
            codec.encode("op-1", OperationType.WRITE_STATE, LogicalPayload(1, targetSizeBytes = 5)); fail("expected")
        } catch (e: CodecException) { }
    }

    @Test fun writeRequiresValue() {
        try { codec.encode("o", OperationType.WRITE_STATE, LogicalPayload(null)); fail("expected") } catch (e: CodecException) { }
    }

    @Test fun decodesAckStatusAndValue() {
        val ok = codec.decodeAck("""{"id":"a","st":"ok","v":0}""".toByteArray())!!
        assertTrue(ok.ok); assertEquals("a", ok.operationId); assertEquals(0L, ok.value)
        val bad = codec.decodeAck("""{"id":"a","st":"err"}""".toByteArray())!!
        assertFalse(bad.ok)
        assertNull(codec.decodeAck("not json".toByteArray()))
    }

    @Test fun placeholdersReportedMissing() {
        val missing = CodecConfig().missingFields("c")
        assertTrue(missing.contains("c.writeTemplate"))
        assertTrue(missing.contains("c.ackOpIdField"))
        assertTrue(CodecConfig(writeTemplate = "no id", readTemplate = "{op_id}", ackOpIdField = "id")
            .missingFields("c").any { it.contains("must contain {op_id}") })
    }
}

class AdapterConfigTest {
    @Test fun defaultsArePlaceholdersAndInvalid() {
        assertTrue(BleAdapterConfig().missingFields().contains("ble.serviceUuid"))
        assertTrue(MqttAdapterConfig().missingFields().contains("mqtt.host"))
        assertTrue(MatterAdapterConfig().missingFields().any { it.startsWith("matter.nodeId") })
    }

    @Test fun insecureMqttNeedsExplicitOptIn() {
        val c = MqttAdapterConfig(host = "h", clientId = "c", commandTopic = "a", ackTopic = "b", codec = TEST_CODEC)
        assertTrue(c.missingFields().contains("mqtt.tlsOrExplicitInsecureLabOptIn"))
        assertTrue(c.copy(allowInsecureLab = true).missingFields().isEmpty())
    }

    @Test fun invalidUuidRejected() {
        val c = BleAdapterConfig(serviceUuid = "1234", commandCharUuid = "x", responseCharUuid = "y", codec = TEST_CODEC)
        assertTrue(c.missingFields().any { it.startsWith("ble.invalidUuid") })
    }

    @Test fun invokeOnOffRequiresOnOffCluster() {
        val c = MatterAdapterConfig(nodeId = 1, endpointId = 1, clusterId = 8, attributeId = 0, writeMode = MatterWriteMode.INVOKE_ONOFF)
        assertTrue(c.missingFields().any { it.contains("0x0006") })
    }
}

class CorrelatorTest {
    private val codec = TemplatePayloadCodec(TEST_CODEC)

    @Test fun matchesThenCountsDuplicate() = runBlocking {
        val c = AckCorrelator(codec)
        val d = c.register("a")
        val (disp, _) = c.onIncoming("""{"id":"a","st":"ok"}""".toByteArray(), 123L)
        assertEquals(AckCorrelator.Disposition.MATCHED, disp)
        val out = c.await("a", d, 100) as AckCorrelator.Outcome.Acked
        assertEquals(123L, out.tReceivedNs)
        assertEquals(AckCorrelator.Disposition.DUPLICATE, c.onIncoming("""{"id":"a","st":"ok"}""".toByteArray(), 124L).first)
        assertEquals(1L, c.duplicateCount)
    }

    @Test fun unknownIdIsUnmatched() {
        val c = AckCorrelator(codec)
        assertEquals(AckCorrelator.Disposition.UNMATCHED, c.onIncoming("""{"id":"zzz"}""".toByteArray(), 1).first)
        assertEquals(AckCorrelator.Disposition.UNPARSEABLE, c.onIncoming("xx".toByteArray(), 1).first)
    }

    @Test fun lateAckAfterTimeoutIsNotAccepted() = runBlocking {
        val c = AckCorrelator(codec)
        val d = c.register("late")
        assertNull(c.await("late", d, 10))
        assertEquals(AckCorrelator.Disposition.DUPLICATE, c.onIncoming("""{"id":"late"}""".toByteArray(), 5).first)
        assertEquals(0, c.inFlight())
    }

    @Test fun correlationHintUsedWhenPayloadHasNoId() = runBlocking {
        val c = AckCorrelator(codec)
        val d = c.register("hint")
        assertEquals(AckCorrelator.Disposition.MATCHED, c.onIncoming("""{"st":"ok"}""".toByteArray(), 9, "hint").first)
        assertTrue(c.await("hint", d, 10) is AckCorrelator.Outcome.Acked)
    }

    @Test fun failAllCompletesPending() = runBlocking {
        val c = AckCorrelator(codec)
        val d = c.register("x")
        c.failAll(ErrorCategory.DISCONNECTED_IN_FLIGHT, "lost", 77)
        val o = c.await("x", d, 10) as AckCorrelator.Outcome.Failed
        assertEquals(ErrorCategory.DISCONNECTED_IN_FLIGHT, o.category)
        assertEquals(77L, o.tDetectedNs)
    }
}

class MetricsTest {
    @Test fun nearestRankPercentiles() {
        val s = (1L..100L).toList()
        assertEquals(50L, Metrics.percentileNearestRank(s, 50.0))
        assertEquals(90L, Metrics.percentileNearestRank(s, 90.0))
        assertEquals(95L, Metrics.percentileNearestRank(s, 95.0))
        assertEquals(99L, Metrics.percentileNearestRank(s, 99.0))
        assertEquals(100L, Metrics.percentileNearestRank(s, 100.0))
        assertEquals(7L, Metrics.percentileNearestRank(listOf(7L), 99.0))
        // n=10: p95 rank = ceil(9.5)=10 -> max
        assertEquals(10L, Metrics.percentileNearestRank((1L..10L).toList(), 95.0))
        assertNull(Metrics.percentileNearestRank(emptyList(), 50.0))
    }

    @Test fun latencySummaryInMs() {
        val l = Metrics.latency(listOf(3_000_000L, 1_000_000L, 2_000_000L))
        assertEquals(1.0, l.minMs!!, 1e-9)
        assertEquals(2.0, l.meanMs!!, 1e-9)
        assertEquals(1.0, l.stdDevMs!!, 1e-9)
        assertEquals(3.0, l.maxMs!!, 1e-9)
        assertEquals(0, Metrics.latency(emptyList()).n)
    }
}

class ScheduleTest {
    private val ps = listOf(ProtocolType.BLE, ProtocolType.MQTT_V5, ProtocolType.MATTER)

    @Test fun fixedIsBlockedAndWarmupFirst() {
        val s = ScheduleBuilder.build(ExperimentConfig(protocols = ps, warmupCount = 2, measuredCount = 3))
        assertEquals(15, s.size)
        assertTrue(s.take(6).all { it.warmup })
        assertTrue(s.drop(6).none { it.warmup })
        assertEquals(listOf(ProtocolType.BLE, ProtocolType.BLE, ProtocolType.MQTT_V5), s.take(3).map { it.protocol })
    }

    @Test fun roundRobinInterleaves() {
        val s = ScheduleBuilder.build(ExperimentConfig(protocols = ps, warmupCount = 0, measuredCount = 2, order = OrderStrategy.ROUND_ROBIN))
        assertEquals(ps + ps, s.map { it.protocol })
    }

    @Test fun seededRandomIsDeterministicAndBalanced() {
        val c = ExperimentConfig(protocols = ps, warmupCount = 1, measuredCount = 20, order = OrderStrategy.SEEDED_RANDOM, randomSeed = 7)
        val a = ScheduleBuilder.build(c)
        assertEquals(a, ScheduleBuilder.build(c))
        assertTrue(a != ScheduleBuilder.build(c.copy(randomSeed = 8)))
        ps.forEach { p -> assertEquals(20, a.count { !it.warmup && it.protocol == p }) }
    }
}

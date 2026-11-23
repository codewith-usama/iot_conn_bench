package com.thesis.iotconnbench.core

import com.thesis.iotconnbench.core.bench.ExperimentConfig
import com.thesis.iotconnbench.core.bench.OrderStrategy
import com.thesis.iotconnbench.core.domain.BleAdapterConfig
import com.thesis.iotconnbench.core.domain.BleReadMode
import com.thesis.iotconnbench.core.domain.MatterAdapterConfig
import com.thesis.iotconnbench.core.domain.MatterWriteMode
import com.thesis.iotconnbench.core.domain.MqttAdapterConfig
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.mapping.Mappers
import com.thesis.iotconnbench.core.select.SelectionMode
import com.thesis.iotconnbench.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MappersTest {
    @Test fun experimentConfigRoundTripsThroughJson() {
        val c = ExperimentConfig(
            name = "n", protocols = listOf(ProtocolType.MQTT_V5, ProtocolType.BLE), operation = OperationType.READ_STATE,
            writeValues = listOf(3, 4), payloadSizeBytes = 128, warmupCount = 1, measuredCount = 2, timeoutMs = 3,
            interOperationDelayMs = 4, order = OrderStrategy.SEEDED_RANDOM, randomSeed = 9, conditionLabels = listOf("a", "b"),
            operatorNotes = "x", selectionMode = SelectionMode.NAIVE_FAILOVER, multipathDeclared = true, reconnectBetweenOps = true,
        )
        @Suppress("UNCHECKED_CAST")
        val back = Mappers.configFromMap(Json.parse(Json.write(Mappers.configToMap(c))) as Map<String, Any?>)
        assertEquals(c, back)
    }

    @Test fun flutterIntsAreAccepted() {
        // StandardMessageCodec delivers small integers as Int, large as Long.
        val c = Mappers.configFromMap(mapOf("protocols" to listOf("BLE"), "measuredCount" to 5, "timeoutMs" to 2500, "randomSeed" to 5_000_000_000L))
        assertEquals(5, c.measuredCount); assertEquals(2500L, c.timeoutMs); assertEquals(5_000_000_000L, c.randomSeed)
    }

    @Test fun adapterConfigsRoundTrip() {
        val ble = BleAdapterConfig(serviceUuid = "s", readMode = BleReadMode.GATT_READ, codec = TEST_CODEC)
        assertEquals(ble, Mappers.bleFromMap(Mappers.bleToMap(ble)))
        val mqtt = MqttAdapterConfig(host = "h", qos = 2, codec = TEST_CODEC)
        assertEquals(mqtt, Mappers.mqttFromMap(Mappers.mqttToMap(mqtt), hasStoredPassword = false))
        val matter = MatterAdapterConfig(nodeId = 7, endpointId = 1, clusterId = 6, attributeId = 0, writeMode = MatterWriteMode.INVOKE_ONOFF)
        assertEquals(matter, Mappers.matterFromMap(Mappers.matterToMap(matter)))
    }

    @Test fun mqttMapNeverContainsPassword() {
        val m = Mappers.mqttToMap(MqttAdapterConfig(host = "h", username = "u", hasStoredPassword = true))
        assertFalse(m.keys.any { it.contains("password", ignoreCase = true) && it != "hasStoredPassword" })
    }
}

package com.thesis.iotconnbench.core.metrics

import com.thesis.iotconnbench.core.bench.RunInfo

/**
 * Comparability check between runs (spec §8.5): returns human-readable warnings whenever the
 * workload, firmware, phone, protocol settings or conditions differ.
 */
object Comparability {
    private val sessionKeys = listOf(
        "phone.manufacturer", "phone.model", "android.sdkInt", "app.versionName", "app.sourceRevision",
        "device.boardModel", "device.firmwareVersion", "device.firmwareHash", "network.routerModel",
        "network.band", "network.channel",
    )
    private val protocolSettingPrefixes = listOf("ble.", "mqtt.", "matter.")

    fun warnings(
        a: RunInfo, aSession: Map<String, String>,
        b: RunInfo, bSession: Map<String, String>,
    ): List<String> = buildList {
        val ca = a.config
        val cb = b.config
        if (ca.operation != cb.operation) add("Operation differs: ${ca.operation} vs ${cb.operation}")
        if (ca.payloadSizeBytes != cb.payloadSizeBytes) add("Payload size differs: ${ca.payloadSizeBytes} vs ${cb.payloadSizeBytes}")
        if (ca.timeoutMs != cb.timeoutMs) add("Timeout differs: ${ca.timeoutMs} vs ${cb.timeoutMs} ms")
        if (ca.interOperationDelayMs != cb.interOperationDelayMs) add("Inter-operation delay differs")
        if (ca.order != cb.order) add("Protocol order strategy differs: ${ca.order} vs ${cb.order}")
        if (ca.conditionLabels.toSet() != cb.conditionLabels.toSet()) {
            add("Condition labels differ: ${ca.conditionLabels} vs ${cb.conditionLabels}")
        }
        if (ca.selectionMode != cb.selectionMode) add("Selection mode differs: ${ca.selectionMode} vs ${cb.selectionMode}")
        for (k in sessionKeys) {
            val va = aSession[k]
            val vb = bSession[k]
            if (va != vb) add("$k differs: ${va ?: "unknown"} vs ${vb ?: "unknown"}")
        }
        val keys = (a.adapterMetadata.keys + b.adapterMetadata.keys)
            .filter { k -> protocolSettingPrefixes.any { k.startsWith(it) } }
        for (k in keys.sorted()) {
            if (a.adapterMetadata[k] != b.adapterMetadata[k]) add("Protocol setting $k differs")
        }
        val modesA = a.adapterMetadata.filterKeys { it.endsWith(".executionMode") }.values.toSet()
        val modesB = b.adapterMetadata.filterKeys { it.endsWith(".executionMode") }.values.toSet()
        if (modesA != modesB) add("Execution mode differs (simulated vs hardware): $modesA vs $modesB")
    }
}

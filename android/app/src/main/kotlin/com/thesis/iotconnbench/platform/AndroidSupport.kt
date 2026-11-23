package com.thesis.iotconnbench.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.thesis.iotconnbench.BuildConfig
import com.thesis.iotconnbench.core.domain.MonotonicClock
import com.thesis.iotconnbench.core.domain.WallClock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The single measurement clock on Android (spec §6.1). */
object AndroidMonotonicClock : MonotonicClock {
    override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
}

object AndroidWallClock : WallClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Secrets (MQTT password, Wi-Fi password used only during Matter commissioning) encrypted with an
 * AES-256-GCM key held in the Android Keystore. Values are never logged or exported.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets.v1", Context.MODE_PRIVATE)
    private val alias = "iotconnbench.secrets.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            c.doFinal(ct).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(name: String) = prefs.contains(name)

    companion object {
        const val MQTT_PASSWORD = "mqtt.password"
        const val WIFI_PASSWORD = "wifi.commissioning.password"
    }
}

object Permissions {
    /** Runtime permissions needed for BLE on this API level (spec §5.1, §11). */
    fun bleRequired(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(context: Context, perms: List<String>): List<String> =
        perms.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun bleGranted(context: Context) = missing(context, bleRequired()).isEmpty()
}

/** Collects reproducibility metadata (spec §6.4). Never collects SSID passwords. */
object MetadataCollector {
    @SuppressLint("MissingPermission")
    fun collect(context: Context): Map<String, String> {
        val m = linkedMapOf<String, String>()
        m["phone.manufacturer"] = Build.MANUFACTURER
        m["phone.model"] = Build.MODEL
        m["phone.device"] = Build.DEVICE
        m["android.release"] = Build.VERSION.RELEASE
        m["android.sdkInt"] = Build.VERSION.SDK_INT.toString()
        m["android.securityPatch"] = Build.VERSION.SECURITY_PATCH
        m["app.applicationId"] = BuildConfig.APPLICATION_ID
        m["app.versionName"] = BuildConfig.VERSION_NAME
        m["app.versionCode"] = BuildConfig.VERSION_CODE.toString()
        m["app.buildType"] = BuildConfig.BUILD_TYPE
        m["app.sourceRevision"] = BuildConfig.SOURCE_REVISION
        m["app.matterSdk"] = BuildConfig.MATTER_SDK
        m["app.mqttClient"] = BuildConfig.MQTT_CLIENT
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        m["bluetooth.available"] = (bt != null).toString()
        m["bluetooth.enabled"] = (bt?.isEnabled == true).toString()
        m.putAll(wifi(context))
        return m
    }

    fun wifi(context: Context): Map<String, String> {
        val m = linkedMapOf<String, String>()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return m
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        m["network.wifiConnected"] = onWifi.toString()
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps?.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        }
        if (onWifi && info != null) {
            val f = info.frequency
            m["network.frequencyMHz"] = f.toString()
            m["network.band"] = when (f) { in 2400..2500 -> "2.4GHz"; in 4900..5900 -> "5GHz"; in 5925..7125 -> "6GHz"; else -> "unknown" }
            m["network.channel"] = channel(f)?.toString() ?: "unknown"
            m["network.linkSpeedMbps"] = info.linkSpeed.toString()
            m["network.rssiDbm"] = info.rssi.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) m["network.wifiStandard"] = info.wifiStandard.toString()
        }
        return m
    }

    private fun channel(f: Int): Int? = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5160..5885 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> null
    }
}

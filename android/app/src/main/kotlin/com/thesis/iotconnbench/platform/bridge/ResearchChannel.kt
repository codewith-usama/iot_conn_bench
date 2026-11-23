package com.thesis.iotconnbench.platform.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.thesis.iotconnbench.core.domain.AdapterException
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.mapping.Mappers
import com.thesis.iotconnbench.platform.Permissions
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Typed platform API (spec §3.2): experiment-level commands on MethodChannel
 * `iotconnbench/api`, and a single event stream on EventChannel `iotconnbench/events`
 * (state changes, lifecycle events, run progress). The Dart counterpart is lib/api/research_api.dart;
 * method names and argument keys are the shared contract (see docs/ARCHITECTURE.md).
 *
 * Decision: a hand-written typed channel instead of Pigeon-generated code, because code generation
 * requires running `dart run pigeon` and the contract is small. Both sides wrap it in typed
 * classes; no measured operation ever crosses this bridge.
 */
class ResearchChannel(private val activity: Activity, messenger: BinaryMessenger) : MethodChannel.MethodCallHandler {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var sink: EventChannel.EventSink? = null
    private val service = ResearchService(activity.applicationContext) { ev -> main.post { sink?.success(ev) } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val methods = MethodChannel(messenger, "iotconnbench/api")
    private var pendingPermission: MethodChannel.Result? = null
    private var pendingExport: Pair<String, MethodChannel.Result>? = null

    init {
        methods.setMethodCallHandler(this)
        EventChannel(messenger, "iotconnbench/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) { sink = events }
            override fun onCancel(arguments: Any?) { sink = null }
        })
    }

    @Suppress("UNCHECKED_CAST")
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val args = (call.arguments as? Map<String, Any?>) ?: emptyMap()
        fun proto() = ProtocolType.valueOf(args["protocol"] as String)
        scope.launch {
            try {
                val value: Any? = withContext(Dispatchers.Default) {
                    when (call.method) {
                        "getStatus" -> service.status()
                        "getConfig" -> service.configView(proto())
                        "saveConfig" -> service.saveConfig(proto(), args["config"] as Map<String, Any?>)
                        "setSecret" -> { service.setSecret(args["name"] as String, args["value"] as String?); true }
                        "discover" -> service.discover(proto(), (args["timeoutMs"] as? Number)?.toLong() ?: 8_000)
                        "selectDevice" -> { service.selectDevice(proto(), Mappers.deviceFromMap(args["device"] as Map<String, Any?>)); true }
                        "connect" -> service.connect(proto())
                        "disconnect" -> { service.disconnect(proto()); true }
                        "commissionMatter" -> service.commissionMatter(args)
                        "startSession" -> service.startSession((args["metadata"] as? Map<String, Any?>)?.mapValues { it.value?.toString() ?: "" } ?: emptyMap())
                        "endSession" -> { service.endSession(); true }
                        "startRun" -> service.startRun(args["config"] as Map<String, Any?>)
                        "cancelRun" -> { service.cancelRun(); true }
                        "listSessions" -> service.sessions()
                        "listRuns" -> service.runs(args["sessionId"] as String?)
                        "runDetails" -> service.runDetails(args["runId"] as String)
                        "records" -> service.records(args["runId"] as String, (args["offset"] as? Number)?.toInt() ?: 0, (args["limit"] as? Number)?.toInt() ?: 200)
                        "events" -> service.events(args["sessionId"] as String, (args["limit"] as? Number)?.toInt() ?: 500)
                        "compareRuns" -> service.compare(args["a"] as String, args["b"] as String)
                        "requestBlePermissions", "exportSession", "setKeepScreenOn" -> NEEDS_ACTIVITY
                        else -> NOT_IMPLEMENTED
                    }
                }
                when {
                    value === NOT_IMPLEMENTED -> result.notImplemented()
                    value === NEEDS_ACTIVITY -> activityCall(call, args, result)
                    else -> result.success(value)
                }
            } catch (e: AdapterException) {
                result.error(e.category.name, e.message, null)
            } catch (e: IllegalArgumentException) {
                result.error("INVALID_ARGUMENT", e.message, null)
            } catch (e: IllegalStateException) {
                result.error("INVALID_STATE", e.message, null)
            } catch (e: Exception) {
                result.error("INTERNAL", e.toString(), null)
            }
        }
    }

    private fun activityCall(call: MethodCall, args: Map<String, Any?>, result: MethodChannel.Result) {
        when (call.method) {
            "requestBlePermissions" -> {
                val missing = Permissions.missing(activity, Permissions.bleRequired())
                if (missing.isEmpty()) { result.success(true); return }
                pendingPermission = result
                activity.requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            }
            "exportSession" -> {
                val sessionId = args["sessionId"] as String
                if (args["pickFolder"] == false) {
                    scope.launch { result.success(withContext(Dispatchers.IO) { service.export(sessionId, null) }) }
                    return
                }
                pendingExport = sessionId to result
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT_TREE)
            }
            "setKeepScreenOn" -> {
                if (args["on"] == true) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                result.success(true)
            }
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQ_PERMISSIONS) return false
        pendingPermission?.success(grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
        pendingPermission = null
        return true
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_EXPORT_TREE) return false
        val (sessionId, result) = pendingExport ?: return true
        pendingExport = null
        val uri: Uri? = if (resultCode == Activity.RESULT_OK) data?.data else null
        scope.launch {
            try {
                result.success(withContext(Dispatchers.IO) { service.export(sessionId, uri) })
            } catch (e: Exception) {
                result.error("EXPORT_FAILED", e.toString(), null)
            }
        }
        return true
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        scope.cancel()
    }

    companion object {
        private val NOT_IMPLEMENTED = Any()
        private val NEEDS_ACTIVITY = Any()
        private const val REQ_PERMISSIONS = 4101
        private const val REQ_EXPORT_TREE = 4102
    }
}

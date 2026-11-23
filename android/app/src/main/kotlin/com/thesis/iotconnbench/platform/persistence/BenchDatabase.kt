package com.thesis.iotconnbench.platform.persistence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.thesis.iotconnbench.core.bench.ExperimentSession
import com.thesis.iotconnbench.core.bench.OperationRecord
import com.thesis.iotconnbench.core.bench.RecordSink
import com.thesis.iotconnbench.core.bench.RunInfo
import com.thesis.iotconnbench.core.bench.RunStatus
import com.thesis.iotconnbench.core.domain.ErrorCategory
import com.thesis.iotconnbench.core.domain.ExecutionMode
import com.thesis.iotconnbench.core.domain.LifecycleEvent
import com.thesis.iotconnbench.core.domain.LifecycleKind
import com.thesis.iotconnbench.core.domain.OperationType
import com.thesis.iotconnbench.core.domain.ProtocolType
import com.thesis.iotconnbench.core.select.SelectionMode
import com.thesis.iotconnbench.core.util.Json
import com.thesis.iotconnbench.core.mapping.Mappers

/**
 * Local experiment database on the framework SQLite API (decision: avoids adding Room + KSP to the
 * Flutter/AGP 9 toolchain; schema is small and explicit). Every operation record is written
 * immediately after the operation completes, so a crash loses at most the in-flight operation.
 */
class BenchDatabase(context: Context) : SQLiteOpenHelper(context, "iotconnbench.db", null, 1), RecordSink {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(
                session_id TEXT PRIMARY KEY, started_wall_ms INTEGER NOT NULL, started_mono_ns INTEGER NOT NULL,
                ended_wall_ms INTEGER, metadata_json TEXT NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE runs(
                run_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(session_id),
                config_json TEXT NOT NULL, adapter_metadata_json TEXT NOT NULL,
                started_wall_ms INTEGER NOT NULL, started_mono_ns INTEGER NOT NULL,
                ended_wall_ms INTEGER, ended_mono_ns INTEGER, status TEXT NOT NULL, status_detail TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE operations(
                operation_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, run_id TEXT NOT NULL REFERENCES runs(run_id),
                sequence INTEGER NOT NULL, protocol TEXT NOT NULL, operation_type TEXT NOT NULL, is_warmup INTEGER NOT NULL,
                success INTEGER NOT NULL, error_category TEXT NOT NULL, error_detail TEXT,
                t_start_ns INTEGER NOT NULL, t_end_ns INTEGER NOT NULL, rtt_ns INTEGER,
                requested_value INTEGER, response_value INTEGER, request_bytes INTEGER, response_bytes INTEGER,
                payload_size_target INTEGER, execution_mode TEXT NOT NULL, condition_labels TEXT NOT NULL,
                selection_mode TEXT NOT NULL, selection_reason TEXT, diagnostics_json TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX ops_run ON operations(run_id, sequence)")
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, run_id TEXT, protocol TEXT, kind TEXT NOT NULL,
                t_ns INTEGER NOT NULL, wall_ms INTEGER NOT NULL, duration_ns INTEGER, detail TEXT, attributes_json TEXT)""",
        )
        db.execSQL("CREATE INDEX events_session ON events(session_id, t_ns)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 only. Future migrations must preserve raw data (never drop tables).
    }

    // ---- RecordSink ----
    override fun saveSession(session: ExperimentSession) {
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("session_id", session.sessionId)
            put("started_wall_ms", session.startedWallMs)
            put("started_mono_ns", session.startedMonoNs)
            session.endedWallMs?.let { put("ended_wall_ms", it) }
            put("metadata_json", Json.write(session.metadata))
        })
    }

    fun endSession(sessionId: String, endedWallMs: Long) {
        writableDatabase.update("sessions", ContentValues().apply { put("ended_wall_ms", endedWallMs) }, "session_id=?", arrayOf(sessionId))
    }

    override fun saveRun(run: RunInfo) {
        writableDatabase.insertOrThrow("runs", null, runValues(run))
    }

    override fun updateRun(run: RunInfo) {
        writableDatabase.update("runs", runValues(run), "run_id=?", arrayOf(run.runId))
    }

    private fun runValues(run: RunInfo) = ContentValues().apply {
        put("run_id", run.runId)
        put("session_id", run.sessionId)
        put("config_json", Json.write(Mappers.configToMap(run.config)))
        put("adapter_metadata_json", Json.write(run.adapterMetadata))
        put("started_wall_ms", run.startedWallMs)
        put("started_mono_ns", run.startedMonoNs)
        run.endedWallMs?.let { put("ended_wall_ms", it) }
        run.endedMonoNs?.let { put("ended_mono_ns", it) }
        put("status", run.status.name)
        put("status_detail", run.statusDetail)
    }

    override fun record(op: OperationRecord) {
        writableDatabase.insertOrThrow("operations", null, ContentValues().apply {
            put("operation_id", op.operationId)
            put("session_id", op.sessionId)
            put("run_id", op.runId)
            put("sequence", op.sequence)
            put("protocol", op.protocol.name)
            put("operation_type", op.operationType.name)
            put("is_warmup", if (op.warmup) 1 else 0)
            put("success", if (op.success) 1 else 0)
            put("error_category", op.errorCategory.name)
            put("error_detail", op.errorDetail)
            put("t_start_ns", op.tStartNs)
            put("t_end_ns", op.tEndNs)
            op.rttNs?.let { put("rtt_ns", it) }
            op.requestedValue?.let { put("requested_value", it) }
            op.responseValue?.let { put("response_value", it) }
            op.requestBytes?.let { put("request_bytes", it) }
            op.responseBytes?.let { put("response_bytes", it) }
            op.payloadSizeTarget?.let { put("payload_size_target", it) }
            put("execution_mode", op.executionMode.name)
            put("condition_labels", op.conditionLabels.joinToString(";"))
            put("selection_mode", op.selectionMode.name)
            put("selection_reason", op.selectionReason)
            put("diagnostics_json", Json.write(op.diagnostics))
        })
    }

    override fun event(e: LifecycleEvent) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("session_id", e.sessionId)
            put("run_id", e.runId)
            put("protocol", e.protocol?.name)
            put("kind", e.kind.name)
            put("t_ns", e.tNs)
            put("wall_ms", e.wallTimeMs)
            e.durationNs?.let { put("duration_ns", it) }
            put("detail", e.detail)
            put("attributes_json", Json.write(e.attributes))
        })
    }

    // ---- Queries ----
    fun sessions(limit: Int = 50): List<ExperimentSession> =
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY started_wall_ms DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            generateSequence { if (c.moveToNext()) session(c) else null }.toList()
        }

    fun session(id: String): ExperimentSession? =
        readableDatabase.rawQuery("SELECT * FROM sessions WHERE session_id=?", arrayOf(id)).use { c -> if (c.moveToNext()) session(c) else null }

    private fun session(c: Cursor) = ExperimentSession(
        sessionId = c.str("session_id")!!,
        startedWallMs = c.long("started_wall_ms")!!,
        startedMonoNs = c.long("started_mono_ns")!!,
        metadata = strMap(c.str("metadata_json")),
        endedWallMs = c.long("ended_wall_ms"),
    )

    fun runs(sessionId: String? = null): List<RunInfo> {
        val (sql, args) = if (sessionId == null) "SELECT * FROM runs ORDER BY started_wall_ms DESC LIMIT 200" to null
        else "SELECT * FROM runs WHERE session_id=? ORDER BY started_wall_ms" to arrayOf(sessionId)
        return readableDatabase.rawQuery(sql, args).use { c -> generateSequence { if (c.moveToNext()) run(c) else null }.toList() }
    }

    fun run(id: String): RunInfo? =
        readableDatabase.rawQuery("SELECT * FROM runs WHERE run_id=?", arrayOf(id)).use { c -> if (c.moveToNext()) run(c) else null }

    @Suppress("UNCHECKED_CAST")
    private fun run(c: Cursor) = RunInfo(
        runId = c.str("run_id")!!,
        sessionId = c.str("session_id")!!,
        config = Mappers.configFromMap(Json.parse(c.str("config_json")!!) as Map<String, Any?>),
        startedWallMs = c.long("started_wall_ms")!!,
        startedMonoNs = c.long("started_mono_ns")!!,
        adapterMetadata = strMap(c.str("adapter_metadata_json")),
        endedWallMs = c.long("ended_wall_ms"),
        endedMonoNs = c.long("ended_mono_ns"),
        status = RunStatus.valueOf(c.str("status")!!),
        statusDetail = c.str("status_detail") ?: "",
    )

    fun operations(runId: String? = null, sessionId: String? = null, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<OperationRecord> {
        val where = when {
            runId != null -> "run_id=?" to arrayOf(runId)
            sessionId != null -> "session_id=?" to arrayOf(sessionId)
            else -> "1=1" to emptyArray()
        }
        val sql = "SELECT * FROM operations WHERE ${where.first} ORDER BY run_id, sequence LIMIT $limit OFFSET $offset"
        return readableDatabase.rawQuery(sql, where.second).use { c ->
            generateSequence {
                if (!c.moveToNext()) null else OperationRecord(
                    sessionId = c.str("session_id")!!,
                    runId = c.str("run_id")!!,
                    operationId = c.str("operation_id")!!,
                    sequence = c.long("sequence")!!.toInt(),
                    protocol = ProtocolType.valueOf(c.str("protocol")!!),
                    operationType = OperationType.valueOf(c.str("operation_type")!!),
                    warmup = c.long("is_warmup") == 1L,
                    success = c.long("success") == 1L,
                    errorCategory = ErrorCategory.valueOf(c.str("error_category")!!),
                    errorDetail = c.str("error_detail"),
                    tStartNs = c.long("t_start_ns")!!,
                    tEndNs = c.long("t_end_ns")!!,
                    rttNs = c.long("rtt_ns"),
                    requestedValue = c.long("requested_value"),
                    responseValue = c.long("response_value"),
                    requestBytes = c.long("request_bytes")?.toInt(),
                    responseBytes = c.long("response_bytes")?.toInt(),
                    payloadSizeTarget = c.long("payload_size_target")?.toInt(),
                    executionMode = ExecutionMode.valueOf(c.str("execution_mode")!!),
                    conditionLabels = c.str("condition_labels")!!.split(';').filter { it.isNotEmpty() },
                    selectionMode = SelectionMode.valueOf(c.str("selection_mode")!!),
                    selectionReason = c.str("selection_reason"),
                    diagnostics = strMap(c.str("diagnostics_json")),
                )
            }.toList()
        }
    }

    fun events(sessionId: String, limit: Int = 100_000): List<LifecycleEvent> =
        readableDatabase.rawQuery("SELECT * FROM events WHERE session_id=? ORDER BY t_ns LIMIT $limit", arrayOf(sessionId)).use { c ->
            generateSequence {
                if (!c.moveToNext()) null else LifecycleEvent(
                    protocol = c.str("protocol")?.let { ProtocolType.valueOf(it) },
                    kind = LifecycleKind.valueOf(c.str("kind")!!),
                    tNs = c.long("t_ns")!!,
                    wallTimeMs = c.long("wall_ms")!!,
                    durationNs = c.long("duration_ns"),
                    detail = c.str("detail") ?: "",
                    attributes = strMap(c.str("attributes_json")),
                    sessionId = c.str("session_id"),
                    runId = c.str("run_id"),
                )
            }.toList()
        }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    @Suppress("UNCHECKED_CAST")
    private fun strMap(json: String?): Map<String, String> =
        if (json.isNullOrBlank()) emptyMap() else (Json.parse(json) as Map<String, Any?>).mapValues { it.value?.toString() ?: "" }
}

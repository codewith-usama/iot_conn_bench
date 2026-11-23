# Export schema v1.0.0

Exports are produced per session from *Results → Export*. Three files share a prefix
`iotbench_<yyyyMMdd_HHmmss>_<session8>`:

| File | Content |
|---|---|
| `.records.csv` | one row per operation (warm-up rows included, flagged) |
| `.session.json` | session metadata, per-run config snapshot, adapter metadata, summaries, lifecycle events |
| `.summary.txt` | human-readable summary |

Files are written to the folder chosen via the Storage Access Framework (no storage permission) and
always to `Android/data/com.thesis.iotconnbench/files/exports/` (pull with
`adb pull /sdcard/Android/data/com.thesis.iotconnbench/files/exports`).

Every CSV row and the JSON root carry `schema_version`. A change to any column's meaning or order
bumps it. Secrets are never inputs to the exporter, and keys matching
password/secret/token/passcode/setup code/pairing code/credential/PSK/fabric key/IPK/NOC are
additionally replaced with `[REDACTED]`.

## CSV columns

| Column | Type | Meaning |
|---|---|---|
| schema_version | string | `1.0.0` |
| session_id, run_id, operation_id | UUID | independent identifiers; many ops ≠ many runs |
| sequence | int | position in the run schedule |
| protocol | BLE / MQTT_V5 / MATTER | path that executed the op |
| operation_type | READ_STATE / WRITE_STATE | |
| is_warmup | bool | excluded from primary statistics |
| success | bool | device-level completion validated |
| error_category | enum | NONE, TIMEOUT, NOT_CONNECTED, DISCONNECTED_IN_FLIGHT, PROTOCOL_ERROR, DEVICE_REJECTED, VALIDATION_FAILED, UNSUPPORTED, NOT_CONFIGURED, PERMISSION_DENIED, UNAVAILABLE, CANCELLED, INTERNAL |
| error_detail | string | |
| t_start_ns, t_end_ns | int64 | `SystemClock.elapsedRealtimeNanos()` (monotonic, phone only) |
| rtt_ns | int64 / empty | `t_end_ns - t_start_ns` for successes; **empty for failures** |
| requested_value, response_value | int / empty | logical state written / reported by device |
| request_bytes, response_bytes | int / empty | encoded sizes (BLE/MQTT) |
| payload_size_target | int / empty | configured target size |
| execution_mode | HARDWARE / SIMULATED | SIMULATED only for unit-test doubles |
| condition_labels | `;`-separated | operator labels, e.g. `baseline;run2` |
| selection_mode | FIXED / NAIVE_FAILOVER / … | |
| selection_reason | string | policy decision reason (multi-path only) |
| diagnostics_json | JSON object | protocol diagnostics, e.g. `ble.mtu`, `ble.gatt_write_cb_ns`, `mqtt.qos`, `mqtt.broker_ack_ns`, `mqtt.puback_reason`, `matter.get_device_ptr_ns`, `matter.interaction` |

Timestamps from different sessions/app processes share the boot-time origin only while the phone is
not rebooted; compare durations, not absolute values, across sessions.

## JSON structure

```text
{ schema_version, exported_at_wall_ms,
  timing: { clock, rtt_definition, percentile_method, throughput_window, throughput_busy },
  records_csv_file,
  session: { session_id, started_wall_ms, started_mono_ns, ended_wall_ms, metadata{phone.*, android.*, app.*, bluetooth.*, network.*, device.*, broker.*, operator.*} },
  runs: [ { run_id, status, status_detail, started/ended wall+mono, config{...}, adapter_metadata{ble.*, mqtt.*, matter.*, adapter.*.version/executionMode/limitations, device.*},
            summary: [ { protocol, attempts, successes, failures, timeouts, success_rate, failure_rate, failures_by_category,
                         latency_ms{n,min,mean,std_dev,p50,p90,p95,p99,max}, throughput_window_ops_per_s, throughput_busy_ops_per_s,
                         reconnect_count, mean_reconnect_ms, path_switch_count, mean_path_switch_ms, warmup_excluded } ],
            operation_count } ],
  lifecycle_events: [ { run_id, protocol, kind, t_ns, wall_ms, duration_ns, detail, attributes } ] }
```

## Statistics definitions

- Measured = `is_warmup == false`; latency statistics use measured **successes** only.
- Percentiles: nearest-rank, `rank = ceil(p/100·n)`, value = `sorted[rank-1]`.
- `std_dev`: sample standard deviation (n−1).
- Throughput: *busy* = successes / Σ(t_end−t_start); *window* = successes / (max t_end − min t_start)
  over that protocol's measured ops (includes delays and, for interleaved orders, other protocols).
- Reconnection duration: `RECONNECTED` events with `duration_ns` in the run.
- Path-switch duration: `PATH_SWITCH.duration_ns` = decision time of the new path − end of the last
  failed op.

## Independent recomputation

```bash
python3 tools/analysis/recompute_metrics.py <prefix>.records.csv <prefix>.session.json
```

recomputes every summary from the CSV alone and exits 1 on any mismatch.
`docs/sample_export/` contains a **SIMULATED** example (unit-test doubles, not experimental data)
that passes this check.

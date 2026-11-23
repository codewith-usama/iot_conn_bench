# Testing strategy and hardware validation

## Layers

| Layer | Where | How to run | Uses hardware |
|---|---|---|---|
| Core unit tests (39): JSON, codec, correlator (duplicates, late acks, link loss), configs/placeholders, percentiles, schedules, runner (no retries, timeouts, guard timeout, not-connected, cancellation, explicit reconnect, validation, failover decisions, refusal of model/oracle/undeclared multipath), export (schema, quoting, redaction, recomputation), comparability, mappers | `android/app/src/test/kotlin` | `cd android && ./gradlew :app:testDebugUnitTest` | no (SIMULATED doubles) |
| Flutter tests: model serialisation, readiness states, widget flow with a fake API | `test/` | `flutter test` | no |
| Static analysis | | `flutter analyze` | no |
| Build (with and without Matter) | | `flutter build apk --debug`; `./gradlew :app:compileDebugKotlin -PenableMatter=false` | no |
| Export recomputation | `tools/analysis/recompute_metrics.py` | see EXPORT_SCHEMA.md | no |
| Integration + hardware acceptance | phone + ESP32 + broker | procedure below | **yes** |

`bash tools/verify.sh` runs every non-hardware layer and writes logs to `build_logs/`.

Adapter-level tests against real radio stacks are not possible on the JVM; BLE GATT ordering,
MQTT correlation and duplicate/late acknowledgements are covered through the shared
`AckCorrelator` tests and the hardware procedure. Matter controller lifecycle is only testable on a
device with the JNI library loaded.

## Hardware acceptance procedure (spec §12.4) — per path

Record each step's outcome in `ACCEPTANCE_REPORT.md` with phone model, board model and firmware hash.

1. **Discover/identify** — BLE: Setup → Scan shows the ESP32 (service UUID filter). MQTT: broker log
   shows phone connect with `p5`. Matter: commissionable advertisement found (BLE→Wi-Fi) or IP known.
2. **Connect/commission** — Connect (BLE, MQTT) / Commission + Connect (Matter). CONNECTED event
   with setup phase durations.
3. **Read known initial state** — Benchmark: READ_STATE, warm-up 0, measured 1. Expect success and
   `response_value` = device state (BLE/MQTT need `ackValueField`).
4. **Write known value** — WRITE_STATE, write values `1`, measured 1. LED on.
5. **Validate device-level ack** — record success; MQTT: confirm the ack on the ack topic came from the
   ESP32 (mosquitto_sub); BLE: notification on the response characteristic.
6. **Read back** — READ_STATE returns 1.
7. **Disable the path** — power off ESP32 radio / stop broker / unplug board during a run.
8. **Failure recorded** — DISCONNECTED event + `DISCONNECTED_IN_FLIGHT`/`TIMEOUT`/`NOT_CONNECTED`
   records; nothing reported as success.
9. **Reconnect** — Connect again (or explicit reconnect option); RECONNECTED duration recorded; next
   ops succeed.
10. **Export** — export the session and run `recompute_metrics.py`; inspect raw rows.

Cross-path check: perform step 4 on one path and step 6 on another path to show all paths act on
the same logical state (only for multipath firmware).

## Appendix B feasibility proofs

Mark each proof complete only with the exported session as evidence:
BLE write + device ack + read-back; MQTT 5 (`p5` in broker log, `MQTT_5_0` in metadata) + ESP32
ack; Matter commissioned **by this app** + OnOff invoke + read over Wi-Fi; each with Kotlin
monotonic start/end timestamps, op id, result and session metadata in the export.

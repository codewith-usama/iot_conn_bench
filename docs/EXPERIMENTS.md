# Experiment execution guide

## Before every session

1. Phone: charging, screen on, app in the foreground (the app keeps the screen on during runs; Android
   may throttle background apps, so measurements taken while backgrounded are not valid). Disable
   battery optimisation for the app; airplane-mode off; only the lab Wi-Fi saved.
2. Dashboard: check every path shows *configured* and the radios are on.
3. Start a session with board model/revision, firmware version + hash, router model, SSID label,
   broker version/location, notes. Phone/Android/app/Wi-Fi band/channel/RSSI are captured
   automatically.
4. Setup: connect each path to be measured. Connection setup times are recorded as events, separate
   from operation RTT.

## A run

Benchmark tab → choose protocols, operation (write toggles through *write values*, e.g. `1,0`),
payload size (BLE/MQTT only; Matter ignores it), warm-up (default 10, excluded from statistics),
measured count, timeout, inter-op delay, order (fixed / round-robin / seeded random), condition
labels, notes → *Start run*. *Stop* cancels safely: the in-flight op is recorded as CANCELLED.

No retries are performed. With *Explicit reconnect between operations* the runner reconnects a
disconnected path before the next op, records RECONNECT_STARTED/RECONNECTED with duration, and the
failed op stays failed.

Recommended pilot: per path 10 warm-up + 100 measured writes at 200 ms delay, three independent
sessions (re-start the session, reconnect), round-robin order with a fixed seed when comparing
paths in one run. Treat sessions — not operations — as independent replicates.

## Multipath / failover

Only enable *Naive failover* when the firmware genuinely exposes the same logical device on several
paths (one board running several stacks, or a documented multi-board bridge). You must tick the
confirmation; the run metadata records `multipath_declared=true`. Every decision is logged
(`SELECTION_DECISION` with features and reason) and switches produce `PATH_SWITCH` events.
Model-based selection is disabled until a trained policy with provenance is supplied; *Oracle* is
offline-analysis only.

## Impairments (spec §10)

The app records the operator's label; it does not create or verify impairments itself. For each
impairment fill this table in the thesis log and the run notes:

| Label | How created (example) | Paths expected to be affected | Verification | Duration / recovery |
|---|---|---|---|---|
| `baseline` | quiet lab, phone 1 m from ESP32 and AP | — | RSSI in metadata | — |
| `wifi_contention` | MacBook `iperf3 -c <server> -u -b 50M` on same AP/channel | MQTT, Matter | iperf3 report; AP channel utilisation | fixed seconds; stop iperf3 |
| `weak_signal` | move phone/ESP32 to marked distance / attenuating enclosure | per placement | `network.rssiDbm`, `ble.rssiDbm` | restore position |
| `ble_degradation` | distance/metal shielding for BLE only | BLE | `ble.rssiDbm` at connect | restore |
| `wifi_disconnection` | power-cycle AP or block ESP32 MAC | MQTT, Matter (BLE should be unaffected) | DISCONNECTED events, router log | AP back up; reconnect |
| `ble_disconnection` | power down/reset BLE stack on ESP32 or move out of range | BLE | GATT DISCONNECTED event + status | reconnect |
| `broker_unavailable` | stop mosquitto | MQTT only | broker stopped log | restart; reconnect |
| `device_reboot` | press EN/reset on ESP32 | all on that board | boot log over USB serial | wait for boot; reconnect/CASE |

Always note whether other paths were affected (e.g. Wi-Fi contention also affects BLE coexistence on
the ESP32's shared 2.4 GHz radio).

## After the session

Results → select session → *Export CSV + JSON*. Run
`python3 tools/analysis/recompute_metrics.py` on the files. Use *Compare selected* only for runs
whose comparison shows no warnings, or report the warnings.

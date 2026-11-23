# Matter over Wi-Fi: controller integration and commissioning guide

## Feasibility audit (spec §5.3, Stage 4)

| Option | Result |
|---|---|
| connectedhomeip Android controller built from source (`scripts/build/build_examples.py --target android-arm64-chip-tool`) | Official path. Needs Linux/macOS + NDK + pigweed bootstrap (~hours, tens of GB). Not attempted in this environment. Fallback if the AAR below fails. |
| `com.google.matter:matter-android-demo-sdk:1.0` (Maven Central, Feb 2024, ~20 MB AAR) | **Selected.** Contains the connectedhomeip `CHIPController`/`AndroidPlatform`/`ChipClusters` JARs and `.so` libraries; used by Google's *Sample Apps for Matter* (3p-ecosystem). Published "for development and example purposes only". minSdk 27. |
| Google Home Mobile SDK (`play-services-home`) | Commissioning via Google Play services into the Google fabric (+ optional own fabric). Requires Google Home Developer Console setup, does not by itself provide an interaction-model client. Not used. |
| Laptop `chip-tool` | Explicitly not acceptable as Android-controller evidence (spec §5.3). Useful only to cross-check the device. |

### Which connectedhomeip API the AAR exposes

Its exact version is not published. Evidence gathered: the demo AAR has no `pairDeviceWithCode`
(added for v1.2) and a 7-argument `AndroidChipPlatform` constructor (v1.0 and v1.1). The adapter
therefore uses only calls whose signatures are **identical in v1.0-branch and v1.1-branch** (verified
against the upstream sources) and which Google's sample app uses with this AAR:

`ChipDeviceController.loadJni()`, `ChipDeviceController(ControllerParams.newBuilder().setControllerVendorId(0xFFF1).build())`,
`setCompletionListener(CompletionListener)`, `pairDeviceWithAddress(nodeId, ip, port, discriminator, pin, null)`,
`pairDevice(gatt, connId, nodeId, pin, NetworkCredentials.forWiFi(WiFiCredentials(ssid, pw)))`,
`getConnectedDevicePointer(nodeId, GetConnectedDeviceCallback)`,
`ChipClusters.OnOffCluster(ptr, endpoint).readOnOffAttribute / on / off`.

`tools/verify.sh` dumps the real signatures from the resolved AAR with `javap` into
`build_logs/10_matter_api.log`; a compile failure in `src/matter` means the AAR differs, and the
log shows exactly how.

## Workload mapping

| Logical op | Matter interaction | Completion (t_end) |
|---|---|---|
| WRITE_STATE value≠0 / =0 | Invoke OnOff `On` (0x01) / `Off` (0x00) on the configured endpoint | `DefaultClusterCallback.onSuccess` = InvokeResponse SUCCESS |
| READ_STATE | Read OnOff attribute 0x0000 | `BooleanAttributeCallback.onSuccess(value)` |

The CASE session pointer is obtained **before** `t_start` for every op; its duration is recorded as
`matter.get_device_ptr_ns` (large values indicate CASE re-establishment). Commissioning phases
(`ble_scan_ns`, `ble_gatt_ready_ns`, `pase_complete_ns`, `stage_*_ns`, `commissioning_complete_ns`,
`total_ns`) are recorded in the COMMISSIONED event, never in operation RTT.

## Commissioning guide (Android itself is the commissioner)

Prerequisites: ESP32 flashed with esp-matter firmware exposing an OnOff server (e.g. light
example); device in commissioning mode (factory-new or commissioning window open); phone on the
same 2.4 GHz Wi-Fi; Bluetooth + permissions granted in the app; know the discriminator and passcode
(printed by the device on boot, or from its onboarding payload). Record ESP-Matter SDK version and
firmware hash in the session metadata.

**BLE → Wi-Fi** (factory-new device): Setup → Matter → *BLE → Wi-Fi*; enter Node ID (e.g. 1),
discriminator, passcode, SSID; store the Wi-Fi password (*Store*, Keystore-encrypted); *Commission*.
The app scans for service 0xFFF6 with the matching discriminator, establishes PASE over BLE,
provisions Wi-Fi, and completes CASE over Wi-Fi.

**On-network** (device already on Wi-Fi with an open commissioning window, e.g. opened by another
admin or `matter esp` CLI): choose *On-network*, enter device IPv4/IPv6 address and port (5540).

Then set endpoint (from firmware; usually 1 for the light example), `clusterId 6`, `attributeId 0`,
`writeMode INVOKE_ONOFF`, save, and *Connect* (establishes CASE). Verify by observing the LED.

### Claiming "Matter over Wi-Fi"

Only after (1) the COMMISSIONED event exists in the session, (2) CONNECTED (CASE) succeeds, (3) the
LED/state changes with WRITE_STATE and READ_STATE returns the new value, and (4) the device is on
the Wi-Fi network (not Thread; ESP32/S3/C3 have no 802.15.4 radio — C6/H2 do, so confirm the
firmware transport).

## Limitations

- Controller credentials are generated per app process (`ControllerParams.newBuilder()`); after the
  app process restarts, the stored node id no longer authenticates — re-commission (factory reset
  the ESP32 if its fabric table fills up, max 5 fabrics). Production-grade persistent fabric
  storage (own CA/NOC chain via `OperationalKeyConfig`) is future work.
- OnOff workload only; arbitrary cluster/attribute write needs the newer generic interaction-model
  Java API (`ChipDeviceController.write/invoke`).
- The AAR is a development/example artifact from Google with no warranty.
- Subscriptions are not part of the measured workload (no SUBSCRIBE_STATE benchmark).

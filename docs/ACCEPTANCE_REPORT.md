# Acceptance report

Status date: 2026-11-23. Rules: hardware criteria are never PASS without a test on physical
hardware; build criteria are PASS only with a build log.

## What was actually executed

| Check | Environment | Result |
|---|---|---|
| Core unit tests (39) — domain, codec, correlator, runner, metrics, export, selection, mappers | Kotlin 2.3.20 compiler (JetBrains npm distribution) + kotlinx-coroutines 1.8.0 on JDK 21, JUnit-4 API shim | **39/39 passed** |
| Independent recomputation of a SIMULATED sample export (`docs/sample_export/`) with `recompute_metrics.py` | Python 3 | **all summaries reproduced** |
| Flutter analyze / tests, Android Gradle build, Gradle unit tests, APK, Matter AAR `javap` dump | developer Mac via `tools/verify.sh` | **NOT RUN yet** (the build environment used for development had no access to Maven/Google/pub.dev; the Mac VM never became available) |
| Any hardware test | — | **NOT RUN** (no phone/ESP32 access) |

## Acceptance criteria

| Criterion | Status | Evidence / note |
|---|---|---|
| **Build and architecture** | | |
| Builds from a clean checkout with documented commands | PASS (debug APK) | `flutter build apk --debug` with `enableMatter=true` on the developer Mac (2026-11-23); app launches on API 36 emulator. `verify.sh` (analyze, tests) not yet run |
| Existing conventions preserved | PASS | Flutter 3.44.6 template, AGP 9.0.1, Kotlin 2.3.20, Gradle 9.1.0 copied from the Mac's existing project |
| Adapters separate from runner | PASS | runner depends only on `ConnectivityAdapter`; verified by JVM tests with doubles |
| Bridge not used per measured operation | PASS | only `startRun`; timing in `BenchmarkRunner`/adapters (code review) |
| No secrets in source or exports | PASS (code/tests) | Keystore `SecretStore`; export redaction test |
| **BLE** | | |
| Real Android–ESP32 connection | NOT TESTED | needs GATT contract values |
| GATT operation completes and is validated | NOT TESTED | |
| Notification/ack correlation | NOT TESTED on hardware; logic PASS in unit tests | `CorrelatorTest` |
| Permission denial and disconnect handled | NOT TESTED | code paths: `PERMISSION_DENIED`, `DISCONNECTED_IN_FLIGHT` |
| **MQTT v5** | | |
| Client negotiates MQTT 5.0 | NOT TESTED | evidence to collect: mosquitto `p5` + `mqtt.negotiatedVersion` |
| Android publishes command | NOT TESTED | |
| ESP32 processes it and sends an application ack | NOT TESTED | firmware dependent |
| Op-id correlation, timeout, QoS, reconnect recorded | NOT TESTED on hardware; logic PASS in unit tests | |
| **Matter** | | |
| Real Android Matter controller integrated | PARTIAL | compiles and links against `matter-android-demo-sdk:1.0`; controller start/commissioning NOT TESTED on hardware |
| Android commissions the ESP32 | NOT TESTED | |
| Android performs the common operation | NOT TESTED | OnOff only |
| Commissioning and interaction timings separate | PASS (design) | COMMISSIONED event phases vs operation RTT |
| No laptop-only/mocked path reported as success | PASS | no Matter simulation exists; `noMatter` returns UNAVAILABLE |
| **Benchmark** | | |
| Monotonic timestamps in Kotlin | PASS | `AndroidMonotonicClock`; adapters stamp t_start/t_end |
| Raw operation records persisted | PASS (code) / NOT TESTED on device | SQLite per op |
| Warm-up vs measured distinguishable | PASS | `is_warmup`; tests |
| Failures/timeouts not hidden | PASS | runner tests |
| Summaries recomputable from raw export | PASS | Python recomputation on sample |
| Metadata sufficient to reproduce | PASS (design) | §6.4 fields + operator metadata |
| Comparison warnings for incompatible runs | PASS | `Comparability` tests; UI warning card |
| **Documentation** | PASS | README + docs/* |

## Appendix B feasibility proofs

| Proof | Status |
|---|---|
| BLE write + device ack + verified state | NOT TESTED |
| MQTT 5.0 + ESP32 application ack | NOT TESTED |
| Matter commissioned by Android + same operation over Wi-Fi | NOT TESTED |
| Measurement record with Kotlin monotonic start/end, op id, metadata | PASS in unit tests; NOT TESTED on device |

## Known risks to check first

1. `src/matter/.../MatterImpl.kt` is written against connectedhomeip v1.0/v1.1 signatures verified
   from upstream source, not yet against the AAR binary. If it fails to compile, `build_logs/10_matter_api.log`
   shows the real signatures; meanwhile build with `enableMatter=false`.
2. HiveMQ builder chain (`subscribeWith().topicFilter().qos().retainHandling().callback()`) is
   compile-unverified.
3. Flutter UI has not been run through `flutter analyze` yet.

## Researcher actions still required

1. Run `cd ~/development && tar xzf iot_conn_bench.tar.gz && bash iot_conn_bench/tools/verify.sh`;
   share `build_logs/` so compile errors can be fixed.
2. `git init` and commit the project (the app records the git revision in every export).
3. Provide Appendix A: phone model/Android version; ESP32 chip marking (photo); BLE service/
   characteristic UUIDs + payload/ack format; MQTT broker/topics/QoS/ack format; esp-matter
   version, transport (Wi-Fi), endpoint, discriminator/passcode procedure; router model/band/channel;
   how contention and disconnections will be produced.
4. Decide whether one board runs multiple stacks (multipath) or one board per protocol.
5. Run the hardware acceptance procedure in `docs/TESTING.md` for each path and update this report.

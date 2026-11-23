# Toolchain, dependencies and permissions

## Toolchain (matches the developer Mac's existing Flutter project)

| Item | Version | Source |
|---|---|---|
| Flutter / Dart | 3.44.6 stable / 3.12.2 | `~/development/flutter` |
| Android Gradle Plugin | 9.0.1 | `android/settings.gradle.kts` (Flutter 3.44 template) |
| Kotlin Gradle plugin | 2.3.20 | same |
| Gradle wrapper | 9.1.0 | `android/gradle/wrapper/gradle-wrapper.properties` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 27 | minSdk 27 required by the Matter AAR |
| JVM target | 17 | |

## Runtime dependencies

| Dependency | Version | Why |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | coroutines, `withTimeout`, `CompletableFuture.await` |
| `com.hivemq:hivemq-mqtt-client` | 1.3.7 | MQTT **5.0** and 3.1.1 client; the app uses `useMqttVersion5()` only. Pulls Netty 4.1, RxJava 2, Dagger (runtime). Netty duplicate `META-INF` files are excluded in packaging. |
| `com.google.matter:matter-android-demo-sdk` | 1.0 | prebuilt connectedhomeip Android controller (JNI + `.so` for all ABIs). Only when `enableMatter=true`. Development/example artifact. |
| Flutter | SDK | UI only; no Flutter plugins are used. |

Dev/test: `junit:junit:4.13.2`, `kotlinx-coroutines-core` (JVM tests), `flutter_test`, `flutter_lints ^6.0.0`.

No Room/KSP, no BLE library, no Pigeon (see ARCHITECTURE.md decisions).

## Android permissions

| Permission | API levels | Why |
|---|---|---|
| `INTERNET` | all | MQTT broker; Matter operational traffic |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | all | record Wi-Fi band/channel/RSSI metadata |
| `CHANGE_WIFI_MULTICAST_STATE` | all | mDNS/DNS-SD discovery of commissioned Matter nodes |
| `BLUETOOTH_SCAN` (`neverForLocation`) | 31+ | BLE scan (ESP32 GATT service; Matter 0xFFF6) |
| `BLUETOOTH_CONNECT` | 31+ | GATT connections |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | ≤30 | legacy BLE |
| `ACCESS_FINE_LOCATION` | ≤30 | required by Android ≤11 for BLE scan results |

Runtime permissions are requested only from *Setup → Request BLE permissions*. No storage
permission: exports use the Storage Access Framework and app-specific storage. No foreground service
(runs are foreground-only by design, with the screen kept on).

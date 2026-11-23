# MQTT v5 broker setup (lab)

The app needs a broker that speaks MQTT 5.0. Mosquitto 2.x does. These steps assume the broker runs
on the MacBook on the same Wi-Fi as the phone and the ESP32.

## Install and configure (macOS)

```bash
brew install mosquitto
mkdir -p ~/mosquitto && cat > ~/mosquitto/lab.conf <<'EOF'
# LAB ONLY: plain TCP, no authentication. Do not expose beyond the isolated test network.
listener 1883 0.0.0.0
allow_anonymous true
log_type all
connection_messages true
persistence false
EOF
mosquitto -c ~/mosquitto/lab.conf -v
```

For an authenticated/TLS setup, add `password_file` and a TLS `listener 8883` with `cafile`,
`certfile`, `keyfile`; in the app enable `useTls`, set `username`, and store the password with
*Store* (Keystore-encrypted, never exported).

Record in the session metadata (Dashboard → Start session): `broker.version`
(`mosquitto -h | head -1`) and `broker.location` (e.g. "MacBook Air M-series, Wi-Fi, same AP").

## App configuration

Setup → MQTT v5: host = Mac's LAN IP (`ipconfig getifaddr en0`), port 1883, `allowInsecureLab` on
(explicit opt-in required for non-TLS), unique `clientId`, topics from the firmware contract, QoS
(record it per run), codec fields. Tap *Connect*.

## Verifying MQTT 5.0 is actually negotiated (acceptance criterion)

Two independent pieces of evidence:

1. **Broker log**: on connect mosquitto prints
   `New client connected from <phone-ip>:<port> as <clientId> (p5, c1, k60)`. `p5` = protocol
   version 5. Save this log line with the session.
2. **App metadata**: the CONNECTED event and run metadata contain
   `mqtt.negotiatedVersion = MQTT_5_0` and `mqtt.connackReason = SUCCESS`. The HiveMQ client
   only sends MQTT 5 CONNECT when built with `useMqttVersion5()`; a 3.1.1-only broker rejects it.

## Checking the device-level acknowledgement independently

```bash
# Watch commands from the phone and the ESP32's acks (MQTT 5 client)
mosquitto_sub -V mqttv5 -h localhost -t '<commandTopic>' -t '<ackTopic>' -v -F '%t %p  corr=%C resp=%R'
```

A run is only valid when every success in the export corresponds to an ack published by the ESP32
(not by `mosquitto_pub` from the laptop). The app never treats PUBACK as success; it stores the
PUBACK latency as `mqtt.broker_ack_ns` in `diagnostics_json`.

## Impairments involving the broker

- *broker_unavailable*: stop mosquitto (Ctrl-C) mid-run; expect `DISCONNECTED` event and
  `DISCONNECTED_IN_FLIGHT`/`NOT_CONNECTED` records. Restart, then reconnect from Setup or enable
  *Explicit reconnect between operations*; `RECONNECTED` duration is recorded.
- Retained messages on the ack topic are ignored and logged (`RETAINED_IGNORED`), and the ack
  subscription uses MQTT 5 `Retain Handling = do not send`.

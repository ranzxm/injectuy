# InjectUY - Ultra Lightweight VPN & Injector Android

Aplikasi VPN Injector ringan untuk Android berbasis Native View dan VpnService.

## Fitur
1. **SSH + HTTP Payload Injector**
   - Support custom HTTP Header / Bug host replacement (`[host]`, `[port]`, `[host_port]`, `[crlf]`, `[protocol]`, `[ua]`).
   - Local socket injection proxy (`127.0.0.1:8989`).
2. **VMess Protocol**
   - Parser link standar `vmess://<base64>`.
   - Support WS, TLS, SNI.
3. **Ukuran Minimalis & Cepat**
   - No Jetpack Compose overhead.
   - Menggunakan pure Android Native XML + ViewBinding.

## Cara Build
Buka project ini di **Android Studio** atau build via Gradle:
```bash
./gradlew assembleDebug
```
Output APK ada di: `app/build/outputs/apk/debug/app-debug.apk`

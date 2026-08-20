# InjectUY - Ultra Lightweight VPN & Injector Android

Aplikasi VPN Injector ringan untuk Android berbasis Native View dan VpnService.

## Fitur
1. **SSH + HTTP Payload Injector**
    - Support custom HTTP Header / Bug host replacement (`[host]`, `[port]`, `[host_port]`, `[crlf]`, `[protocol]`, `[ua]`).
    - Config terenkripsi dengan lock field, expiry, dan custom server message HTML.
2. **Ukuran Minimalis**
    - Menggunakan Android Native XML + ViewBinding.

## Batasan Saat Ini

- Aplikasi belum memiliki packet forwarder TUN-to-SOCKS. Koneksi SSH yang berhasil belum mengalihkan traffic perangkat sebagai VPN penuh.
- VMess dan local injector proxy belum memiliki alur eksekusi produksi.

## Cara Build
Buka project ini di **Android Studio** atau build via Gradle:
```bash
./gradlew assembleDebug
```
Output APK ada di: `app/build/outputs/apk/debug/app-debug.apk`

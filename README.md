# MariaRay

Simple V2Ray/Shadowsocks config filter for Android.

## Features
- Accepts: Sub URL, base64 subscription text, or single configs (`ss://`, `vmess://`, `vless://`, `trojan://`)
- 3-stage filter: TCP reachability → HTTP test → speed test
- Copy filtered configs to clipboard

## Build
Push to `main`. GitHub Actions builds APK and uploads to `mariaray-debug` artifact.

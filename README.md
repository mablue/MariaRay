# MariaRay

Simple V2Ray/Shadowsocks config filter for Android.

## Features
- Accepts: Sub URL, base64 subscription text, or single configs (ss / vmess / vless / trojan)
- 3-stage filter: TCP -> HTTP (multi-probe) -> speed
- uTLS fingerprint (chrome by default), ALPN, reality, flow support
- Debug report with 25+ tests
- Sub link search (GitHub repos + DuckDuckGo, no login required)
- Copy filtered configs to clipboard
- Runs in a foreground service (survives backgrounding)
- Auto-incrementing versionCode from github.run_number

## Signing

All builds use a stable keystore committed to this repo. Future APKs install over previous ones without uninstalling.

### Credentials

| Field | Value |
|-------|-------|
| Keystore file | app/mariaray.jks |
| Alias | mariaray |
| Store password | MariaRay2024! |
| Key password | MariaRay2024! |
| Validity | 10000 days |
| DN | CN=MariaRay, OU=Dev, O=MariaRay, L=Tehran, S=Tehran, C=IR |

### First-time migration

If you have an older MariaRay installed (signed with the auto-generated GitHub Actions debug key), uninstall it once:

- Settings -> Apps -> MariaRay -> Uninstall
- Or: adb uninstall com.mariaray.app

Then install the new APK. All future updates work in place.

### Verifying APK signature

Run these two commands and compare the SHA256:

~~~bash
keytool -list -v -keystore app/mariaray.jks -storepass 'MariaRay2024!' | grep SHA256
apksigner verify --print-certs app-release.apk
~~~

The SHA256 must match.

## Build

Push to any branch. GitHub Actions builds app-release.apk and uploads it as the mariaray artifact.

versionCode = github.run_number, versionName = 1.(run_number)

## Security note

The keystore is intentionally public so that anyone can rebuild the app and updates install over each other. Do not reuse this keystore for any other app. If MariaRay ever becomes security-sensitive, move the keystore to GitHub Secrets and inject it in CI.

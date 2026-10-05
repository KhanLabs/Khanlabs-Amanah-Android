# KhanLabs LAN Share (Android): main instructions

Read this first, then `PROGRESS.md` (where things stand) and `docs/PLAN.md` (the full plan, sections B, C and D matter most here).

## What this is

One Android app for phones, tablets and Android TV (including Fire TV). It sends files, folders and text to other KhanLabs devices, and receives them. It works:
- on the same Wi-Fi network;
- directly between two devices with no router (Wi-Fi Direct, or a local hotspot).

The goal is to be the best app for moving large files between phones and between every kind of Android device.

The Windows app is a separate repo, `KhanLabs/khanlabs-lanshare`. The two apps must always be able to talk to each other.

Current state: **planning only, no code yet.** The first coding work is Phase 2 in `PROGRESS.md`.

## Ground rules

1. **The protocol is the contract.** `docs/PROTOCOL.md` is a copy of the master `PROTOCOL.md` in the Windows repo.
   - Protocol changes are made there first, then copied here in the same piece of work.
   - Never break protocol v1. The app must speak v1 as both sender and receiver, because old Windows exes will be around for years.
   - v2 lives under `/api/kl/v2`.
2. **It must work with the Windows app.** Before calling a protocol feature done, run it against the Windows app in both directions and run the conformance suite (once it exists).
3. **Never overwrite or lose a user's file.**
   - Write to a pending or temp item first, then make it visible. On MediaStore, use `IS_PENDING=1` and clear it when the file is complete.
   - If the name is taken, use `name (1).ext`.
   - Clean up partial files unless they are kept for resume.
4. **Treat every name and path from the network as hostile.** Reject `..`, absolute paths and empty parts, and make names safe with this platform's own rules. Never build a path from a raw name.
5. **Nothing arrives without consent.** Incoming sends wait for Accept. Auto-accept is only for devices the user has paired and marked trusted, it is opt-in per device, and it needs the v2 device proof. Never trust the sender ID in a request body.
6. **Portable and free of Google services.**
   - No Google Play Services, Firebase, ML Kit, Play Core, analytics, ads or accounts.
   - QR scanning uses CameraX with ZXing.
   - The app must work fully offline.
7. **Writing style** for docs, UI text and commit messages: plain simple English, no slang, no emojis.
8. **Commits** are authored as `Khan <Mueed@KhanLabs.dev>`. No `Co-Authored-By` lines. Do not push unless the owner says so.
9. **Update `PROGRESS.md`** at the end of every work session: what was done, what is next, anything broken.

## Tech choices (decided, see `docs/PLAN.md` C.1 and C.2)

- **Language and UI:** Kotlin, Jetpack Compose for phones and tablets, and Compose for TV (`androidx.tv:tv-material`) for TV. Not Flutter.
- **Android versions:** `minSdk 23`, target the latest stable SDK.
- **One APK:**
  - one universal APK for phones and TV;
  - no native code (`.so` files), so it runs on 32-bit TV boxes too;
  - signed with both v1 (JAR) and v2 schemes.
- **Server:** a small HTTP/1.1 server written for this app on `SSLServerSocket`, with coroutines and a bounded pool. Not NanoHTTPD, not Ktor.
- **Client:** OkHttp with a custom `X509TrustManager` that accepts exactly one certificate (SHA-256 of the DER = device ID), and keep-alive pooling. Do not use OkHttp's `CertificatePinner`.
- **Certificate:** ECDSA P-256, self-signed, made with BouncyCastle (`bcpkix`) and kept in app-private storage.
- **Data:** Room for the resume journal, history and trusted devices. kotlinx.serialization for JSON.

## Planned modules

| Module | Contents |
|---|---|
| `core-protocol` | Pure Kotlin/JVM, no Android imports. Models, name rules, chunk math, digests, batch format, v1 and v2 state machines. Runs in CI against the Python app. |
| `core-net` | HTTP server and client, TLS identity, pinning, device proof |
| `core-link` | NSD discovery, Wi-Fi Direct, LocalOnlyHotspot, QR payloads, binding sockets to a `Network` |
| `core-store` | MediaStore, SAF and file writers; resume journal; trusted devices |
| `transfer` | Scheduler, jobs and foreground service, notifications |
| `ui-mobile`, `ui-tv` | Phone and TV screens |
| `app` | The single APK; picks phone or TV UI at runtime with `UiModeManager` |

## Android rules to follow

**Identity**
- Exclude the identity files from cloud backup and device-to-device transfer (`dataExtractionRules`, and `fullBackupContent` for API 30 and older). Otherwise a new phone gets the old phone's device ID.

**Discovery**
- Use NsdManager with `_klshare._tcp`.
- On Android 13 and older, resolve one service at a time to avoid `FAILURE_ALREADY_ACTIVE`.
- Hold a `MulticastLock` while the device list is open.
- Always offer "add by IP" and QR as well.

**Wi-Fi Direct and hotspot**
- **Permissions:** `NEARBY_WIFI_DEVICES` with `neverForLocation` on API 33+. `ACCESS_FINE_LOCATION`, with location turned on, on older versions.
- **Check support:** check `FEATURE_WIFI_DIRECT` at runtime; many TVs lack it.
- **Groups:** use `WifiP2pConfig.Builder` (API 29+) for predictable groups.
- **Addresses:** the group owner is `192.168.49.1`. The client must call `POST /hello`, because the group owner can't see the client's address.
- **Hotspot fallback:** `LocalOnlyHotspot` (API 26+). Warn that normal Wi-Fi may drop while it is on.
- **Joining:** join with `WifiNetworkSpecifier`, and **bind sockets to that `Network`**, or traffic may go over mobile data.

**Storage**
- **Default:** save to `Download/KhanLabs` through `MediaStore.Downloads` (API 29+). On API 23 to 28, use direct paths with `WRITE_EXTERNAL_STORAGE`.
- **A folder the user picks:** `ACTION_OPEN_DOCUMENT_TREE` with the permission kept. Use `DocumentsContract` directly, because `DocumentFile.findFile` is too slow for thousands of files.
- **Writing at offsets:** `openFileDescriptor(uri, "rw")` plus `FileChannel.write(buf, position)`. If storage can't seek, advertise `random_access:false`.
- **Before accepting:** check free space and the FAT32 4 GB limit.

**Sending**
- Accept `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, and the photo picker.
- Pass URIs to the transfer service in `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`.

**Background work**
- **API 34+:** user-started transfers run as user-initiated data transfer jobs.
- **Older versions:** a `dataSync` foreground service. Android 15 allows 6 hours a day.
- **During a transfer:** hold a partial wake lock and a `WifiLock`.
- **Resume** is what protects users when the OS kills the app.

**TV**
- **Launcher:** leanback and touchscreen are `required="false"`. Add a `LEANBACK_LAUNCHER` activity and a 320x180 banner.
- **Remote:** every screen works with a D-pad: visible focus, a set first focus, focus restored on Back, and no long-press-only actions.
- **File picker:** many TVs have no system file picker (DocumentsUI), so build an in-app file browser.
- **Other files:** "All files access" may need adb on some TVs. Document it rather than hiding it.
- **Received files:** install APKs through the PackageInstaller session API. Open videos with `ACTION_VIEW` (VLC, Kodi).

## Build, test, release (once code exists)

```
gradlew assembleDebug          # debug APK
gradlew test                   # JVM unit tests (core-protocol runs without a device)
gradlew connectedCheck         # instrumented tests on a device or emulator
gradlew assembleRelease        # signed universal APK (needs the release keystore)
```

**Release steps**
1. Bump `versionCode` and `versionName`.
2. Update `CHANGES.md`.
3. Run all tests and the conformance suite against the current Windows app.
4. Build the signed APK.
5. Publish it on GitHub Releases with its SHA-256.

**Signing key:** never commit the release keystore or its passwords. Losing the key means every user has to uninstall to update, so keep two offline backups.

## Known Android behaviour to plan for

- **Battery managers:** Xiaomi, Samsung and others kill background work aggressively. Resume and clear in-app guidance are the answer.
- **Wi-Fi Direct support** differs by phone maker and chipset. Test on real devices, not only the emulator. The emulator has no Wi-Fi Direct.
- **Fire TV and some TV boxes have no Google Play Services.** That is fine, because this app must not use them.

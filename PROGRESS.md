# Progress: KhanLabs LAN Share (Android)

Update this file at the end of every work session. Newest entries at the top of "Log".
The plan these phases come from is `docs/PLAN.md` (section D).

## Where things stand (2026-10-05)

- **State:** the first phone build (0.1.0, debug) works on a OnePlus 8T (Android 14) with Windows 1.2.1: files, folders and text both ways, found automatically, Accept from the notification while the app is in the background.
- **Windows app:** 1.2.1 has the Phase 0 fixes. The v2 spec, test vectors and conformance runner are written.
- **Tested on the PC:** the Kotlin receiver passes the Windows repo's conformance suite (36 fast and 2 slow cases). Files and folders go both ways between the Kotlin code and the Windows 1.2.1 code, and the hashes match.
- **Tested on the phone:** the Windows conformance suite passes against the phone over Wi-Fi (38 of 38, slow tests included).
- **Next:** a long transfer with the screen off, and a send picked from the file picker with many files.

## Phase 2: phone app

Setup:
- [x] Gradle project with the modules from `CLAUDE.md` (all except `ui-tv`)
- [ ] `minSdk 23`, one universal APK, no native code, v1 and v2 signing
- [ ] CI on GitHub Actions: build, unit tests, and `core-protocol` against the Python app

Protocol and identity:
- [x] `core-protocol`: models, name rules, chunk math, tree digest, batch format, device proof and pairing MAC, passing every case in `docs/v2/vectors.json`
- [x] Device certificate (ECDSA P-256, BouncyCastle), device ID, excluded from backup
- [x] HTTP/1.1 server with TLS, idle timeouts and connection limits
- [x] OkHttp client with a single-certificate trust manager
- [x] Protocol v1, sender and receiver, working with Windows 1.2.x (tested on a OnePlus 8T)
- [ ] Protocol v2, sender and receiver: sessions, chunks, batches, parallel connections, resume journal, device proof

Finding devices:
- [x] NSD announce and browse
- [x] Add a device by IP
- [ ] QR scan (CameraX and ZXing) to connect and pair
- [ ] Trusted devices list

Sending and receiving:
- [x] Share sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`), file and photo pickers
- [ ] Save through MediaStore (`Download/KhanLabs`) with `IS_PENDING` (done), or to a SAF folder the user picks (not started)
- [x] Accept and Decline from a notification
- [ ] User-initiated jobs (API 34+) or a foreground service; wake lock and `WifiLock` (foreground service and locks done; user-initiated jobs not started)
- [ ] Progress with speed and time remaining; history; text messages (all but history done)

Nice to have:
- [ ] Clipboard push to trusted devices
- [ ] Choose which files to accept

Done when:
- [ ] A 10 GB video goes phone to PC and PC to phone with the screen off
- [ ] Wi-Fi off and on during a transfer resumes by itself within 30 s
- [ ] After the OS kills the app, the transfer resumes when it is reopened
- [ ] 5,000 photos keep their folders and dates
- [ ] Battery use per GB is recorded
- [ ] Conformance suite passes in both roles

## Phase 3: Android TV

Must have:
- [ ] Leanback launcher entry and banner; same APK
- [ ] Compose for TV screens, every one reachable with a D-pad
- [ ] Home screen: "Ready to receive" with name, IP and QR code; Received list
- [ ] Auto-accept from trusted devices
- [ ] Open received files (`ACTION_VIEW`), install APKs (PackageInstaller)
- [ ] In-app file browser for when DocumentsUI is missing; document the adb step for "All files access"

Done when:
- [ ] Every flow works with only a remote on the TV devices in the test list
- [ ] Pairing a phone to a TV by QR takes under 30 s
- [ ] A 4 GB file to a FAT32 USB stick is refused up front with a clear message

## Phase 4: direct mode, no router

Before planning dates:
- [ ] Spike: Wi-Fi Direct group with `WifiP2pConfig.Builder` on 3 or more phone brands
- [ ] Spike: LocalOnlyHotspot plus `WifiNetworkSpecifier` join and socket binding
- [ ] Spike: Windows joining a phone's group as ordinary Wi-Fi

Must have:
- [ ] Phone to phone over Wi-Fi Direct, started from a QR code
- [ ] LocalOnlyHotspot fallback
- [ ] `POST /hello`; sockets bound to the right `Network`
- [ ] Offer direct mode when the LAN blocks device-to-device traffic

Nice to have:
- [ ] Nearby list without a QR code (P2P service discovery)
- [ ] 5 GHz group band
- [ ] Wi-Fi Aware where supported

Done when:
- [ ] Two phones with no network start sending within 20 s of scanning
- [ ] 5 GB phone to phone reaches at least 70% of Quick Share's speed on the same phones
- [ ] A phone joins a TV's hotspot by QR

## Phase 5: release

- [ ] Release keystore made, with two offline backups and a written plan for keeping them
- [ ] Signed universal APK on GitHub Releases with SHA-256 checksums
- [ ] Opt-in update check (works with Obtainium)
- [ ] User guide, including TV setup
- [ ] Later: F-Droid or IzzyOnDroid (needs open source), Play Store build, LocalSend receive adapter

## Test devices

| Group | Devices |
|---|---|
| Phones | Recent Pixel; Samsung mid-range; Xiaomi or Redmi (HyperOS); an Android 7 or 8 phone; an Android Go phone |
| TV | Chromecast with Google TV or Google TV Streamer; NVIDIA Shield; cheap Amlogic box (Android 9 to 11, 32-bit); Fire TV Stick 4K; Sony or TCL Google TV set |
| Tablets | Fire tablet; Samsung tablet |
| Networks | 5 GHz router; 2.4 GHz only; mesh with client isolation; guest network; phone hotspot; Wi-Fi Direct |

## Decisions

| Date | Decision | Why |
|---|---|---|
| 2026-10-05 | Kotlin, Compose and Compose for TV, not Flutter | Most of the hard parts are native Android APIs, and Compose for TV handles the D-pad properly |
| 2026-10-05 | `minSdk 23`, one APK, no native code | Matches AndroidX and Compose; runs on 32-bit TV boxes |
| 2026-10-05 | No Google Play Services | Must work on Fire TV, AOSP boxes and phones without Google |
| 2026-10-05 | Own server on `SSLServerSocket`, OkHttp client | NanoHTTPD is unmaintained; Ktor needs Netty for TLS |
| 2026-10-05 | QR code is the main way to pair and to start Wi-Fi Direct | P2P service discovery is unreliable on many devices |
| 2026-10-05 | Don't base the protocol on LocalSend's | No resume or proven identity, and we don't control it; an optional adapter may come later |
| 2026-10-05 | `compileSdk 37`, `targetSdk 36` | The Compose BOM 2026.09.00 needs compile SDK 37; the target stays at 36 until it has been tested |
| 2026-10-05 | One thread per connection, capped at 64, instead of coroutines in the server | Blocking socket code is simpler to read, and the connection cap already bounds the threads |
| 2026-10-05 | Plain `ServerSocket` with TLS added per connection | Lets a plain HTTP request get a 301 to `https://`, like the Windows app |
| 2026-10-05 | `Connection: close` on every v1 request | The Windows app answers with HTTP/1.0; OkHttp reused those connections and they failed |
| 2026-10-05 | Read the peer's certificate from the TLS socket, not from OkHttp's `handshake` | OkHttp leaves `peerCertificates` empty for self-signed certificates |
| 2026-10-06 | Peek the first byte with `MSG_PEEK` on Android | Android's TLS can't take back bytes that were already read, and plain HTTP must still get a 301 |
| 2026-10-06 | Ask a "lost" device for `/info` before removing it | Android 14 reports services as lost while they are still there |

Known gaps in the first build:
- Android 15 limits `dataSync` foreground services to 6 hours a day; the service stops when that runs out.
- Files shared from other apps are read later, when the send starts; a few apps take back access before then.
- No protocol v2 yet, so no resume and no device proof.

Open questions for the owner (from `docs/PLAN.md`, section F):
- Who builds the Android app, and is there a date?
- Open source or closed?
- Play Store, or sideload only?
- iOS or macOS, ever?
- Is the name final?
- How is the project paid for?

## Log

### 2026-10-06
- First run on a real phone (OnePlus 8T, Android 14, over USB debugging) against Windows 1.2.1.
- Fixed: every incoming connection was reset. Android's TLS has no `createSocket(socket, consumed, autoClose)`, so the first byte is now peeked with `MSG_PEEK` (`transfer/SocketPeek.kt`).
- Fixed: Android 14 reported the PC as lost every minute or two while it was still running. A lost device is now asked for `/info` and kept if it answers.
- Fixed: after many connections, a restarted app moved from port 47820 to 47821. `SO_REUSEADDR` is now on, except on Windows.
- Tested: PC to phone (4 files, a sub-folder, 50 MB; hashes match), phone to PC (text, and a 50 MB file; hash matches), the Accept button in the notification, numbered names when a file already exists, and the full conformance suite against the phone.

### 2026-10-05 (later)
- Gradle project (Gradle 9.8, AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00) with `core-protocol`, `core-net`, `core-store`, `core-link`, `transfer`, `ui-mobile` and `app`.
- `core-protocol` passes every v2 test vector, and its name rules match the Windows app.
- `core-net`: identity, pinning client, HTTPS server, v1 receiver and sender. The Windows conformance suite passes against it, and files go both ways with the Windows 1.2.1 code.
- Phone app: MediaStore saving, NSD, Add by IP, share sheet, Accept and Decline notifications, a foreground service, and a home screen.
- Debug APK builds. Not yet installed on a phone.

### 2026-10-05
- Created this private repo.
- Opus wrote the plan for both apps (`docs/PLAN.md`).
- Added `CLAUDE.md` (main instructions), this file, `README.md` and a copy of `PROTOCOL.md`.

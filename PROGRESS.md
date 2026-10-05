# Progress: KhanLabs LAN Share (Android)

Update this file at the end of every work session. Newest entries at the top of "Log".
The plan these phases come from is `docs/PLAN.md` (section D).

## Where things stand (2026-10-05)

- **State:** planning only. No Android project and no code yet.
- **Windows app:** 1.2.0 is released. It speaks protocol v1 (`docs/PROTOCOL.md`).
- **Before coding:** the Windows side does Phase 0 first. That is the 1.2.1 fixes, plus the v2 spec with test vectors and the conformance runner.
- **When the phone work can start:** Phase 2 can start once the v2 spec is written, about halfway through the Windows Phase 1.

## Phase 2: phone app

Setup:
- [ ] Gradle project with the modules from `CLAUDE.md` (`core-protocol`, `core-net`, `core-link`, `core-store`, `transfer`, `ui-mobile`, `ui-tv`, `app`)
- [ ] `minSdk 23`, one universal APK, no native code, v1 and v2 signing
- [ ] CI on GitHub Actions: build, unit tests, and `core-protocol` against the Python app

Protocol and identity:
- [ ] `core-protocol`: models, name rules, chunk math, tree digest, batch format, device proof and pairing MAC, passing every case in `docs/v2/vectors.json`
- [ ] Device certificate (ECDSA P-256, BouncyCastle), device ID, excluded from backup
- [ ] HTTP/1.1 server on `SSLServerSocket` with idle timeouts and connection limits
- [ ] OkHttp client with a single-certificate trust manager
- [ ] Protocol v1, sender and receiver, working with Windows 1.2.x
- [ ] Protocol v2, sender and receiver: sessions, chunks, batches, parallel connections, resume journal, device proof

Finding devices:
- [ ] NSD announce and browse
- [ ] Add a device by IP
- [ ] QR scan (CameraX and ZXing) to connect and pair
- [ ] Trusted devices list

Sending and receiving:
- [ ] Share sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`), file and photo pickers
- [ ] Save through MediaStore (`Download/KhanLabs`) with `IS_PENDING`, or to a SAF folder the user picks
- [ ] Accept and Decline from a notification
- [ ] User-initiated jobs (API 34+) or a foreground service; wake lock and `WifiLock`
- [ ] Progress with speed and time remaining; history; text messages

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

Open questions for the owner (from `docs/PLAN.md`, section F):
- Who builds the Android app, and is there a date?
- Open source or closed?
- Play Store, or sideload only?
- iOS or macOS, ever?
- Is the name final?
- How is the project paid for?

## Log

### 2026-10-05
- Created this private repo.
- Opus wrote the plan for both apps (`docs/PLAN.md`).
- Added `CLAUDE.md` (main instructions), this file, `README.md` and a copy of `PROTOCOL.md`.

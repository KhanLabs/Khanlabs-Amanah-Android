# Progress: Amanah (Android)

Update this file at the end of every work session. Newest entries at the top of "Log".
The plan is `docs/PLAN.md` (rewritten 2026-10-06). The work order was changed on 2026-10-06 at the owner's request: UI and features first (R6 and R7, and the UI parts of R2), then the reliability phases R1 to R5 (test lab, v1 fixes, protocol v2, hardening), then R8 to R10. Nothing goes public before the R5 gates pass. The older "Phase" names below map to it roughly as Phase 0 and 1 = R2 and R3, Phase 2 = R4 and R6, Phase 3 = R8, Phase 4 = R9, Phase 5 = R5 and R10.

## Where things stand (2026-10-06)

- **State:** the first phone build (0.1.0, debug) works on a OnePlus 8T (Android 14) with Windows 1.2.1: files, folders and text both ways, found automatically, Accept from the notification while the app is in the background.
- **Windows app:** 1.2.1 has the Phase 0 fixes. The v2 spec, test vectors and conformance runner are written.
- **Tested on the PC:** the Kotlin receiver passes the Windows repo's conformance suite (36 fast and 2 slow cases). Files and folders go both ways between the Kotlin code and the Windows 1.2.1 code, and the hashes match.
- **Tested on the phone:** the Windows conformance suite passes against the phone over Wi-Fi (38 of 38, slow tests included).
- **Done 2026-10-06:** 1 GB with the screen off in deep Doze, and 2,000 files with hard names (see the Log).
- **Done 2026-10-06 (later):** phone UI overhaul (commit `0047500`): Send, Activity and Settings tabs, Receiving chip and sheet, folder send, offer countdown, activity history. Tested on the OnePlus 8T, including 200% text and 320 dp wide.
- **Done 2026-10-06 (UI first, part 2):** first-run screen with a one-time permission request, a warning when notifications are off, QR connect (phone shows and scans codes, the PC shows a code), `klshare://` links from a camera app, a Quick Settings tile, devices in other apps' Share menu and the app icon menu, photo previews in Activity, and a fix for the offer sheet at 320 dp and 200%.
- **Done 2026-10-06 (messages):** the Text button became **Messages**. A Messages tab lists conversations (unread count on the tab), and a conversation screen shows your texts and theirs with a box to reply. A text arrives without a prompt only from a device you already exchanged a message with that went through, and only from an address that device announced. Notification has Reply and Mark as read. Code: `ChatStore` in `core-net`, `Engine.sendMessage/reply`, `MessagesTab.kt`; protocol field `kind:"message"`. 53 Kotlin tests pass. Tried on the phone on 2026-10-07 (see the Log); the notification Reply button has not been tapped yet.
- **Done 2026-10-06 (Share to anyone):** a person without Amanah scans a QR code and gets a web page with only the files you picked (details in the Log). Code: `ShareServer`, `ShareServerTest`, `ZipPlan`, `SharePage` in `core-net`, `Engine.startShare/stopShare`, `ShareScreen.kt`. 78 Kotlin tests pass (23 protocol, 55 net). The page was checked in a browser at phone width (light and dark). Tried on the phone on 2026-10-07 (see the Log): the screen, the notification, the service, the page, the downloads and Stop. **Not tried yet:** the time limit ending on its own and a real second phone scanning the code.
- **Done 2026-10-06 (own Wi-Fi, phone side):** with no router, the phone can make its own Wi-Fi network (`LocalOnlyHotspot`) or join one (`WifiNetworkSpecifier`). The screen is on the Send tab ("Wi-Fi network"). While the network is on it shows an Amanah code (joins the network and connects in one scan) and a standard Wi-Fi code. Share to anyone shows a two-step code (join, then open the page). Code: `OwnWifi.kt`, `WifiCode.kt`, `ConnectCode` (`ssid` and `psk`), `Engine.startOwnNetwork/joinNetwork/joinAndConnect`, `OwnWifiScreen.kt`. 87 Kotlin tests pass (32 protocol, 55 net). Making a network was run on the phone on 2026-10-07 (see the Log). **Not tried yet:** joining a network and a second device joining or scanning the codes.
- **Next:** Windows side of own Wi-Fi (join a phone's network; make one, experimental); a real-camera QR scan; the remaining Windows UI changes; then the reliability phases R1 to R5.

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

Share to anyone (`docs/PLAN.md`, section 5.8; brought forward on 2026-10-06 at the owner's request):
- [x] Pick files, folders or photos, then a screen with the QR code, link, PIN, live counts and Stop
- [x] Browser page: Download everything as one ZIP (made while sending, exact length), a ZIP per folder, resume for single files; no photo thumbnails (the page has no scripts)
- [x] Secret link, optional PIN with lockout, a time limit (15 min, 1 h, 4 h, until stopped); Stop cuts downloads
- [x] "Get Amanah" card on the page: this phone's own APK and an "Open in Amanah" link
- [ ] Tried on the phone and with a second phone
- [ ] Works in Chrome, Safari on iPhone, Firefox, Samsung Internet and Brave (plain HTML and CSS, but only Chromium was looked at)

Own Wi-Fi (`docs/PLAN.md`, section 5.9; brought forward on 2026-10-06 at the owner's request):
- [x] Make a network on the phone (Android 8 and newer), with a permission request, a name and password, and Turn off
- [x] Join a network by scanning, or by typing the name and password (Android 10 and newer)
- [x] One scan joins and connects: `klshare://connect` carries `ssid` and `psk`; a plain `WIFI:` code also works
- [x] Share to anyone shows a two-step code while the phone hosts the network
- [ ] Tried on a phone: hotspot start, join, the Android prompt, the Share page over it
- [ ] Tried on three phone brands (some drop the phone's own Wi-Fi while the hotspot is on)

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
- [ ] A "Check for updates" button that only opens the website (no automatic checks; Obtainium users check by themselves)
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
| 2026-10-06 | Add "Share to anyone" (QR code to a browser page) | The other person needs no app; the owner wants it as a main feature |
| 2026-10-06 | "Share to anyone" uses plain HTTP with a secret link, not HTTPS | Browsers don't trust our own certificates and show a worse warning; real certificates need an internet service |
| 2026-10-06 | 100% free: no ads, subscriptions, limits, account or data collection. Optional support buttons ($2, $5, $10, $25, Custom) in About open `khanlabs.dev/support` in the browser | The promise is the product; the app has no payment code and never contacts the internet itself. Never nag |
| 2026-10-06 | "Made with ♥ by Khan" always shows at the bottom: Windows footer, every phone screen, every served web page | The owner's request; part of the brand |
| 2026-10-06 | Build the UI and features first; the reliability phases R1 to R5 come after | The owner's request. Safe, because nothing is released before the R5 gates pass |
| 2026-10-06 | Debug builds get a text-size and a small-screen option in Settings | OnePlus blocks `wm size`, `wm density` and font scale over adb, so 200% text and 320 dp could not be tested otherwise |

Known gaps in the first build:
- Android 15 limits `dataSync` foreground services to 6 hours a day; the service stops when that runs out.
- Files shared from other apps are read later, when the send starts; a few apps take back access before then.
- No protocol v2 yet, so no resume and no device proof.

Open questions for the owner (from `docs/PLAN.md`, section 9.1):
- Who builds the Android app, and is there a date?
- Open source or closed?
- Play Store, or sideload only?
- iOS or macOS, ever?
- Is the name final?
- Which payment service is behind `khanlabs.dev/support`? The page doesn't exist yet; today the link shows the home page.

## Log

### 2026-10-07 (phone checks)
- Run on the OnePlus 8T (Android 14) with a test receiver on the PC (`rx_server.py`, port 47840, added with Add by IP) and the Windows protocol code as the other side.
- **Messages:** a text sent from the phone arrived at the receiver. A text sent back from the same device ID and address came in without a prompt, raised the unread count to 1 on the conversation row, and showed a notification with Reply and Mark as read. Opening the conversation cleared the count and the notification. Not run: tapping Reply or Mark as read in the notification.
- **Finding:** the reply was filed under the other device on the same PC (the real Windows app, port 47820) and not under the test receiver (port 47840). `ChatStore.peerOf` looks the sender up by address first, and both apps share one IP. This only happens with two devices on one address (two copies on one PC, or a VM), so it is left alone for now.
- **Share to anyone:** the screen, the QR code and link, the service notification and the stop button all work. From the PC: the page lists both files, `f/0` and `f/1` are byte-for-byte equal to the originals, Range requests return 206 with the right bytes, `all.zip` holds both names, `get/0` is byte-for-byte the installed APK, a wrong token, the root and another path return the same 404, and after Stop the page returns 410. The counters showed 1 person, 3 downloads, 5.7 MB sent. Not run: the time limit ending by itself and a real second phone scanning the code.
- **Light theme:** Send, Messages, Activity, Settings and the offer sheet are readable in Light.
- **Single file Open:** a received single file shows Open, Show and Share on its Activity card. Open brings up the system "Open with" list.
- **Own Wi-Fi, making a network:** with Nearby devices allowed, "Make a network" started a Wi-Fi network (`AndroidShare_xxxx`) on its own interface (`wlan2`) while the phone stayed connected to the home Wi-Fi. The screen showed the Amanah code, the standard Wi-Fi code, the name and password with Copy buttons, and the phone's address on the network (it matched `wlan2`). The app listened on `[::]:47820`. "Turn off the network" removed `wlan2`, and the service kept running for receiving. Not run: joining a network (needs a second access point), a PC or second phone joining, and a scan of either code. The PC was not moved to the phone's network because its only connection is Wi-Fi.
- Commits `0692191` (error messages, local-address check) and `0c2b930` (network search code, no screen yet) hold the work that was left uncommitted. Nothing is pushed.

### 2026-10-06 (own Wi-Fi, Android)
- `WifiCode` reads and writes the standard `WIFI:T:WPA;S:..;P:..;;` text (escapes for `\ ; , : "`, quotes from phones accepted, WPA and WPA2 only). A network name is 1 to 32 bytes, a password is 8 to 63 printable ASCII characters. The same rules and golden strings are in the Windows repo (`connect_code.py`, `test_connect_code.py`).
- `ConnectCode` has optional `ssid` and `psk`. A bad pair is dropped and the rest of the code still works, so old apps ignore the extra part and still connect on a shared network.
- `OwnWifi` (main thread only): `startLocalOnlyHotspot` for making, `WifiNetworkSpecifier` plus `requestNetwork` plus `bindProcessToNetwork` for joining (traffic of this app only, the network has no internet capability). Needs Nearby devices (Android 13 and newer) or Location (Android 8 to 12, with Location on). The phone's address on its own network is found by diffing the addresses before and after the hotspot starts.
- The foreground service now runs while receiving, a share or an own network is on. Turning off the service ends the network.
- A scanned code that names a network asks first ("Join the network and add this device?"), then Android asks again.
- Decisions: no live hotspot or `netsh wlan connect` test on the PC, because its only connection is Wi-Fi.
- Not checked: everything on a phone. Open points: some phones turn their own Wi-Fi off while the hotspot is on (the screen warns); Android 9 and older cannot join from an app (the screen says so and points to Wi-Fi settings); with receiving off there is no Amanah code (the screen offers to turn it on).

### 2026-10-06 (Share to anyone, Android)
- Same behaviour as the Windows app (`anyone.py`): plain HTTP on its own port (47890, next 9 tried) at `/s/<random 128-bit token>/`; a wrong token or any other path gets the same 404; optional 6-digit PIN (5 free tries per address, then a lock that doubles up to 15 minutes; 20 wrong tries from anywhere in 10 minutes lock everything for 15 minutes); a TLS hello is hung up on; the Host header must be an IP address; the page has no scripts and a strict Content-Security-Policy; the token is never logged. When it ends, the address says "This share has ended" for 5 minutes.
- `HttpsServer` can now run without TLS (`tls = null`) and `Exchange` can stream a body of a known length. The 53 older tests still pass.
- `ZipPlan` writes a stored ZIP whose length is known before the first byte, with ZIP64 past 4 GB or 65,535 entries. Checked with `java.util.zip.ZipFile`, with lowered limits to reach the ZIP64 forms. A file that changes size while it is sent cuts the ZIP short, so the browser shows a failed download instead of a broken file.
- The service now runs while receiving is on **or** a share is live. With receiving off and a share live, the phone does not listen on the protocol port and does not announce itself. Android 15 allows only 6 hours a day for this kind of service; when it times out the share ends.
- The page offers this app's own APK from `applicationInfo.sourceDir`. That works for a sideloaded or debug build; an install split into several files (Play) is detected and the card is left out. Decide before the Play release.
- Checked: 25 new tests over real sockets (listing, 404 sameness, Range, HEAD, ZIPs, Host check, TLS hello, PIN, lockout, stop cuts a running download, time limit, counts, installer card, grouping of names). Page screenshots at 390 px wide, light and dark (Playwright Chromium).
- Not checked: anything on the phone. No USB connection this session, so the Compose screen, the notification text, the foreground service start and stop, and the share ending by itself have only been compiled, not run.

### 2026-10-06 (rename to Amanah)
- The owner chose the name **Amanah** ("something entrusted to your care"), shown with "File sharing by KhanLabs". The app label, top bar, welcome screen, About screen, messages and the `app` text sent to other devices say Amanah now.
- The application id is now `dev.khanlabs.amanah` (debug: `dev.khanlabs.amanah.debug`). It is a new app on the phone: the old debug app, its device ID and its Activity list are gone. Nothing was public yet, so this was the cheap time to do it.
- Not changed: the repo names, the code package `dev.khanlabs.lanshare`, the wire names (`_klshare._tcp`, `klshare://`) and the `Download/KhanLabs` save folder. Renaming the GitHub repos waits for the owner's OK.
- Checked on the OnePlus 8T on 2026-10-07 (new app installed, old debug app removed): the welcome screen says Amanah, Get started asks for notifications once, the Windows app appears under Nearby, and an offer of 3 files from the Windows code was accepted and arrived (3 of 3, sizes match). Windows tests (161) and Kotlin tests pass on the working tree, which also holds uncommitted work on error messages (`Errors.kt`), network search (`Scan.kt`) and the local-address check in `Pinning.kt`; that work is not committed yet.

### 2026-10-06 (first run, QR connect, shortcuts)
- First run: a welcome screen with the name field and a Get started button (commit `0047500` and later). Permissions are asked once, when Get started is tapped, not on every launch. If notifications are off later, the Receiving sheet shows a card with a Turn on button that opens the system page.
- QR connect: text format `klshare://connect?v=1&id=<64 hex>&name=&ip=&port=` (`ConnectCode.kt`, same bytes as `connect_code.py` in the Windows repo, one golden string tested on both sides). Only local network addresses are accepted, so a code can never make the app contact the internet. Scanning pins the full 256-bit ID through the TLS certificate. Tested over adb against the real PC: right ID connects, wrong ID is refused, a public address is refused, a silent address times out with a clear message.
- The phone shows its own QR code (Receiving sheet) and scans one (Send tab, device picker, help sheet). The camera is only used to read the code. Links opened from the phone's camera app ask "Add this device?" first.
- Quick Settings tile "Receive files" turns receiving on and off and starts or stops the service. Tested with `cmd statusbar click-tile`.
- Sharing Shortcuts: up to 4 devices from the Nearby and added lists show in other apps' Share menu (tested from Firefox's Share) and in the app icon's menu (asks what to send). Direct send from the Share menu goes straight to the device and shows progress on the Send tab. The list stays on the phone.
- Activity: expanded cards show up to 4 small previews of received photos (and videos on Android 10 and up). Tap one to open it.
- Not tested yet: a real camera scan, Android 17 `ACCESS_LOCAL_NETWORK`, the Text dialog, the single-file Open button, the Light theme.

### 2026-10-06 (UI overhaul)
- New phone UI, commit `0047500`. Tabs: Send, Activity, Settings. The Receiving chip in the top bar opens a sheet with the switch, the device name, the address, the ID and the save folder with a Show button.
- Send: Files, Photos and videos, Folder and Text. Pick content first and then a device, or a device first and then content. Folder walks the tree (up to 10,000 files) and keeps sub-folders and empty files. Tested: 12 files, 150.9 MB, at about 11.4 MB/s.
- Offer sheet: countdown, a pinned Accept and Decline, "New device" wording, a short warning that anyone on the Wi-Fi can send under any name.
- After a transfer: Open (one file), Show (the exact folder), Share (chooser), Copy (text). All tested.
- Activity tab: filter chips, tap a card for names and actions, delete one or clear all. Stored on the phone only, in `noBackupFilesDir/history.json`, up to 200 entries.
- Checked at 200% text and at 320 dp wide. Fixed there: the wrapped "Decline" button, the clipped nav labels, and the title that wrapped.
- Fixed later the same day: in the offer sheet at 320 dp and 200%, the "New device" row looked covered by the "Answer within" text. It was a scrolling area with no visible edge; sheets now draw a line where the scrolling area ends. Still not tested on the phone: Light theme, the Text dialog, the share-from-another-app flow and the single-file Open button are not tested on the phone yet.
- A launch of the Windows app with `pythonw.exe` left two Block rules in Windows Firewall (Private profile). Only `python.exe` has an allow rule. The rules were not changed.

### 2026-10-06 (later)
- Rewrote `docs/PLAN.md` as the complete plan (same file in both repos): reliability first (R1 to R5), then features (R6 to R10), a full testing plan with 17 release gates, the UI plan, and the one-time tip page with crypto. Research behind it: `docs/UI-RESEARCH.md` (Android repo).
- Decided: tips are one-time only; the app has no payment or crypto code; "Made with ♥ by Khan" shows on every screen; the opt-in update check was removed (the apps never contact the internet).
- Tough test, PC to phone, 1 GB with the screen off, the battery "unplugged" and deep Doze forced (`dumpsys deviceidle force-idle`): done in 120 s at about 9 MB/s, SHA-256 matches. Passed.
- Tough test, PC to phone, 2,000 files (64 MB, 0 to 64 KB, 25 nested folders) with names like `café`, `日本語`, emoji, `50% off`, `[brackets]`, 180-character names and leading spaces: first run failed, because the Windows sender sent names the receiver refuses (fixed in the Windows repo). After the fix, all 2,000 arrive with the right paths and hashes. Passed.
- Measured: small files are slow in v1, about 14 files a second (141 s for those 2,000 files). Batching in v2 (`docs/PLAN.md` B.7) should fix this.
- About screen (top bar): version, "Built for people, not advertisers", the five promises, and the optional Support buttons. Tested on the OnePlus: $5 opens `khanlabs.dev/support?amount=5` in the browser.
- "Made with ♥ by Khan" bar pinned to the bottom of every screen.
- UI and privacy research saved as `docs/UI-RESEARCH.md`.

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

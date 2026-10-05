> **Copy.** The master copy of this plan is `docs/PLAN.md` in [KhanLabs/khanlabs-lanshare](https://github.com/KhanLabs/khanlabs-lanshare). If they differ, that one is right.

# Plan: KhanLabs LAN Share for Windows, Android and Android TV

**Goal:** the best app for moving large files between phones, and between every kind of Android device (phones, tablets, Android TV boxes, Fire TV), plus Windows PCs. It should work with or without a router, and need no account, no internet, no ads and no Google Play Services.

**Repos**
- Windows: `KhanLabs/khanlabs-lanshare` (this plan's master copy lives here, in `docs/PLAN.md`)
- Android (phones and TV, one APK): `KhanLabs/khanlabs-lanshare-android`

**How this plan was made:** a review of version 1.2.0 by Claude Opus. It read the code, measured transfers and compared competitors. It was then turned into this roadmap. Progress against it is tracked in each repo's `PROGRESS.md`.

**What the owner asked for (2026-10-05)**
- An Android app that is compatible with the Windows app, and works on phones and Android TV (remote only, no touch).
- A way to connect two devices directly over Wi-Fi (Wi-Fi Direct), without a router.
- A portable app: a signed APK people can sideload, and the one-file exe on Windows.
- Improvements and new features for the Windows app.

---

## Summary

**Verdict.** Version 1.2.0 is careful, but it is not yet ready to be "the best for large files". Its handling of names, files on disk and PINs is careful, better than most similar tools. The weak spot is reliability, not speed:

- A send cannot resume. A dropped connection late in a 20 GB transfer loses the whole file, and sending again makes `name (1).ext` copies of the files that did arrive.
- **A confirmed hang:** a sender that goes quiet without closing the connection blocks a receiver thread forever. A phone leaving Wi-Fi or going to sleep does this. The file stays locked, so a retry is refused.
- Many small files are slow. Every file needs a new TCP connection, a TLS handshake and a new thread.
- The sender ID shown in the Accept dialog is whatever the sender typed. Nothing checks it. This must be fixed before any "trusted devices" or auto-accept feature.

Single-file speed is fine. On loopback, v1 moved a 1 GB file at about 656 MB/s with TLS on both ends.

**Main recommendations**
1. Ship a 1.2.1 hardening release first.
2. Design protocol v2 on HTTP/1.1 with keep-alive. It adds:
   - asynchronous offers;
   - resumable chunked uploads, with SHA-256 per chunk;
   - batching for small files;
   - two to four parallel connections;
   - signed proof of who is sending;
   - QR pairing.

   Keep v1 working. Do not move to HTTP/2 or QUIC.
3. Build the Android app in Kotlin with Jetpack Compose and Compose for TV. One APK, minSdk 23, no native code, no Play Services.
4. Build a shared conformance test suite and a fault-injection proxy from day one, and publish benchmarks.
5. LocalSend is the main free, open-source competitor. Win where its users keep asking for help: resume, many small files, Android TV with a remote, and transfers with no router.

---

## Measurements of v1.2.0

| Test (loopback, Windows 11, Python 3.12.10) | Result |
|---|---|
| 1 file, 1 GB, v1 `SendJob` to v1 `Receiver` | 1.64 s, about **656 MB/s** |
| 1,000 files of 8 KB | 2.13 s, about **469 files/s** (2.1 ms per file with zero network delay) |
| Sender sends half a file, then goes silent with the socket open | After 20 s the file is still `receiving` and the `.uploading` file is still on disk. A retry is refused, and the retrying client doesn't even get the error reply. |

These are loopback numbers. They show that Python and TLS are not the bottleneck. They are not Wi-Fi speeds.

---

## A. What's wrong with v1 for large files

### A.1 Already good
- File names from the network are treated as hostile. Files are saved to a temp file and moved into place without overwriting. Host headers are checked, and tokens are compared in constant time.
- Pinning the device ID (SHA-256 of the certificate) is simple and correct once a device is known.
- The protocol is written down, small, and tested.

### A.2 Throughput
- **One big file:** fine. On a real LAN the limits are the Wi-Fi link, phone or TV storage, and Wi-Fi packet loss. Two to four parallel streams may help on lossy Wi-Fi. Measure it before promising numbers.
- **Many files:** the server speaks HTTP/1.0, and the sender opens a new connection for every file. Each file costs about 3 round trips plus a TLS handshake. On normal Wi-Fi that is roughly 17 to 32 ms per file, so 10,000 small files spend 3 to 6 minutes on overhead alone. TLS sessions are not reused.
- **TLS cost:** not a problem on PCs. Keep ChaCha20-Poly1305 allowed for old 32-bit ARM TV boxes that have no AES instructions.

### A.3 Reliability
- **Hang (confirmed).** `_HTTPServer.finish_request` turns the timeout off after the TLS handshake. If a sender stops sending without closing, `rfile.read()` in `ApiHandler.upload` blocks forever:
  - the file stays `receiving`, so a retry gets `409 already`;
  - the thread and the temp file stay until the app quits.

  The same missing timeout lets anyone on the LAN open idle connections until the app runs out of threads.
- **No resume.** On error, the temp file is deleted. `SendJob` stops at the first failed file and does not retry.
- **An offer can't be rejoined.** If the connection drops during the 120 s wait, re-sending the same offer ID gets `409 duplicate`. The receiver may already have accepted, and then it waits an hour for uploads that never come.
- **Sleep.** Nothing stops Windows from sleeping in the middle of a long send.

### A.4 Integrity
There are no hashes. TLS protects the bytes on the wire. The gaps are:
- a source file that changes while it is being sent;
- storage or USB errors;
- most of all, bugs in future resume code.

Chunk hashes make resume safe, and a "Verified" mark is something large-file users value.

### A.5 Many files, limits and metadata
- The 10,000-file limit and the 4 MB offer body are too small for a phone's camera folder.
- Modified times are not kept. Empty folders are dropped.
- One file name that Windows doesn't allow (Android allows `:` and `?`) rejects the whole send. The receiver should make names safe itself instead.
- Free space is checked once and not reserved. A file over 4 GB going to a FAT32 USB stick fails late with a vague error. This will be common on TV boxes.

### A.6 The 120-second blocking offer
This is the wrong shape for phones and TVs:
- apps in the background often can't show the prompt in time;
- a dropped connection can't be rejoined;
- every waiting offer holds a thread;
- 120 s is too short for "send it, then walk over to the PC".

Offers should return at once, and the sender should check the answer with a long-poll.

### A.7 Progress
- The sender counts bytes handed to the socket, not bytes the receiver has saved, so it shows 100% too early.
- There is no speed and no time remaining. Both are must-haves for multi-GB files.
- The receiver can't tell the sender to slow down.

### A.8 Security and trust
1. **The sender ID isn't verified.** The Accept dialog shows `offer.sender["id"]`, which comes from the request body, so anyone can claim to be any device. Auto-accept would be dangerous until this is fixed.
2. **The short ID is only 32 bits.** Matching the first 8 hex digits of a target takes hours of CPU time. Use it as a label, not a security check.
3. **Offer spam.** There is no rate limit. Every offer brings the window forward, opens a topmost dialog and sends a notification. The firewall rule covers all network types, so anyone on a coffee shop network can flood the screen.
4. **Received files are public by default.** They go to the shared folder, so a private file someone sends you appears on the web page at once.
5. **Copied identity.** The private key sits next to the portable exe. Copy that folder to a second PC and both PCs have the same device ID, so they can't see each other. Anyone who can read the folder can also pretend to be that PC.

### A.9 Discovery
- Addresses are worked out once, at start-up. A laptop that changes network keeps announcing its old IP address.
- Only one address is picked, assuming every network is a /24.
- A fake device can use the same name as a real one.
- When a network blocks device-to-device traffic (guest Wi-Fi, some mesh routers), the app can't explain why nothing shows up.

### A.10 Other desktop points
- The one-file exe unpacks itself on every launch, so it starts slowly and often triggers virus warnings. Code signing would help more than any code change.
- Keep FTP, but move it to an advanced section.
- Tkinter is fine. A UI rewrite isn't justified by anything in this plan.

### A.11 Versioning
v1 has no list of features, so every new capability would need a new base path. v2 should advertise its features and limits. Version 1.2.0 already ignores TXT keys it doesn't know, so new keys are safe to add.

---

## B. Protocol v2

### B.1 Goals
**Goals**
- resume after any interruption, including an app restart on either side;
- checked integrity;
- small files as fast as big ones;
- asynchronous accept;
- proven sender identity;
- pairing without first-contact risk;
- the same API over a router, Wi-Fi Direct or a hotspot.

**Not in v2.0:** relays over the internet, iOS background receiving, folder sync.

### B.2 Compatibility
- **Paths:** v2 is served at `/api/kl/v2`. A v2 device keeps serving `/api/kl/v1` exactly as today.
- **TXT record:**
  - `v` = highest version supported;
  - `vs=1,2` = all versions supported;
  - `p2p=1` = Wi-Fi Direct supported.

  Keep the whole record under about 200 bytes, because Wi-Fi P2P service discovery limits its size.
- **`GET /api/kl/v2/info`** returns:
  - `protocols`;
  - `features` (`resume`, `batch`, `pair`, `p2p`, `proof`);
  - `limits` (`max_streams`, `max_chunk`, `batch_max_bytes`, `batch_max_files`, `random_access`);
  - a one-time `nonce`.
- **Falling back:** if `/v2/info` returns 404, the sender uses v1 and tells the user that the other device is on an older version and transfers can't resume.
- **Android:** the app implements v1 in both roles. That is only four endpoints, and old portable exes will be around for years.

### B.3 Transport: stay on HTTP/1.1

| Option | Decision | Why |
|---|---|---|
| HTTP/1.1, keep-alive, 2 to 4 TLS connections | **Use** | Python stdlib, OkHttp and browsers all support it. Easy to debug. Parallel streams cope with Wi-Fi loss. |
| HTTP/2 | No | No server in the Python stdlib. Heavy on Android. One TCP stream means head-of-line blocking on lossy Wi-Fi. |
| HTTP/3 / QUIC | No (revisit only if measurements demand it) | No mature Python server. Native code on Android. High CPU use on cheap ARM devices. |

- **TLS:** use TLS 1.3 where the platform has it, else TLS 1.2. Keep ChaCha20-Poly1305 allowed.
- **Tokens:** send them in an `Authorization` header, not in the URL.

### B.4 Identity, proof, pairing and trust

**Device proof** (fixes A.8.1):
1. The client calls `GET /info` on a keep-alive connection and gets a nonce tied to that connection.
2. On `POST /sessions`, `/pair` and `/hello`, the client sends its certificate (`cert`, base64 DER) and `proof` = ECDSA signature over `"KLv2-proof" || receiver_id || nonce || session_id`.
3. The receiver checks the signature, then works out the sender's ID itself as SHA-256(cert).

Mutual TLS isn't used, because Python's `ssl` module can't accept unknown self-signed client certificates. **Have this design reviewed by someone with cryptography experience before release.**

**QR pairing:**
1. The device with a screen shows `klshare://pair?id=<full fingerprint>&ip=...&port=...&secret=<128-bit one-time>&name=...`. In direct mode the code also carries `ssid` and `psk`.
2. The scanner pins the full fingerprint from the QR code, so there is no first-contact risk.
3. The scanner sends `POST /pair` with its proof and `HMAC(secret, A_id||B_id)`.
4. Both sides save each other as trusted.

Phones scan; TVs and PCs only need to show a QR code.

**Pairing by code** (PC to PC, no camera): only safe with a PAKE (SPAKE2, RFC 9382) that also binds both fingerprints. Until that exists, show a long comparison code of at least 64 bits. Never use a plain HMAC with a 6-digit code: it can be broken offline.

**Receive policy:** Everyone, Everyone for 10 minutes, Trusted only, or Off. For each trusted device, auto-accept files and auto-accept text can be turned on separately. Default to Trusted only on networks Windows marks as Public.

### B.5 Sessions (offers that don't block)
- **`POST /sessions`** sends `{session, sender, cert, proof, summary:{count, bytes, top_names}, files:[first page], more, text}`.
  - It returns `202 pending`, or `200` with the accept data when a trusted device is auto-accepted.
  - Sending the same session again (same manifest hash, same proven sender) returns the current state, not an error.
- **`GET /sessions/{sid}?wait=25`** is a long-poll. It returns the state, the accepted file IDs (the receiver may accept only some), `chunk_size`, the token, and the resume state of each file.
- **Pending offers** last 10 minutes by default, and the prompt stays available as a notification.
- **`POST /sessions/{sid}/manifest`** sends more file entries in pages of up to 5,000. This removes the file limit.
- **`DELETE /sessions/{sid}`** cancels. The other side finds out through the long-poll, or through `410` on its next upload.

### B.6 Chunked, resumable uploads with hashes
- **`PUT /sessions/{sid}/files/{fid}/chunks/{n}`** carries one chunk, with the header `KL-Chunk-SHA256`.
  - The receiver picks the chunk size: a power of two from 1 to 64 MiB, default 8 MiB.
  - The receiver writes the chunk at its offset, checks its hash and records it, then answers `201`.
  - Progress counts these answers, so it is accurate.
- **`POST .../files/{fid}/done {digest}`** finishes a file. The receiver checks the digest, moves the file into place without overwriting, sets its modified time, and returns the name it was saved as.
- **File digest `sha256-tree-v1`** = SHA-256(chunk_size as 8 bytes || digest of chunk 0 || digest of chunk 1 || ...).
  - Chunks can arrive in any order.
  - The sender doesn't need to read the file twice.
  - Browsers can compute it with WebCrypto, so the web page can use the same uploads.
- **Why SHA-256 and not BLAKE3:** SHA-256 has hardware support on x86 and ARMv8 and is built into Python, Android and browsers. BLAKE3 would need native code on Android. An `alg` field allows a change later.
- **Rejected chunks:** the receiver reads a rejected chunk to the end and throws it away, so the sender always gets the error reply.

### B.7 Batching small files
- **`POST /sessions/{sid}/batch`** uses `Content-Type: application/x-kl-batch`.
- **Format:** a series of records, each a 4-byte length, then a JSON header `{file, size, sha256}`, then the bytes. A 4-byte zero ends the batch.
- **Saving:** each file is saved as soon as its bytes arrive, and the reply has a result for each file.
- **Limits** come from `/info`, for example files under 1 MiB, and up to 32 MiB or 1,000 files per batch.
- **No tar:** its format has too many corner cases to parse safely.

### B.8 Parallel connections
- The receiver advertises `max_streams` (for example PC 6, phone 4, TV box 2).
- The sender starts with 2 connections and adds one while throughput keeps rising by 10% or more. It drops back on errors.
- The receiver may answer `503` with `Retry-After` when its disk is falling behind.
- **Chunk order:** hand chunks out roughly in order, within a small window of about 8. On NTFS and exFAT, writing far past the end makes the file system fill the gap with zeros, which is slow.
- When `random_access:false` (some Android storage can't seek), that file's chunks go strictly in order on one connection.

### B.9 Resume after a restart
- **Receiver:** keeps a journal for each session (manifest, which chunks arrived, temp path or MediaStore URI) for 7 days, and reloads it at start-up.
- **Sender:** keeps the session ID, the token, and each item's size and modified time. A file that changed restarts from zero.
- **Reconnecting:** send `POST /sessions` again, read `GET /sessions/{sid}`, then send only the missing chunks.

### B.10 Names, metadata and limits
- Send `mtime` (ms), `kind: file | dir` (so empty folders are kept), and an optional `mime`.
- Each receiver makes names safe by its own platform rules and reports the saved name.
- Optional "skip identical files" (same size and digest) stops `(1)` copies when a folder is sent again.
- Before accepting, the receiver checks FAT32's 4 GB limit and reserves the space.

### B.11 Wi-Fi Direct and hotspot
The API stays the same. Only the way the two devices reach each other changes.

1. **Finding each other:**
   - a QR code (most reliable);
   - Wi-Fi P2P service discovery (`_klshare._tcp` with a short TXT);
   - normal mDNS when both are on the same LAN.
2. **The group:** on Android the group owner is `192.168.49.1`. The client learns that from `WifiP2pInfo`. The group owner can't see the client's address, so the client calls `POST /hello` with its port and proof.
3. **Devices without Wi-Fi Direct:** an Android P2P group owner is also a normal WPA2 access point, with the name and password from `WifiP2pGroup`. A Windows PC, or a phone without Wi-Fi Direct, can join it as ordinary Wi-Fi. This is the simplest way to do "PC to phone with no router".
4. **Hotspot fallback:** `LocalOnlyHotspot` (API 26+). Its QR code holds the SSID and password plus the pairing data.

**Rule of thumb:** the device with a screen and no camera (TV, PC) hosts the network. The phone scans and joins.

### B.12 v2 endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/info` | Identity, versions, features, limits, nonce |
| POST | `/hello` | Tell the other device your address on a P2P group or hotspot |
| POST | `/pair` | Finish QR pairing |
| POST | `/sessions` | Create or rejoin a send |
| GET | `/sessions/{sid}?wait=` | Long-poll state, tokens, resume state |
| POST | `/sessions/{sid}/manifest` | More file entries |
| PUT | `/sessions/{sid}/files/{fid}/chunks/{n}` | One chunk |
| POST | `/sessions/{sid}/batch` | Many small files |
| POST | `/sessions/{sid}/files/{fid}/done` | Finish and check one file |
| DELETE | `/sessions/{sid}` | Cancel |

---

## C. Android app architecture

### C.1 Kotlin, Jetpack Compose and Compose for TV
- **Native APIs:** Wi-Fi Direct, LocalOnlyHotspot, NSD, MediaStore and SAF, user-initiated jobs, PackageInstaller and network-bound sockets are all native Android APIs. With Flutter, that hard 70% would still be written in Kotlin.
- **TV:** Compose for TV (`androidx.tv:tv-material`) handles D-pad focus properly. LocalSend (Flutter) still has an open issue saying it can't be used with a TV remote.
- **Shared code:** the protocol code lives in plain Kotlin/JVM modules. They can run in CI against the Python app, and could move to Kotlin Multiplatform later if iOS ever happens.

### C.2 minSdk 23 (Android 6)
- New AndroidX and Compose releases require 23.
- Android 5 TV boxes are rare and too slow for multi-GB work anyway.
- Sign with v1 (JAR) and v2 schemes, because v2-only signing needs API 24.
- No native code, so one universal APK covers 32-bit TV boxes, 64-bit phones and emulators.

### C.3 Modules
| Module | Contents |
|---|---|
| `core-protocol` (pure JVM) | Models (kotlinx.serialization), name rules, chunk math, digests, batch format, v1 and v2 state machines |
| `core-net` | Client (OkHttp), server, TLS identity, pinning, device proof |
| `core-link` | NSD discovery, Wi-Fi Direct, hotspot, QR payloads, binding sockets to a network |
| `core-store` | MediaStore, SAF and file writers; resume journal and trusted devices (Room) |
| `transfer` | Scheduler, foreground service and jobs, notifications |
| `ui-mobile`, `ui-tv`, `app` | One APK; the UI is chosen at runtime with `UiModeManager` |

### C.4 Server, client and identity
- **Server:** a small HTTP/1.1 server written for this app on `SSLServerSocket`, with coroutines and a bounded pool. It needs strict idle timeouts and connection limits (the lesson from A.3), and streams straight into SAF and MediaStore.
  - Not NanoHTTPD: unmaintained since 2016.
  - Not Ktor: it needs Netty for TLS, which is heavy.
- **Client:** OkHttp with a custom `X509TrustManager` that accepts exactly one certificate, the one whose SHA-256 equals the device ID. Also a hostname verifier that accepts the IP, and connection pooling for keep-alive. OkHttp's `CertificatePinner` doesn't fit, because it still validates the certificate chain.
- **Identity:** an ECDSA P-256 key and a self-signed certificate built with BouncyCastle (`bcpkix`), stored in app-private storage.
  - Optionally wrap it with a Keystore AES key. Don't rely on keys that live only in the Keystore: some TV boxes have unreliable ones.
  - **Exclude the identity from backup and device-to-device transfer** (`dataExtractionRules`). Otherwise a new phone gets the old phone's ID.

### C.5 Discovery
- **NsdManager** announces and browses on the LAN.
- **Resolving:** on Android 13 and older, resolve one service at a time to avoid `FAILURE_ALREADY_ACTIVE`.
- **MulticastLock:** hold one while the discovery screen is open. NsdManager doesn't strictly need it, but some drivers filter multicast.
- **Other ways in:** QR connect code and add by IP. Later, a subnet scan of `/info` for networks that block multicast.

### C.6 Wi-Fi Direct and hotspot
- **Permissions:**
  - `NEARBY_WIFI_DEVICES` with `neverForLocation` on API 33+;
  - `ACCESS_FINE_LOCATION`, with location turned on, on older versions;
  - `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE`.
- **Check support:** check `FEATURE_WIFI_DIRECT` at runtime. Many TVs lack it.
- **Groups:** on API 29+, use `WifiP2pConfig.Builder` for predictable groups: a `DIRECT-` network name, a passphrase, and 5 GHz when supported. Predictable groups are what let a QR code carry the details.
- **P2P service discovery** is unreliable on many devices, so it is a nice-to-have. The QR code is the main path.
- **Hotspot fallback:** `LocalOnlyHotspot` (API 26+). Show a standard `WIFI:` QR code plus the pairing data. Many devices drop their normal Wi-Fi while it is on, so warn the user.
- **Joining:** on API 29+, join with `WifiNetworkSpecifier`. **Bind sockets to that network**, or Android may send the traffic over mobile data.
- **Windows:**
  - First option: join the phone's group as an ordinary Wi-Fi client (`netsh wlan add profile` and `connect`).
  - Spike first: on Windows 11 24H2 some `netsh wlan` commands need location permission.
  - Also spike WinRT Wi-Fi Direct and Mobile Hotspot through `pywinrt`.

### C.7 Saving large files
- **Default location:** `Download/KhanLabs` through `MediaStore.Downloads` (API 29+, no permission needed). Write with `IS_PENDING=1` and clear it when the file is done. On API 23 to 28, use direct paths with `WRITE_EXTERNAL_STORAGE`.
- **A folder the user picks:** `ACTION_OPEN_DOCUMENT_TREE`, with the permission kept. Use `DocumentsContract` directly, because `DocumentFile.findFile` is very slow with thousands of files.
- **Writing at offsets:** `openFileDescriptor(uri, "rw")` plus `FileChannel.write(buf, pos)`. Storage that can't seek gets `random_access:false`.
- **Before accepting:** check free space and the FAT32 limit.

### C.8 Sending
- **Ways in:** the share sheet (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`), the in-app pickers, and the photo picker.
- **Read permission:** pass URIs to the transfer service in `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`. Prefer persistable URIs for big jobs.
- **Files with no size** (some providers): copy small ones to cache first; refuse big ones.

### C.9 Background work and battery
- **API 34+:** run user-started transfers as **user-initiated data transfer jobs**.
- **Older versions:** a `dataSync` foreground service. Android 15 limits these to 6 hours a day.
- **"Ready to receive"** is a foreground service: "while the app is open" or "for 10 minutes" on phones, "always" on TVs.
- **During transfers:** hold a partial wake lock and a `WifiLock`.
- **Battery managers:** show device-specific guidance for Xiaomi, Samsung and others. Ask for the battery-optimization exemption only in the sideload build.
- **Resume** is the real protection. When the OS kills the app, nothing is lost.

### C.10 Android TV
- **One APK:** leanback and touchscreen both set to `required="false"`, a `LEANBACK_LAUNCHER` activity, and a 320x180 banner.
- **Remote only:** clear focus rings, a set first focus, focus restored on Back, and no long-press-only actions.
- **Home screen:** a big "Ready to receive" panel with the device name, IP address and a QR code, plus a Received list with Open, Install and Delete.
- **Prompts are unreliable on TV**, so **pairing plus auto-accept from trusted devices is a must on TV.**
- **No system file picker:** many TV builds lack DocumentsUI, so build an in-app browser.
  - MediaStore covers media files.
  - Other files on API 30+ need `MANAGE_EXTERNAL_STORAGE`. Some TVs have no settings screen for it, so it has to be granted over adb. Document this.
- **Install APKs on the TV:** the most common reason to send files to a TV. Use the PackageInstaller session API with `REQUEST_INSTALL_PACKAGES`.
- **Open with:** `ACTION_VIEW`, to hand videos to VLC, Kodi or the TV's own player.

### C.11 Portable distribution
- **No Google services:** no Google Play Services, Firebase, ML Kit or Play Core. Scan QR codes with CameraX and ZXing.
- **Releases:** a signed universal APK on GitHub Releases, with SHA-256 checksums.
- **Updates:** an opt-in update check against GitHub Releases. It also works with Obtainium.
- **Signing key:** losing it is permanent, because every user would have to uninstall to update. Keep two offline backups.
- **F-Droid and IzzyOnDroid** need the app to be open source.
- **A later Play Store build** drops `MANAGE_EXTERNAL_STORAGE`, possibly APK install, and the battery exemption.

---

## D. Roadmap

Durations are rough, for one experienced developer per app. **M** = must-have, **N** = nice-to-have.

### Phase 0: make 1.2 safe and write the v2 spec (Windows, 2 to 3 weeks)

**M: release 1.2.1**
- **Timeouts:** a read timeout (about 60 s) on every API connection, with the offer wait timed separately.
- **Limits:** a cap on connections per IP and on pending offers.
- **Accept dialogs:** one queued dialog ("3 more waiting") that doesn't steal focus.
- **Sender ID:** label it "unverified" until the device proof exists, or hide it.
- **Receive folder:** default to its own folder (for example `Downloads\KhanLabs`), not the shared folder.
- **New identity on a new PC:** store Windows' `MachineGuid` with the key, and make a new key if it differs.
- **Sleep:** stop Windows from sleeping during transfers (`SetThreadExecutionState`).
- **Progress:** show speed and time remaining.
- **Discovery:** re-announce when the network changes, and announce all addresses.

**M: v2 spec and test harness**
- `PROTOCOL-v2.md` with JSON Schemas and test vectors (chunk digest, tree digest, proof signature, batch records).
- A conformance runner that already passes against 1.2.1 for v1.

**Done when:**
- the hang test frees the thread within 60 s and the retry works;
- 100 offers per second from one IP open at most one dialog;
- all old and new tests pass.

### Phase 1: Windows 1.3 with protocol v2 (5 to 7 weeks)

**M**
- **v2 transfers:** server and client with keep-alive, sessions, chunks and SHA-256, batches, and adaptive parallel connections.
- **Resume:** a journal so transfers survive restarts.
- **No file limit:** manifest paging, modified times, empty folders, names made safe when received.
- **Trust:** device proof, QR pairing (the PC shows the QR code), trusted devices, receive policy and auto-accept.
- **Compatibility:** v1 fallback both ways.

**N**
- Explorer "Send to" entry;
- drag and drop onto a device;
- command line `klshare send`;
- send to several devices at once.

**Done when:**
- **Resume:** a 20 GB file PC to PC over Wi-Fi, with the sender killed at 5 random points, finishes with a matching digest. Each interruption re-sends at most (connections × chunk size).
- **Small files:** 10,000 files of 50 KB are at least **5 times faster than 1.2.0** on the same Wi-Fi. Record the 1.2.0 baseline first.
- **Large files:** one large file reaches at least **90% of the `iperf3` TCP speed** between the same two machines.
- **Compatibility:** 1.2.0 and 1.3 work in both directions.
- The conformance suite passes.

### Phase 2: Android phone app (8 to 12 weeks; can start halfway through Phase 1)

**M**
- **Everything in C.3 to C.9** except Wi-Fi Direct and the TV screens.
- **Both protocols:** v1 and v2, as sender and receiver.
- **Finding devices:** NSD, add by IP, QR scan to connect and pair.
- **Sending:** the share sheet and pickers.
- **Saving:** MediaStore and SAF.
- **Accepting:** Accept and Decline from the notification.
- **Background work:** user-initiated jobs, or a foreground service.
- **Resume, history and text messages.**

**N**
- clipboard push to trusted devices;
- choosing which files to accept.

**Done when:**
- a 10 GB video goes phone to PC and PC to phone with the screen off;
- Wi-Fi turned off and on mid-transfer resumes by itself within 30 s;
- after the OS kills the app, the transfer resumes when the app is reopened;
- 5,000 photos keep their folders and dates;
- battery use per GB is recorded;
- the conformance suite passes in both roles.

### Phase 3: Android TV (4 to 6 weeks)

**M**
- **Remote-only UI:** leanback entry and Compose for TV, with every screen reachable by D-pad.
- **Receiving:** a receive screen with a QR code, and auto-accept from trusted devices.
- **Received files:** open them, and install APKs.
- **Picking files:** an in-app file browser, with a fallback when DocumentsUI is missing.

**Done when:**
- every flow works with **only a remote** on the TV devices in the matrix below;
- pairing a phone to a TV by QR takes under 30 s;
- a 4 GB file to a FAT32 USB stick is refused up front with a clear message.

### Phase 4: Direct mode without a router (5 to 8 weeks)

**M**
- **Phone to phone:** Android to Android over Wi-Fi Direct, started from a QR code.
- **Hotspot fallback:** LocalOnlyHotspot.
- **Connecting:** `/hello`, with sockets bound to the right network.
- **Windows:** joins the phone's group as an ordinary Wi-Fi client.
- **Suggestion:** the app offers Direct mode when the LAN blocks device-to-device traffic.

**N**
- a list of nearby devices without scanning a QR code (P2P service discovery);
- the 5 GHz group band;
- WinRT Wi-Fi Direct on Windows;
- Wi-Fi Aware on phones that support it.

**Done when:**
- two phones with no network start sending within 20 s of scanning;
- a 5 GB phone-to-phone send reaches **at least 70% of Quick Share's speed** on the same phones;
- a phone joins a TV's hotspot by QR.

### Phase 5: distribution and polish (3 to 4 weeks, then ongoing)

**M**
- Android release signing and a plan for backing up the key;
- **Windows code signing** (OV certificate, or Azure Trusted Signing);
- GitHub Releases with checksums;
- opt-in update checks;
- user guide.

**N**
- F-Droid or IzzyOnDroid;
- a Play Store build;
- winget and Scoop;
- the web page using v2 chunked uploads, so iPhones get resumable uploads through Safari;
- a LocalSend adapter (see E);
- PAKE code pairing.

### Testing strategy
1. **Spec first, with test vectors**, used by both apps' unit tests.
2. **Conformance suite:** a black-box pytest runner pointed at any `host:port`. It covers:
   - v1 and v2 endpoints and errors;
   - path traversal and bad input;
   - idle timeouts and slow senders;
   - resume, wrong digests and `503`;
   - the client side, through a scripted fake receiver.
3. **CI:** both apps run against each other on GitHub Actions: the Python app against the Kotlin core on the JVM, in both directions.
4. **Fault-injection proxy:** it cuts connections at random points, stalls without closing, adds 50 to 200 ms of delay, and limits bandwidth. Also kill processes at random.
5. **Fuzzing:** offer JSON, batch records and file names (Hypothesis in Python, Jazzer in Kotlin).
6. **Benchmarks:**
   - Fixed data sets: 1 × 20 GB, 1 × 4 GB video, 10,000 × 50 KB, and 50,000 mixed photos.
   - Record an `iperf3` baseline for each device pair, then MB/s, files/s, CPU, battery per GB and resume overhead.
   - Compare with LocalSend and Quick Share on the same hardware, and publish the results.
7. **Devices:**

| Group | Devices | Why |
|---|---|---|
| Windows | Win 10 22H2 laptop (Wi-Fi 5); Win 11 24H2 desktop (Ethernet + Wi-Fi 6) | Firewall, sleep, NTFS, `netsh` limits |
| Phones | Recent Pixel; Samsung mid-range; Xiaomi/Redmi (HyperOS); an Android 7-8 phone; an Android Go phone | Behaviour differences, battery managers, low memory |
| TV | Google TV Streamer or Chromecast with Google TV; NVIDIA Shield; cheap Amlogic box (Android 9-11, 32-bit); Fire TV Stick 4K; a Sony or TCL Google TV set | No DocumentsUI, no Wi-Fi Direct, slow storage, Fire OS without Google Play |
| Tablets | Fire tablet; Samsung tablet | No Play Services; big screens |
| Networks | 5 GHz router; 2.4 GHz only; mesh with client isolation; guest network; Windows hotspot; Android hotspot; Wi-Fi Direct | Every way of finding and reaching a device |

8. **Long tests:** 24 hours of TV receive mode, and overnight phone-to-PC backups.
9. **Security review** of the device proof and pairing before the 1.3 and Android releases.

---

## E. Competitors

| Product | Better than us today | Weaker for large files | What to learn |
|---|---|---|---|
| **LocalSend** | Every platform including iOS and macOS. Big user base. Open source. Browser receive mode. Partial accept. Subnet-scan fallback. | No resume. Users report problems with many files and slow speeds. Can't be used with a TV remote. Encryption can be turned off. Sender fingerprint not proven. | Partial accept, auto-save for favourites, scan fallback, browser link mode, `sha256` and `modified` metadata |
| **Quick Share** (Google/Samsung) | Built in. Bluetooth discovery, then Wi-Fi Direct or hotspot, so phone to phone is very fast with no router. Windows app. | Needs Google Play Services (no AOSP TV boxes, Fire TV or Huawei). Not on Android TV. Closed. | Low-power discovery first, then the fastest link. Our QR flow is the simpler version. |
| **Send Anywhere** | Works over the internet with a 6-digit key. | Needs internet and a server. Ads and accounts. | The short-code experience |
| **Snapdrop / PairDrop** | No install. | Needs a signalling server. Browsers struggle with multi-GB files. No resume. | Our HTTPS web page already covers this on a LAN with no internet |
| **KDE Connect** | Strong pairing, clipboard, notifications. | File transfer is a side feature. No resume. Not for TV. | Pairing and trust experience |
| **SHAREit / Xender** | Fast phone to phone with no router, hugely popular in South Asia. | Heavy ads, privacy concerns, security flaws. | "No router" matters. A clean, encrypted, ad-free option has room. |

**LocalSend compatibility**
- **Don't base v2 on the LocalSend protocol.** It has no resume or chunking, no proven sender identity, an unencrypted mode, and we don't control how it changes.
- **After Phase 2, consider an optional receive-only LocalSend adapter**, about 1 to 2 weeks per app. Our offer, token and upload design is close to theirs. This would let iPhone and Mac users running LocalSend send to us without an iOS app.
- **Costs of the adapter:**
  - port 53317 clashes if LocalSend itself is installed;
  - their protocol keeps changing, so it needs maintenance;
  - those transfers only get LocalSend-level guarantees, and the UI should say so.
- **Licence:** follow the Apache-2.0 licence if any of their code is used.
- **Don't copy Quick Share's reverse-engineered protocol.** It is fragile and Google can change it at any time.

**What makes us the best for large files.** Not raw speed: on one LAN every tool hits the same Wi-Fi limit. It's this combination:
1. **Never lose progress.** Resume across drops, sleep, app kills and reboots.
2. **Verified.** A per-file digest check, shown to the user.
3. **Many files at full speed**, with published benchmarks.
4. **No router needed**, on any Android device, including ones without Google services.
5. **Android TV as a first-class device:** remote-only UI, APK install, open in VLC or Kodi.
6. **Trusted devices** that skip prompts after one QR scan.
7. **No account, no internet, no ads, no Play Services**, plus a web page for everything else, including iPhones.

---

## F. Risks and open questions

**Questions for the owner** (answers go in `PROGRESS.md` under "Decisions")
1. **Team and time.** This is roughly 6 to 9 months for one developer per app. Who builds the Android app? Is there a date?
2. **Open source?** F-Droid, IzzyOnDroid and LocalSend's trust come from being open source. A closed app needs a clear reason to exist.
3. **Play Store?** Its rules restrict "All files access", APK install, foreground services and battery exemptions. Plan two builds from the start if Play is likely.
4. **iOS or macOS, ever?** If within about 18 months, consider Kotlin Multiplatform for the protocol core now.
5. **Desktop stack.** Python is fine up to gigabit. A faster shared core (Rust or Kotlin) may be needed later. Not recommended now.
6. **Name.** "LAN Share" doesn't describe Wi-Fi Direct or phone-to-phone use.
7. **Money.** Ads or tracking would destroy the main advantages. Decide early how this is paid for.

**Technical risks**
1. **Wi-Fi Direct** varies by chipset and maker, many TVs lack it, and Windows support from Python is unproven. Do spikes before fixing Phase 4 dates.
2. **The device proof and pairing** must be reviewed. A mistake there is worse than having no feature.
3. **TV storage:** sending arbitrary files from many Android TV 11+ devices may need "All files access" granted over adb.
4. **Battery managers** on Xiaomi, Samsung and others will cause support work. Resume is the main protection.
5. **Windows warnings:** without code signing, SmartScreen and Defender will hurt adoption more than any missing feature.
6. **Losing the Android signing key** is permanent. Write a custody plan before the first public APK.
7. **v1 support** is cheap but not free. Pick an end date, for example a year after 1.3 ships.

---

## Sources
- [LocalSend protocol](https://github.com/localsend/protocol/blob/main/README.md)
- [LocalSend repository](https://github.com/localsend/localsend)
- LocalSend issues: [#1191 resume](https://github.com/localsend/localsend/issues/1191), [#2824 batch resume](https://github.com/localsend/localsend/issues/2824), [#2268 many files](https://github.com/localsend/localsend/issues/2268), [#2558 slow transfers](https://github.com/localsend/localsend/issues/2558), [#1264 TV remote](https://github.com/localsend/localsend/issues/1264)
- [Compose UI release notes (minSdk 23)](https://developer.android.com/jetpack/androidx/releases/compose-ui)
- [Compose for TV release notes](https://developer.android.com/jetpack/androidx/releases/tv)

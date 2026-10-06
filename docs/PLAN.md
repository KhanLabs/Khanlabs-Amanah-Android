# KhanLabs LAN Share: the complete plan

| | |
|---|---|
| Date | 2026-10-06 |
| Owner | Khan (Islamabad, Pakistan) |
| Windows app | Python and Tkinter, version 1.2.1, repo `KhanLabs/khanlabs-lanshare` |
| Android app | Kotlin and Compose, version 0.1.0 (debug), repo `KhanLabs/khanlabs-lanshare-android` |
| Test phone | OnePlus 8T, Android 14, on the "Khan 2.4G" Wi-Fi |
| Replaces | `docs/PLAN.md` in both repos (written 2026-10-05) |
| Also uses | `docs/UI-RESEARCH.md`, `PROTOCOL.md`, `PROTOCOL-v2.md`, `PROGRESS.md` in both repos |

**How to read this plan**
- Estimates are in working days for one developer who knows the code.
- "Unverified" means a fact was not confirmed from a primary source on 2026-10-06.
- Sources are linked where they are used, and listed again at the end.
- Phases are named R1 to R10 (section 6). Features in section 3 point to these phases.
- Release gates are named G1 to G17 (section 7.18).

---

## 1. Summary

**What it is.** KhanLabs LAN Share moves files and text between your own devices. It works on the same Wi-Fi today, and later with no router at all. There is a Windows app and an Android app. An Android TV version comes later. "Share to anyone" will let a person with no app download files in their browser by scanning a QR code.

**Who it is for**
1. People who move large files and folders between a phone and a PC.
2. Homes with an Android TV or a TV box, where sending files with a remote is hard today.
3. People who use SHAREit or Xender today and want the same "no internet needed" speed without ads or tracking. This is common in Pakistan and South Asia.
4. People with devices that have no Google services, such as Fire TV and cheap TV boxes.

**The promise**
> Built for people, not advertisers. No ads. No tracking. No accounts. No cloud required. Free forever.

Every screen shows "Made with ♥ by Khan" at the bottom.

**The top priority is reliability.** The owner's main goal is an app that works without fail. In order:
1. Never lose a file and never save a wrong one. Every file is checked with SHA-256.
2. Transfers resume after any drop, sleep, app kill or reboot.
3. Devices are always found. When automatic discovery fails, a QR code always works.
4. Every error is clear and says what to do next.
5. Only then, new features.

The roadmap (section 6) follows this order. The release gates (section 7.18) are strict. Any single case of a wrong file saved as "done" blocks every release until it is fixed and tested.

**Where things stand today**
- Windows 1.2.1 is built and runs on the owner's PC. It fixed the hang, offer spam, copied identity, sleep and progress problems found in 1.2.0.
- Android 0.1.0 sends and receives files, folders and text with Windows over protocol v1. It passes the Windows conformance suite (38 of 38) on the real phone over Wi-Fi.
- Real-phone tests on 2026-10-06 (OnePlus 8T, 2.4 GHz Wi-Fi):
  - **Passed:** 1 GB PC to phone with the screen off and deep Doze forced. 120 s at about 9 MB/s, SHA-256 matches.
  - **Failed, fixed, then passed:** 2,000 files (64 MB) in 25 nested folders with hard names. One name with a leading space made the phone refuse the whole send with a vague message. Windows now makes names safe before sending (commit c21d535). All 2,000 files then arrived with the right paths and hashes.
  - **Measured:** small files are slow in v1, about 14 files per second (2,000 files in 141 s). Protocol v2 must reach at least 300 small files per second on Wi-Fi 5 (gate G17).
- Protocol v2 (resume, checks, fast small files, device proof, QR pairing) is written as a draft with test vectors. Neither app runs it yet.
- Neither repo has automated builds (no CI).
- Nothing is public yet. Both repos are private.

**What comes next (one developer)**

| Step | Phases | Working days |
|---|---|---|
| Reliability first: test lab, v1 fixes, protocol v2 on both apps, hardening | R1 to R5 | 93 to 122 |
| First public release: Windows 1.3 and Android 1.0, both with protocol v2 | end of R5 | |
| Features: UI changes, Share to anyone, TV, direct mode, stores, tip page | R6 to R10 | 81 to 116 |
| **Total** | | **174 to 238 (about 8 to 11 months)** |

**The five gates that matter most** (full list in section 7.18)
1. Zero files saved wrong. 2,000 of 2,000 clean scripted transfers pass, and no chaos run ever saves a wrong file.
2. Transfers survive kills and drops. 100 random kills per device pair all resume, and each resume re-sends at most (connections × chunk size), for example 32 MiB.
3. Devices are found. At least 99% of discovery trials find the other device within 15 seconds, and QR connect works in 100% of trials.
4. Privacy is provable. A 30-minute packet capture shows zero connections from the apps to any address outside the local network, and the Exodus report shows zero trackers.
5. Long runs stay healthy. 24-hour and 72-hour soak tests end with zero crashes, memory growth of 10% or less, and no growth in open handles.

---

## 2. Product principles

### 2.1 Reliability comes before features
1. A feature does not ship until the reliability gates in section 7.18 pass with it turned on.
2. A file is "done" only after its SHA-256 digest matches. Until protocol v2, the app does not claim "verified".
3. A transfer never fails silently. It either completes and is checked, or it stops with a message that says what happened and what to do.
4. Progress counts bytes the receiver has saved, not bytes that were sent.
5. If two goals conflict, the safer one wins. For example, a slower transfer that resumes is better than a faster one that cannot.
6. One odd file never stops a whole send. Senders make every name safe before sending. If a receiver still refuses something, it names the file and the reason, and the sender shows that. (Lesson from the 2,000-file test on 2026-10-06.)

### 2.2 Privacy
1. **Local only.** The apps talk only to devices on the local network. Files and text go straight from one device to the other. They never pass through a server, ours or anyone else's.
2. **Encrypted between apps.** App-to-app transfers use TLS, pinned to the other device's ID (the SHA-256 of its certificate).
3. **Two honest exceptions, stated in the app:**
   - "Share to anyone" and the folder share page use plain HTTP, so a browser with no app can open them (section 5.8). The link has a secret 16-byte token, and an optional PIN.
   - The Windows folder share can use plain FTP if the user turns it on. It is off by default.
4. **The device name and ID are visible** to other devices on the same Wi-Fi while the app is open. The privacy text says so.
5. **No network calls of our own.** The apps never contact the internet. The only links to khanlabs.dev open in the browser when the user taps them.
6. **Prove it.** A CI check blocks tracker libraries. An Exodus report is linked. Packet captures are part of every release test (section 7.11).
7. **Planned guard:** the network code refuses any address that is not private (10/8, 172.16/12, 192.168/16, 169.254/16, and local IPv6 ranges). Then the app cannot reach the internet even by mistake. Open question: whether to allow VPN ranges such as 100.64/10 (section 9).

### 2.3 Free, with optional one-time tips
1. **100% free.** No ads. No subscriptions. No feature or transfer limits. No account. No telemetry of any kind: no crash upload, no analytics, no update pings.
2. **Everything is included** for everyone.
3. **One-time tips only.** No monthly or recurring support. No memberships. No "supporter" features.
4. **One calm card**, only in About (phone and Windows) and in Settings: "Enjoying the app? Leave a one-time tip." Buttons: $2, $5, $10, $25 and Custom. Each opens `https://khanlabs.dev/support?amount=N` in the browser.
5. **The app has no payment code and no crypto code.** Card, bank and crypto options live only on the website page.
6. **Never nag.** The app never asks for a tip. No pop-ups, no badges, no red dots, no reminders, no message after a transfer, no "you have sent 100 files" prompts. The card never opens by itself.
7. **The tip page is low priority.** It is built last (phase R10). Until then the buttons may point to a page that says "Thank you. Tips are not set up yet."
8. **Google Play build:** no tip card and no link to the tip page (section 8.6).

### 2.4 Brand
| Item | Rule |
|---|---|
| Name | KhanLabs LAN Share. Wordmark in Georgia. Body text in Segoe UI on Windows, the system font elsewhere. |
| Colors | The current scheme everywhere (section 4.1). No new accent colors. |
| Brand line | "Built for people, not advertisers. No ads. No tracking. No accounts. No cloud required. Free forever." |
| Footer | "Made with ♥ by Khan" at the bottom of every screen. The heart is red #FF5F57, drawn as an icon or a text glyph, never an emoji. Screen readers read "Made with love by Khan". |
| Short privacy line | "No account. No ads. Nothing leaves your network." |
| Wording | "New device" instead of "not verified". "Paired" after QR pairing. Plain, calm words in errors. |

### 2.5 What we must never add
1. Ad libraries of any kind (AdMob, AppLovin, Pangle and similar).
2. Analytics or install attribution (Firebase Analytics, AppsFlyer, Adjust), even "anonymous" ones.
3. Crash reporters that upload (Crashlytics, Sentry, Bugsnag, ACRA set to send). Instead: a local log and a "Copy error report" button the user can send themselves.
4. Automatic update checks, remote config, feature flags or licence checks. This removes the "opt-in update check" from the old plan. Updates come from the store, from Obtainium (which the user runs), or from a "Check for updates" button that opens the website in the browser.
5. Google Play Services, Firebase or ML Kit. QR codes use ZXing.
6. Outside fonts, images or scripts on any served web page. Everything is inline.
7. Internet relays, cloud sending or accounts. If ever wanted, they need a separate opt-in feature and a rewritten promise first.
8. Payment code, crypto code, in-app purchases, or subscriptions.
9. Any request for a tip outside the About and Settings card.
10. Paid features, "pro" versions, or limits that a tip removes.

### 2.6 Keeping the promise provable
1. CI fails the Android build if the APK contains known tracker packages (for example `com.google.firebase`, `com.google.android.gms.ads`, `io.sentry`, `com.appsflyer`).
2. CI fails the Windows build if the code imports telemetry packages or contains any `http://` or `https://` address that is not on a short allow list (khanlabs.dev pages).
3. Each release has an Exodus Privacy report ("0 trackers"), using [exodus-standalone](https://github.com/Exodus-Privacy/exodus-standalone).
4. Each release candidate passes a 30-minute packet capture with zero non-local connections (section 7.11).
5. If the apps become open source, anyone can check all of this. Open source is recommended (section 9).

---

## 3. Feature list

**Status:** Done, Partly (some of it works), Not started, or n/a (does not apply).
**Apps:** W = Windows, A = Android phone, TV = Android TV (same APK as the phone, later).
**Phase:** R1 to R10 from section 6. "Done" means finished before this plan.

### 3.1 Moving files and text

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 1 | Send and receive files over protocol v1 | Done | Done | Not started | Done; TV in R8 |
| 2 | Send and receive folders, keeping the structure | Done | Partly: receives folders (2,000 files in 25 nested folders passed on 2026-10-06); no Folder picker for sending | Not started | A: R6 |
| 3 | Text messages | Done | Done | Not started | Done |
| 4 | Accept and Decline prompt | Done (window with countdown, no focus grab) | Partly: notification and dialog work; duplicate buttons, no countdown | Not started | A: R2 |
| 5 | Progress with speed and time left | Done | Done | Not started | Done |
| 6 | Cancel a transfer | Done | Partly: receiver side passes conformance; sender side not tested on the phone | Not started | A: R1 (test) |
| 7 | Keep the device awake during a transfer | Done | Done (wake lock, Wi-Fi lock, foreground service) | Not started | Done |
| 8 | Protocol v2: sessions, chunks with SHA-256, batches for small files, parallel connections | Partly: spec, reference code `klv2.py`, test vectors | Partly: `core-protocol` passes all vectors; no transport | Not started | W: R3, A: R4 |
| 9 | Resume after any drop, kill or reboot (journal) | Not started | Not started | Not started | W: R3, A: R4 |
| 10 | "Verified" mark after the file digest matches | Not started | Not started | Not started | W: R3, A: R4 |
| 11 | No file-count limit (manifest pages), modified times, empty folders | Not started | Not started | Not started | W: R3, A: R4 |
| 12 | Free-space and FAT32 4 GB check before accepting | Not started | Not started | Not started | W: R3, A: R4 |
| 13 | Skip identical files when a folder is sent again | Not started | Not started | Not started | R4 (nice to have) |
| 14 | Choose which files to accept | Not started | Not started | Not started | R6 (nice to have) |
| 15 | Send to several devices at once | Not started | Not started | n/a | R6 (nice to have) |
| 16 | Command line `klshare send` | Not started | n/a | n/a | R6 (nice to have) |
| 17 | Clipboard push to paired devices | Not started | Not started | n/a | R6 (nice to have) |

### 3.2 Finding devices

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 18 | mDNS announce and browse (`_klshare._tcp`) | Done | Done | Not started | Done |
| 19 | Re-announce when the network changes | Partly: built; not tested on a real network change | Partly: not tested | Not started | R1 (test), A: R2 |
| 20 | Re-check a "lost" device with `/info` before removing it | n/a | Done | Not started | Done |
| 21 | Add a device by IP address | Done | Done | Not started | Done |
| 22 | Show a QR code to connect (address, port, full ID) | Not started | Not started | Not started | R2; TV R8 |
| 23 | Scan a QR code to connect (CameraX and ZXing) | n/a | Not started | n/a | R2 |
| 24 | "Search this network" fallback when multicast is blocked | Not started | Not started | Not started | R2 |
| 25 | "Not showing up?" help | Not started | Not started | Not started | R2 |
| 26 | Windows "Public network" check | Not started | n/a | n/a | R2 |
| 27 | Firewall warning and Fix button | Partly: built; the UAC path is not tested | n/a | n/a | R1 (test) |
| 28 | Android 17 local network permission (`ACCESS_LOCAL_NETWORK`) | n/a | Not started | Not started | R2 |

### 3.3 Trust and safety

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 29 | Limits on connections and pending offers (`429 busy`) | Done | Done | Not started | Done |
| 30 | New identity when the app is copied to another PC | Done | n/a | n/a | Done |
| 31 | Identity kept out of backups | n/a | Done (`allowBackup=false`, `noBackupFilesDir`) | Not started | Done |
| 32 | "New device" wording instead of "not verified" | Not started | Not started | Not started | R2 |
| 33 | Device proof (signed, protocol v2) | Partly: spec and reference code | Partly: proof code passes vectors | Not started | W: R3, A: R4 |
| 34 | QR pairing | Not started | Not started | Not started | W: R3, A: R4, TV: R8 |
| 35 | Paired devices list (rename, forget) | Not started | Not started | Not started | W: R3, A: R4 |
| 36 | Receive policy: Off, Ask every time, Paired devices only, Everyone for 10 minutes | Partly: receive on/off only | Partly: receive on/off only | Not started | W: R3, A: R4 |
| 37 | Accept without asking, per paired device | Not started | Not started | Not started | W: R3, A: R4, TV: R8 |
| 38 | Private-address guard (refuse non-local addresses) | Not started | Not started | Not started | R2 |
| 39 | Mark received files as "from another computer" on Windows (Mark of the Web) | Not started | n/a | n/a | R2 |
| 40 | Local error log and "Copy error report" button | Partly: `error.log` exists | Not started | Not started | R2 |
| 41 | Clear error messages with a next step, for every failure | Partly | Partly | Not started | R2 |
| 41a | Sender makes every name safe before sending (v1) | Done (commit c21d535, `safe_part` and `safe_relpath`) | Done (`Names.safePart`) | Not started | Done; CI corpus in R1 |
| 41b | A refused offer names the file and the reason, and the sender shows it | Not started (today: "each file needs a unique id, a valid relative name and a size") | Not started | Not started | R2 |

### 3.4 Receiving, saving and history

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 42 | Own receive folder (`Downloads\KhanLabs`, `Download/KhanLabs`) | Done | Done (MediaStore with `IS_PENDING`) | Not started | Done |
| 43 | Pick another save folder (SAF on Android) | Done | Not started | Not started | A: R4 |
| 44 | Background transfers: user-initiated data transfer jobs on Android 14+ | n/a | Partly: `dataSync` foreground service only | Not started | R4 |
| 45 | Handle Android 15's 6-hour `dataSync` limit cleanly | n/a | Not started (known gap) | Not started | R2 |
| 46 | Read shared files at the moment they are shared, not later | n/a | Not started (known gap) | n/a | R2 |
| 47 | Activity / History list | Done | Not started | Not started | A: R6 |
| 48 | Open, Show in folder and Share after receiving | Done | Not started | Not started | A: R6 |

### 3.5 Ways to send and the main UI

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 49 | Android share sheet as a way in | n/a | Done | n/a | Done |
| 50 | Content-first Send buttons: Files, Photos, Folder, Text | n/a | Not started (device-first works) | n/a | R6 |
| 51 | Recent devices in the Android share sheet (Sharing Shortcuts) | n/a | Not started | n/a | R6 |
| 52 | Quick Settings tile for receiving | n/a | Not started | n/a | R6 |
| 53 | Drag and drop onto a device (`tkinterdnd2`) | Not started | n/a | n/a | R6 |
| 54 | Explorer "Send to" entry (opt-in) | Not started | n/a | n/a | R6 |
| 55 | Phone tabs: Send, Activity, Settings | n/a | Not started | n/a | R6 |
| 56 | Receiving chip and Receiving sheet | Not started | Not started | n/a | R6 |
| 57 | Windows: four tabs (Send, Share a folder, History, Settings), device cards | Not started (five tabs and a table today) | n/a | n/a | R6 |
| 58 | First-run screen | Not started | Not started | Not started | A: R2 (minimal), W: R6, TV: R8 |
| 59 | Tray menu: Send files, Show QR code, Receiving on or off | Partly: tray icon, close to tray | n/a | n/a | R6 |

### 3.6 Brand, About and tips

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 60 | "Made with ♥ by Khan" footer | Done (contrast fix needed, section 4.2) | Partly: built and pinned; not yet tested at 200% font and 320 dp | Not started | Fix in R2, tests in R1, TV in R8 |
| 61 | Footer on the folder share web page | Done (light theme fix needed) | n/a | n/a | R2 |
| 62 | Footer on the Share to anyone page | Not started | Not started | n/a | R7 |
| 63 | About screen: version, brand line, promises, tip card | Done | Done | Not started | Done; wording "one-time tip" in R2 |
| 64 | Tip page on khanlabs.dev (card, bank, crypto) | n/a (website) | n/a (website) | n/a | R10 (low priority) |
| 65 | Play build without the tip card | n/a | Not started | Not started | R10 |

### 3.7 Share to anyone and the folder share

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 66 | Folder share web page (HTTPS, PIN, notepad, uploads) | Done | n/a | n/a | Done |
| 67 | FTP and FTPS folder share | Done | n/a | n/a | Done |
| 68 | Fix: notepad save accepts requests from other sites (CSRF) | Not started | n/a | n/a | R2 |
| 69 | Fix: PIN lockout is per IP only; add a cap for all IPs | Not started | n/a | n/a | R2 |
| 70 | Fix: "Download all" zip has no length, so a cut download can look complete | Not started | n/a | n/a | R2 |
| 71 | Share to anyone: pick files, QR and link screen | Not started (prototype only) | Not started | Not started | R7; TV later |
| 72 | Share to anyone page: grouped files, thumbnails, Download all, Range, PIN, time limit | Not started (prototype only) | Not started | n/a | R7 |
| 73 | Web page uploads with v2 chunks (resumable uploads from iPhones) | Not started | n/a | n/a | R10 (nice to have) |

### 3.8 TV, direct mode and distribution

| # | Feature | W | A | TV | Phase |
|---|---|---|---|---|---|
| 74 | TV: launcher entry, D-pad screens, receive home with QR, Received list | n/a | n/a | Not started | R8 |
| 75 | TV: open files with other apps, install APKs, in-app file browser | n/a | n/a | Not started | R8 |
| 76 | Direct mode: Wi-Fi Direct phone to phone, started by QR | n/a | Not started | Not started | R9 |
| 77 | Direct mode: LocalOnlyHotspot fallback, `POST /hello` | n/a | Not started | Not started | R9 |
| 78 | Windows joins a phone's group as normal Wi-Fi | Not started | n/a | n/a | R9 |
| 79 | CI on both repos | Not started | Not started | n/a | R1 |
| 80 | Signed release builds with checksums, public download page | Partly: unsigned exe built | Not started | Not started | R5 |
| 81 | Android developer verification registration | n/a | Not started | Not started | R5 |
| 82 | IzzyOnDroid or F-Droid (needs open source) | n/a | Not started | Not started | R10 |
| 83 | Google Play build | n/a | Not started | Not started | R10 |
| 84 | winget and Scoop | Not started | n/a | n/a | R10 |
| 85 | LocalSend receive adapter | Not started | Not started | n/a | R10 (nice to have) |
| 86 | Pairing by code without a camera (PAKE) | Not started | Not started | n/a | R10 (nice to have) |

---

## 4. UI plan

### 4.1 Color tokens

The owner's scheme is fixed. These tokens are the single source for Windows (`brand.py`), Android (`Theme.kt`) and every web page.

**Dark theme (all apps; the default on Windows and TV)**

| Token | Hex | Use | Contrast notes (WCAG 2.x) |
|---|---|---|---|
| paper | #080A0E | Page background | |
| card | #12151B | Cards, footer bar | |
| card2 | #181C24 | Raised cards | |
| hover | #1F2530 | Hover and pressed states | |
| ink | #E7EAEF | Main text | 16.4 on paper, 15.2 on card, 12.8 on hover |
| muted | #97A0AD | Secondary text, **the footer text** | 7.5 on paper, 6.9 on card, 5.8 on hover. Passes AA everywhere. |
| teal | #2DD4BF | Accent, buttons, focus | 10.6 on paper, 9.8 on card |
| sage | #34D399 | Success | 10.3 on paper, 9.5 on card |
| brick | #FF5F57 | Errors, **the heart** | 6.6 on paper, 6.1 on card, 5.2 on hover |
| border | #2F3742 | Lines between areas | 1.52 on card. Decorative only. |
| faint | #5C6470 | **Do not use for text** | 3.31 on paper, 3.06 on card, 2.57 on hover. Fails AA. |

Text on a teal button uses paper #080A0E (10.64:1). White on teal is 1.86:1 and must never be used.

**Light theme (web pages and the phone in light mode)**

| Token | Hex | Use | Contrast |
|---|---|---|---|
| bg | #F6F7F9 | Page background | |
| card | #FFFFFF | Cards | |
| line | #E2E5EA | Lines | Decorative only |
| ink | #11141B | Main text | 17.2 on bg, 18.4 on white |
| muted | #5B6472 | Secondary text, footer | 5.58 on bg, 5.98 on white |
| teal | #0F766E | Accent, buttons | 5.11 on bg, 5.47 on white |
| on-teal | #FFFFFF | Text on teal buttons | 5.47 |
| error | #C62828 | Errors | 5.24 on bg, 5.62 on white |
| heart | #FF5F57 | The heart | **2.79 on bg, 2.99 on white. Below 3:1.** See open question Q3. |

**Rules**
1. No text uses `faint`. This fixes the Windows footer, which uses `faint` today for "Made with" and the version label (`lan_ftp.py`, `made_with()`, 3.06:1 on card).
2. Control outlines and focus rings need 3:1 against their background. Use muted or teal, not border.
3. Every web page sets `<meta name="color-scheme" content="light dark">` and CSS `color-scheme`. This stops Brave and Samsung Internet from forcing their own dark colors. The 2026-10-06 Brave test showed white text on teal (1.86:1) on the folder page "Download all" button.
4. The folder share page's light theme is warm beige today (paper #f4efe4, accent #0d9488). Its accent on its background is 3.27:1, white on the accent is 3.74:1 and its faint text is 2.5:1. All three fail. It moves to the light tokens above.
5. The Android light scheme today sets only primary, secondary and error. It gets the full light token set.
6. Colors never carry meaning alone. "Paired" and "New" are words, not just colors.

### 4.2 The footer: "Made with ♥ by Khan"

| Platform | Where | Content | Status |
|---|---|---|---|
| Windows | Bottom bar of the main window and of the About window | "KhanLabs LAN Share v1.2.1 \| Made with ♥ by Khan \| About \| khanlabs.dev" | Done. Fix: text color faint to muted. |
| Phone | A bar below every screen, above the system navigation bar | "Made with ♥ by Khan". Tapping it opens About. | Built (commit 6d37632). Needs the tests below. |
| Folder share page | Pinned to the bottom of the page | Version, "Made with ♥ by Khan", khanlabs.dev link | Done (commit 70cb613). Fix the light theme. |
| Share to anyone page | Pinned to the bottom of the page | "Made with ♥ by Khan" and "Sent straight from Khan's PC over this Wi-Fi. Nothing is uploaded." | R7 |
| PIN page, "share ended" page | Same as above | "Made with ♥ by Khan" | R7 |
| TV | A thin line inside the safe area at the bottom | "Made with ♥ by Khan" | R8 |

**Rules**
1. **Every screen** means every full-screen place the user can be: tabs, settings pages, About, first run, the QR scanner, the Share to anyone screen, and every web page. Modal sheets, dialogs and the keyboard may cover it while they are open. It is visible again when they close. (Owner to confirm, Q4.)
2. The text is muted. The heart is brick #FF5F57 on dark. On light, see Q3.
3. The heart is an icon (Android vector, `contentDescription = "love"`) or the text glyph U+2665 followed by U+FE0E so it never turns into a color emoji. On the web it has `role="img"` and `aria-label="love"`.
4. It never clips. At 200% font size or 320 dp width it wraps to a second line instead.
5. It stays above the Android navigation bar (window insets), and inside the TV safe area.
6. It has no tip link and no counter. It is a signature, not an advertisement.

### 4.3 Phone screens

**Main screen (Send tab)**
```
+--------------------------------------------+
| LAN Share                 (o) Receiving  > |  chip: opens the Receiving sheet
+--------------------------------------------+
| [ Sending 3 files to Khan                ] |  only while something runs
| [ ########----  64%  12 MB/s  8 s  Cancel ] |
|                                            |
| Send                                       |
| [ Files ] [ Photos ] [ Folder ] [ Text ]   |
|                                            |
| Nearby                                     |
| [PC]  Khan               Windows PC  Paired|
| [TV]  Living room TV     Android TV  New   |
| Not showing up?                            |
+--------------------------------------------+
|        Made with ♥ by Khan                 |
+--------------------------------------------+
|   Send        Activity        Settings     |
+--------------------------------------------+
```
1. Device rows show an icon, the name, a readable type and a badge: **Paired** (after QR pairing) or **New**. The IP and ID move to a long-press "Details" sheet.
2. The app looks for devices the whole time it is open, whether receiving is on or not. Pull down to refresh.
3. Conformance mode moves to Settings, Developer, in debug builds only.
4. Empty state: "Open LAN Share on your PC or TV" with [Scan QR code], and "Get it for Windows at khanlabs.dev".

**Send flow (content first)**
1. Tap Files, Photos, Folder or Text. The system picker opens.
2. A sheet shows "Send 3 files (12 MB) to..." and the device list.
3. Tap a device. Sending starts. Progress shows on the device row and in the top card.

Device-first sending stays: tap a device, then pick Files, Photos and videos, Folder, Text or Paste clipboard. The share sheet stays, plus Sharing Shortcuts for the two most recent devices.

**Offer sheet (receiving)**
```
+--------------------------------------------+
| Khan wants to send you                     |
| [PC] Khan  -  Windows PC  -  New device    |
|      ID 194E-0972   (i)                    |
| 4 files, 50.3 MB                           |
|   photo (1).jpg, big.bin, empty.txt        |
|   and 1 more                (See all)      |
| "hello from the PC"                        |
| Answer within 1:52                         |
| [ Decline ]                   [ Accept ]   |
+--------------------------------------------+
```
1. One pair of buttons only. The Activity row behind it says "Waiting for your answer".
2. The (i) text: "Anyone on this Wi-Fi can pick a name. To be sure this is really Khan's PC, pair by scanning its QR code."
3. After pairing (protocol v2), the line shows "Paired" with a check mark.
4. When done: "Received 4 files from Khan" with Open, Show files and Share. For text: Copy, and Open link if it is one URL.

**Receiving sheet** (from the chip)
```
Receiving
 ( ) Off
 (*) Ask me every time
 ( ) Paired devices only            after protocol v2
This phone: OnePlus 8T                 [Rename]
[ QR code ]  Scan this from LAN Share on another device
Address 192.168.1.241:47820  -  ID 1520-296C
Saved to Download/KhanLabs             [Change]
```

**Activity tab:** a timeline (Today, Yesterday) with an All, In, Out filter. Each row opens its file list. Buttons: Show in folder, Share, Remove from list (the files stay). Stored in Room.

**Settings tab**
1. This device: name, full ID with Copy, addresses.
2. Receiving: mode, save folder, "Keep receiving in the background" (while open, for 10 minutes, always), battery help per phone maker.
3. Paired devices: rename, forget, "Accept without asking" for each.
4. Privacy: the privacy text (section 4.8) and "What this app can access", a list of permissions and whether each is on.
5. About (section 4.7).
6. Developer (debug builds only): Conformance mode.

**"Not showing up?" sheet**
1. Check that both devices are on the same Wi-Fi. Guest networks often block this.
2. On the PC, open LAN Share and allow it through the firewall.
3. **Scan the PC's QR code.** This always works when both devices are on the same network.
4. Search this network (the fallback scan).
5. Add by IP address.

**First run** (one screen)
```
+--------------------------------------------+
| KhanLabs LAN Share                         |
| Send files to your own devices on this     |
| Wi-Fi.                                     |
| No account. No ads. Nothing leaves your    |
| network.                                   |
| Name this phone                            |
| [ OnePlus 8T                          ]    |
| Other devices on this Wi-Fi see this name. |
|                       [ Continue ]         |
|        Made with ♥ by Khan                 |
+--------------------------------------------+
```
Permissions are asked when first needed, each with one line of reason:

| Permission | When | Line shown first |
|---|---|---|
| Local network (Android 17+, when the app targets API 37) | Right after Continue | "Android will ask if LAN Share can find devices on your network. This is how your PC shows up." |
| Notifications (Android 13+) | A card on the Send tab | "Get told when someone sends you something?" |
| Camera | When the user taps Scan QR code | "The camera is used only to read the QR code. No photos are taken." |
| Nearby Wi-Fi devices | Only for direct mode (R9) | "Needed to connect two phones without a router. Not used to find your location." |
| Battery optimization exemption | Only when "Keep receiving in the background" is turned on, sideload build only | "Some phones stop apps in the background. This keeps receiving on." |

The app stays usable if the person says no. Each refusal shows what still works.

**Share to anyone screen (R7)**
```
+--------------------------------------------+
| Share to anyone                    [Stop]  |
| OnePlus 8T  -  12 files  -  1.4 GB         |
| [        large QR code        ]            |
| http://192.168.1.241:47890/s/Xk3...        |
| [Copy link]                                |
| Ends in: [ 15 min v ]   PIN: [ off v ]     |
| If the browser warns, tap Continue or Keep.|
| The files come straight from this phone.   |
| 2 people downloading                       |
|        Made with ♥ by Khan                 |
+--------------------------------------------+
```

### 4.4 Windows screens

**Main window: four tabs** (Send, Share a folder, History, Settings). The notepad moves into Share a folder, because it belongs to the web page.
```
+------------------------------------------------------------------------+
| KhanLabs.dev | LAN Share     (o) Receiving: Ask every time v   Khan 194E |
+------------------------------------------------------------------------+
| Send | Share a folder | History | Settings                             |
+------------------------------------------------------------------------+
| Nearby                                 [Show my QR code] [Add by IP...] |
| [Phone] OnePlus 8T   [TV] Living room TV   [PC] Office PC               |
|  Android phone        Android TV            Windows PC                  |
|  Paired               New                   New                         |
| Not showing up?                                                         |
| +--------------------------------------------------------------------+ |
| |     Drop files or folders here to send to OnePlus 8T               | |
| |     or [Send files...] [Send a folder...] [Send text...]           | |
| +--------------------------------------------------------------------+ |
| Transfers                                            [Clear finished]  |
|  ^ To OnePlus 8T  video.mp4  ######--- 71%  38 MB/s  12 s   [Cancel]   |
+------------------------------------------------------------------------+
| KhanLabs LAN Share v1.3 | Made with ♥ by Khan | About | khanlabs.dev   |
+------------------------------------------------------------------------+
```
1. Device cards with icon, name, type and a Paired or New badge. The selected card has a teal border. Address and ID show on hover and in a right-click "Details".
2. Drag and drop with `tkinterdnd2`, which ships a PyInstaller hook ([tkinterdnd2](https://github.com/pmgagne/tkinterdnd2)).
3. Explorer "Send to" (opt-in in Settings) puts a shortcut in the user's `SendTo` folder. It starts the exe with the chosen paths, which opens "Send to which device?". A second launch hands the paths to the running copy through the single-instance port.
4. The Public network check: "Windows treats this network as public, so other devices may not see this PC." [Open network settings] [Allow anyway]. The owner's own "Khan 5G 2" network was on the Public profile (`HANDOFF.md`).

**Offer window:** keep the topmost window with a countdown that does not take focus, and Esc declines. Add the device type and the Paired or New badge. Replace "says ID ... (not verified)" with "New device. ID 194E-0972". Add a checkbox "Open the folder when it's done".

**First-run window (once):** device name; one button "Allow on private networks" (the firewall step, which says Windows will ask); a "Start with Windows" checkbox.

**Tray menu:** Send files..., Show QR code, Receiving on or off, Open received files, Quit.

**About window:** see section 4.7.

### 4.5 Browser pages

**Share to anyone page** (served by the sender's device)
```
+------------------------------------------+
|              [PC icon]                   |
|              Khan's PC                   |
|          12 files - 1.4 GB               |
|        [     Download all     ]          |
|   One .zip file. Or tap a single file.   |
| Photos 6                                 |
| [img][img][img]                          |
| Videos 1                                 |
| holiday.mp4             820 MB  Download |
| Documents 2  ...                         |
|                                          |
| Sent straight from Khan's PC over this   |
| Wi-Fi. Nothing is uploaded. This link    |
| stops working when the share ends.       |
|          Made with ♥ by Khan             |
+------------------------------------------+
```
1. Light or dark follows the phone's setting. Light uses the light tokens.
2. Thumbnails are made small by the sender (at most 64 KB each). Today's prototype sends full images.
3. Single files support Range requests, so videos play and seek in the browser and broken downloads can continue.
4. "Download all" has an exact `Content-Length` (section 5.8).
5. The PIN page and the "This share has ended" page use the same design and footer.
6. No outside fonts, scripts or images.

**Folder share page (Windows, existing):** keep it. Move to the light tokens, add `color-scheme`, fix the three safety issues (rows 68 to 70 in section 3).

### 4.6 TV screens (R8)
1. **Home is the receive screen:** the TV's name, a large QR code, and the address in small text (for "Add by IP" from a PC without a camera). Below it, the Received list with Open, Install (for APKs) and Delete.
2. **Remote only:** every control has a clear focus ring (3:1 or better), focus never gets lost, Back restores focus, and no action needs a long press.
3. **Safe margins:** 48 dp left and right, 27 dp top and bottom.
4. **Offer prompt:** for a new device, focus starts on Decline, so a stray OK press does not accept. Paired devices can skip the prompt.
5. **No photo thumbnails on the home screen**, because the TV is shared by the household.
6. Footer inside the safe area.

### 4.7 About and tips screen

Same content on phone, Windows and TV (TV has no tip card, because opening a browser on a TV is awkward; see Q12).

```
About
KhanLabs LAN Share
Version 1.3.0
Built for people, not advertisers.
  - No ads.
  - No tracking.
  - No accounts.
  - No cloud required.
  - Free forever.

+------------------------------------------+
| Enjoying the app?                        |
| Leave a one-time tip. Everything stays   |
| free either way.                         |
| [ $2 ] [ $5 ] [ $10 ] [ $25 ] [ Custom ] |
+------------------------------------------+
Website: khanlabs.dev       Open-source licences
        Made with ♥ by Khan
```
1. Each button opens `https://khanlabs.dev/support?amount=N` (Custom opens it with no amount) in the system browser.
2. If no browser exists: "No browser found. Visit khanlabs.dev/support on another device." (built today).
3. The card is one calm block. No images of coins, no progress bars, no "goal", no countdown.
4. The Play build has no tip card and no link to the tip page.
5. Wording change in R2: "Leave a one-time tip" instead of the current support wording, to match the one-time decision.

### 4.8 Wording

| Situation | Text |
|---|---|
| Unpaired sender | "New device. ID 194E-0972." with the (i) help |
| Paired sender | "Paired" with a check mark |
| Privacy (Settings) | "LAN Share collects no data. It has no ads, no analytics, no crash reports and no account. The app only talks to devices on your local network. Files and text go straight from one device to the other over an encrypted connection. They never pass through a server, ours or anyone else's. While the app is open, other devices on the same Wi-Fi can see this device's name." |
| Older peer | "Khan's PC runs an older version. This send works, but it cannot resume if it is cut off." |
| Transfer stopped | Always three parts: what happened, whether anything was lost, what to do. Example: "Stopped: the Wi-Fi connection dropped. 2 of 5 files were saved and checked. The rest will continue when both devices are back on the same Wi-Fi." |
| Never use | "not verified", "Oops", "Uh oh", "Something went wrong" with no detail, stack traces |

---
## 5. Architecture and protocol

### 5.1 The parts

| Part | Windows | Android (phone and TV, one APK) |
|---|---|---|
| Language and UI | Python 3.12, Tkinter, one-file exe (PyInstaller) | Kotlin, Jetpack Compose; Compose for TV later |
| App-to-app server | Own HTTPS server on port 47820 (falls back up to 47829), HTTP/1.0 replies | Own server: plain `ServerSocket`, TLS added per connection, HTTP/1.1 with `Connection: close` |
| Client | Python `ssl` and sockets, pinned to the peer's ID | OkHttp 5.5.0 with a trust manager that accepts exactly one certificate |
| Identity | ECDSA P-256 key and self-signed certificate; tied to a hash of Windows' `MachineGuid` | ECDSA P-256 with BouncyCastle 1.86, in `noBackupFilesDir` |
| Discovery | mDNS `_klshare._tcp`, re-announced after a network change (checked every 10 s) | `NsdManager`, `MulticastLock`, one resolve at a time |
| Saving | `Downloads\KhanLabs`, temp file then move without overwriting | MediaStore `Download/KhanLabs` with `IS_PENDING`; direct paths on API 23 to 28 |
| Folder share | HTTPS web page on port 80, FTP/FTPS on 21 (passive 60000 to 60099) | n/a |
| Single instance | Port 47829 on loopback | n/a |
| Tests | 33 + 15 + 25 + 16 unit tests; conformance 36 fast + 2 slow | 22 JVM tests (V1 5, V2 vectors 9, loopback 8) |

**Android modules:** `core-protocol` (pure JVM: models, name rules, chunk math, digests, batch format, proof), `core-net` (server, client, TLS identity, pinning), `core-link` (NSD, later Wi-Fi Direct and hotspot, QR payloads), `core-store` (MediaStore, SAF, later the resume journal and paired devices in Room), `transfer` (scheduler, foreground service, notifications), `ui-mobile`, `ui-tv` (not started), `app`. Build: AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00, `compileSdk 37`, `targetSdk 36`, `minSdk 23`, no native code, no Google Play Services.

**Principle kept from the old plan:** the protocol is the product. Both apps follow the written spec (`PROTOCOL.md`, `PROTOCOL-v2.md`). The conformance suite tests any app at any `host:port`. Test vectors are shared by both languages.

### 5.2 Protocol v1 (what runs today)

Base path `/api/kl/v1`, HTTPS, the server certificate pinned by device ID.

| Method | Path | Purpose |
|---|---|---|
| GET | `/info` | Name, ID, model, version |
| POST | `/offer` | Offer files and text. Blocks up to 120 s for Accept or Decline. |
| PUT | `/upload?offer=&file=&token=` | One file per request |
| POST | `/cancel` | Cancel an offer |

- **Device ID** = SHA-256 of the certificate DER. **Short ID** = its first 8 hex digits, shown as `194E-0972`.
- **mDNS TXT keys:** `id`, `name`, `model`, `v`, `app`, `share`. Unknown keys are ignored.
- **Plain HTTP** on the API port gets `301` to HTTPS. A wrong `Host` gets `421`.
- **Limits:** 16 connections per IP and 64 in total; 2 pending offers per IP and 8 in total (`429 busy`); 60 s idle timeout; 10,000 files; 4 MB offer body; 64 KB text.
- **Weak points** (fixed by v2): a send cannot resume; one connection and TLS handshake per file (measured on 2026-10-06: about 14 small files per second on 2.4 GHz); the sender ID in the offer is not proven; no checksums; the 120 s blocking offer.
- **Names in v1:** the receiver refuses a whole offer if one name breaks its rules. So **senders must make names safe** before sending. Windows does since commit c21d535 (`safe_part`, `safe_relpath`); Android does with `Names.safePart`. A refusal must name the file and the reason (R2).

### 5.3 Changes since the old plan

| # | Change | Why | Status |
|---|---|---|---|
| 1 | **Android server: plain `ServerSocket` with TLS added per connection**, instead of an `SSLServerSocket` with coroutines. One thread per connection, capped at 64. | Plain HTTP must still get a `301` to HTTPS, like Windows. Blocking code is simpler, and the connection cap bounds the threads. | Done |
| 2 | **`MSG_PEEK` on Android** to read the first byte without using it up (`SocketPeek.kt`, using `Os.poll` and `Os.recvfrom`). 0x16 means TLS; anything else is plain HTTP. | Android's TLS cannot take back bytes that were already read. Without this, every incoming connection was reset. | Done |
| 3 | **`Connection: close` on every v1 request.** | Windows answers with HTTP/1.0. OkHttp reused those connections and they failed. v2 uses keep-alive. | Done |
| 4 | **NSD "lost" devices are re-checked with `/info`** before being removed. | Android 14 reported the PC as lost every minute or two while it was still running. | Done |
| 5 | **`SO_REUSEADDR` on, except on Windows.** On Windows, use `SO_EXCLUSIVEADDRUSE` instead (check that the Python app does; unverified). | Without it, a restarted Android app moved from port 47820 to 47821. On Windows, `SO_REUSEADDR` lets another program take the same port. | Android done; Windows to check in R2 |
| 6 | **Android 17 local network permission.** `ACCESS_LOCAL_NETWORK` is a runtime permission in the Nearby devices group. It is enforced for apps that target API 37 or higher. Apps that target 36 or lower keep access through `INTERNET`. It blocks outgoing and **incoming** LAN TCP, UDP unicast, multicast and broadcast, mDNS, SSDP and `NsdManager`. DNS on port 53 is not blocked. When blocked, TCP times out and UDP gets `EPERM`. Sources: [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17), [local network permission](https://developer.android.com/privacy-and-security/local-network-permission). | Our server and discovery would both stop silently on the day we raise `targetSdk` to 37. | Declare it, ask at first run, and test with `adb shell am compat enable RESTRICT_LOCAL_NETWORK <package>` and a reboot (Android 16 Beta 3 or later, or 17). R2. |
| 7 | **QR pairing replaces the short ID as proof.** The short ID is only 32 bits; matching it takes hours of computer time. It stays as a label. The QR code carries the full 256-bit ID and a 128-bit one-time secret. | Only the QR code (or a future PAKE code) proves who the other device is. | R3 and R4 |
| 8 | **v2 device proof nonces are tied to the client's IP, not to one connection.** Single use, valid 120 s, at most 32 per IP. | OkHttp's connection pool may use a different connection for the next request. | In `PROTOCOL-v2.md` |
| 9 | **The v2 proof message is six lines:** `KLv2-proof`, purpose (`session`, `pair`, `hello`), receiver ID, sender ID, nonce, subject. Signed with ECDSA P-256 SHA-256. | A purpose line stops a proof made for one action being used for another. | In `PROTOCOL-v2.md` |
| 10 | **Receivers make names safe themselves** and report the saved name, instead of refusing the whole send. | One Android name with `:` should not stop 5,000 photos. | In `PROTOCOL-v2.md` |
| 11 | **v2 adds `DELETE .../files/{fid}`** (drop a file) **and `DELETE .../files/{fid}/chunks`** (restart a file). | For files that change during a send. | In `PROTOCOL-v2.md` |
| 12 | **Share to anyone uses plain HTTP**, a 16-byte token, an optional PIN and a time limit. Not HTTPS. App-to-app transfers stay TLS-pinned. | Browsers do not trust our own certificates, and their full-page warning is worse than the HTTP one (section 5.8). | R7 |
| 13 | **No update check of any kind.** | It would be a network call to the internet, which the promise rules out. | Decided |

### 5.4 Protocol v2 (from `PROTOCOL-v2.md`)

**Goals:** resume after any interruption including an app restart on either side; checked integrity; small files as fast as big ones; offers that do not block; proven sender identity; pairing without first-contact risk; the same API over a router, Wi-Fi Direct or a hotspot. Not in v2.0: internet relays, iOS background receiving, folder sync.

**Transport:** HTTP/1.1 with keep-alive, at `/api/kl/v2`. `Content-Length` is required; chunked transfer encoding is refused. TLS 1.3 where available, else 1.2. ChaCha20-Poly1305 stays allowed for old 32-bit TV boxes. Tokens go in the `Authorization` header, never in the URL. HTTP/2 and QUIC stay out (no Python stdlib server, head-of-line blocking on lossy Wi-Fi, native code on Android).

**Compatibility:** a v2 device keeps serving `/api/kl/v1` unchanged. TXT gets `vs=1,2` and `p2p=1`. Keep the whole TXT under about 200 bytes. If `GET /api/kl/v2/info` returns 404, the sender uses v1 and says so (section 4.8).

| Method | Path | Purpose |
|---|---|---|
| GET | `/info` | Identity, versions, features, limits, a one-time nonce |
| POST | `/hello` | Tell the other device your address on a Wi-Fi Direct group or hotspot |
| POST | `/pair` | Finish QR pairing (HMAC "KLv2-pair") |
| POST | `/sessions` | Create or rejoin a send. `202` pending, or `200` when auto-accepted. Sending the same session again returns its state. |
| GET | `/sessions/{sid}?wait=25&after=` | Long-poll: state, accepted files, chunk size, token, resume state |
| POST | `/sessions/{sid}/manifest` | More file entries, pages of up to 5,000 |
| PUT | `/sessions/{sid}/files/{fid}/chunks/{n}` | One chunk with header `KL-Chunk-SHA256` |
| POST | `/sessions/{sid}/batch` | Many small files, `application/x-kl-batch` |
| POST | `/sessions/{sid}/files/{fid}/done` | Finish and check one file |
| DELETE | `/sessions/{sid}/files/{fid}` | Drop a file |
| DELETE | `/sessions/{sid}/files/{fid}/chunks` | Restart a file |
| DELETE | `/sessions/{sid}` | Cancel. The other side sees it on the long-poll, or as `410`. |

**Details that matter for reliability**
1. **Chunks:** the receiver picks the size, a power of two from 1 to 64 MiB, default 8 MiB. It writes the chunk at its offset, checks the hash, records it, then answers `201`. Progress counts these answers. A rejected chunk is read to the end and thrown away, so the sender always gets the error.
2. **File digest `sha256-tree-v1`** = SHA-256(chunk size as 8 bytes, then the digest of each chunk in order). Chunks can arrive in any order, the sender reads the file once, and browsers can compute it with WebCrypto.
3. **Batches:** records of a 4-byte big-endian length, a JSON header `{file, size, sha256}`, then the bytes. A zero length ends the batch. Each file is saved as its bytes arrive. No tar.
4. **Parallel connections:** start at 2, add one while speed rises 10% or more, up to the receiver's `max_streams`. The receiver may answer `503 slow_down` with `Retry-After`. Chunks are handed out within a window of 8, because writing far past the end is slow on NTFS and exFAT. Storage that cannot seek gets `in_order`, one connection.
5. **Resume journal:** the receiver keeps, per session, the manifest, the chunks that arrived and the temp path or MediaStore URI, for 7 days. The sender keeps the session ID, the token, and each file's size and modified time. A file that changed starts again from zero. Reconnect = `POST /sessions` again, `GET /sessions/{sid}`, then only the missing chunks.
6. **Timeouts:** pending sessions expire after 10 minutes; accepted sessions are kept 7 days.
7. **Limits:** JSON bodies up to 8 MiB; up to 1,000,000 entries per session.
8. **Metadata:** `mtime` in ms, `kind: file | dir` (keeps empty folders), optional `mime`. The receiver checks free space and FAT32's 4 GB limit before accepting.
9. **Pairing:** `klshare://pair?v=2&id=&name=&ip=&port=&secret=`. The scanner pins the full ID from the QR code, then sends `POST /pair` with its proof and the HMAC. Five wrong MACs withdraw the code. In direct mode the QR code also carries the network name and password.
10. **Receive policy:** Everyone, Everyone for 10 minutes, Paired devices only, Off. Per paired device: accept files without asking, accept text without asking. Default to Paired only on networks Windows marks as Public.
11. **A cryptography review of the proof and pairing is required before v2 ships.** Mutual TLS is not used, because Python's `ssl` cannot accept unknown self-signed client certificates.
12. **Pairing by code without a camera** (PC to PC) needs a PAKE such as SPAKE2 ([RFC 9382](https://www.rfc-editor.org/rfc/rfc9382)). Until then, show a comparison code of at least 64 bits. Never a plain HMAC with a 6-digit code.

### 5.5 Android: background work, storage and permissions
1. **Background transfers:** on Android 14 and later, user-started transfers run as user-initiated data transfer jobs. On older versions, a `dataSync` foreground service.
2. **The 6-hour limit:** on Android 15 and later, `dataSync` services may run 6 hours in 24, then `Service.onTimeout()` is called. The timer resets when the app comes to the foreground ([FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)). The app must stop within seconds, save the journal, and post "Paused by Android's background limit. Open LAN Share to continue."
3. **Shared files:** take the URIs with `FLAG_GRANT_READ_URI_PERMISSION` in `ClipData` and, where possible, a persistable grant at the moment of sharing. Open file descriptors at that moment for small sets. Some apps take back access later (a known gap today).
4. **Saving:** MediaStore `Download/KhanLabs` with `IS_PENDING=1`, cleared when the file is checked. SAF folders through `DocumentsContract` directly (not `DocumentFile.findFile`, which is very slow with thousands of files). Write at offsets with `openFileDescriptor(uri, "rw")` and `FileChannel.write(buf, pos)`.
5. **Permissions today:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WRITE_EXTERNAL_STORAGE` (max SDK 28). Later: `ACCESS_LOCAL_NETWORK`, `CAMERA`, `NEARBY_WIFI_DEVICES` with `neverForLocation`, `CHANGE_WIFI_STATE`; for TV, `REQUEST_INSTALL_PACKAGES`.
6. **Target SDK:** stays at 36 until the local network permission flow passes its tests. Google Play needs API 36 for new apps and updates from 31 August 2026 ([target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)). Raising to 37 is a planned step with its own test run (section 7.14).
7. **Battery managers:** show maker-specific help (Xiaomi, Samsung, OnePlus and others). Ask for the battery exemption only in the sideload build. Resume is the real protection.

### 5.6 Windows
1. **Keep Python and Tkinter.** Python reached 656 MB/s on loopback with TLS. A rewrite is not justified.
2. **Threads:** one per connection with the same caps as Android. Every socket has a read timeout (60 s API, 120 s web page).
3. **Sleep:** `SetThreadExecutionState` on its own thread while a transfer runs.
4. **Explorer "Send to":** the exe is started with paths; if a copy is already running, the paths go to it over the loopback single-instance port 47829. That port must accept only loopback connections and only a strict, size-limited message.
5. **Received files** get a Mark of the Web (`Zone.Identifier` stream with `ZoneId=3`), so Windows SmartScreen checks a received program before it runs. This is how browsers treat downloads.
6. **Long paths:** use `\\?\` paths for names past 260 characters.

### 5.7 Discovery: "devices are always found"
1. **mDNS** first (both apps).
2. **Re-check before removing:** a device reported lost is asked for `/info` first (done on Android; add to Windows).
3. **Network changes:** re-announce and re-browse within 10 s of an address change (Windows checks every 10 s; Android uses `ConnectivityManager` network callbacks).
4. **QR connect:** always works when the devices can reach each other. It is the main fix in "Not showing up?".
5. **Search this network:** when the user taps it, try TCP port 47820 on every address in the local /24 (up to 32 at a time, 300 ms timeout), then `/info` on the ones that answer. This takes about 10 seconds and finds devices when multicast is blocked. Only on a tap, never in the background.
6. **Add by IP:** the last fallback.
7. **Explain failures:** if nothing is found after 10 s, show "Not showing up?" more strongly. If Windows says the network is Public, say so.
8. **Client isolation** (guest Wi-Fi, some mesh routers) blocks all device-to-device traffic. Nothing on the LAN can fix it. The app says so, and later offers direct mode (R9).

### 5.8 Share to anyone

**How it works**
1. Person A picks files (or a folder) and taps Share to anyone.
2. A's screen shows the device name, count, size, a QR code and the link (section 4.3).
3. Person B scans the QR code. The browser opens a page served by A's device over the same Wi-Fi.
4. The page offers Download all (one zip) and single files grouped as Photos, Videos, Music, Documents and Other.
5. The share ends when A taps Stop, closes the screen, or the time limit runs out. Running downloads are cut. The link then shows "This share has ended".

**Rules**

| Rule | Detail |
|---|---|
| Port and protocol | Plain HTTP on its own port (47890 in the prototype). Not HTTPS. |
| Why not HTTPS | Browsers trust only certificates from public authorities, which are never issued for addresses such as 192.168.1.20. Our own certificate causes a full-page "not private" warning, which is worse. The only fix is an internet certificate service, which breaks the promise. |
| Secret link | `/s/<token>/`, token = 16 random bytes from a secure random source (`secrets.token_urlsafe(16)`, `SecureRandom`). Compared in constant time. |
| PIN | Optional 6 digits. 5 tries, then a lock of 60 s that doubles up to 15 minutes, per IP. **Plus a cap for all IPs together:** after 20 wrong PINs in 10 minutes from any addresses, PIN entry stops for that share for 15 minutes and A's screen says so. |
| Time limit | A picks 15 minutes, 1 hour, 4 hours, or until stopped. Default 1 hour. |
| Read-only | B can only download what A picked. |
| Download all | Zip, stored (not compressed), ZIP64. **The exact size is computed before sending** (for a stored zip it is known from the names and sizes) and sent as `Content-Length`. Then a cut download shows as failed in the browser, not as a complete but broken zip. The folder share page gets the same fix. |
| Single files | `Range` support. `Content-Disposition` with `filename*=UTF-8''...`, quotes and line breaks removed. |
| Logs | **The token never appears in logs.** The prototype prints the full path, so today it does (seen in `share_proto.log`). Log `/s/<hidden>/...`. |
| Browsers that try HTTPS first | Chrome and others may send a TLS ClientHello to the HTTP port first. The prototype then crashed in `parse_request` (`AttributeError: 'Handler' object has no attribute 'path'`). Peek the first byte; on 0x16, close at once without logging an error, so the browser falls back to HTTP quickly. |
| Headers | `Host` must be the IP and port (stops DNS rebinding); `Cache-Control: no-store`; `X-Content-Type-Options: nosniff`; `Referrer-Policy: no-referrer`; `X-Frame-Options: DENY`; CSP with `default-src 'self'` and inline styles only; `CSP: sandbox` on files that could run in the browser. |
| Escaping | Sender name and file names are HTML-escaped everywhere; the zip name is made safe. |
| Phone | Reuses the HTTP server in `core-net`, makes the QR code with ZXing (core only), and runs a foreground service while sharing. |
| Later | With direct mode, a first QR code joins A's hotspot and a second opens the page. |

**Browser check (2026-10-06, prototype on the PC, opened on the OnePlus 8T)**

| Browser | Result |
|---|---|
| Firefox | Page and downloads open with no warning. HTTPS-First does not upgrade local addresses (observed; Mozilla [bug 1716376](https://bugzilla.mozilla.org/show_bug.cgi?id=1716376) discusses it). |
| Brave | Full-page "doesn't support a secure connection" warning (one tap: Continue to site), then "File can't be downloaded securely" (one tap: Keep). The 59 MB zip then downloaded fine. |
| Chrome | Not tested. From Chrome 154 (October 2026) "Always Use Secure Connections" is on by default for public sites; the default does not warn for private addresses such as 192.168.x.x ([heise](https://www.heise.de/en/news/Chrome-to-warn-about-HTTP-connections-by-default-from-2026-10962985.html), [PPC Land](https://ppc.land/chrome-enforces-secure-connections-by-default-in-october-2026/)). Chrome's download warning for a private-address HTTP page: unverified. Chrome's Local Network Access prompt applies to public sites reaching local devices, not to our same-origin page ([Chrome blog](https://developer.chrome.com/blog/local-network-access)). |
| Safari (iPhone) | Not tested. Safari has an optional "Not Secure Connection Warning" setting ([Apple](https://support.apple.com/guide/iphone/get-http-website-warnings-iphfba2ed790/ios)). Reports of a wrong warning on local addresses in iOS 26: unverified. |
| Samsung Internet | Not tested. A report says HTTPS by default arrived in August 2026 (version 30.0.4.29): unverified. |
| In-app browsers (WhatsApp, Instagram) | Not tested. Download behavior: unverified. |

The sender's screen says: "If the browser warns, tap Continue or Keep. The files come straight from this phone."

### 5.9 Direct mode (R9, unchanged in substance)
1. The API stays the same. Only the way the devices reach each other changes.
2. Finding each other: QR code (main path), Wi-Fi P2P service discovery (nice to have; unreliable on many devices), or normal mDNS.
3. Android Wi-Fi Direct with `WifiP2pConfig.Builder` (API 29+): a `DIRECT-` network name, a passphrase, 5 GHz when supported. The group owner is `192.168.49.1`. The client calls `POST /hello`, because the owner cannot see the client's address.
4. A group owner is also a normal WPA2 access point. A Windows PC or a phone without Wi-Fi Direct joins it as normal Wi-Fi (`netsh wlan` on Windows; check whether Windows 11 24H2 needs location permission for some commands).
5. Fallback: `LocalOnlyHotspot` (API 26+), with a standard `WIFI:` QR code plus pairing data. Warn that normal Wi-Fi may drop.
6. Join with `WifiNetworkSpecifier` (API 29+) and **bind sockets to that network**, or traffic may go over mobile data.
7. Rule: the device with a screen and no camera (TV, PC) hosts. The phone scans and joins.
8. Run three short experiments before fixing dates: groups on three or more phone brands; LocalOnlyHotspot plus `WifiNetworkSpecifier`; Windows joining a phone's group.

### 5.10 Android TV (R8, unchanged in substance)
1. One APK: `leanback` and `touchscreen` both `required="false"`, a `LEANBACK_LAUNCHER` activity, a 320x180 banner.
2. Compose for TV (`androidx.tv:tv-material`) for D-pad focus.
3. Pairing plus auto-accept from paired devices is required on TV, because prompts are easy to miss.
4. Many TV builds lack the system file picker. Build an in-app browser. Other files on API 30+ may need "All files access", which some TVs can only grant over adb. Document it.
5. Install APKs with the PackageInstaller session API. Open other files with `ACTION_VIEW` (VLC, Kodi, the TV's player).
6. A 4 GB file to a FAT32 USB stick is refused up front.
7. Google Play needs TV apps to target API 34 or higher ([target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)).

---

## 6. Roadmap

**Order:** reliability first (R1 to R5), then features (R6 to R10). Nothing is released to the public until R5's gates pass. Patch releases of safety fixes for the existing Windows 1.2.x are allowed if they pass the v1 gates.

| Phase | What | Apps | Working days | Release |
|---|---|---|---|---|
| R1 | Test lab and CI | Both | 8 to 10 | none |
| R2 | v1 reliability, discovery and clear errors | Both | 18 to 22 | Windows 1.2.2 (patch); Android 0.2 (private) |
| R3 | Protocol v2 on Windows, with resume and checks | Windows | 25 to 35 | Windows 1.3 beta (private) |
| R4 | Protocol v2 on Android, resume, background jobs, paired devices | Android | 27 to 35 | Android 0.9 beta (private) |
| R5 | Hardening and the first public release | Both | 15 to 20 | **Windows 1.3, Android 1.0** |
| R6 | UI changes (the rest of the research top 10) | Both | 12 to 15 | Windows 1.4, Android 1.1 |
| R7 | Share to anyone | Both | 9 to 11 | Windows 1.4 or 1.5, Android 1.2 |
| R8 | Android TV | Android | 20 to 30 | Android 1.3 |
| R9 | Direct mode, no router | Both | 30 to 45 | Android 1.4, Windows 1.6 |
| R10 | Stores, extras and the tip page | Both | 10 to 15 | ongoing |
| | **Total** | | **174 to 238** | |

The research's top 10 UI changes (about 16 to 20 days in `docs/UI-RESEARCH.md`) are split: the parts that make errors and discovery clear move into R2 (offer sheet, "Not showing up?", QR, first run with the Android 17 permission, privacy checks). The rest is R6.

The calendar time for the outside cryptography review (R3) is not in the estimates. Plan 2 to 4 weeks of waiting, during which R4 work continues.

### R1: Test lab and CI (8 to 10 days)

**Must have**
1. GitHub Actions on both repos, per-commit jobs (section 7.17): unit tests, vectors in both languages, v1 conformance against the Python and Kotlin servers on loopback, Python-to-Kotlin and Kotlin-to-Python transfers, tracker and URL checks. 2 days.
2. A self-hosted runner on the owner's PC with the OnePlus 8T on USB, for nightly real-device runs. 1 day.
3. Fixed test data sets D1 to D14 (section 7.1) and a script that makes them. The hard-name sets D3 and D14 run against both senders on every commit (PR-17). 1 day.
4. The transfer runner: runs N scripted transfers between any two devices and checks every SHA-256, file name, folder and leftover temp file. 2 days.
5. A Linux chaos lab: two containers with `tc netem`, plus the existing `faultproxy.py`. 1.5 days.
6. Footer and screen tests: Compose screenshot tests at 200% font and 320 dp; Playwright for web pages. 1.5 days.
7. Record baselines: `iperf3` and 1.2.0/1.2.1 speeds on the "Khan 2.4G" and a 5 GHz network. Already measured on 2026-10-06: 1 GB PC to phone at about 9 MB/s, and about 14 small files per second, both on 2.4 GHz. 0.5 day.
8. Tests for things marked "not checked yet": phone sender-side cancel, Windows network change, firewall Fix with UAC, Start with Windows. 0.5 day.

**Done when**
1. CI runs on every push to both repos, in 15 minutes or less, and passes.
2. The nightly real-phone job passes 7 nights in a row.
3. The transfer runner reports 200 of 200 clean v1 transfers for W→A and A→W, with matching hashes.
4. Baselines are recorded in `bench/results.csv` in both repos.

### R2: v1 reliability, discovery and clear errors (18 to 22 days)

**Must have**
1. **No silent bad files:** exact `Content-Length` for the folder share zip (1 day).
2. **Safety:** notepad save checks `Origin` and needs `Content-Type: application/json` (0.5 day); PIN cap for all IPs (0.5 day); check `SO_EXCLUSIVEADDRUSE` on Windows (0.25 day); Mark of the Web on received files (0.5 day); private-address guard in both clients (1 day).
3. **Clear errors:** an error catalogue in both apps. Every failure code maps to a message with what happened, what was saved, and what to do. A refused offer names the file and the reason (for example "`  notes.txt`: a name cannot start with a space"), and the sender shows it. A local error log and "Copy error report" on Android (2.5 days).
4. **Android lifecycle:** handle the `dataSync` 6-hour limit cleanly (1 day); take URI grants and open files at share time (1.5 days); re-announce on network change (0.5 day).
5. **Devices always found:** show a QR code on PC and phone, scan it on the phone with CameraX and ZXing (3 to 4 days); "Not showing up?" on both apps and the Windows Public network check (1.5 days); "Search this network" (2 days); re-check lost devices on Windows (0.5 day).
6. **Clear prompts:** the offer sheet fix (one pair of buttons, countdown, "New device" wording) on the phone, and "New device" wording on Windows (1 to 1.5 days).
7. **Android 17:** declare `ACCESS_LOCAL_NETWORK`, a minimal first-run screen that asks for it, and the compat-flag test (1 to 1.5 days).
8. **Look and wording:** footer text from faint to muted on Windows; folder page light tokens and `color-scheme`; "one-time tip" wording (0.5 day).

**Done when**
1. 1,000 of 1,000 clean v1 transfers pass for W→A, A→W and W→W, with matching hashes and no leftover temp files.
2. The hard-name sets D3 and D14 from both senders: 0 refused offers; when a test receiver refuses one file on purpose, the sender shows that file's name and the reason.
3. Under the chaos profiles in section 7.5, no v1 run saves a wrong file and no run hangs: each failure ends within 60 s with a catalogued message.
4. Discovery: 200 trials on each home network, 99% find the other device within 15 s; QR connect works in 200 of 200.
5. The injected faults that apply to v1 (section 7.5, E-list) each show the right catalogued message; zero raw exceptions in the UI.
6. Folder zip cut at 20 random points: the browser marks all 20 as failed.
7. CSRF test, PIN cap test and private-address guard test pass.
8. The compat-flag test on Android 17 passes: permission asked, refusal explained, discovery works within 10 s of granting.

### R3: Protocol v2 on Windows (25 to 35 days)

**Must have**
1. v2 server and client: keep-alive, sessions with long-poll, chunked uploads with SHA-256, batches, 2 to 4 parallel connections, `503 slow_down`, `in_order`.
2. Resume journal on both sides; survives app restart and PC reboot.
3. Manifest paging (no file limit), modified times, empty folders, receiver makes names safe.
4. Free-space and FAT32 checks before accepting.
5. Device proof, QR pairing (the PC shows the code), paired devices, receive policy, auto-accept per device.
6. Falls back to v1 in both directions, with the "older version" message.
7. The v2 conformance suite (at least 120 cases; section 7.2), written alongside the code.
8. The outside cryptography review of proof and pairing. All high findings fixed.

**Done when**
1. 20 GB PC to PC over Wi-Fi, sender killed at 5 random points, ends with a matching digest; each interruption re-sends at most (connections × chunk size).
2. 10,000 files of 50 KB at least 5 times faster than 1.2.0 on the same Wi-Fi, and at least 300 files per second PC to PC on Wi-Fi 5 (G17).
3. One large file at 90% or more of the `iperf3` speed between the same two PCs.
4. 1.2.0, 1.2.1 and 1.3 work together in both directions.
5. v1 and v2 conformance suites pass 100%.

### R4: Protocol v2 on Android (27 to 35 days)

**Must have**
1. v2 sender and receiver (14 to 19 days), sharing `core-protocol`.
2. Resume journal in Room; MediaStore pending files resumed or cleaned after a crash (4 to 5 days).
3. User-initiated data transfer jobs on Android 14+, `dataSync` service below that (3 to 4 days).
4. SAF save folder (2 to 3 days).
5. QR pairing (scan and show), paired devices, receive policy, accept without asking (3 days; the QR code itself is done in R2).
6. Free-space and FAT32 checks (1 day).

**Done when** (from the old Phase 2, made stricter)
1. A 10 GB video goes phone to PC and PC to phone with the screen off, digests match.
2. Wi-Fi off and on during a transfer resumes by itself within 30 s, in 50 of 50 trials.
3. After the OS kills the app, the transfer resumes when the app is reopened, in 50 of 50 trials.
4. 5,000 photos keep their folders and dates.
5. Small files phone to PC and PC to phone: at least 300 files per second on Wi-Fi 5 (G17). Today's v1 does about 14.
6. Battery use per GB is recorded on the OnePlus 8T.
7. Conformance passes 100% in both roles, v1 and v2.

### R5: Hardening and the first public release (15 to 20 days)

**Must have**
1. The full release test run (section 7): device lab, chaos, scale, soak 24 and 72 hours, fuzzing 24 CPU-hours per target, privacy capture, browser checks for the folder page, accessibility.
2. Fix everything the run finds. Budget at least half of this phase for fixes.
3. Release basics: Android release keystore with two offline backups and a written custody plan; signed APK (v1 and v2 schemes); Windows exe signed if a route exists (Q8); SHA-256 checksums; a public download page (the repos are private, so GitHub Releases there are not public).
4. Android developer verification registration (Q9).
5. Exodus report. User guide.
6. 7 days of daily use by the owner on the release candidate.

**Done when:** every gate in section 7.18 passes. Then release Windows 1.3 and Android 1.0.

### R6: UI changes (12 to 15 days)
Clean phone home screen with tabs and the Receiving chip and sheet (1.5 days); content-first Send buttons with Folder (2 days); Activity tab (2 to 3 days); Sharing Shortcuts and Quick Settings tile (1.5 to 2 days); Windows drag and drop and "Send to" (2 days); Windows four tabs, device cards, Receiving chip, first-run window, tray menu (3 to 4 days). Nice to have: partial accept, send to several devices, `klshare send`, clipboard push.

**Done when:** every new screen passes the UI gates (section 7.10), and the R5 reliability gates still pass.

### R7: Share to anyone (9 to 11 days)
Python server and page with all rules in section 5.8 (4 days); Android version with picker, QR screen and foreground service (4 to 5 days); browser checks (1.5 days).

**Done when**
1. Page, Download all and single files work in Chrome, Safari on iPhone, Firefox, Samsung Internet and Brave, with at most 2 one-tap warnings in any of them.
2. A 5 GB share downloads as one zip on a phone and on a PC, and every file matches.
3. A zip cut at 20 random points is marked failed by the browser all 20 times.
4. A wrong or old link shows "This share has ended". Stop cuts running downloads within 2 s.
5. The token appears zero times in any log. A TLS ClientHello on the port is closed within 100 ms with no error logged.
6. The footer is visible at 320 px width and 200% text zoom.

### R8: Android TV (20 to 30 days)
As section 5.10.

**Done when:** every flow works with only a remote on the TV devices in section 7.16; pairing a phone to a TV by QR takes under 30 s; a 4 GB file to a FAT32 USB stick is refused up front; a 24-hour receive soak passes.

### R9: Direct mode (30 to 45 days, including 5 days of experiments first)
As section 5.9.

**Done when:** two phones with no network start sending within 20 s of scanning; 5 GB phone to phone reaches at least 70% of Quick Share's speed on the same phones; a phone joins a TV's hotspot by QR; the network-binding test shows zero bytes over mobile data.

### R10: Stores, extras and the tip page (10 to 15 days)
1. Tip page on khanlabs.dev (2 days; section 8.7). Low priority.
2. IzzyOnDroid or F-Droid, if open source (2 days).
3. Google Play build flavour without the tip card (2 to 3 days, plus review time).
4. winget and Scoop (1 to 2 days).
5. Nice to have: LocalSend receive adapter (5 to 10 days per app; not in the total), PAKE code pairing, v2 chunked uploads on the web page.

---
## 7. Testing plan

This is the most important section. The goal is an app that does not fail. The tests are meant to be hard to pass.

### 7.0 How this section works
1. **Every test has an ID** (for example `C-07`) and a pass line with a number. A test without a number is not finished.
2. **Three run levels:**
   - **Per commit** (CI, 15 minutes or less): fast checks.
   - **Nightly** (CI and the self-hosted runner, up to 4 hours): fuzzing, chaos, emulators, the real phone.
   - **Per release candidate (RC)**: everything, including soak tests, the device lab and manual checks.
3. **Gates** are listed in section 7.18. A failed gate blocks the release.
4. **Numbers marked "initial target"** are first guesses. They are fixed after the R1 baselines and then only made stricter, never looser, without the owner's written agreement in `PROGRESS.md`.
5. **Pairs:** W = Windows, A = Android phone, TV = Android TV. "W→A" means Windows sends and Android receives. Every pair is tested in both directions, so both apps are tested as sender and as receiver.
6. **Results** are saved as JUnit XML (kept 90 days) and in `bench/results.csv` (kept in the repo).

### 7.1 Test data sets

| ID | Content | Why |
|---|---|---|
| D1 | One empty file (0 bytes) | Edge case for chunk math and zip |
| D2 | One 1-byte file | Edge case |
| D3 | 200 files with hard names: Urdu, Arabic and Chinese text; the same letter as one code point and as two (NFC and NFD); pictograph characters; leading and trailing spaces; trailing dots; `CON`, `NUL`, `COM1`, `LPT1`, `aux.txt`; `: ? * " < > \|`; `\`; names of 255 UTF-8 bytes; `a.txt` and `A.txt`; `..`; a leading `-`; `%2F`; zero-width characters; the right-to-left override character | Name rules and safety on every file system |
| D4 | One 1 GB random file | Speed and integrity |
| D5 | One 20 GB random file | Resume and long transfers |
| D6 | 10,000 files of 50 KB | Small-file speed |
| D7 | 50,000 real photos and videos in dated folders (a copy of a camera folder) | Real use, metadata |
| D8 | A tree 64 levels deep, paths over 300 characters, 500 empty folders | Paths and empty folders |
| D9 | Boundary sizes: chunk size −1, exact, +1 for 1, 8 and 64 MiB chunks; 2^31−1, 2^31, 2^32−1, 2^32 and 2^32+1 bytes | Off-by-one and 32-bit bugs |
| D10 | One file of 4 GiB + 1 byte | FAT32 limit |
| D11 | Text: empty, exactly 64 KB, 64 KB + 1 byte, many lines, right-to-left text, a single URL | Text limits |
| D12 | 300 mixed files, 3 GB: photos, videos, documents, an APK | Everyday send |
| D13 | A file that grows while it is being sent (a log being written) | Changed-file handling |
| D14 | The 2026-10-06 phone test set: 2,000 files, 64 MB, 25 nested folders, names with accents (café), Japanese (日本語), Greek (Ελληνικά), Hebrew, pictograph characters, `50% off`, `[brackets]`, 180-character names and leading spaces | It found a real bug: one leading space made the receiver refuse the whole send |

The script that makes D1 to D14 is in both repos, so every machine uses identical data. D7 is made once and kept on the lab PC (it is personal data and never leaves it).

**The standard check** (applied after every transfer in every test below)
1. Every received file's SHA-256 equals the source.
2. Names follow the name rules table in `PROTOCOL.md`. No file is written outside the receive folder.
3. The folder structure matches. With v2, empty folders exist.
4. With v2, modified times match within 2 seconds (FAT32 stores 2-second steps).
5. No leftover temp files, `.uploading` files or MediaStore entries with `IS_PENDING=1`.
6. Both apps show the same final state. "Verified" appears only after the digest matched.
7. No crash, no ANR, and no unexpected entry in the error log.

### 7.2 Protocol and conformance

**Server role: the conformance suite** (`conformance/`, pytest, black box, any `host:port`)

| ID | Test | Pass when |
|---|---|---|
| PR-01 | v1 suite against the Windows server | 38 of 38, including the 2 slow tests |
| PR-02 | v1 suite against the Android server on the JVM (loopback, CI) | 38 of 38 |
| PR-03 | v1 suite against the real phone over Wi-Fi (`gradlew :core-net:conformanceTarget`, port 47890) | 38 of 38, nightly |
| PR-04 | v1 suite against TV builds (from R8) | 38 of 38 |
| PR-05 | v2 suite (written in R3) against every server above | 100% of at least 150 cases: info and nonce 8, proof 16, sessions 12, long-poll 8, manifest pages 8, chunks 20, done and digest 8, batch 12, parallel and `503` 8, resume 12, pairing 10, hello 4, receive policy 6, names 10, limits 8 |
| PR-06 | Spec coverage: every MUST and MUST NOT line in `PROTOCOL.md` and `PROTOCOL-v2.md` maps to a test ID in `conformance/coverage.csv`. A script checks it in CI. | 100% of lines covered |

**Client role: the recording receiver.** A scripted fake receiver checks what a sender does. It is new work in R1 (v1) and R3 (v2).

| ID | Test | Pass when |
|---|---|---|
| PR-10 | Request format: `Content-Length` on every body, no chunked encoding, token in `Authorization` (v2), `Host` set correctly | 100% of requests in D3, D6, D12 sends |
| PR-11 | `429 busy`, decline and the 120 s timeout (v1) | Sender stops and shows the catalogued message, every time |
| PR-12 | `503 slow_down` with `Retry-After` | Sender waits at least the given time; never more streams than `max_streams` |
| PR-13 | Chunk window | Chunks in flight never more than 8 apart; `in_order` files use one connection, strictly in order |
| PR-14 | Resume | After a forced reconnect, the sender asks for the state and sends only missing chunks: 0 duplicate chunks in 100 runs |
| PR-15 | Cancel (`DELETE` or `410`) | Sender stops within 2 s and sends no more bytes |
| PR-16 | Pinning | A receiver with a different certificate gets 0 file bytes (connection refused at TLS) |
| PR-17 | **Sender name safety.** Each sender (Windows `safe_part`/`safe_relpath`, Android `Names.safePart`) sends D3 and D14 to a strict v1 receiver that refuses any name that is not already safe. Runs on every commit for both senders. | 0 refused offers; every saved path matches what the name rules predict |
| PR-18 | **Receiver says which file and why.** A test receiver refuses one chosen file in a 2,000-file offer | The refusal names that file and the reason; the sender shows both (E-41); never the old "each file needs a unique id, a valid relative name and a size" |

**All pairs, both versions.** Each cell runs D3, D4, D6, D11, D12 and D14 with the standard check.

| Sender ↓ / Receiver → | W 1.2.0 | W 1.2.1 | W 1.3 (v2) | A 0.x (v1) | A 1.0 (v2) | TV (v2) |
|---|---|---|---|---|---|---|
| W 1.2.0 | v1 | v1 | v1 | v1 | v1 | v1 |
| W 1.2.1 | v1 | v1 | v1 | v1 | v1 | v1 |
| W 1.3 (v2) | v1 + message | v1 + message | v2 | v1 + message | v2 | v2 |
| A 0.x (v1) | v1 | v1 | v1 | v1 | v1 | v1 |
| A 1.0 (v2) | v1 + message | v1 + message | v2 | v1 + message | v2 | v2 |
| TV (v2) | v1 + message | v1 + message | v2 | v1 + message | v2 | v2 |

Pass: every cell passes the standard check; the protocol used matches the cell; "v1 + message" cells show the "older version" message. A→A uses two phones, or the phone and an emulator. W→W uses two PCs, or two copies on one PC for CI.

**Golden vectors**

| ID | Test | Pass when |
|---|---|---|
| PR-20 | `docs/v2/vectors.json` (today 22 vectors: 2 identities, 2 device IDs, 6 file digests, 6 proofs, 4 pairing MACs, 2 batches) in Python (`test_v2.py`) and Kotlin (`V2VectorsTest`) | 100% on every commit |
| PR-21 | Add negative vectors: wrong purpose line, wrong receiver ID, reused nonce, nonce from another IP, nonce older than 120 s, chunk size not a power of two, batch record longer than the limit, cut-off batch, digest with one wrong chunk | At least 60 vectors in total by end of R3; 100% pass in both languages |
| PR-22 | Nightly cross-language random check: 10,000 random cases (tree digests of random chunk lists, proofs signed in one language and checked in the other, batch encode in one and decode in the other) | 0 differences |

### 7.3 Fuzzing and hostile input

**Tools**
- [Atheris](https://github.com/google/atheris) for Python. It supports Python 3.11 to 3.14 on Linux and macOS only, so it runs in WSL or on a Linux CI runner.
- [Hypothesis](https://hypothesis.readthedocs.io/) for property tests in Python, on any system.
- [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) for Kotlin on the JVM. It is open source again under Apache-2.0 ([announcement](https://www.code-intelligence.com/blog/jazzer-back-to-open-source)).
- [boofuzz](https://github.com/jtpereyda/boofuzz) for byte-level fuzzing of the live servers over sockets (current maintenance status: unverified).
- An own raw-socket script for slow, half-open and broken clients.

**Targets**

| ID | Target | Both apps? |
|---|---|---|
| F-01 | HTTP request parser: request line, headers, `Content-Length`, `Transfer-Encoding`, `Host` | Yes |
| F-02 | First-byte detection (TLS or plain HTTP), including the Share to anyone port | Yes |
| F-03 | v1 offer JSON (up to 4 MB) | Yes |
| F-04 | v2 session, manifest and pairing JSON (up to 8 MiB) | Yes |
| F-05 | v2 batch records | Yes |
| F-06 | Name rules (turn a network name into a safe local path) | Yes |
| F-07 | QR payload parser (`klshare://pair?...`, `WIFI:`) | Android, and the Windows "Add by link" if built |
| F-08 | mDNS TXT records | Yes |
| F-09 | Folder share web routes: `?note`, uploads, PIN cookie, paths | Windows |
| F-10 | Zip writer with hostile names and boundary sizes | Yes |

**Budgets and pass lines**

| Level | Budget | Pass when |
|---|---|---|
| Per commit | Hypothesis: 200 examples per property | 0 failures |
| Nightly | 1 CPU-hour per target | 0 crashes, 0 hangs over 10 s, 0 uncaught exceptions that stop the server, under 256 MB memory per input |
| Per RC | 24 CPU-hours per target | Same, and the parser code reached by the corpus is 90% or more of its lines (coverage.py, JaCoCo) |

Every crash becomes a regression test before its fix is merged. The corpus is kept in the repo.

**Hostile inputs with fixed answers**

| ID | Input | Expected |
|---|---|---|
| H-01 | Name `../../evil.txt` | Saved as a safe name inside the receive folder |
| H-02 | Name `C:\Windows\x`, `\\server\share\x`, `\\?\C:\x` | Safe name inside the receive folder |
| H-03 | Name `CON.txt`, `nul`, `COM1` (on Windows) | Renamed, for example `_CON.txt` |
| H-04 | Name `file.txt:stream` (NTFS alternate data stream) | `:` replaced; no stream written |
| H-05 | `a.txt` and `A.txt` in one send to a case-insensitive disk | Second saved as `A (1).txt`; both checked |
| H-06 | `Content-Length: 99999999999999999999` | `400` or `413`, connection closed, memory never grows by more than 16 MB |
| H-07 | Both `Content-Length` and `Transfer-Encoding` | `400`, connection closed |
| H-08 | `Transfer-Encoding: chunked` on v2 | Refused as the spec says |
| H-09 | One header line of 8,193 bytes, or 101 headers | `431` or `400`, connection closed |
| H-10 | 1 byte every 10 s (slow client) | Closed by the 60 s idle timeout |
| H-11 | 1,000 idle connections from one IP | At most 16 kept; others refused at once |
| H-12 | 3rd pending offer from one IP; 9th in total | `429 busy` |
| H-13 | v1 offer with 10,001 files; v2 session with 1,000,001 entries | Refused with the limit error |
| H-14 | JSON nested 10,000 levels deep | `400`, no stack overflow |
| H-15 | Batch record claims 4 GB | Refused before reading it |
| H-16 | Chunk with a wrong `KL-Chunk-SHA256` | Rejected, read to the end, not recorded |
| H-17 | `done` with a wrong digest | File not moved into place; state shows the mismatch |
| H-18 | TLS ClientHello on the Share to anyone port | Closed within 100 ms, nothing logged as an error |
| H-19 | Random bytes on every open port (boofuzz) | No crash; port still serves normal requests afterwards |
| H-20 | QR code with a public IP, `0.0.0.0`, a 4 KB name, or missing fields | Refused with "This QR code is not from LAN Share on this network" |
| H-21 | A raw name with a leading space (NTFS allows it) sent by a test client that skips the sender's cleaning | v1 receiver: refuses with the file name and reason (E-41). v2 receiver: saves it under a safe name and reports that name. |

### 7.4 Security tests

| ID | Test | Method | Pass when |
|---|---|---|---|
| S-01 | Pinning against a man in the middle | Fault proxy presents a different certificate | 100% refused; 0 file bytes sent |
| S-02 | Replay a captured v2 proof | Re-send a recorded `POST /sessions` | Refused |
| S-03 | Proof for another receiver, wrong purpose, reused nonce, nonce from another IP, nonce after 121 s | Scripted | All refused |
| S-04 | More than 32 nonces per IP | Scripted | Oldest dropped, as the spec says |
| S-05 | Pairing: 5 wrong MACs | Scripted | Code withdrawn; the 6th try fails even with the right MAC |
| S-06 | Same name as a paired device, different key | Second device with the paired name | Shows "New device", never "Paired" |
| S-07 | Short ID clash | Test hook forces two keys with the same 8 hex digits | UI shows "New device"; full IDs differ in Details |
| S-08 | Share to anyone token | 1,000 links | 16 bytes each from a secure source; 0 repeats; wrong token gets the same "ended" page in constant time |
| S-09 | Token and PIN in logs | Full Share to anyone session, then search all logs | 0 matches |
| S-10 | PIN brute force | 5 wrong per IP; then 20 wrong from 20 IPs in 10 minutes | Per-IP lock after 5; all PIN entry stops after the 20th; sender's screen says so |
| S-11 | DNS rebinding | Request with `Host: evil.example` | `421` on the API, refused on web pages |
| S-12 | Notepad CSRF (folder share) | A page on another origin posts to `?note` | Refused; note unchanged |
| S-13 | XSS through names | D3 plus the OWASP XSS filter evasion list as file and sender names | Playwright sees 0 script runs and 0 dialogs |
| S-14 | Header injection | Names with quotes, `\r\n` | `Content-Disposition` stays one valid header |
| S-15 | Response headers | Check every route of every web server | `nosniff`, `no-store`, `no-referrer`, `DENY` and the CSP present on 100% of responses |
| S-16 | TLS settings | [sslyze](https://github.com/nabla-c0d3/sslyze) or [testssl.sh](https://github.com/testssl/testssl.sh) against each TLS port | No TLS 1.0 or 1.1, no RC4, 3DES, NULL or export suites; ChaCha20 offered |
| S-17 | Private-address guard | Add by IP `8.8.8.8`, QR with a public IP, a redirect to a public IP | 0 connections attempted (packet capture) |
| S-18 | Android exported parts | `adb shell dumpsys package <pkg>`; a test app sends crafted intents | Only the launcher, share targets and notification actions are exported; none accepts a path or URI without a grant |
| S-19 | Windows single-instance port | Connect from another machine; send 10 MB; send a crafted path list | Remote refused; oversized refused; paths only open the "Send to which device?" window |
| S-20 | Received programs | Receive an `.exe` and an `.apk` | Nothing opens by itself; Windows file has `Zone.Identifier` with `ZoneId=3`; APK installs only after the user taps Install |
| S-21 | Folder share escape | Junction or symlink inside the shared folder pointing outside it | Not served (current behavior: unverified; test it in R1) |
| S-22 | Known vulnerable libraries | `pip-audit`; Gradle dependency verification and an OWASP or GitHub advisory check | 0 known high or critical issues in shipped libraries |
| S-23 | Outside cryptography review | A reviewer with cryptography experience reads `PROTOCOL-v2.md` sections 4 and 10 and the code | Written report; 0 open high or medium findings |

### 7.5 Network chaos

**Tools:** the existing `conformance/faultproxy.py` (delay, rate, cut after N bytes, stall after N bytes); [clumsy](https://jagt.github.io/clumsy/) on Windows (drop, lag, throttle, reorder, tamper); [`tc netem`](https://man7.org/linux/man-pages/man8/tc-netem.8.html) in the Linux lab; Android emulator network options (`-netdelay`, `-netspeed`, see [emulator command line](https://developer.android.com/studio/run/emulator-commandline)); `adb shell svc wifi disable` and `enable`; real router reboots and DHCP renewals.

**Profiles**

| Profile | Setting |
|---|---|
| N-clean | No changes |
| N-loss1 | 1% loss, 50 ms delay |
| N-loss5 | 5% loss, 100 ms delay ± 50 ms |
| N-slow | 1 Mbit/s |
| N-reorder | 25% reordered, 1% duplicated |
| N-flap | Link down 10 s every 2 minutes |
| N-stall | Stall without closing, at a random byte |
| N-cut | Connection cut at a random byte |

**Scenarios**

| ID | Scenario | Pass when |
|---|---|---|
| C-01 | D4 under each profile, v1 and v2, every pair | Standard check passes, or (v1 only) a catalogued error within 60 s. 0 wrong files. |
| C-02 | D5 (20 GB) with 20 random cuts (v2) | Digest matches; each cut re-sends at most connections × chunk size (for example 4 × 8 MiB = 32 MiB) |
| C-03 | Stall without closing | Receiver frees the slot within 60 s; sender notices within 60 s and retries (v2) or reports (v1) |
| C-04 | Wi-Fi off for 10 s, 60 s and 5 minutes (phone) | v2 resumes within 30 s after Wi-Fi returns, with no taps, in 50 of 50 runs per duration |
| C-05 | New IP address mid-transfer (DHCP renewal) | v2 finds the device again and resumes within 30 s |
| C-06 | Router reboot mid-transfer | v2 resumes within 30 s after the network is back |
| C-07 | Roaming between 2.4 and 5 GHz on the same network name | Transfer continues or resumes within 30 s |
| C-08 | Bit flip on the receiver's disk write (test hook) | Chunk hash mismatch found, chunk sent again, final digest matches; "Verified" not shown until it does |
| C-09 | N-slow, 1 GB | No timeout; time left is within ±20% of the real time after the first 10% |
| C-10 | Guest network with client isolation | "Not showing up?" is shown within 10 s; QR connect explains that the network blocks devices |
| C-11 | Multicast blocked | QR, Search this network and Add by IP all work |
| C-12 | VPN on (for example WireGuard) and two adapters (Ethernet and Wi-Fi) | Correct LAN address announced and used |
| C-13 | Phone walks out of range for 2 minutes and back | Resumes within 30 s (v2) |
| C-14 | The 1,000-run chaos batch: random profile and pair for each run, D4 and D12 alternating | At least 99.5% finish verified with no taps; 100% of the rest end within 60 s with a catalogued message; 0 hangs; 0 wrong files |

**Error message checks (the E-list).** The error catalogue holds every failure. Each test forces one failure and checks that the right message appears, with what happened, what was saved and what to do. At least 41 entries, including:

| ID | Forced failure | ID | Forced failure |
|---|---|---|---|
| E-01 | Other device gone before the offer | E-21 | Port 47820 in use (falls back to 47821) |
| E-02 | Declined | E-22 | All ports 47820 to 47829 in use |
| E-03 | Offer not answered in time | E-23 | Name already exists (numbered copy) |
| E-04 | Other device busy (`429`) | E-24 | Path too long |
| E-05 | Receiver disk full before start | E-25 | Wrong PIN (web) |
| E-06 | Receiver disk full during transfer | E-26 | Share ended (web) |
| E-07 | File over 4 GB to FAT32 | E-27 | Wrong token (web) |
| E-08 | Source file deleted during send | E-28 | Notifications off (offer cannot be shown in the background) |
| E-09 | Source file changed during send | E-29 | Camera refused (QR scan) |
| E-10 | Shared file access taken back by the other app | E-30 | QR code not from LAN Share |
| E-11 | Wi-Fi dropped | E-31 | QR code from another network |
| E-12 | IP address changed | E-32 | Pairing code withdrawn after 5 wrong tries |
| E-13 | Other device's ID changed (pin mismatch) | E-33 | Paired device's key changed |
| E-14 | Other device older (v1 only) | E-34 | Chunk failed its check 3 times in a row |
| E-15 | Other device newer, unknown features | E-35 | Session expired on the receiver |
| E-16 | Receive folder not writable | E-36 | Receiver cancelled |
| E-17 | Picked save folder (SAF) no longer allowed | E-37 | Sender cancelled |
| E-18 | Android background limit reached (`onTimeout`) | E-38 | Too many files for an old receiver |
| E-19 | Local network permission refused (Android 17) | E-39 | Windows firewall blocking |
| E-20 | Windows network marked Public | E-40 | Battery saver stopped the app (maker-specific) |
| | | E-41 | Receiver refused one file name (shows which file and why) |

Pass: 41 of 41 show the catalogued message; 0 raw exceptions, stack traces or "Something went wrong" without detail on any screen or web page.

### 7.6 Size and scale

| ID | Test | Pass when |
|---|---|---|
| Z-01 | D9 boundary sizes, every pair, v1 and v2 | Standard check |
| Z-02 | D1 and D2 | Standard check; zip of D1 opens in 7-Zip |
| Z-03 | D5 (20 GB) every pair; 100 GB PC to PC over Ethernet once per RC | Standard check |
| Z-04 | D6 and D7 every pair | Standard check; D7 keeps folders and dates (v2) |
| Z-05 | v1: 10,000 files accepted; 10,001 refused with the limit message | As stated |
| Z-06 | v2: 1,000,000 manifest entries (1 KB files, PC to PC) | Accepted; memory growth from 10,000 to 1,000,000 entries 100 MB or less (initial target), because the manifest is paged and stored, not held whole |
| Z-07 | D8 deep tree, Windows with and without long paths turned on | Standard check; names past 260 characters work |
| Z-08 | 2 GB offer to a receiver with 1 GB free | Refused before any byte is sent, with the space numbers |
| Z-09 | Disk fills during a transfer (another program writes) | Paused with E-06; nothing marked done; resumes after space is freed (v2) |
| Z-10 | D10 to a FAT32 USB stick (Windows, TV) | Refused before sending, with E-07 |
| Z-11 | Activity / History with 10,000 rows | Scrolling: 95% of frames under 16.7 ms on the OnePlus 8T and under 33 ms on the Android Go phone |
| Z-12 | Share to anyone page with 10,000 files | First view within 3 s on a mid-range phone; Download all works |
| Z-13 | 10 receivers at once from one Windows sender (nice-to-have feature, when built) | Standard check on all 10 |
| Z-14 | Peak memory in any test | Android 200 MB or less (initial target); Windows 300 MB or less except Z-06 |

### 7.7 Android lifecycle

| ID | Test | Method | Pass when |
|---|---|---|---|
| L-01 | App killed while sending and while receiving | `adb shell am kill` in the background; `run-as <pkg> kill -9` in debug builds | Reopening resumes from the journal (v2) in 50 of 50; v1 reports clearly |
| L-02 | Force stop | `adb shell am force-stop <pkg>` | No partial file marked done; resumes when reopened (v2) |
| L-03 | Doze during a screen-off transfer | `adb shell dumpsys battery unplug`, `adb shell dumpsys deviceidle force-idle` ([testing Doze](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze)) | Transfer completes, digest matches. **Passed on 2026-10-06** (OnePlus 8T, v1): 1 GB PC to phone, 120 s at about 9 MB/s on 2.4 GHz; the foreground service and locks held in deep Doze. Repeat with v2 and on every phone in the lab. |
| L-04 | Restricted standby bucket | `adb shell am set-standby-bucket <pkg> restricted` | Receiving works while the app is open; clear note in Settings |
| L-05 | The 6-hour `dataSync` limit | Shorten the timeout for testing as the [FGS timeout page](https://developer.android.com/develop/background-work/services/fgs/timeout) describes (exact commands unverified here) | `onTimeout` stops the service in time, journal saved, E-18 shown, resumes on open; 0 crashes, 0 ANRs |
| L-06 | User-initiated job stopped by the system (Android 14+) | Job stop from the test harness | Resumes when allowed |
| L-07 | Screen off for a 10 GB transfer | Real phone | Completes; digest matches |
| L-08 | Rotation, dark mode switch, font size change, split screen during the offer sheet | Real phone and emulator | Sheet stays; countdown correct; one Accept tap accepts once |
| L-09 | Low memory | `adb shell am send-trim-memory <pkg> RUNNING_CRITICAL`; Android Go phone | No crash; transfer continues or resumes |
| L-10 | Battery saver and Data Saver on; OnePlus "battery optimization" on | Real phone | Works, or E-40 with maker help |
| L-11 | Share source app takes back access | Test app revokes the grant | E-10 for that file only; others continue |
| L-12 | Crash with `IS_PENDING` files | Kill during receive, then reopen | Pending entries resumed or removed; 0 left after 7 days (`adb shell content query`) |
| L-13 | App update during a transfer | `adb install -r` | Journal kept; transfer resumes |
| L-14 | Phone reboot during a transfer | Real phone | Resumes after the app is opened (v2) |
| L-15 | Clock jumps (time zone, manual time) | Settings | Nonces and timeouts use a steady clock; no failures |
| L-16 | Permissions taken away while running | Settings: notifications, local network (Android 17), camera | Clear message for each; no crash |
| L-17 | Android 17 local network permission | `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pkg>`, reboot | Asked once at first run; refusal shows E-19; after granting, devices found within 10 s; incoming connections work |
| L-18 | API range | Emulators API 23, 26, 29, 33, 34, 35, 36, 37 | Install, first run, send and receive D12 |
| L-19 | Work profile or second user | Emulator | Works in that profile, or says it is not supported (no crash) |
| L-20 | "Don't keep activities" on | Developer options | No lost state in any flow |

### 7.8 Windows lifecycle

| ID | Test | Pass when |
|---|---|---|
| WL-01 | 2-hour transfer with a 15-minute sleep timer | PC does not sleep; `powercfg /requests` shows the request only while transferring, gone within 5 s after |
| WL-02 | Forced sleep and hibernate mid-transfer (lid close; [PsShutdown](https://learn.microsoft.com/en-us/sysinternals/downloads/psshutdown) `-d`) | v2 resumes within 30 s of waking |
| WL-03 | Network change: switch Wi-Fi, plug Ethernet, VPN on and off | Re-announced within 10 s; the other device sees the new address within 20 s |
| WL-04 | Public network profile (the owner's "Khan 5G 2" was Public) | Public network check shows; E-20 when blocked |
| WL-05 | Firewall Fix: accept UAC; cancel UAC | Accept: rule added, devices connect. Cancel (exit code 1223): app starts and explains |
| WL-06 | Start with Windows | Registry entry written; app starts in the tray after sign-in |
| WL-07 | Second launch with "Send to" paths | Paths reach the running copy within 1 s |
| WL-08 | Portable folder copied to another PC | New identity; the first PC's identity parked (regression test) |
| WL-09 | Exe on a read-only place | Data goes to `%APPDATA%`; app works |
| WL-10 | Sign out and shut down mid-transfer | Journal saved; resumes on next start (v2) |
| WL-11 | Quit from the tray | Servers stop and ports are free within 2 s |
| WL-12 | Port 47820 in use | Uses 47821; announced port matches |
| WL-13 | Windows 10 22H2, Windows 11 24H2 and 25H2 | D12 both ways |
| WL-14 | Display scaling 100%, 150%, 200%, 250% | No clipped text; footer visible |
| WL-15 | Non-English Windows and a user name with non-Latin letters | Paths and names work |
| WL-16 | Microsoft Defender scan of the exe | 0 detections |
| WL-17 | VirusTotal scan of the release exe (our own binary, no user data) | Recorded. A detection by Microsoft, Kaspersky, ESET, Bitdefender or Avast blocks the release until a false-positive report is cleared |

### 7.9 Browser matrix (folder share page and Share to anyone)

**Browsers**

| Platform | Browsers |
|---|---|
| Android | Chrome, Samsung Internet, Firefox, Brave; the browsers inside WhatsApp and Instagram |
| iPhone (iOS 26) | Safari with "Not Secure Connection Warning" off and on; Chrome |
| Windows | Chrome, Edge, Firefox, Brave |
| Mac (if one can be borrowed) | Safari |

Chrome is also tested with "Always use secure connections" set to "all sites", not only the default.

**Checks per browser**

| ID | Check | Pass when |
|---|---|---|
| B-01 | Open by scanning the QR code with the camera app | Page visible within 3 s on the test Wi-Fi |
| B-02 | Warnings | Count of taps recorded; at most 2 one-tap warnings; the sender's screen text matches what is shown |
| B-03 | Single file download | SHA-256 matches |
| B-04 | Download all (D12, and a 5 GB set on devices with space) | Zip opens in Windows Explorer, 7-Zip (`7z t` shows no errors), iOS Files, Android Files; every file's SHA-256 matches |
| B-05 | Cut Download all at a random point, 20 times | Browser shows "failed" all 20 times |
| B-06 | Video plays and seeks | Seeking works (Range) |
| B-07 | Resume a cut single-file download | Continues where the browser supports it |
| B-08 | PIN page, wrong PIN, lock | As section 5.8 |
| B-09 | Share ended | "This share has ended" within 1 s; running downloads cut within 2 s |
| B-10 | Light, dark and forced dark | All text 4.5:1 or better; button text never white on teal |
| B-11 | 320 px width and 200% text size | No sideways scrolling; footer visible and not clipped |
| B-12 | Browser tries HTTPS first | Falls back to HTTP; page visible within 3 s; no error in the sender's log |

**Gate (when a release includes web pages):** B-01 to B-12 pass in Chrome Android, Safari iPhone, Firefox Android, Samsung Internet and Brave Android. Other browsers: results recorded; failures become bugs.

### 7.10 UI and accessibility

| ID | Test | Method | Pass when |
|---|---|---|---|
| U-01 | **Footer on every screen** | Android: Compose test for every route at font scale 2.0 and a 320 dp wide screen; real phone with `adb shell settings put system font_scale 2.0`, `adb shell wm size 720x1440` and `adb shell wm density 360` (320 dp wide). On the OnePlus 8T, `settings put system font_scale` and `pm grant` fail unless the developer option "Disable permission monitoring" is on; turn it on for test runs, or use an emulator or another phone. Web: Playwright at 320 × 640 px with the root font at 200%; real phones with browser text size 200%. Windows: every window at 100% to 250% scaling and the minimum window size. TV: 720p and 1080p. | "Made with ♥ by Khan" is fully inside the visible area, above the navigation bar, not covered, and not cut off (no ellipsis) on 100% of screens |
| U-02 | Contrast | A script computes every text and background pair declared in `brand.py`, `Theme.kt` and the page CSS | Text 4.5:1 or better; large text, icons and control outlines 3:1 or better; 0 uses of `faint` for text |
| U-03 | Screen readers | TalkBack (phone), NVDA and Narrator (Windows), VoiceOver (iPhone web pages) | Every control has a name; the offer sheet reads sender, file count, size and time left; the heart is read as "love" |
| U-04 | Tkinter and screen readers | NVDA on the offer window | Title, sender and both buttons read. If Tk cannot do this, it is an S2 bug for the owner to decide |
| U-05 | Touch targets | Accessibility Scanner; [Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android) checks in Compose tests | 48 × 48 dp or larger on Android; 44 × 44 px or larger on web pages |
| U-06 | Keyboard only (Windows, web) | Manual and Playwright | Every action reachable; focus visible; Esc declines an offer |
| U-07 | D-pad only (TV) | Manual on each TV device | Every screen reachable; focus never lost; Decline has first focus for new devices |
| U-08 | Web accessibility | [axe-core](https://github.com/dequelabs/axe-core) through Playwright | 0 serious or critical issues |
| U-09 | Long and right-to-left text | Pseudo-locales `en-XA` and `ar-XB` | No clipped text; footer intact |
| U-10 | Wording | CI search of all strings | 0 uses of "not verified"; brand line matches exactly |
| U-11 | **Never nag** | CI search plus a walk through every screen | Tip card only in About and Settings; 0 tip prompts in any other screen, notification or after a transfer; no badges or dots |
| U-12 | Screenshots | Compose screenshot tests (for example [Roborazzi](https://github.com/takahirom/roborazzi)) for every screen in light, dark, 100% and 200% font | Changes reviewed by a person before merging |

### 7.11 Privacy verification

| ID | Test | Method | Pass when |
|---|---|---|---|
| P-01 | Phone packet capture: a 30-minute script (first run, discovery, send, receive, pairing, Share to anyone, About without tapping a tip) | [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) (no root, uses VpnService, exports PCAP), plus a capture on the PC side with Wireshark, plus the emulator's `-tcpdump` option. Whether PCAPdroid sees local-network traffic is unverified, so the PC capture is required. | 0 packets from the app to any non-private address; 0 DNS lookups by the app except mDNS |
| P-02 | Windows packet capture, same script | `pktmon` (built into Windows) or Wireshark | Same |
| P-03 | Tip and website links | Tap $5 | Only the browser makes requests; the app makes none |
| P-04 | Tracker scan | [exodus-standalone](https://github.com/Exodus-Privacy/exodus-standalone) on the release APK | 0 trackers |
| P-05 | Library deny list | CI | 0 matches (Firebase, Play Services ads, Sentry, Crashlytics, Bugsnag, AppsFlyer, Adjust, Facebook, AppLovin, Pangle and similar) |
| P-06 | URL allow list | CI search of both repos | Only allowed khanlabs.dev links |
| P-07 | Permissions | CI compares the merged manifest with the approved list | No difference |
| P-08 | Backup | `adb shell bmgr backupnow <pkg>` | No app data backed up; identity never leaves the device |
| P-09 | Logs | Search all logs after P-01 for tokens, PINs, notepad text and file contents | 0 matches |
| P-10 | Second opinion | GlassWire on the owner's phone during P-01 | No internet use shown for the app |

### 7.12 Performance and battery

Baselines first (R1): `iperf3` for each device pair on each network (1 stream and 4 streams, 30 s, 3 runs, take the middle value), and 1.2.0 and 1.2.1 speeds. **The owner's test Wi-Fi is 2.4 GHz ("Khan 2.4G"), so a 5 GHz network is needed too; 2.4 GHz numbers alone would understate speed.**

| ID | Test | Pass when |
|---|---|---|
| PF-01 | One large file (D4), PC to PC on Wi-Fi | 90% or more of `iperf3` (from the old plan) |
| PF-02 | D4 phone to PC and PC to phone | 80% or more of `iperf3` (initial target) |
| PF-03 | D4 PC to PC on gigabit Ethernet | 85% or more of `iperf3` (initial target) |
| PF-04 | D6 (10,000 × 50 KB) | At least 5 times faster than 1.2.0 on the same network (from the old plan) |
| PF-04b | **Small files with v2** (D6 and D14), phone to PC, PC to phone and PC to PC, on Wi-Fi 5 | **At least 300 files per second** (gate G17). Measured baseline, v1 on 2.4 GHz on 2026-10-06: about 14 files per second (D14 in 141 s). |
| PF-05 | Offer appears on the receiver after Send | 2 s or less, 95% of the time |
| PF-06 | First file byte after Accept | 1 s or less, 95% of the time |
| PF-07 | Discovery time | Middle value 5 s or less; 99% within 15 s |
| PF-08 | Android cold start ([Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)) | 1.5 s or less on the OnePlus 8T; 3 s or less on the Android Go phone |
| PF-09 | Windows exe start to window | 5 s or less on an SSD; recorded on an HDD |
| PF-10 | Heat: 20 minutes at full speed on the phone | Speed drops by 30% or less |
| PF-11 | Battery per GB: D5 phone to PC, screen off | Recorded on the OnePlus 8T and the Android Go phone (`dumpsys batterystats`); 10% or less worse than the last release |
| PF-12 | Idle: app closed, receiving set to "while open", 8 hours | 0 alarms, 0 jobs and 0 wake locks from the app (`dumpsys alarm`, `dumpsys jobscheduler`, `dumpsys power`) |
| PF-13 | After a transfer ends | Wake locks and Wi-Fi locks released within 5 s |
| PF-14 | Share to anyone thumbnails | 64 KB or less each |
| PF-15 | Comparison with LocalSend and Quick Share on the same hardware | Recorded with the method; no public speed claim until published |

### 7.13 Soak tests

| ID | Test | Pass when |
|---|---|---|
| SK-01 | **24 hours, PC receiving:** two senders (phone and a second PC) each send a mixed set every 5 minutes (288 sessions each), 10% of runs with a chaos profile | 100% of sessions end verified or with a catalogued message; 0 crashes; memory growth from hour 1 to hour 24 10% or less; open handles back to the start count (±5) after each quiet period; thread count back to start (±2); no leftover temp files; log 10 MB or less |
| SK-02 | **24 hours, phone receiving** with "Keep receiving" on and the screen off | Same; and the Android 15 background limit behaves as L-05 says |
| SK-03 | **24 hours, TV receiving** (from R8) | Same |
| SK-04 | **72 hours, Windows:** app idle with discovery and the folder share on; a script loads the web page every minute; devices join and leave every 2 minutes; a network change every 6 hours | Same, plus ports still open, and 0 devices removed while they still answer |
| SK-05 | **Overnight phone backup:** D7 phone to PC with Wi-Fi dropped once an hour | Standard check |
| SK-06 | **Discovery soak:** 24 hours with the PC and phone apps open | 0 false "lost" removals; the other device always listed within 15 s after it returns |

Tools: `typeperf` for Windows counters (private bytes, handle count, thread count), `adb shell dumpsys meminfo`, the open-file count from `/proc/<pid>/fd` in debug builds, and `dumpsys gfxinfo`.

### 7.14 Upgrade and compatibility

| ID | Test | Pass when |
|---|---|---|
| UP-01 | Windows 1.2.0 → 1.2.1 → 1.3 data folder | Same device ID; settings, notepad and history kept |
| UP-02 | Windows 1.3 → 1.2.1 (downgrade) | Starts; same ID; v2 data ignored without errors |
| UP-03 | Android release-to-release upgrade (`adb install -r`) | ID, paired devices, history and journals kept |
| UP-04 | Room database migrations | Every schema step passes `MigrationTestHelper` |
| UP-05 | A journal from version N resumed by version N+1 | Resumes (the journal format has a version number) |
| UP-06 | Unknown TXT keys and unknown JSON fields from a newer peer | Ignored; transfer works |
| UP-07 | Mixed versions on one network (the matrix in 7.2) | All cells pass |
| UP-08 | `targetSdk` 36 → 37 (a planned step) | L-17, L-18 and the full device lab pass before release |
| UP-09 | Windows 10 22H2 to Windows 11 25H2; Android 6 to 17 | Covered by WL-13 and L-18 |

### 7.15 Release tests

| ID | Test | Pass when |
|---|---|---|
| RL-01 | APK signature | `apksigner verify --print-certs` shows the published certificate fingerprint; v1 and v2 schemes present |
| RL-02 | Windows signature (if signed) | `signtool verify /pa` passes |
| RL-03 | Checksums | `SHA256SUMS` matches the files downloaded from the public page on a clean machine |
| RL-04 | Reproducible APK | Two builds from the same tag on two machines are identical, apart from the signature (needed for IzzyOnDroid's checks later) |
| RL-05 | Clean install, Windows | Windows Sandbox: first run, firewall prompt, SmartScreen screen recorded, D12 both ways |
| RL-06 | Clean install, Android | Fresh emulator and a reset phone: first run, permissions, D12 both ways |
| RL-07 | 30-minute smoke test | Fixed checklist: discovery, QR connect, send and receive D12 and D11, cancel, resume after a kill, Share to anyone (when built), About shows the right version, footer on every screen |
| RL-08 | Play build (when it exists) | `targetSdk` 36 or higher; the APK has no `khanlabs.dev/support` text and no tip card (CI search) |
| RL-09 | Release notes | Version, changes, and either "privacy promise unchanged" or a clear note of what changed |
| RL-10 | Rollback | The previous release stays downloadable |
| RL-11 | Owner's daily use | 7 days on the RC with 0 S1 and 0 S2 found |

### 7.16 Device lab

**Owned now**

| Device | Use |
|---|---|
| OnePlus 8T, Android 14 (OxygenOS, based on ColorOS) | Main phone, nightly runner over USB. Turn on the developer option "Disable permission monitoring" for automated runs, or `settings put` and `pm grant` fail. |
| Owner's PC, Windows 11 IoT Enterprise 25H2 | Main PC, self-hosted runner |
| Second PC at 192.168.1.232 | Second PC for W→W and two-PC tests |
| "Khan 2.4G" and "Khan 5G 2" Wi-Fi | Networks (the second one is on the Public profile) |

**To buy or borrow, in order** (prices: unverified)

| # | Device | Why | Needed by |
|---|---|---|---|
| 1 | Second Android phone, ideally Samsung mid-range | A→A tests; Samsung Internet; One UI battery rules | R2 |
| 2 | Android 17 phone (for example a recent Pixel) | Local network permission on real hardware | R2 |
| 3 | Xiaomi or Redmi phone (HyperOS) | Strict battery manager | R4 |
| 4 | Android Go or 2 GB RAM phone | Low memory, slow storage | R4 |
| 5 | iPhone (borrow) | Safari checks | R7 |
| 6 | Android 7 or 8 phone | Old APIs | R5 |
| 7 | Windows 10 laptop with Wi-Fi 5 | Windows 10, laptop sleep | R5 |
| 8 | Google TV Streamer or Chromecast with Google TV; a cheap 32-bit Amlogic box (Android 9 to 11); Fire TV Stick 4K | TV, no system file picker, no Google services | R8 |
| 9 | A 5 GHz router that can turn on client isolation and a guest network | Network cases | R1 |

**Emulators:** phones API 23, 26, 29, 33, 34, 35, 36, 37; Android TV API 30 and 34; a low-RAM profile.

**Networks:** 2.4 GHz, 5 GHz, guest network with client isolation, phone hotspot, Wi-Fi Direct (R9), Ethernet, mesh if one can be borrowed.

### 7.17 Automation and CI

**Runners**
1. GitHub-hosted runners for Linux jobs (fuzzing, chaos lab, Kotlin JVM tests). Private repos get a limited free monthly allowance, and Windows and macOS minutes cost more; check the current numbers on GitHub's billing pages (unverified here).
2. A self-hosted runner on the owner's PC for Windows tests and for the phone on USB. Free, and the phone is already connected.

**Jobs**

| Level | Time limit | Contents |
|---|---|---|
| Per commit | 15 min | Python unit tests (33 + 15 + 25 + 16 today); Kotlin JVM tests (22 today) and lint; vectors in both languages (PR-20); v1 conformance against both servers on loopback (PR-01, PR-02); Python↔Kotlin transfers of D1, D2, D3, D11 and a 1,000-file D6; the hard-name sets D3 and D14 through both senders (PR-17); Hypothesis; tracker, URL, permission and wording checks (P-05 to P-07, U-10, U-11); contrast script (U-02) |
| Nightly | 4 hours | Fuzzing 1 CPU-hour per target; chaos lab (C-01 subset, C-03, C-08); scale on loopback (Z-01, Z-05, Z-06); v2 conformance; random cross-language vectors (PR-22); emulator tests on API 23, 34 and 37 with screenshots at 200% font and 320 dp (U-01, U-12); Playwright web tests with axe-core (B-10, B-11, U-08); real phone: PR-03, a 1 GB W→A and A→W, one kill-and-resume (L-01) |
| Per RC | 3 to 5 days | Everything in 7.2 to 7.16, including SK-01 to SK-06, the 1,000-run chaos batch (C-14), 24 CPU-hours of fuzzing per target, browser matrix, device lab, privacy captures, RL-01 to RL-11 |

**Flaky tests:** a test that fails and then passes on a rerun is flaky. It is moved to quarantine within 1 working day and fixed within 5. The flaky rate must stay at 1% or less over 30 days. A release cannot ship with a quarantined test in the protocol, security, privacy or data-integrity areas.

### 7.18 Bug triage and release gates

**Severity**

| Level | Meaning | Examples | Fix time |
|---|---|---|---|
| S1 Critical | Data loss, a wrong file, a security hole, or a broken privacy promise | Wrong bytes saved as done; file written outside the receive folder; a spoofed device shown as Paired; a token in logs; any internet connection by the app; crash on every start | Blocks all releases. Released versions: fixed release within 2 working days |
| S2 Major | A main flow fails or misleads | Transfer fails without a clear message; a hang over 60 s; a crash in a main flow; footer missing on a screen; a contrast failure in a main flow; any tip prompt outside About | Blocks the release |
| S3 Minor | Works, with a fault | Wrong text, layout glitch, slow but correct | Next release, or owner decides |
| S4 Cosmetic | Looks only | Spacing, icon alignment | When convenient |

**Special rule:** if a wrong file is ever saved as done (in any test or user report), all releases stop. The cause is found, a regression test is added, and the 2,000-run suite (G2) and the 1,000-run chaos batch (C-14) are run again in full before any release.

**Release gates.** G1 to G12 and G17 have no exceptions. G13 to G16 can be waived only by the owner, in writing in `PROGRESS.md`, with a reason and a fix date.

| Gate | Area | Pass line |
|---|---|---|
| G1 | Open bugs | 0 open S1 and 0 open S2 anywhere in the app (not only in new features). S3: 10 or fewer, each reviewed. |
| G2 | **Data integrity** | 2,000 of 2,000 clean scripted transfers across all pairs (W→W, W→A, A→W, A→A; TV pairs from R8), mixing D1 to D14. 0 wrong files in all chaos, fuzz, scale and soak runs. |
| G3 | **Resume** (from R3/R4) | 100 random kills per pair (50 sender, 50 receiver): 100% resume; each kill re-sends at most connections × chunk size. Wi-Fi off and on: 50 of 50 resume within 30 s. |
| G4 | Chaos | C-14: 99.5% or more finish verified with no taps; 100% of the rest end within 60 s with a catalogued message; 0 hangs. |
| G5 | **Discovery** | 500 trials per network that allows multicast: 99% or more found within 15 s, 100% within 60 s. QR connect: 100 of 100. 0 false removals in SK-06. |
| G6 | Clear errors | E-01 to E-41: 41 of 41 correct; 0 raw exceptions or stack traces on any screen or page. |
| G7 | Conformance | PR-01 to PR-06 on every server build; PR-10 to PR-18; PR-20 to PR-22; every cell of the version matrix passes. |
| G8 | Security | S-01 to S-22 pass; fuzzing 24 CPU-hours per target with 0 crashes; S-23 (outside review) done before any v2 release with 0 open high or medium findings. |
| G9 | **Privacy** | P-01 to P-10: 0 non-private connections, 0 trackers, 0 secrets in logs, permission list unchanged. |
| G10 | **Soak** | SK-01 to SK-06: 0 crashes and 0 ANRs; memory growth 10% or less; handle growth 0 (±5). |
| G11 | Scale | Z-01 to Z-14 pass. |
| G12 | Lifecycle | L-01 to L-20 and WL-01 to WL-17 pass. |
| G13 | UI and accessibility | U-01 to U-12 pass: footer visible on 100% of screens at 200% font and 320 dp; all contrast pairs pass; 0 nag prompts. |
| G14 | Performance | PF-01 to PF-15 targets met; no measure more than 10% worse than the last release. |
| G15 | Browsers | Section 7.9 gate, for releases with web pages. |
| G16 | Release | RL-01 to RL-11 pass, including 7 days of the owner's daily use. |
| G17 | **Small-file speed** (every release with protocol v2) | PF-04b: at least 300 small files per second on Wi-Fi 5 in every direction tested (phone to PC, PC to phone, PC to PC). PR-17 and PR-18 pass: 0 offers refused because of a name, and any refusal names the file and the reason. |

**Freeze rule:** once a release candidate is cut, any code change restarts the RC checks for the changed areas, and SK-01 (24 hours) runs again.

---
## 8. Release and distribution

### 8.1 Channels

| Channel | App | When | Notes |
|---|---|---|---|
| Public download page (khanlabs.dev/download) with SHA-256 checksums | Both | R5 | Both repos are private, so their GitHub Releases are not public. Use a public releases-only repo, or the website, or make the repos public (Q5). |
| Obtainium (the user's own updater, tracks a public releases repo) | Android | R5 | No update code in our app |
| Private beta: limited-distribution developer account | Android | R4 to R5 | Up to 20 devices, no ID and no fee (see 8.3) |
| IzzyOnDroid, then F-Droid | Android | R10 | Need open source (Q5) |
| Google Play (a separate "play" build) | Android | R10 | No tip card (8.6) |
| winget and Scoop | Windows | R10 | After signing is solved |
| Microsoft Store (MSIX) | Windows | Later, optional | The Store signs MSIX packages. Package identity would also allow a Windows Share entry. Individual registration cost and Python-app packaging: unverified. |

### 8.2 Windows
1. **One-file exe** built with PyInstaller (`build_exe.py`). If antivirus false alarms appear, fall back to a zipped one-folder build.
2. **Code signing from Pakistan.** Microsoft's Artifact Signing (formerly Azure Trusted Signing, from $9.99 a month) accepts individuals only from the USA and Canada, and organizations only with 3 or more years of history there. So it is very likely not available to the owner ([code signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)). Signing also gives no instant SmartScreen trust; reputation builds over time ([SmartScreen reputation](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation)).
3. **Options, in order** (all unverified for an individual in Pakistan; see Q8):
   1. If open source: free signing programs for open-source projects (for example SignPath Foundation) or a low-cost open-source certificate (for example Certum).
   2. An OV certificate from a public certificate authority with cloud signing (keys must live on hardware or a cloud HSM).
   3. The Microsoft Store (MSIX), which signs the package.
4. **Until signed:** publish SHA-256 checksums; the user guide shows the SmartScreen "More info, Run anyway" steps with screenshots; submit each release to [Microsoft's file submission page](https://www.microsoft.com/en-us/wdsi/filesubmission) as a false positive if Defender flags it.
5. **Release gate:** Defender 0 detections (WL-16).

### 8.3 Android sideload
1. **One universal APK**, signed with v1 and v2 schemes (v2-only needs API 24; we support 23).
2. **Release key:** made once, kept in two offline backups in two places, with a written custody plan in the repo (without the key). Losing it means every user must uninstall to update.
3. **Android developer verification.** Google now requires apps on certified Android devices to come from verified developers, sideloaded apps included.
   - Registration opened in March and April 2026. Limited-distribution accounts arrived in August 2026: up to 20 devices, no ID and no fee.
   - Enforcement started on 30 September 2026 on certified devices in Brazil, Indonesia, Singapore and Thailand. It goes worldwide in 2027.
   - Apps from unverified developers need an advanced install flow with a 24-hour wait, or adb.
   - Sources: [Android Authority timeline](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/), [Cybernews](https://cybernews.com/security/google-test-sideloading-unverified-android-apps/). The fee and steps for full verification, and the effect on F-Droid: unverified.
   - Recommendation (Q9): use a limited-distribution account for the private beta; complete full verification and register the package name and release key before the first public APK (R5), well before 2027.
4. **Battery exemption** request only in the sideload build.

### 8.4 IzzyOnDroid and F-Droid
IzzyOnDroid needs an OSI or FSF approved open-source licence, no proprietary parts, and no ads or tracking. It checks for reproducible builds and ships the developer-signed APK ([inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)). Our app already has no Google libraries, so it fits once the code is open (Q5). F-Droid builds from source and has stricter rules; do IzzyOnDroid first.

### 8.5 Release steps (every release)
1. Freeze: cut the RC branch. From here, any change restarts the affected checks (section 7.18).
2. Run the RC level of section 7. Record results in `PROGRESS.md`.
3. 7 days of owner daily use (RL-11).
4. Check every gate G1 to G17. No release with a failed gate.
5. Build and sign. Write `SHA256SUMS`.
6. Verify on a clean machine (RL-01 to RL-06).
7. Publish the files, the checksums and the release notes. Keep the previous release available.
8. Update `CHANGES.md`, `PROGRESS.md` and the user guide.

### 8.6 Google Play build (R10)
1. A product flavour named `play`.
2. **No tip card and no link to any page that asks for money.** Google Play's payments policy requires Play billing for in-app donations unless they are tax-exempt donations, and does not allow linking out to pay otherwise ([payments policy](https://support.google.com/googleplay/android-developer/answer/9858738)). In practice:
   - AnkiDroid had to remove its Open Collective donation link from the Play build by 11 September 2026; Google wanted US 501(c)(3) status and did not accept 501(c)(6) ([AnkiDroid issue 21656](https://github.com/ankidroid/Anki-Android/issues/21656)).
   - StreetComplete was flagged even for a link to a home page that mentions donations ([StreetComplete issue 3768](https://github.com/streetcomplete/StreetComplete/issues/3768)).
   - The US external links program charges a 10% or 20% fee ([external offers](https://support.google.com/googleplay/android-developer/answer/16470497)). Not worth it for tips.
3. So the Play build's About shows the version, the brand line, the promises and the licences, and either no website link or a link to a khanlabs.dev page with no mention of tips (Q7). "Made with ♥ by Khan" stays; it has no link.
4. Drop `MANAGE_EXTERNAL_STORAGE`, the battery exemption, and possibly APK install on TV.
5. `targetSdk` 36 or higher ([target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)). Declare the foreground service type and reason in Play Console.
6. Data safety: "No data collected, no data shared" is honest for app-to-app transfers, which are encrypted end to end between the two devices. Whether Share to anyone (plain HTTP to another person's browser) needs any declaration: unverified; check Play's Data safety rules for user-initiated sharing before submitting.

### 8.7 The tip page on khanlabs.dev (low priority, R10, about 2 days)

**Today:** `https://khanlabs.dev/support?amount=5` returns 200 but shows the portfolio home page ("Mueed Ali Khan | Systems Administration & Security"). The page still has to be built.

**Rules for the page**
1. One-time tips only. No monthly option, no memberships, no supporter tiers, no goal bar, no list of tippers.
2. Top line: "Thank you. A tip does not unlock anything. LAN Share is free for everyone, forever."
3. The amount from `?amount=N` is shown as the suggestion. Custom has no suggestion.
4. No analytics, no outside scripts, fonts or images, no cookies. The website is not the app, but it keeps the same promise.
5. Everything static. QR codes are made at build time as SVG files, never by an online QR service (which would see every visit).

**Ways to tip, in order of readiness**

| Way | For whom | Status | Notes |
|---|---|---|---|
| Bank transfer in Pakistan (Raast or IBAN) | Tippers in Pakistan | Ready to use | Raast is the State Bank of Pakistan's free, instant person-to-person system, by IBAN or Raast ID ([SBP press release](https://www.sbp.org.pk/press/2022/Pr-15-Feb-2022.pdf), [Raast](https://en.wikipedia.org/wiki/Raast)). Show an IBAN of a separate account, not a personal phone number (a Raast ID is usually a phone number). The tipper's bank shows the account title. |
| Crypto: stablecoins on low-fee networks | Tippers abroad | Ready after the checks below | See "Crypto tips" below |
| Card | Tippers abroad | Not ready | No verified service fits yet (Q1) |

**Crypto tips: what to offer**

| Option | Show? | Why |
|---|---|---|
| **USDC or USDT on Solana** (one address takes both) | Yes, first | Network fee under $0.001 ([eco.com fee ranking](https://eco.com/support/en/articles/15247716-cheapest-tether-network-2026-usdt-fees-by-chain-ranked), [cryptonetworkguide](https://cryptonetworkguide.com/blog/usdc-cheapest-network-2026)). Stable value, so a $5 tip stays $5. |
| **USDT or USDC on BNB Smart Chain (BEP-20), Polygon or Base** (one EVM address takes all three) | Yes, second | Fees of a cent or less on the network. Binance's USDT withdrawal fee on BEP-20 has been the lowest of its networks (reported between 0.01 and 0.29 USDT in 2026; fees change, so this is unverified for any given day) ([withdrawalfees.com](https://withdrawalfees.com/exchanges/binance), [cryptocalcspro](https://cryptocalcspro.com/exchanges/binance-withdrawal-fees-2026)). Most tippers will send from an exchange, so the exchange's withdrawal fee matters more than the network fee. |
| USDT on TRON (TRC-20) | Not at first | Very common, but each transfer costs about $0.20 to $5 depending on the sender's setup, and Binance charged 1 USDT to withdraw it in May 2026 ([eco.com](https://eco.com/support/en/articles/15010637-cheapest-way-to-send-usdt-network-fee-comparison-2026), [cryptocalcspro](https://cryptocalcspro.com/exchanges/binance-withdrawal-fees-2026)). That can be half of a $2 tip. Add later only if tippers ask. |
| Tokens on Ethereum mainnet | No | Highest fees (Binance USDT-ERC20 withdrawal 1.6 USDT in May 2026). |
| Bitcoin | Not at first | On-chain fees make $2 tips poor value. Lightning is cheap, but the owner would need a Lightning wallet; for example Phoenix receives for free only when it already has capacity, and adding capacity costs 1% with a minimum of 3,000 sats ([Phoenix review](https://www.bitcoinproducts.com/blog/phoenix-wallet-review)). Add later if asked. |

**What to show for each crypto option**
1. The coin and network in words: "USDC or USDT on Solana".
2. The address as text, with a Copy button.
3. A QR code of the plain address only (no amount inside; wallet support for amounts varies, unverified).
4. A warning: "Send only USDC or USDT, and only on Solana, to this address. Tips on other networks may be lost."
5. The amount as a hint: "$5 is about 5 USDC."
6. The privacy note below.

**Privacy notes (on the page)**
> Crypto tips are public. Anyone can look up this address and see every tip, its amount, its time and the sending address. If you send from an exchange, the exchange also knows it was you. KhanLabs does not collect or store anything about tippers.

**For the owner**
1. Use a **self-custody wallet** used only for tips, not an exchange deposit address (those can change, and an exchange may not credit tokens sent on a network it does not support). Keep the recovery phrase offline, with the same care as the Android signing key.
2. Never reuse a personal wallet. Anyone could see its balance and history.
3. **Address swap risk:** if the website were changed by an attacker, tips would go to the attacker. Publish the same addresses in the public releases repo, and run a monthly check that the page and the repo match.
4. **Freezes:** Tether and Circle can freeze stablecoin addresses. Exchanges may hold or ask about incoming funds that came from flagged addresses. The risk for small tips is low but real.
5. **Pakistan rules:**
   - The Virtual Assets Act 2026 was passed in March 2026. Buying, holding and receiving crypto is legal for individuals. Businesses that provide crypto services need a licence from PVARA, the Pakistan Virtual Assets Regulatory Authority ([ProPakistani](https://propakistani.pk/2026/03/07/crypto-in-pakistan-just-got-regulated-parliament-approves-virtual-assets-act/), [The Block](https://www.theblock.co/post/392665/pakistan-parliament-passes-virtual-assets-act-formalizing-crypto-regulatory-authority), [Deccan Herald](https://www.deccanherald.com/world/pakistan/pakistan-legalises-use-of-virtual-assets-including-crypto-currencies-3968945)).
   - On 14 April 2026 the State Bank let banks hold accounts for licensed crypto firms, ending the 2018 ban ([Finance Magnates](https://www.financemagnates.com/cryptocurrency/pakistan-ends-seven-year-crypto-banking-ban-but-bars-trading-by-banks/), [crypto.news](https://crypto.news/pakistan-lifts-crypto-banking-ban-after-8-years/)). So converting tips to rupees through a licensed exchange and a bank is now possible.
   - Tax: crypto holdings and income must be declared in the yearly return; guides describe a 15% tax on trading gains and normal income tax on other crypto income ([ICT guide](https://www.ict.edu.pk/blogs/tax-crypto-income-pakistan-2026-guide), [Business Recorder](https://www.brecorder.com/news/40390133)). How tips are taxed: unverified.
   - That receiving personal tips is not a "virtual asset service" needing a licence is our reading of the summaries, not legal advice: unverified.
   - **Recommendation:** ask a Pakistani tax adviser before turning crypto tips on. It is a one-hour question.

---

## 9. Risks and open questions

### 9.1 Open questions for the owner (each with a recommendation)

**Q1. Which service handles card tips for someone in Pakistan?**

| Service | Pays out to Pakistan? | Fit for one-time tips | Source |
|---|---|---|---|
| Stripe | No. Pakistan is not a supported country; cross-border payouts only reach the US, UK, EEA, Canada and Switzerland. A Bandcamp help page claims Stripe payouts for Pakistan since July 2026: unverified. | n/a | [Stripe global](https://stripe.com/global), [cross-border payouts](https://docs.stripe.com/connect/cross-border-payouts) |
| PayPal | No. Not available to Pakistan residents (checked 9 September 2026). | n/a | [ElevatePay](https://www.elevatepay.co/blog/is-paypal-available-in-pakistan) |
| Ko-fi | No. Payouts only through PayPal and Stripe. | Would fit | [Ko-fi help](https://help.ko-fi.com/hc/en-us/articles/115003980093-How-do-I-get-paid) |
| Buy Me a Coffee | No. Its payout country page (24 August 2026) names only Stripe and does not list Pakistan. A 2021 post promised Payoneer and Wise; the current page does not show it. | Would fit | [BMC payout countries](https://help.buymeacoffee.com/en/articles/6258038-supported-countries-for-payouts-on-buy-me-a-coffee), [BMC 2021 post](https://x.com/buymeacoffee/status/1372828170156613636) |
| GitHub Sponsors | No. Pakistan is not among the supported regions. | Would fit | [GitHub Sponsors](https://docs.github.com/en/sponsors/getting-started-with-github-sponsors/about-github-sponsors) |
| Patreon | Payoneer payouts exist for non-US creators ($1 per payout, $25 minimum); Pakistan: unverified. | **No: it is built on memberships**, which the owner ruled out | [Patreon payouts](https://support.patreon.com/hc/en-us/articles/203913499-Paying-out-your-earnings) |
| Lemon Squeezy | Pakistan was listed for bank payouts. But it prohibits donations where no product exists, and it is moving sellers to Stripe Managed Payments (35+ countries; Pakistan coverage unverified). | Poor: a tip has no product | [Supported countries](https://docs.lemonsqueezy.com/help/getting-started/supported-countries), [prohibited products](https://docs.lemonsqueezy.com/help/getting-started/prohibited-products), [2026 update](https://www.lemonsqueezy.com/blog/2026-update) |
| Paddle | Supports Pakistani sellers (wire or Payoneer), but prohibits donations. | No | [Paddle rules](https://www.paddle.com/help/start/intro-to-paddle/what-am-i-not-allowed-to-sell-on-paddle), [Paddle vs Lemon Squeezy in Pakistan](https://jawadhassan.dev/blog/paddle-vs-lemonsqueezy-pakistan) |
| Payoneer | Yes. Works in Pakistan with CNIC verification; USD, GBP and EUR receiving accounts. Payment requests let a payer pay by card or bank transfer. Card fees are reported between 2.9% + $0.49 and 3.99% + $0.49; withdrawal to a Pakistani bank about 1.2% to 4%; $29.95 yearly fee if under $6,000 received. | Unclear: built for billing clients. Whether tips are allowed and whether a reusable link exists: unverified. | [Payoneer: request a payment](https://payoneer.custhelp.com/app/answers/detail/a_id/12280/~/request-a-payment---information-for-receivers), [Payoneer pricing](https://www.payoneer.com/pricing/), [WorldFirst review](https://www.worldfirst.com/sasia/blog/business-banking-insights/payoneer-review/), [Pakera fees](https://pakera.pk/payoneer-fees-pakistan-2026/) |
| Open Collective (Open Source Collective) | Pays the maintainer's expenses by Wise or PayPal. Wise to a PKR account: unverified. Needs the app to be open source. | Good: one-time contributions are normal there | [Open Collective payouts](https://docs.opencollective.com/help/expenses-and-getting-paid/receiving-payment-through-payoneer-or-wise) |

**Recommendation**
1. Launch the tip page with **Raast or IBAN** (Pakistan) and **stablecoins on Solana and on BNB Smart Chain, Polygon and Base** (abroad). No payment company, no policy risk, no fees for the owner beyond the exchange's.
2. **Card:** ask Payoneer in writing whether one-time tips through payment requests are allowed and whether a reusable payment link exists. Add "Pay by card" only if both answers are yes.
3. If the apps go open source, add **Open Collective**, after confirming Wise pays out to a PKR account.
4. Do not use Stripe, PayPal, Ko-fi, Buy Me a Coffee, GitHub Sponsors, Patreon, Paddle or Lemon Squeezy.
5. Spend no more than 2 days on this, in R10.

**Q2. Which coins and networks for crypto tips?** USDC and USDT on Solana, plus one EVM address for BNB Smart Chain, Polygon and Base. Not TRON, Ethereum mainnet or Bitcoin at first. Details and the privacy text are in section 8.7. Check with a tax adviser first.

**Q3. The heart color on light backgrounds.** #FF5F57 is 2.79:1 on #F6F7F9 and 2.99:1 on white, below the 3:1 needed for icons. **Recommendation:** keep #FF5F57 on dark backgrounds (5.2 to 6.6:1) and use #E5484D on light backgrounds only (3.65 and 3.91:1), the closest red that passes. If the heart is treated as text, #DC2626 (4.51 and 4.83:1) is needed instead.

**Q4. What "every screen" means for the footer.** **Recommendation:** every full-screen place (section 4.2); modal sheets, dialogs and the keyboard may cover it while open.

**Q5. Open source?** **Recommendation:** yes, after the v2 cryptography review (end of R3). It brings IzzyOnDroid and F-Droid, Open Collective, possible free code signing, and proof of the privacy promise; and the app survives even if KhanLabs stops. Licence: GPL-3.0, so any fork must stay open and cannot quietly add ads or tracking. Apache-2.0 is the alternative if wide reuse matters more.

**Q6. Google Play, or sideload first?** **Recommendation:** sideload first (download page, Obtainium, IzzyOnDroid). A Play build later, in R10.

**Q7. Links in the Play build.** **Recommendation:** no tip card and no website link in the Play build (section 8.6). Do not join the paid external links program.

**Q8. Windows code signing.** **Recommendation:** decide after Q5. If open source, apply to an open-source signing program first. If not, ask two certificate authorities in writing whether they validate individuals in Pakistan and offer cloud signing, and compare prices. Until then, ship unsigned with checksums and clear SmartScreen steps.

**Q9. Android developer verification.** **Recommendation:** a limited-distribution account now, for the private beta; full verification and package registration before R5.

**Q10. Should the private-address guard allow VPN ranges such as 100.64/10 (used by Tailscale)?** **Recommendation:** blocked by default; an advanced setting "Allow VPN addresses" for people who send over their own VPN.

**Q11. Version numbers after the reliability-first order.** The old plan put Share to anyone in Windows 1.3 with v2. **Recommendation:** Windows 1.3 = protocol v2 and reliability; Share to anyone moves to the next release (R7).

**Q12. A tip card on TV?** **Recommendation:** no. Many TVs have no usable browser. TV About shows "khanlabs.dev" as plain text.

**Q13. Is the name final?** "LAN Share" does not describe direct mode. **Recommendation:** keep it until before any store listing (R10), then decide once.

**Q14. Team and dates.** One developer needs about 8 to 11 months for everything, and about 4.5 to 6 months to the first public release. **Recommendation:** set dates only after R1, when real baselines exist.

**Q15. When does v1 support end?** **Recommendation:** one year after both apps ship v2. Until then, v1 keeps working in both roles.

**Q16. iOS or macOS?** **Recommendation:** not planned. The browser pages cover iPhones. Revisit after R9.

### 9.2 Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| 1 | A bug in the new resume code saves a wrong file | Breaks the main promise | SHA-256 per chunk and per file; C-08 bit-flip test; gates G2 and G3; the special rule in 7.18 |
| 2 | A mistake in the device proof or pairing design | A fake device could be trusted | Outside review (S-23) before any v2 release; negative vectors |
| 3 | Raising `targetSdk` to 37 turns on the local network permission, which blocks incoming connections too | The app stops working silently | Declare and ask for the permission in R2; L-17 test; raise the target only as its own tested step (UP-08) |
| 4 | Android 15's 6-hour `dataSync` limit | Long transfers stop | User-initiated jobs on Android 14+; clean `onTimeout` handling; resume (L-05) |
| 5 | Developer verification makes sideloading harder from 2027 | Users cannot install | Register early (Q9) |
| 6 | Phone makers' battery managers (Xiaomi, Samsung, OnePlus) | Background receiving stops | Maker-specific help; resume; L-10 |
| 7 | Wi-Fi Direct differs by chipset and maker; many TVs lack it | Direct mode is late or limited | Experiments before dates (R9) |
| 8 | Some TVs need "All files access" granted over adb | TV sending is hard | In-app browser; documented adb step |
| 9 | Browser HTTP warnings change (Chrome, Safari, Samsung Internet) | Share to anyone gets scarier | Browser matrix every release; sender's screen text matches what browsers show |
| 10 | No Windows code signing route from Pakistan | SmartScreen and antivirus warnings scare users | Q8; checksums; false-positive reports |
| 11 | Losing the Android release key or the tip wallet's recovery phrase | Users must reinstall; tips lost | Two offline backups each; written custody plan |
| 12 | Google Play's donation link rules | Play rejection | Play build without tip links (8.6) |
| 13 | The test Wi-Fi is 2.4 GHz only | Speed numbers look worse than real; G17 cannot be checked | A 5 GHz router in R1 (lab item 9) |
| 14 | Only one developer | Illness or other work stops progress | `PROGRESS.md` kept current; CI; open source |
| 15 | Both repos are private | No public place for releases | Public releases repo or the website (8.1) |
| 16 | Tkinter has weak screen reader support | Windows accessibility gaps | U-04; owner decides on any gap |
| 17 | Different file systems and name rules (found a real bug on 2026-10-06) | Whole sends refused | Senders make names safe; receivers name the file and reason; PR-17, PR-18, H-21 |
| 18 | OnePlus (ColorOS) blocks `settings put` and `pm grant` without a developer option | Automated large-font tests fail | Turn on "Disable permission monitoring", or use emulators and another phone |
| 19 | Crypto tips: frozen addresses, flagged funds, or tax questions | Small money problems for the owner | Stablecoins only on two networks; separate wallet; tax adviser first (8.7) |
| 20 | Python speed above about 1 Gbit/s | Slower than possible on fast networks | Not a problem today; measure (PF-03) before any rewrite |

---

## 10. Appendix: what changed compared with the old PLAN.md

### 10.1 Removed

| Old plan item | Why it was removed |
|---|---|
| Opt-in update check (C.11 and Phase 5) | Any update check is a request to the internet. The owner's "never add" list rules out update pings. Updates come from stores, Obtainium, or a button that opens the website. |
| The "Money" open question (F.7) | Decided: 100% free, with optional one-time tips on the website only. |
| "Our HTTPS web page already covers this" (Snapdrop row in E) | Share to anyone is plain HTTP by decision. The folder page is HTTPS, but with our own certificate, so browsers warn. |
| Azure Trusted Signing as a main signing path (Phase 5) | Now called Artifact Signing; it accepts individuals only from the USA and Canada, so it is very likely not available to the owner in Pakistan. |
| "Ready to receive" panel with the IP on the phone's home screen (C.9, C.10) | Most people never need it. On the phone it moves to the Receiving sheet. It stays on TV, where home is the receive screen. |
| The "unverified" label for senders (Phase 0) | Sounds like an error in the normal case. Replaced by "New device", and "Paired" after QR pairing. |
| "Chrome 147 and later skips its warning for private addresses" | Replaced by the Chrome 154 facts (section 5.8). |
| Server on `SSLServerSocket` with coroutines and a bounded pool (C.4) | Replaced by a plain `ServerSocket` with TLS per connection and one thread per connection (section 5.3). |
| Nonce tied to a connection; proof as one joined string (B.4) | Replaced by nonces tied to the client IP and a six-line proof message with a purpose line (`PROTOCOL-v2.md`). |
| Phase order 0 to 5, with Share to anyone before TV | Replaced by R1 to R10: reliability first, then features. |
| The 9-point testing strategy | Replaced by section 7, with numbers for every test and gates G1 to G17. |

### 10.2 Changed
1. Done-when criteria are stricter and counted (for example "50 of 50 resume within 30 s", not just "resumes").
2. Share to anyone moved from Windows 1.3 to after protocol v2 (R7).
3. Parts of the research's top 10 UI changes moved before v2 because they make discovery and errors clear (R2).
4. The device list grew (section 7.16) and is ordered by need.
5. Android TV must target API 34 or higher on Play; phones API 36.

### 10.3 Added
1. Reliability-first principles and the special rule for any wrong file.
2. The error catalogue (E-01 to E-41), the hard-name lesson (senders make names safe; receivers say which file and why), and the small-file gate G17 (at least 300 files per second with v2 on Wi-Fi 5).
3. Safety fixes: exact zip length, notepad CSRF, a PIN cap for all IPs, no tokens in logs, fast close of TLS on the HTTP port, Mark of the Web, the private-address guard, `SO_EXCLUSIVEADDRUSE` on Windows.
4. Android 17 local network permission, Android 15 `dataSync` limit handling, Android developer verification.
5. The brand: color tokens with contrast numbers, the footer rules, the About screen and one-time tips, the never-add list.
6. The tip page plan for Pakistan: Raast or IBAN, stablecoins, Payoneer to check, and the services that do not work.
7. "Search this network" and QR connect as the discovery fallbacks.
8. Test results from 2026-10-06 on the OnePlus 8T.

### 10.4 Kept
1. Section A (problems in v1). Most were fixed in 1.2.1; the rest (resume, checksums, slow small files, unproven sender) are fixed by v2.
2. Section B (protocol v2 design), now in `PROTOCOL-v2.md`.
3. Section C (Android architecture), updated in section 5.
4. Section E (competitors), summarized below.

### 10.5 Competitors (from the old plan, still true)

| Product | Stronger than us today | Weaker | What we take from it |
|---|---|---|---|
| LocalSend | Every platform including iOS; big user base; open source; browser receive mode | No resume; trouble with many files; cannot be used with a TV remote; sender not proven | Partial accept; scan fallback; browser link mode |
| Quick Share | Built in; very fast phone to phone with no router | Needs Google Play Services; not on Android TV; closed | Fast link after discovery; our QR flow is the simple version |
| Send Anywhere | Works over the internet with a short code | Needs internet and a server; ads; accounts | The short-code experience |
| Snapdrop and PairDrop | No install | Needs a signalling server; weak with multi-GB files; no resume | Share to anyone covers this on a LAN with no internet |
| KDE Connect | Strong pairing, clipboard | File transfer is a side feature; no resume; not for TV | Pairing experience |
| SHAREit and Xender | Fast with no router; very popular in South Asia | Heavy ads, tracking (5 to 22 tracker libraries in the most-installed sharing apps per Exodus, October 2026, from `docs/UI-RESEARCH.md`), security flaws | "No router" matters; a clean option has room |

What makes us the best for large files is the combination, not raw speed: never lose progress; verified files; many files at full speed; no router needed; Android TV as a real target; paired devices that skip prompts; no account, no internet, no ads, no Play Services.

---

## Sources

**Android platform**
- [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Foreground service timeout (Android 15)](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Testing Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze)
- [Emulator command line](https://developer.android.com/studio/run/emulator-commandline)
- [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Google Play payments policy](https://support.google.com/googleplay/android-developer/answer/9858738)
- [Google Play external offers program](https://support.google.com/googleplay/android-developer/answer/16470497)
- [AnkiDroid issue 21656](https://github.com/ankidroid/Anki-Android/issues/21656)
- [StreetComplete issue 3768](https://github.com/streetcomplete/StreetComplete/issues/3768)
- [Android sideloading changes timeline (Android Authority)](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/)
- [Google tests sideloading of unverified apps (Cybernews)](https://cybernews.com/security/google-test-sideloading-unverified-android-apps/)
- [IzzyOnDroid inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)

**Browsers**
- [Chrome HTTP warnings by default from 2026 (heise)](https://www.heise.de/en/news/Chrome-to-warn-about-HTTP-connections-by-default-from-2026-10962985.html)
- [Chrome enforces secure connections by default in October 2026 (PPC Land)](https://ppc.land/chrome-enforces-secure-connections-by-default-in-october-2026/)
- [Chrome Local Network Access](https://developer.chrome.com/blog/local-network-access)
- [Mozilla bug 1716376](https://bugzilla.mozilla.org/show_bug.cgi?id=1716376)
- [Safari HTTP website warnings (Apple)](https://support.apple.com/guide/iphone/get-http-website-warnings-iphfba2ed790/ios)

**Windows**
- [Code signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
- [SmartScreen reputation](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation)
- [Microsoft file submission](https://www.microsoft.com/en-us/wdsi/filesubmission)
- [PsShutdown](https://learn.microsoft.com/en-us/sysinternals/downloads/psshutdown)
- [tkinterdnd2](https://github.com/pmgagne/tkinterdnd2)

**Testing tools**
- [Atheris](https://github.com/google/atheris), [Hypothesis](https://hypothesis.readthedocs.io/), [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) ([back to open source](https://www.code-intelligence.com/blog/jazzer-back-to-open-source)), [boofuzz](https://github.com/jtpereyda/boofuzz)
- [clumsy](https://jagt.github.io/clumsy/), [tc netem](https://man7.org/linux/man-pages/man8/tc-netem.8.html)
- [sslyze](https://github.com/nabla-c0d3/sslyze), [testssl.sh](https://github.com/testssl/testssl.sh)
- [PCAPdroid](https://github.com/emanuele-f/PCAPdroid), [exodus-standalone](https://github.com/Exodus-Privacy/exodus-standalone)
- [Accessibility Test Framework for Android](https://github.com/google/Accessibility-Test-Framework-for-Android), [axe-core](https://github.com/dequelabs/axe-core), [Roborazzi](https://github.com/takahirom/roborazzi)
- [RFC 9382 (SPAKE2)](https://www.rfc-editor.org/rfc/rfc9382)

**Payments for an owner in Pakistan**
- [Stripe global](https://stripe.com/global), [Stripe cross-border payouts](https://docs.stripe.com/connect/cross-border-payouts)
- [PayPal in Pakistan (ElevatePay)](https://www.elevatepay.co/blog/is-paypal-available-in-pakistan)
- [Ko-fi payouts](https://help.ko-fi.com/hc/en-us/articles/115003980093-How-do-I-get-paid)
- [Buy Me a Coffee payout countries](https://help.buymeacoffee.com/en/articles/6258038-supported-countries-for-payouts-on-buy-me-a-coffee), [Buy Me a Coffee 2021 post](https://x.com/buymeacoffee/status/1372828170156613636)
- [GitHub Sponsors regions](https://docs.github.com/en/sponsors/getting-started-with-github-sponsors/about-github-sponsors)
- [Patreon payouts](https://support.patreon.com/hc/en-us/articles/203913499-Paying-out-your-earnings)
- [Lemon Squeezy supported countries](https://docs.lemonsqueezy.com/help/getting-started/supported-countries), [prohibited products](https://docs.lemonsqueezy.com/help/getting-started/prohibited-products), [2026 update](https://www.lemonsqueezy.com/blog/2026-update)
- [Paddle rules](https://www.paddle.com/help/start/intro-to-paddle/what-am-i-not-allowed-to-sell-on-paddle), [Paddle vs Lemon Squeezy in Pakistan](https://jawadhassan.dev/blog/paddle-vs-lemonsqueezy-pakistan)
- [Payoneer: request a payment](https://payoneer.custhelp.com/app/answers/detail/a_id/12280/~/request-a-payment---information-for-receivers), [Payoneer pricing](https://www.payoneer.com/pricing/), [WorldFirst Payoneer review](https://www.worldfirst.com/sasia/blog/business-banking-insights/payoneer-review/), [Payoneer fees in Pakistan (Pakera)](https://pakera.pk/payoneer-fees-pakistan-2026/)
- [Open Collective payouts](https://docs.opencollective.com/help/expenses-and-getting-paid/receiving-payment-through-payoneer-or-wise)
- [Raast launch (SBP)](https://www.sbp.org.pk/press/2022/Pr-15-Feb-2022.pdf), [Raast](https://en.wikipedia.org/wiki/Raast)

**Crypto in Pakistan and network fees**
- [Virtual Assets Act passed (ProPakistani)](https://propakistani.pk/2026/03/07/crypto-in-pakistan-just-got-regulated-parliament-approves-virtual-assets-act/), [The Block](https://www.theblock.co/post/392665/pakistan-parliament-passes-virtual-assets-act-formalizing-crypto-regulatory-authority), [Deccan Herald](https://www.deccanherald.com/world/pakistan/pakistan-legalises-use-of-virtual-assets-including-crypto-currencies-3968945)
- [SBP ends crypto banking ban (Finance Magnates)](https://www.financemagnates.com/cryptocurrency/pakistan-ends-seven-year-crypto-banking-ban-but-bars-trading-by-banks/), [crypto.news](https://crypto.news/pakistan-lifts-crypto-banking-ban-after-8-years/)
- [Crypto tax in Pakistan 2026 (ICT)](https://www.ict.edu.pk/blogs/tax-crypto-income-pakistan-2026-guide), [Business Recorder](https://www.brecorder.com/news/40390133)
- [Cheapest USDT networks 2026 (eco.com)](https://eco.com/support/en/articles/15247716-cheapest-tether-network-2026-usdt-fees-by-chain-ranked), [USDT fee comparison (eco.com)](https://eco.com/support/en/articles/15010637-cheapest-way-to-send-usdt-network-fee-comparison-2026), [USDC cheapest network](https://cryptonetworkguide.com/blog/usdc-cheapest-network-2026)
- [Binance withdrawal fees (withdrawalfees.com)](https://withdrawalfees.com/exchanges/binance), [Binance withdrawal fees 2026 (cryptocalcspro)](https://cryptocalcspro.com/exchanges/binance-withdrawal-fees-2026)
- [Phoenix wallet review](https://www.bitcoinproducts.com/blog/phoenix-wallet-review)

**Repos (read on 2026-10-06)**
- `docs/PLAN.md`, `docs/UI-RESEARCH.md`, `PROGRESS.md`, `CLAUDE.md` (Android); `PROGRESS.md`, `CLAUDE.md`, `PROTOCOL.md`, `PROTOCOL-v2.md`, `docs/v2/vectors.json`, `conformance/`, `transfer.py`, `web_share.py`, `web_page.html`, `brand.py`, `lan_ftp.py` (Windows); the Share to anyone prototype `share_proto.py` and its log; screenshots from the OnePlus 8T and the PC.

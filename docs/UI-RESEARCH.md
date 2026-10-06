# KhanLabs LAN Share: UI and privacy research

Date: 6 October 2026. Covers Windows 1.2.1 and Android 0.1.0.
Store numbers (ratings, downloads, "Contains ads", Data safety labels) were read from Google Play on 6 October 2026, US store. Tracker counts come from Exodus Privacy reports; each report's date is given. Numbers in square brackets, like [12], point to the source list in section 9.

---

## 1. Summary

1. Privacy is a real, provable difference: the biggest sharing apps carry 5 to 22 ad and tracking libraries, and SHAREit's own Play label says its data is not encrypted in transit. But their high ratings show people forgive ads when transfers work, so ease and reliability must match first.
2. "The other device does not show up" is the top complaint for every local-network app (LocalSend, KDE Connect, Warpinator, Quick Share). A QR code to connect and a "Not showing up?" helper will do more than any setting.
3. People find sending easiest when they pick the content first and then tap a device, ideally from the system share sheet in two taps (AirDrop, Quick Share, LocalSend). Today's phone app makes you pick the device first.
4. The phone home screen gives its best space to IP address, port, ID, folder path and a debug card. Move these into a "Receiving" sheet. Lead with Send buttons, nearby devices and running transfers, and give History and Settings their own tabs.
5. Two platform facts change the plan. Android 17 blocks local-network traffic for apps that target it unless they hold the new "local network" permission. And an 8-character ID code can be brute-forced (KDE Connect CVE-2025-32898), so use QR pairing, not the short ID, to prove who a device is.

---

## 2. Competitors

**"zapiera" is most likely Zapya.** No app called "zapiera" turned up in any search. Zapya (by DewMobile) is the closest name, and it is an offline file-sharing app with over 100 million installs, so this report treats it as Zapya [1][2].

### 2.1 Table

"Trackers" is the Exodus Privacy count of known ad, analytics and crash-reporting libraries found in the app. "Label" is the app's own Google Play Data safety section.

| App | Platforms | Ads | Data collection | Biggest UI strength | Biggest UI complaint |
|---|---|---|---|---|---|
| Zapya | Android, iOS, Windows, macOS | Yes, plus in-app purchases | Label: shares device IDs, crash logs and location. 2 trackers (May 2025 report). Policy mentions IMEI, contacts upload, file-name history | Many ways to connect: QR code, group, shake, radar | Ads; sending folders from PC to phone broke in an update |
| SHAREit | Android, iOS, Windows | Yes, heavy, plus in-app purchases | Label: installed apps, precise location (also for ads), files, name, email, phone; "data isn't encrypted". 20 trackers, 191 permissions | Fast phone-to-phone with no router; phone clone | "More ads than functionality"; app bloated with downloader and file manager |
| Xender | Android, iOS; PC through an extra app | Yes | Label: installed apps, device IDs for ads, crash logs. 5 trackers | Works with no router; Android to iPhone | Full-screen ads that will not close; PC needs another install |
| Send Anywhere | Android, iOS, Windows, macOS, Linux, web | Yes, plus in-app purchases | Label: device IDs (also for ads), name, email, location (optional), crash logs. 22 trackers | 6-digit key works across networks | Keys expire, transfers fail; one review says the QR code led to an ad |
| ShareMe (Xiaomi) | Android | No "Contains ads" label, but reviews report ads | Label: shares device IDs for ads, crash logs. 20 trackers | Files sorted by type; resume | 80-second ad; drops connections |
| LocalSend | Android, iOS, Windows, macOS, Linux | No | Label: none collected or shared. 0 trackers | Three simple tabs; appears in the share sheet | Devices not found (Windows firewall); no resume; no TV remote |
| Quick Share (Google, Samsung) | Android with Play Services, ChromeOS, Windows 10/11; some Pixels and Samsungs send to iPhone | No | Part of Google Play Services (not checked separately) | Built into the share sheet; own devices auto-accept | Slow or failed discovery; Windows firewall trouble |
| AirDrop (Apple) | iPhone, iPad, Mac (some Android via Quick Share since Nov 2025) | No | Built in. Hashed phone numbers and emails can be reversed (2021 research, 2024 China claim) | Nearby people as bubbles in the share sheet | Apple-only for most of its life; unwanted images in public |
| Snapdrop / PairDrop | Any web browser | Snapdrop now owned by LimeWire; PairDrop has none | Snapdrop now uploads files to LimeWire servers. PairDrop is open source | No install; tap a circle to send | Browser limits on very large files; Snapdrop changed owner and behaviour |
| KDE Connect | Linux, Windows, macOS, Android, iOS | No | Label: none. 0 trackers | Pairing with a check code; many extra features | Transfer status hard to see; drops connections |
| Warpinator | Linux (official); Android and Windows (unofficial ports) | No | Label: none. 0 trackers; only 12 permissions | Group code; several transfers at once | Connection failures; needs "tinkering" |
| Feem | Android, iOS, Windows, macOS, Linux | Yes | Label: shares location and device IDs for ads, crash data. 3 trackers | Works over Wi-Fi Direct with no router; chat-style history | 3.4 rating; upgrade ads (unverified) |
| Send files to TV | Android, Android TV, Windows, macOS, Linux | Yes, plus in-app purchases | Label: shares location, device IDs, app use for ads and analytics. 2 trackers | Simple, built for TV | No folders; on new Android versions sends little more than pictures |

### 2.2 Notes per app

**Zapya.** 100M+ installs, 4.6 stars from 1.24M reviews, "Contains ads" [2]. Its Data safety label shares crash logs and device IDs for analytics, and location [3]. Its privacy policy lists device identifiers, phone IMEI, contacts upload and a "File Transfer History (transferred file names and devices)" [4]. Exodus found AdMob and Firebase Analytics in version 6.5.9.7 (report of 7 May 2025, older than the current app) [5]. Google Play pulled it for about a week in October 2019 because a third-party SDK was judged harmful [1]. Reviews praise speed and Android-to-iPhone transfers, but say folders from the PC stopped working and the default save place was confusing [2].

**SHAREit.** 1B+ installs, 4.3 stars from 18.3M reviews, ads and in-app purchases [6]. Its label lists files and docs, installed apps, precise location (partly for "advertising or marketing"), name, email and phone number. It also says "Data isn't encrypted" and "Your data isn't transferred over a secure connection" [7]. Exodus found 20 trackers and 191 permissions (report of 1 Oct 2026) [8]. In 2019 RedForce showed that anyone on the same network could download files from a SHAREit phone [11]. In 2021 Trend Micro found flaws allowing code execution and data leaks, which went unfixed for about three months [9][10]. India banned it in June 2020 with 58 other apps [12]. A five-star review still says "More ads than functionality" [6].

**Xender.** 500M+ installs, 4.6 stars from 4.58M reviews, ads [13]. Label: app interactions, installed apps, device IDs for advertising, crash logs [14]. Exodus: 5 trackers including AdMob, Crashlytics and Pangle [15]. Banned in India in June 2020 [12]. A one-star review: "Overpowering full screen ads that don't close", photos could only be picked one at a time, and the PC needed an extra app [13].

**Send Anywhere.** Now published by Rakuten Symphony Korea. 10M+ installs, 4.5 stars, ads and in-app purchases [16]. Label: device IDs (also for ads), app interactions, crash logs, optional precise location, name, email and user IDs [17]. Exodus: 22 trackers, the most in this group (report of 21 June 2026) [18]. Its 6-digit key is the best-known "short code" flow, but it relies on its servers. Recent reviews report expired keys, failed sends, freezes on "select all", and a QR code that led to an ad in the Play Store [16].

**ShareMe (Xiaomi).** 1B+ installs, 4.6 stars [72]. Play shows no "Contains ads" label, yet the label shares device IDs for advertising [73]. Exodus found 20 trackers, almost all advertising (report of 8 Sep 2026) [74]. A review complains about an "80-second ad" and lag [72]. Offers resume after interruption [72].

**LocalSend.** Open source, 10M+ installs, 4.6 stars from 15.5K reviews; the only in-app purchases are donations [19]. Label: no data collected or shared [20]. Exodus: 0 trackers on the F-Droid build, and it already declares `ACCESS_LOCAL_NETWORK` for Android 17 [21]. Its privacy policy is about 400 words and says it collects nothing [22]. Security record: a discovery flaw let an attacker on the same network pretend to be another device and read or change files (CVE-2025-54792) [23]. A path-traversal bug could write files anywhere (CVE-2025-27142) [24]. Both were fixed in 1.17.0. A cross-site scripting bug in "Share via link" followed in 2026 (CVE-2026-25154) [25]. UI: three tabs, Receive, Send and Settings [36], and it appears in the Android share menu [34]. Complaints: Windows devices not found because of the firewall or a Public network [31][32][33]; the Receive page is "essentially useless" [26]; history cannot open files [28]; no notification after an automatic save [29][30]; cannot be used with a TV remote (issue open since April 2024) [27].

**Quick Share (Google and Samsung).** Built into Android through Google Play Services. Separate app on Windows 10/11 [37]. Who can send to you: Your devices, Contacts, or Everyone for 10 minutes [39]. Your own devices on the same Google account auto-accept [38]. SafeBreach found 10 flaws in 2024, including sending files without the receiver's approval and remote code execution on Windows. Google's first fix could be bypassed (2025) [40][41][42]. Reviewers praise "Tap Share, Quick Share, My Computer and done", but Windows Firewall "sometimes prevents it from finding your phone" [43]. Discovery can take "several minutes" [35]. Since November 2025 some Pixels, and later Samsung phones, can send to iPhones by AirDrop [44][45].

**AirDrop (Apple).** Receiving modes: Off, Contacts Only, or Everyone for 10 Minutes [46]. Apple limited "Everyone" to 10 minutes in iOS 16.2 (2022), first in China [49]. TU Darmstadt showed in 2021 that AirDrop leaks hashed phone numbers and emails that can be reversed quickly [47]. In 2024 a Beijing forensic institute said it used this to identify senders [48]. The UI lesson: the receiver chooses who can reach them, and "Everyone" switches itself off.

**Snapdrop and PairDrop.** Snapdrop was a browser "AirDrop" using direct WebRTC links. It was sold to LimeWire in 2025: a GitHub issue reported the sale in April 2025 [50], and LimeWire announced it on X on 21 May 2025 [51]. Files now upload to LimeWire's servers [52]. PairDrop is the open-source fork. It pairs devices with a 6-digit code or QR code and supports temporary "rooms" [53]. This is a clear example of a privacy-friendly tool changing after a sale. Our promise should say what happens if KhanLabs is ever sold (see section 7).

**KDE Connect.** Open source, 1M+ installs, 4.0 stars [54]. Label: no data collected or shared [55]. Exodus: 0 trackers [56]. Its store text is a good model: "KDE Connect never sends any information to KDE nor to any third party" [54]. In April 2025 KDE published several advisories. One matters directly to us: the 8-character pairing check code "is susceptible to brute-force attacks", so an attacker could make a key whose code matches another device (CVE-2025-32898) [57]. Another: unauthenticated discovery packets let an attacker rename a device on screen (CVE-2025-32900) [58]. A three-star review asks for transfer status, retries and logs in the main screen [54].

**Warpinator.** Linux Mint's tool, with an unofficial Android port [59] and an unofficial Windows port, Winpinator [63]. Label: nothing collected [60]. Exodus: 0 trackers and only 12 permissions [61]. Its store text says it "Does not require your location or any other unnecessary permissions" [59]. A "group code" limits who can connect [62]. Reviews report "Connection failed ... Couldn't establish duplex" and 15 minutes of setup [59].

**Feem.** 1M+ installs, 3.4 stars, ads [64]. The website says "Your files never leave your network" and "0 servers" [67]. But the Play label shares approximate location and device IDs for advertising, plus crash data [65], and Exodus finds AdMob, Crashlytics and Firebase Analytics [66]. This shows why our promise must cover all data, not only files. Reviews praise Wi-Fi Direct with no router [64]. Full-screen "upgrade to Pro" ads during transfers were reported in a search summary, but I could not confirm this (unverified).

**Send files to TV.** 10M+ installs, 4.2 stars, ads and in-app purchases [68]. The label shares location, device IDs and app use for advertising and analytics [69]. Exodus: AdMob and Firebase Analytics [70]. The developer writes that he wants to remove "these awful and unfriendly ads" if donations allow [71]. Reviews: cannot send folders, and on current Android it can send little more than pictures, so no APKs to the TV [68]. That gap, sending APKs and folders to a TV, is open for us.

---

## 3. What users find easy, and what drives them away

### 3.1 What people find easy (most important first)

1. **Sending from the share sheet in two taps.** "Tap Share, Quick Share, My Computer and done" [43]. LocalSend: "you'll find LocalSend as an option in the sharing menu. You don't need to open the app" [34]. A Warpinator review describes the same thing [59]. A LocalSend reviewer asks for a Quick Settings tile next [19].
2. **Devices that are simply there, and stay remembered.** "I can send files directly to her without locating her device each time" [35]. PairDrop's paired devices persist across sessions [53].
3. **No prompt between your own devices.** Quick Share auto-accepts on the same account [38]. AirDrop and Quick Share on Windows do the same for trusted senders [43].
4. **No setup and no account.** "This app is easy to setup and use (stupidproof)" [19]. "Setting it up is as simple as installing an app" [35].
5. **Seeing progress and a clear "done".** Users ask for status in the main screen [54], a notification when files arrive [29][30], and history they can open [28].

### 3.2 What drives people away (most important first)

1. **Ads, worst of all full-screen or during a transfer.** Xender [13], SHAREit [6], ShareMe [72]. Send Anywhere showed an app ad where a code should be [16].
2. **The other device does not appear.** LocalSend on Windows [31][32][33], Quick Share [35][43], Warpinator [59], KDE Connect [54]. Common causes: Windows firewall, a network marked Public, guest Wi-Fi that blocks devices from seeing each other.
3. **Failed or restarted big sends.** Send Anywhere [16]. LocalSend has no resume and struggles with many files (issues #1191 and #2268, listed in `docs/PLAN.md`).
4. **Folder structure lost or folders not supported.** Send files to TV [68], Zapya after an update [2].
5. **Extra installs, extra steps, odd permissions.** Xender's PC app [13], Warpinator's setup [59]. Older Android versions required Location permission for hotspot apps, which looks suspicious [76].

### 3.3 What makes people trust the device they send to

- **A name and an icon they recognise.** AirDrop shows people from your contacts [46]. Quick Share separates "your devices" from "other nearby devices" [39].
- **Pairing once, then no more questions.** PairDrop pairs with a code or QR and remembers [53]. Quick Share trusts devices on your account [38].
- **The receiver controls who can reach them**, with a mode that switches itself off (AirDrop and Quick Share "Everyone for 10 minutes") [39][46].
- **A check that cannot be faked.** A short code alone is not enough: KDE's 8-character code could be brute-forced [57]. Our short ID (8 hex digits, for example 1520-296C) has the same weakness if people treat it as proof. Use QR pairing, which pins the full 64-digit ID, as the real check (already planned in `docs/PLAN.md` B.4).
- **Approval enforced by the receiver, not just shown.** Quick Share's worst bug let files arrive without approval [40][41]. Our protocol only issues upload tokens after Accept, which is the right design. Keep it that way, and keep testing it.

---

## 4. Recommended phone app UI

### 4.1 What is wrong with today's screen (from s2.png, s4.png, s6.png and HomeScreen.kt)

- The top third of the screen is the "this device" card: name, ID, `192.168.1.241:47820` in large type, and the folder path. Most people never need any of this.
- "Conformance mode" (debug only) sits on the main list.
- Device rows show `windows` in lower case, the ID and the IP. They show no icon and nothing that says whether you know this device.
- Sending starts device-first: tap the device, then a menu (Files / Photos and videos / Text). There is no Folder option, even though folders are a key feature.
- The receive switch also controls discovery. The empty state says "Turn on receiving to look for other devices", which mixes up two things.
- The offer dialog (s4) has Accept/Decline, and the card behind it shows a second pair. The sender line reads "From 192.168.1.232, ID BBBB-BBBB (not verified)", which sounds like an error but is the normal case today. There is no countdown, although the offer expires after 120 seconds.
- The finished card (s6) offers only "Copy text" and "Dismiss". There is no way to open the files or show them.
- There is no history once a card is dismissed.

### 4.2 Main screen layout

Use a bottom navigation bar with three tabs: **Send**, **Activity**, **Settings**. Material 3 says a navigation bar is for three to five top-level destinations [81]. Do **not** add a separate Receive tab: LocalSend's own users call theirs "essentially useless" [26]. Show receiving as a status chip in the top bar instead.

```
+--------------------------------------------+
| LAN Share                 (o) Receiving  > |  <- chip; tap opens the Receiving sheet
+--------------------------------------------+
| [ Sending 3 files to Khan                ] |  <- only while something is running
| [ ########----  64%  12 MB/s  8 s  Cancel ] |
|                                            |
| Send                                       |
| [ Files ] [ Photos ] [ Folder ] [ Text ]   |  <- big buttons, icon + label
|                                            |
| Nearby                                     |
| +----------------------------------------+ |
| | [PC]  Khan                     Paired  | |
| |       Windows PC                       | |
| +----------------------------------------+ |
| +----------------------------------------+ |
| | [TV]  Living room TV            New    | |
| |       Android TV                       | |
| +----------------------------------------+ |
| Not showing up?                            |
+--------------------------------------------+
|   Send        Activity        Settings     |
+--------------------------------------------+
```

- Device rows: an icon by type, the name, a readable type ("Windows PC", "Android phone", "Android TV"), and a badge: **Paired** (after QR pairing, protocol v2) or **New**. Remove the IP and ID from the row; keep them in a long-press "Details" sheet.
- Keep looking for devices the whole time the app is open, whether or not receiving is on. Replace the Refresh button with pull-to-refresh.
- "Not showing up?" opens a short help sheet:
  1. Check that both devices are on the same Wi-Fi (guest networks often block this).
  2. On the PC, open LAN Share and allow it through the firewall.
  3. **Scan the PC's QR code** (main fix).
  4. Add by IP address (fallback).

### 4.3 Send flow

Support three ways in. Make the first one the main one.

1. **Content first (main).** Tap Files, Photos, Folder or Text. The system picker opens. A bottom sheet then shows "Send 3 files (12 MB) to..." with the device list. Tap a device and sending starts. This matches AirDrop, Quick Share and LocalSend [34][36][38].
2. **Device first (keep).** Tap a device. A sheet opens: "Send to Khan: Files / Photos and videos / Folder / Text / Paste clipboard".
3. **From another app's share sheet (already built).** Keep the "Pick a device" banner. Add Android **Sharing Shortcuts** so the two most recent devices appear directly in the system share sheet, for example "Khan (LAN Share)", which saves one more tap [77]. Add a Quick Settings tile later [19].

While sending, show progress on the device row itself as well as in the top card, so the person sees where it is going. (AirDrop and Quick Share show a ring around the target; I observed this pattern but have no source to cite here.)

### 4.4 Receive flow

- **Notification** with Accept and Decline (already built). Keep the high-importance channel.
- **In-app sheet** (replaces today's dialog plus duplicate card buttons):

```
+--------------------------------------------+
| Khan wants to send you                     |
| [PC] Khan  -  Windows PC  -  Paired        |
|                                            |
| 4 files, 50.3 MB                           |
|   [img] photo name (1).jpg                 |
|         big.bin                            |
|         empty.txt                          |
|   and 1 more                (See all)      |
|                                            |
| "hello from the PC test"                   |
|                                            |
| Answer within 1:52                         |
| [ Decline ]                   [ Accept ]   |
+--------------------------------------------+
```

- For a device that is not paired, replace "(not verified)" with one calm line: **"New device. ID 194E-0972."** Put an info icon next to it that explains: "Anyone on this Wi-Fi can pick a name. To be sure it is really Khan's PC, pair by scanning its QR code." After protocol v2 adds device proof, show **Paired** with a check mark instead.
- Show the same countdown the Windows app shows ("Answer within 1:52").
- While the sheet is open, the matching Activity row just says "Waiting for your answer", with no second pair of buttons.
- **When done:** the notification and the Activity row say "Received 4 files from Khan" with **Open**, **Show files** and **Share** buttons. For text: **Copy**, plus **Open link** if the text is a single URL.

### 4.5 Activity (history)

```
+--------------------------------------------+
| Activity                    [All|In|Out]   |
| Today                                      |
|  v  From Khan    4 files, 50.3 MB   Saved  |
|  ^  To Khan      "Gate B12..."      Sent   |
| Yesterday                                  |
|  v  From Pixel   12 photos          Saved  |
+--------------------------------------------+
```

- Store it in Room (already in the planned `core-store` module).
- Tap a row to see the file list. Each file opens on tap. Buttons: Show in folder, Share, Remove from list (the files stay).
- A chat-style view per device (Feem does this [64]) is an option later. A single timeline is simpler to build and to scan.

### 4.6 Receiving sheet and Settings

Tapping the status chip opens the **Receiving sheet**:

```
Receiving
 ( ) Off
 (*) Ask me every time                <- default today
 ( ) Paired devices only              <- after protocol v2
This phone: OnePlus 8T          [Rename]
[ QR code ]  Scan this from LAN Share on another device
Address 192.168.1.241:47820  -  ID 1520-296C
Saved to Download/KhanLabs      [Change]
```

**Settings tab:**
- This device: name, full ID (with Copy), addresses.
- Receiving: mode, save folder, "Keep receiving in the background" (while the app is open / for 10 minutes / always), battery help per phone maker (`docs/PLAN.md` C.9).
- Paired devices: rename, forget, "accept without asking" for each (after v2).
- Privacy: the statement from section 7, plus "What this app can access": each permission and whether it is on.
- About: version, open-source licences, website.
- Developer (debug builds only): Conformance mode.

### 4.7 First run

Keep it to one screen. NN/g: "Avoid creating app onboarding whenever possible" [82]. Ask for each permission when it is first needed, with a reason, and keep the app usable if the person says no [78].

```
+--------------------------------------------+
| KhanLabs LAN Share                         |
| Send files to your own devices on this     |
| Wi-Fi.                                     |
| No account. No ads. Nothing leaves your    |
| network.                                   |
|                                            |
| Name this phone                            |
| [ OnePlus 8T                          ]    |
| Other devices on this Wi-Fi see this name. |
|                       [ Continue ]         |
+--------------------------------------------+
```

When to ask for each permission:
- **Local network (Android 17 and later):** right after Continue, because nothing works without it. One line first: "Android will ask if LAN Share can find devices on your network. This is how your PC shows up." Apps that target Android 17 and do not hold `ACCESS_LOCAL_NETWORK` have their local-network traffic blocked [75]. It belongs to the Nearby devices group [75].
- **Notifications (Android 13 and later):** as a card on the Send tab: "Get told when someone sends you something? [Allow] [Not now]".
- **Camera:** only when the person taps "Scan QR code".
- **Nearby Wi-Fi devices:** only for direct mode (phone to phone, no router). Declare it with `neverForLocation` so Android knows it is not used for location [76]. On Android 12 and older, Wi-Fi Direct needs Location permission; say plainly that the app does not read your location [76].
- **Battery optimisation:** only when the person turns on "Keep receiving in the background".

After first run, land on the Send tab. If no devices are found yet, the empty state says: "Open LAN Share on your PC or TV" with a [Scan QR code] button, and "Get it for Windows at khanlabs.dev".

---

## 5. Recommended Windows app UI

### 5.1 What it has today (from `lan_ftp.py`)

- Brand bar, then five tabs: Devices, Share a folder, Notepad, History, Settings.
- The Devices tab has a table of devices (Name, Type, Address, ID), the hint "Double-click a device to send it files", three send buttons, a Transfers table and a firewall warning banner with a Fix button.
- The incoming offer is a topmost window with a countdown. It does not take keyboard focus, and Esc declines. That is good and careful; keep it.
- Tray notifications for offers and finished sends.
- No drag-and-drop, and no Explorer menu entry.

`docs/PLAN.md` A.10 says a full UI rewrite is not justified, and nothing here changes that. Every item below can be built in Tkinter.

### 5.2 Main window

Rename "Devices" to **Send**. Move the shared notepad into the **Share a folder** tab, because it is shown on the shared web page. That leaves four tabs: Send, Share a folder, History, Settings.

```
+------------------------------------------------------------------------------+
| KhanLabs.dev | LAN Share       (o) Receiving: Ask every time v    Khan  194E  |
+------------------------------------------------------------------------------+
| Send | Share a folder | History | Settings                                   |
+------------------------------------------------------------------------------+
| Nearby                                       [Show my QR code] [Add by IP...] |
| +----------------+  +----------------+  +----------------+                   |
| | [Phone]        |  | [TV]           |  | [PC]           |                   |
| | OnePlus 8T     |  | Living room TV |  | Office PC      |                   |
| | Android phone  |  | Android TV     |  | Windows PC     |                   |
| | Paired         |  | New            |  | New            |                   |
| +----------------+  +----------------+  +----------------+                   |
| Not showing up?                                                              |
| +--------------------------------------------------------------------------+ |
| |        Drop files or folders here to send to OnePlus 8T                  | |
| |        or  [Send files...]  [Send a folder...]  [Send text...]           | |
| +--------------------------------------------------------------------------+ |
| Transfers                                                 [Clear finished]   |
|  ^ To OnePlus 8T   video.mp4   ######---  71%  38 MB/s  12 s     [Cancel]    |
|  v From Pixel 9    3 photos    Saved               [Open] [Show in folder]   |
+------------------------------------------------------------------------------+
```

- **Device cards instead of a table.** Show an icon, the name, the type and a Paired/New badge. Mark the selected card with a teal border. Show the address and ID on hover or in a right-click "Details".
- **Drag and drop.** Dropping on a card sends to that device. Dropping on the big area sends to the selected device. Plain Tkinter cannot take drops from Explorer; `tkinterdnd2` adds this and ships a PyInstaller hook [84].
- **Explorer "Send to" entry (opt-in in Settings).** Place a shortcut in the user's `SendTo` folder that starts the exe with the chosen paths, which opens a small "Send to which device?" window. This gives a files-first flow from Explorer. A Windows 11 Share-sheet entry needs package identity (an MSIX or a "package with external location") [83], so leave that for later.
- **Receiving chip** in the brand bar, with the same modes as the phone. Its menu also offers "Show QR code".

### 5.3 Send flow

- Device first: select a card, then drop or click a send button (today's flow, with drop added).
- Files first: Explorer, then "Send to", then "KhanLabs LAN Share", then pick the device.
- Text: "Send text..." (exists). Add "Send clipboard" to the tray menu.

### 5.4 Receive flow

- Keep the topmost offer window with its countdown and no focus grab.
- Add the device type and the Paired/New badge to the window. Replace "says ID ... (not verified)" with "New device. ID 194E-0972" and the same info text as on the phone.
- Add a checkbox, "Open the folder when it's done".
- When done, the tray notification says "Received 3 files from OnePlus 8T". Clicking it should open the folder. Whether `pystray` notifications can react to clicks reliably is unverified; test it.

### 5.5 History

The History tab already has Open, Show in folder and Copy message. Two changes:
- Finished transfers in the Send tab move to History on their own after about a minute, so the Send tab only shows what is running.
- Add the same In/Out filter as the phone.

### 5.6 Settings and first run

- **First-run window (once):** device name; a firewall step with one button, "Allow on private networks", which says Windows will ask (the code for this exists in `winsys.py`); and a "Start with Windows" checkbox.
- **Public network check:** if Windows marks the current network as Public, say so on the Send tab: "Windows treats this network as public, so other devices may not see this PC." Offer [Open network settings] and [Allow anyway]. This is the most common LocalSend failure on Windows [31][32][33].
- **Tray menu:** Send files..., Show QR code, Receiving on/off, Open received files, Quit.
- **Code signing:** until the exe is signed, SmartScreen warnings will scare first-time users more than any UI issue (`docs/PLAN.md` F.5).

---

## 6. TV app notes

- **Home is the receive screen:** a big "Ready to receive" panel with the TV's name and a large QR code, then a Received list with Open, Install (for APKs) and Delete. This matches `docs/PLAN.md` C.10.
- **Remote only.** Every control needs a clear focus state, and focus must never get lost. Avoid text entry. Pair by scanning the TV's QR code with the phone [79]. LocalSend still cannot be used with a remote [27], so this is a real gap to fill.
- **Safe margins:** keep content 48 dp from the left and right edges and 27 dp from the top and bottom, because some TVs crop the picture [80].
- **Prompts are easy to miss on a TV.** Paired devices should be able to skip Accept. For a new device, start focus on Decline, so a stray OK press does not accept. This is a judgment call that mirrors the Windows window's no-focus-grab rule.
- **The TV is shared by the household** [79]. Do not show photo thumbnails on the home screen by default.
- **APKs and folders:** sending APKs to a TV and keeping folders are the gaps users report in "Send files to TV" [68]. Make both work on day one.

---

## 7. The privacy promise

### 7.1 Is it true today?

Mostly yes. I checked both repos:
- No analytics, ad, crash-reporting or Google Play Services libraries in the Android build files.
- No outside web addresses are called by either app. The only one is the khanlabs.dev link in the Windows footer, which opens only when clicked.
- Android backup is off (`allowBackup="false"`), so the device key and settings are not copied to a cloud backup.

Three details the wording must respect:
1. **"Encrypted" holds between LAN Share apps.** On Windows, "Share a folder" can use plain FTP if the user turns it on, and the web page uses a self-signed certificate.
2. **The device name and ID are visible to the network.** While the app is announcing itself, every device on the same Wi-Fi can see its name and ID. Say so.
3. **Android lists `INTERNET` as "have full network access".** Android has no separate "local network only" permission name for this, so the store text should explain it.

**Play Data safety.** Google Play counts data as "collected" when it is sent off the device, but makes an exception for data that is end-to-end encrypted, "unreadable by you or anyone other than the sender and recipient" [85]. Our transfers go device to device over TLS pinned to the other device's ID. So "No data collected" and "No data shared" is an honest declaration, the same as LocalSend, KDE Connect and Warpinator declare [20][55][60].

### 7.2 Tagline options (recommendation first)

1. **"Your files stay on your Wi-Fi. No ads, no tracking, no account."**
2. "No ads. No tracking. No account."
3. "Device to device. Nothing to us."
4. "We collect nothing. Not even crash reports."

### 7.3 In-app statement (Settings, Privacy)

> **Your privacy**
> LAN Share collects no data. It has no ads, no analytics, no crash reports and no account.
> The app only talks to devices on your local network. Files and text go straight from one device to the other over an encrypted connection. They never pass through a server, ours or anyone else's. KhanLabs never sees what you send.
> While the app is open, other devices on the same Wi-Fi can see this device's name.
> Your phone or PC maker, and the operating system, may collect data of their own. That is outside this app's control.

Short line for the first-run screen: **"No account. No ads. Nothing leaves your network."**

### 7.4 Store-page paragraph

> **Private by design**
> KhanLabs LAN Share has no ads, no analytics, no tracking and no account. We do not collect any data, so there is nothing for us to sell, share or lose. Files and text go directly from your device to the other device on your Wi-Fi, encrypted, and never through the internet or a server. The app contains no advertising, analytics or crash-reporting code. [You can check this in the Exodus Privacy report: link.]
>
> **Why the app asks for permissions**
> - Network access: Android calls this "full network access". The app uses it only to reach devices on your local network.
> - Find devices on your local network (Android 17 and later): so your other devices show up.
> - Notifications: to tell you when someone wants to send you something, and when a transfer is done.
> - Camera (optional): only to scan a pairing QR code. No photos are taken or saved.
> - Nearby Wi-Fi devices (optional): to connect two phones without a router. Not used to find your location.

KDE Connect's store text is a good model of tone: "KDE Connect never sends any information to KDE nor to any third party" [54]. Signal's legal page opens in the same plain way [88].

### 7.5 What we must never add

1. **Ad libraries of any kind** (AdMob, AppLovin, Pangle and similar).
2. **Analytics or install attribution** (Firebase Analytics, AppsFlyer, Adjust), even if "anonymous".
3. **Crash reporters that upload** (Crashlytics, Sentry, Bugsnag, or ACRA set to send over the network). F-Droid counts "sending crash reports without your knowledge or permission" as tracking [87]. Instead, write a local log and add a "Copy error report" button that the person can send themselves. The Windows app already writes `error.log` locally.
4. **Automatic update checks, remote config, feature flags or licence checks.** Each one is a request to the internet that reveals an IP address and app version. Use store updates, or a "Check for updates" button that opens the website in the browser.
5. **Google Play Services, Firebase or ML Kit.** These also break Fire TV and devices without Google. Use ZXing for QR codes, as `docs/PLAN.md` C.11 says.
6. **Web fonts, images or scripts loaded from outside** in the Windows shared web page. Keep everything inline, as it is now.
7. **Internet relay or cloud sending, or accounts.** If these are ever added, make them a separate, clearly opt-in feature, and rewrite the promise before release.

Also:
- The website khanlabs.dev is not "the app". If it uses analytics, say so separately, or keep it clean too.
- Say what happens if the company is sold. Snapdrop shows why people ask [50][52]. Open-sourcing the apps (`docs/PLAN.md` F.2) is the strongest answer: anyone can keep the old version going.

### 7.6 Keeping it provable

- Get an Exodus Privacy report for the APK and link it ("0 trackers") [89].
- Add a CI check that fails the build if the APK contains known tracker packages (`com.google.firebase`, `com.google.android.gms.ads` and similar) or the exe imports known telemetry packages.
- Optional, strong: make the network code refuse any address that is not private (10/8, 172.16/12, 192.168/16, 169.254/16, and the IPv6 local ranges). Then the app cannot reach the internet even by mistake, and the promise becomes "This app can only connect to devices on private networks." Decide first whether VPN ranges such as 100.64/10 should be allowed.
- If the apps become open source, list them on F-Droid or IzzyOnDroid. Much of LocalSend's and KDE Connect's trust comes from this (`docs/PLAN.md` F.2).

### 7.7 Pointing to competitors fairly

- **Use their own published facts, with dates and links:** their Play Data safety wording, Exodus tracker counts with report dates, CVE numbers, government decisions. Example: "Some of the most-installed sharing apps include 5 to 22 advertising and tracking libraries (Exodus Privacy, checked October 2026). LAN Share includes none."
- **Keep competitor names out of the Play listing.** Play's metadata rules discourage deceptive claims, ranking claims and unattributed testimonials [86]. Put a dated comparison table on khanlabs.dev instead, and recheck it every 6 months.
- **Give credit where it is due.** LocalSend, KDE Connect and Warpinator also have zero trackers. Do not claim to be the only private option. Say what is different: Android TV with a remote, resume, keeping folders, sharing a folder to any browser.
- **Avoid loaded words** like "spyware" or "Chinese apps". State facts such as "banned in India in June 2020" only with the source [12].
- **Do not compare speed** until our benchmarks are published (`docs/PLAN.md` testing strategy).

---

## 8. Top 10 changes, ranked by value for effort

Estimates are for one developer who knows the code, in working days. "v2" means the item needs protocol v2 features (device proof, pairing).

| # | Change | App | Time |
|---|---|---|---|
| 1 | Clean the phone home screen: status chip plus Receiving sheet, IP/ID/folder moved off the main list, Conformance mode moved to Settings (debug only), device icons and readable types, discovery always on while open | Phone | 1 day |
| 2 | Fix the offer sheet: one Accept/Decline, countdown, "New device" wording instead of "(not verified)", photo thumbnails; Open/Show/Share on finished transfers | Phone | 1 to 1.5 days |
| 3 | Privacy text in the app and store listing, Play Data safety form, Exodus report, CI tracker check | Both | 1 day |
| 4 | First-run screen and permissions asked in context, including Android 17 `ACCESS_LOCAL_NETWORK` | Phone | 1 to 1.5 days |
| 5 | Content-first Send buttons (Files, Photos, Folder, Text) and the device sheet; Folder needs a tree picker and a folder walk | Phone | 2 days |
| 6 | "Not showing up?" helper on both apps, plus Windows Public-network detection | Both | 1.5 days |
| 7 | Show a QR code on PC and phone (address, port, full ID) and scan it on the phone with CameraX and ZXing. This also covers v1 "connect"; v2 pairing reuses it | Both | 3 to 4 days |
| 8 | Activity tab on the phone, saved in Room, with open/share/show and an In/Out filter | Phone | 2 to 3 days |
| 9 | Windows drag-and-drop (`tkinterdnd2`, including the PyInstaller build) and an opt-in Explorer "Send to" entry | Windows | 2 days |
| 10 | Recent devices in the Android share sheet (Sharing Shortcuts) and a Quick Settings tile for receiving | Phone | 1.5 to 2 days |

Total: about 16 to 20 working days.

After protocol v2 lands, next in line: Paired/New badges backed by device proof, the "Paired devices only" receive mode, per-device "accept without asking", and the TV home screen.

---

## 9. Sources

Competitors
1. https://en.wikipedia.org/wiki/Zapya
2. https://play.google.com/store/apps/details?id=com.dewmobile.kuaiya.play&hl=en_US
3. https://play.google.com/store/apps/datasafety?id=com.dewmobile.kuaiya.play&hl=en_US
4. https://www.izapya.com/policy_en.html
5. https://reports.exodus-privacy.eu.org/en/reports/com.dewmobile.kuaiya.play/latest/
6. https://play.google.com/store/apps/details?id=com.lenovo.anyshare.gps&hl=en_US
7. https://play.google.com/store/apps/datasafety?id=com.lenovo.anyshare.gps&hl=en_US
8. https://reports.exodus-privacy.eu.org/en/reports/com.lenovo.anyshare.gps/latest/
9. https://www.trendmicro.com/en_us/research/21/b/shareit-flaw-could-lead-to-remote-code-execution.html
10. https://www.bleepingcomputer.com/news/security/shareit-fixes-security-bugs-in-app-with-1-billion-downloads/
11. https://seclists.org/bugtraq/2019/Feb/45
12. https://inc42.com/features/a-deep-dive-into-the-59-banned-chinese-apps-in-india/
13. https://play.google.com/store/apps/details?id=cn.xender&hl=en_US
14. https://play.google.com/store/apps/datasafety?id=cn.xender&hl=en_US
15. https://reports.exodus-privacy.eu.org/en/reports/cn.xender/latest/
16. https://play.google.com/store/apps/details?id=com.estmob.android.sendanywhere&hl=en_US
17. https://play.google.com/store/apps/datasafety?id=com.estmob.android.sendanywhere&hl=en_US
18. https://reports.exodus-privacy.eu.org/en/reports/com.estmob.android.sendanywhere/latest/
19. https://play.google.com/store/apps/details?id=org.localsend.localsend_app&hl=en_US
20. https://play.google.com/store/apps/datasafety?id=org.localsend.localsend_app&hl=en_US
21. https://reports.exodus-privacy.eu.org/en/reports/org.localsend.localsend_app/latest/
22. https://localsend.org/privacy
23. https://github.com/localsend/localsend/security/advisories/GHSA-424h-5f6m-x63f
24. https://nvd.nist.gov/vuln/detail/CVE-2025-27142
25. https://www.sentinelone.com/vulnerability-database/cve-2026-25154/
26. https://github.com/localsend/localsend/issues/300
27. https://github.com/localsend/localsend/issues/1264
28. https://github.com/localsend/localsend/issues/807
29. https://github.com/localsend/localsend/issues/248
30. https://github.com/localsend/localsend/issues/2352
31. https://github.com/localsend/localsend/issues/149
32. https://github.com/localsend/localsend/issues/2205
33. https://github.com/localsend/localsend/issues/1421
34. https://www.androidpolice.com/localsend-airdrop-quick-share-replacement-app/
35. https://www.androidauthority.com/quick-share-vs-localsend-3564831/
36. https://www.linuxuprising.com/2023/03/send-files-securely-over-local-network.html
37. https://en.wikipedia.org/wiki/Quick_Share
38. https://support.google.com/android/answer/9286773?hl=en
39. https://9to5google.com/2024/04/09/quick-share-how-to-modify-settings/
40. https://www.safebreach.com/blog/rce-attack-chain-on-quick-share/
41. https://www.safebreach.com/blog/an-update-on-quickshell-sharing-is-caring-about-an-rce-attack-chain-on-quick-share/
42. https://www.securityweek.com/several-vulnerabilities-found-in-googles-quick-share-data-transfer-utility/
43. https://www.makeuseof.com/quick-share-on-windows-is-finally-good-and-i-use-it-every-day/
44. https://www.macrumors.com/2026/02/11/airdrop-quick-share-interoperability-more-phones/
45. https://9to5google.com/2026/06/03/android-airdrop-list-of-supported-devices/
46. https://support.apple.com/en-us/119857
47. https://privatedrop.github.io/
48. https://www.macrumors.com/2024/01/09/airdrop-cracked-chinese-authorities/
49. https://www.engadget.com/apple-china-airdrop-limit-everyone-ios-16-2-223231192.html
50. https://github.com/SnapDrop/snapdrop/issues/663
51. https://x.com/limewire/status/1925211679211565223 (date taken from the post ID; the page itself was not opened)
52. https://www.johannsetzer.com/articles/snapdrop-is-gone-what-happened-and-the-best-alternatives
53. https://github.com/schlagmichdoch/pairdrop
54. https://play.google.com/store/apps/details?id=org.kde.kdeconnect_tp&hl=en_US
55. https://play.google.com/store/apps/datasafety?id=org.kde.kdeconnect_tp&hl=en_US
56. https://reports.exodus-privacy.eu.org/en/reports/org.kde.kdeconnect_tp/latest/
57. https://kde.org/info/security/advisory-20250418-3.txt
58. https://kde.org/info/security/advisory-20250418-2.txt
59. https://play.google.com/store/apps/details?id=slowscript.warpinator&hl=en_US
60. https://play.google.com/store/apps/datasafety?id=slowscript.warpinator&hl=en_US
61. https://reports.exodus-privacy.eu.org/en/reports/slowscript.warpinator/latest/
62. https://github.com/linuxmint/warpinator
63. https://github.com/quentinmenner/winpinator
64. https://play.google.com/store/apps/details?id=com.feeperfect.airsend.android&hl=en_US
65. https://play.google.com/store/apps/datasafety?id=com.feeperfect.airsend.android&hl=en_US
66. https://reports.exodus-privacy.eu.org/en/reports/com.feeperfect.airsend.android/latest/
67. https://feem.io/
68. https://play.google.com/store/apps/details?id=com.yablio.sendfilestotv&hl=en_US
69. https://play.google.com/store/apps/datasafety?id=com.yablio.sendfilestotv&hl=en_US
70. https://reports.exodus-privacy.eu.org/en/reports/com.yablio.sendfilestotv/latest/
71. https://sendfilestotv.app/
72. https://play.google.com/store/apps/details?id=com.xiaomi.midrop&hl=en_US
73. https://play.google.com/store/apps/datasafety?id=com.xiaomi.midrop&hl=en_US
74. https://reports.exodus-privacy.eu.org/en/reports/com.xiaomi.midrop/latest/

Platform and design guidance
75. https://developer.android.com/about/versions/17/behavior-changes-17
76. https://developer.android.com/develop/connectivity/wifi/wifi-permissions
77. https://developer.android.com/training/sharing/direct-share-targets
78. https://developer.android.com/training/permissions/requesting
79. https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv
80. https://developer.android.com/design/ui/tv/guides/styles/layouts (margin values taken from a search summary of this page)
81. https://m3.material.io/components/navigation-bar/guidelines (three-to-five rule taken from a search summary; the page did not render for me)
82. https://www.nngroup.com/articles/mobile-app-onboarding/
83. https://learn.microsoft.com/en-us/windows/apps/develop/windows-integration/integrate-sharesheet-unpackaged
84. https://github.com/pmgagne/tkinterdnd2

Privacy wording and policy
85. https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
86. https://support.google.com/googleplay/android-developer/answer/9898842?hl=en
87. https://f-droid.org/en/docs/Anti-Features/
88. https://signal.org/legal/
89. https://reports.exodus-privacy.eu.org/

Not opened, cited through `docs/PLAN.md`: https://github.com/localsend/localsend/issues/1191 and https://github.com/localsend/localsend/issues/2268.

Local files read: `D:\Projects\khanlabs-lanshare\README.md`, `PROTOCOL.md`, `lan_ftp.py`; `D:\Projects\khanlabs-lanshare-android\docs\PLAN.md`, `PROGRESS.md`, `ui-mobile\...\HomeScreen.kt`, `transfer\...\Notifications.kt`, both `AndroidManifest.xml` files; screenshots `s2.png`, `s4.png`, `s6.png`.

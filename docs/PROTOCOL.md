> **Copy.** The master copy of this file is `PROTOCOL.md` in [KhanLabs/khanlabs-lanshare](https://github.com/KhanLabs/khanlabs-lanshare). If they differ, that one is right. Update both in the same change.

# KhanLabs Send protocol, version 1

This is how Amanah devices (called KhanLabs LAN Share before 2026-10-06) find each other on a local network and send files and text. The Windows app implements it in `transfer.py`. A phone app (Android or iOS) that follows this document can send to and receive from the Windows app, and from other phones.

The design is simple on purpose: DNS-SD to find devices, then JSON over HTTPS. Every send asks the receiving person to accept it first.

## 1. Device identity

Each device makes one self-signed TLS certificate the first time it runs and keeps it. The Windows app uses ECDSA P-256 and makes it valid for 10 years. Any key type that TLS 1.2 or 1.3 supports is fine.

- **Device ID** = SHA-256 of the certificate in DER form, written as 64 upper-case hex digits.
- **Short ID** = the first 8 hex digits, written `XXXX-XXXX` (for example `5953-B8C1`). People can read it aloud to compare.

There is no certificate authority. Clients skip the normal certificate checks and instead **pin the device ID**: after the TLS handshake, hash the server's certificate and compare it with the ID you expect. If it doesn't match, close the connection and tell the user that the device's identity has changed.

You learn the expected ID from discovery (section 2) or from `GET /info` the first time you add a device by IP. After that, store it with the device and check it on every connection.

## 2. Discovery (mDNS / DNS-SD)

Each device announces itself on the local network with DNS-SD:

| Field | Value |
|---|---|
| Service type | `_klshare._tcp.local.` |
| Instance name | `<device name> <first 6 hex digits of the ID>`, for example `Office PC 5953B8` |
| Port | The HTTPS port of the API (default 47820, see below) |
| Addresses | The device's IPv4 LAN addresses |

TXT record:

| Key | Meaning |
|---|---|
| `id` | Device ID, 64 hex digits |
| `name` | Device name to show, up to 63 characters |
| `model` | `windows`, `android`, `ios`, `macos` or `linux` |
| `v` | Protocol version, `1` |
| `app` | App version, for example `1.2.0` |
| `share` | The address of the device's shared folder web page when it's sharing one (for example `https://192.168.1.5:8080`), otherwise empty |

Notes:
- Ignore records whose `id` is your own, or isn't 64 hex digits.
- When the name or `share` changes, update the TXT record (or re-register if the instance name changes).
- On Android use `NsdManager` (or a multicast library such as JmDNS). Android needs a `WifiManager.MulticastLock` while browsing.
- On iOS use `NWBrowser` with `.bonjour(type: "_klshare._tcp", domain: nil)`. Add `_klshare._tcp` to `NSBonjourServices` and write an `NSLocalNetworkUsageDescription` in Info.plist.
- Some networks block multicast (guest Wi-Fi, some mesh routers). Always offer "Add by IP address" as a fallback, using `GET /info`.

**Port:** the API listens on TCP 47820. If that port is taken, the Windows app tries 47821 to 47829 and announces whichever one worked. Phones should do the same. For "Add by IP", try 47820 first.

## 3. HTTPS API

Base path: `/api/kl/v1`. Every request is HTTPS (TLS 1.2 or newer) to the receiving device. Plain HTTP on the same port gets a `301` to `https://`.

Request and response bodies are JSON (`Content-Type: application/json`), except uploads. Errors look like this:

```json
{"error": "declined", "message": "The offer was declined"}
```

`error` is a fixed code for your code to check. `message` is plain English for people. Send `Host` as the IP address you connected to; other host names are refused with `421`.

The Windows app answers with `HTTP/1.0` and closes the connection after each response, so open a new connection (and check the pin again) for every request.

### 3.1 `GET /info`

Who is this device?

```json
{"protocol": 1, "id": "5953B8C1…", "name": "Office PC", "model": "windows",
 "app": "KhanLabs LAN Share 1.2.0", "receiving": true}
```

`id` must equal the hash of the certificate the connection used. If it doesn't, don't trust the device. `receiving` is false when the person turned receiving off.

### 3.2 `POST /offer`

Ask to send files and/or text. **The request stays open until the person answers**, for up to 120 seconds, so use a read timeout of at least 135 seconds.

```json
{
  "offer": "q3V9xY2kL0aB7cD4eF6gH8",
  "sender": {"id": "21E16B02…", "name": "Mueed's Pixel", "model": "android", "app": "KhanLabs LAN Share Android 1.0"},
  "files": [
    {"id": "0", "name": "Holiday photos/IMG_001.jpg", "size": 2000000},
    {"id": "1", "name": "Holiday photos/day 2/IMG_002.jpg", "size": 1800000}
  ],
  "text": "Gate B12, boarding 14:05"
}
```

| Field | Rules |
|---|---|
| `offer` | Random ID made by the sender, 16 to 64 characters from `A-Z a-z 0-9 - _`. Use at least 128 random bits. |
| `sender` | Optional, but the receiver shows `name` and the short ID in the Accept dialog. |
| `files` | Up to 10,000 entries. `id` is any string up to 64 characters, unique in this offer. `name` is a relative path with `/` between folders. No empty parts, no `.` or `..`, no leading `/`, and every part must be a valid Windows file name (none of `\ / : * ? " < > \|`, no control characters, no trailing dot or space, not a reserved name such as `CON` or `NUL`, at most 255 characters). `size` is the exact size in bytes. |
| `text` | Optional, at most 64 KB of UTF-8. |

The offer needs at least one file or some text. The whole JSON body may be up to 4 MB.

Answers:

| Status | `error` | Meaning |
|---|---|---|
| 200 | | Accepted. Body below. |
| 403 | `declined` | The person said no. |
| 403 | `cancelled` | The offer was cancelled before an answer. |
| 403 | `not_receiving` | Receiving is turned off. |
| 408 | `timeout` | Nobody answered within 120 seconds. |
| 400 | `bad_offer`, `bad_files`, `bad_text`, `empty`, `bad_json` | The request breaks a rule above. |
| 409 | `duplicate` | That offer ID was already used. |
| 429 | `busy` | Too many offers are already waiting for an answer (2 from your address, or 8 in all). Wait for the person to answer the first one, then try again. |
| 507 | `no_space` | The files wouldn't fit on the receiving disk. |

The 200 body gives one upload token per file:

```json
{"offer": "q3V9xY2kL0aB7cD4eF6gH8", "files": {"0": "Xk2…", "1": "pQ9…"}}
```

A text-only offer is complete as soon as it is accepted: the receiver shows the text, and there is nothing to upload.

### 3.3 `PUT /upload?offer=<offer>&file=<file id>&token=<token>`

Send one file's bytes as the raw body (`Content-Type: application/octet-stream`). `Content-Length` must equal the size from the offer. Upload files one after another.

| Status | `error` | Meaning |
|---|---|---|
| 201 | | Saved. Body: `{"ok": true, "name": "Holiday photos/IMG_001 (1).jpg"}` with the name it was saved as. |
| 400 | `wrong_size` | `Content-Length` doesn't match the offer. |
| 403 | `bad_token` | Unknown offer, file or token. |
| 409 | `already` | That file was already received, or is being received now. |
| 410 | `cancelled` | The send was cancelled. Stop sending. |
| 411 | `length_required` | No `Content-Length`. Chunked uploads aren't supported. |
| 507 | `no_space` | The disk filled up. |
| 500 | `failed` | Something else went wrong while saving. |

**Don't pause in the middle of a body.** Since 1.2.1 the receiver drops a connection that sends nothing for 60 seconds. The file is then marked failed and can be uploaded again from the start with the same token.

The receiver writes to a temporary file and only renames it into place when every byte has arrived, so a broken upload never leaves a half file. It never replaces an existing file: if the name is taken it saves as `name (1).ext`, `name (2).ext` and so on. Folders in the path are created as needed.

### 3.4 `POST /cancel`

```json
{"offer": "q3V9xY2kL0aB7cD4eF6gH8"}
```

Either side can stop a send. The sender calls this. The receiver cancels on its own when the person clicks Cancel, and then answers any running or later upload with `410`. Always returns `{"ok": true}`.

## 4. A complete send

1. Find the device by DNS-SD, or by IP with `GET /info`. Remember its ID.
2. Open HTTPS, check the certificate hash against the ID, then `POST /offer`.
3. Show "Waiting for <name> to accept". Let the user cancel: close the connection and `POST /cancel`.
4. On 200, `PUT /upload` each file with its token, on a new connection each time, checking the ID every time.
5. Show progress from the bytes you've written.

## 5. Receiving (for phone apps)

A phone that wants to receive runs the same API:

- Listen on TCP 47820 (or the next free port up to 47829) with TLS, using the device certificate.
- Announce it with DNS-SD as in section 2.
- On `POST /offer`, check the rules in 3.2, then ask the user with the sender's name, short ID, the file count, the total size and any text. Hold the request open until they answer or 120 seconds pass.
- Make tokens with at least 128 random bits. Compare them in constant time.
- Save uploads to a temporary file, check that the byte count matches, then move the file into place without replacing anything.
- Forget an accepted offer when nothing has been uploaded for an hour.
- Drop a connection that sends nothing for 60 seconds, so a sender that left the network can't hold a file or a thread. Let the same file be uploaded again after that.
- Limit open connections per address (the Windows app allows 16) and offers waiting for an answer (2 per address, 8 in all; answer the rest with `429 busy`). Show one prompt at a time.

iOS can only receive while the app is in the foreground. Android can keep receiving in a foreground service with a notification.

## 6. Shared folder web page (no app needed)

When a Windows device shares a folder, anyone can open it in a browser: the address is in its `share` TXT value and in the QR code in its window. A phone app can open that address in a browser view or the system browser. The page may ask for a 6-digit PIN. It uses the same certificate, so you can check the device ID there too.

## 7. Security notes

- Everything is encrypted with TLS. The device ID pin stops someone on the network from pretending to be a device you already know.
- The first time you meet a device, its ID comes from discovery or `/info`, so the first contact isn't verified. Show the short ID on both screens so people can compare it if they care.
- **The `sender.id` in an offer is not proof of anything.** Any device can put any ID there. Show it as "not verified", and never use it to skip the prompt. Protocol v2 adds a signed proof for that.
- Nothing is saved without the receiving person accepting, and accepted files never replace existing ones.
- Treat every name in an offer as hostile. Check each part as described in 3.2 before you join it to a folder path.

## 8. Versioning

`v` in TXT and `protocol` in `/info` are `1`. New optional JSON fields may be added without changing the version, so ignore fields you don't know. A breaking change will use a new base path (`/api/kl/v2`).

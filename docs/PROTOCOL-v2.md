> **Copy.** The master copy of this file is `PROTOCOL-v2.md` in [KhanLabs/khanlabs-lanshare](https://github.com/KhanLabs/khanlabs-lanshare). If they differ, that one is right. Update both in the same change.

# KhanLabs Send protocol, version 2 (draft)

**Status: draft.** Nothing implements this yet. The Windows app 1.3 and the Android app will be the first. Until then, details can change; after 1.3 ships, only compatible changes are allowed (see section 17).

Version 2 is for sending large files and many small files reliably between Windows, Android and Android TV. It keeps everything from version 1 that worked (DNS-SD, HTTPS with a pinned device ID, asking before every send) and adds:

- **Resume:** files go in chunks, and a send carries on after a broken connection or a restart of either app.
- **Checked files:** every chunk and every file has a SHA-256 digest.
- **Fast small files:** many small files go in one request (a batch).
- **Parallel connections:** 2 or more connections at once, on keep-alive.
- **Offers that don't block:** the sender asks once, then waits with a long-poll. The prompt can stay up for 10 minutes.
- **Proven sender:** the sender signs with its device key, so the receiver knows who is sending.
- **QR pairing and trusted devices:** a trusted device can be auto-accepted.
- **No file limit:** the file list can be sent in pages.

Read `PROTOCOL.md` (version 1) first: device identity, discovery and the shared folder web page work the same way and are not repeated here. Every v2 device must still serve version 1 at `/api/kl/v1`, because old copies of the Windows app will be around for years.

Files that go with this document:
- `docs/v2/schema.json`: a JSON Schema for every message. The names in the examples below (`info`, `sessionCreate` and so on) are its `$defs`.
- `docs/v2/vectors.json`: test vectors for digests, proofs, the pairing MAC and batches.
- `klv2.py`: a reference implementation of those formats in Python.
- `test_v2.py`: checks that the three files above agree with each other and with this document.

## 1. Conventions

### 1.1 HTTP

- Base path: `/api/kl/v2`. HTTPS only, TLS 1.2 or newer (1.3 where the platform has it). Keep ChaCha20-Poly1305 allowed, because cheap Android devices have no AES hardware.
- **HTTP/1.1 with keep-alive.** A client may send many requests on one connection, one after another (no pipelining). Check the device ID once per connection, after the handshake.
- **Every request and response body has a `Content-Length`.** `Transfer-Encoding: chunked` is not supported, in either direction. A request with a body and no `Content-Length` gets `411 length_required`.
- `Host` must be the IP address you connected to, as in version 1. Other names get `421`.
- A receiver drops a connection that sends nothing for **60 seconds**, between requests or in the middle of a body. A long-poll (section 6.2) never waits more than 25 seconds, so a waiting client never hits this limit.
- Request and response bodies are JSON (`Content-Type: application/json`, UTF-8), except chunks (`application/octet-stream`) and batches (`application/x-kl-batch`).

### 1.2 Errors

Every error has a JSON body:

```json error
{"error": "bad_token", "message": "Unknown session or token"}
```

`error` is a fixed code for your code to check (the full list is in section 15). `message` is plain English for people, and may change. Some errors add fields, for example `missing` (section 7.2).

### 1.3 Encodings

| What | Format |
|---|---|
| Device ID | SHA-256 of the certificate (DER), 64 upper-case hex digits. The same as version 1. |
| Session ID, token, nonce | base64url without padding, at least 128 random bits (22 to 64 characters from `A-Z a-z 0-9 - _`). Made with a secure random generator. |
| File ID | 1 to 64 characters from `A-Z a-z 0-9 - _`. Unique within a session. |
| Digests and MACs | 64 lower-case hex digits. Receivers also accept upper case. |
| `cert`, `proof` | Standard base64 with padding. |
| Times | `mtime` is milliseconds since 1970-01-01 UTC. |

Compare tokens, MACs and digests in constant time.

### 1.4 Unknown fields

Ignore JSON fields you don't know. New optional fields can be added to any message without a new version. `docs/v2/schema.json` allows them.

## 2. Discovery and choosing a version

DNS-SD works as in version 1 (service `_klshare._tcp`), with these TXT keys changed or added:

| Key | Meaning |
|---|---|
| `v` | The highest protocol version the device supports: `2`. Version 1 apps ignore this key, so they still see v2 devices. |
| `vs` | Every version it supports, comma separated: `1,2`. |
| `p2p` | `1` when the device can do Wi-Fi Direct (section 11). Leave it out otherwise. |

Keep the whole TXT record under about 200 bytes, because Wi-Fi Direct service discovery limits its size.

A v2 device also adds `"protocols": [1, 2]` to its version 1 `GET /api/kl/v1/info` reply, so "Add by IP" can tell the versions apart with one request.

**Choosing:** if the other device lists 2 (in `vs` or `protocols`), use version 2. If `GET /api/kl/v2/info` returns 404, or the device only lists 1, use version 1 and tell the user that the other device runs an older version, so a broken send will start again from the beginning.

## 3. The flow in short

1. `GET /info`: check the ID, read the limits, get a nonce.
2. `POST /sessions`: the file list (or its first page), a summary, any text, and a signed proof. You get a session token straight away.
3. `POST /sessions/{sid}/manifest`: the rest of the file list, if it didn't fit in one page.
4. `GET /sessions/{sid}?wait=25`: wait for the person to accept. Repeat until the state changes.
5. Send the wanted files: big files as chunks (`PUT .../chunks/{n}`, then `POST .../done`), small files in batches (`POST .../batch`). Use several connections.
6. Keep reading `GET /sessions/{sid}` while sending, to see if the receiver cancels.
7. If anything breaks, reconnect, read the session state, and send only what is missing.

## 4. Device proof

Version 1 trusts the `sender.id` the sender types into its offer. Version 2 makes the sender prove it holds the key of that ID.

The proof is used in `POST /sessions`, `POST /pair` and `POST /hello`. Each of those requests carries four fields:

| Field | Value |
|---|---|
| `sender` | Who is sending, as in version 1. `sender.id` must match `cert`. |
| `cert` | The sender's own device certificate (DER, base64). It must be the same certificate the sender serves on its own API, so its ID is the same in both directions. |
| `nonce` | A nonce from the receiver's `GET /info` (section 5). |
| `proof` | A signature, made as below. |

**The signed message** is six lines of ASCII, each ending with a newline (`\n`, byte 0x0A):

```
KLv2-proof
<purpose>
<receiver ID>
<sender ID>
<nonce>
<subject>
```

- `purpose` is `session`, `pair` or `hello`, for the request it is used in.
- `receiver ID` is the ID of the device you are sending to: the ID you pinned when you connected.
- `sender ID` is your own ID: SHA-256 of `cert`.
- `subject` is the session ID for `session`, your API port in decimal for `hello`, and empty for `pair` (the line is then just `\n`).

**The signature** is ECDSA with the P-256 curve and SHA-256 over those bytes, in DER form (the normal output of Java's `SHA256withECDSA`, Python's `cryptography` and Swift's `derRepresentation`), then base64. Version 2 needs a P-256 device key. The Windows app has used one since 1.2.0. On Android, make the key in the Android Keystore.

**The receiver checks, in this order:**
1. The nonce is one it issued, to this client's IP address, less than 120 seconds ago, and not used before. Otherwise `403 bad_nonce`. Mark the nonce used whatever happens next.
2. `cert` is a certificate with a P-256 public key. Otherwise `400 bad_cert`.
3. It works out the sender ID itself, as SHA-256 of `cert`. If `sender.id` is different, `403 bad_proof`.
4. It builds the message with its own ID as the receiver ID, and checks the signature with the key from `cert`. Otherwise `403 bad_proof`.

The certificate's dates, issuer and self-signature are not checked: the ID is what matters.

On `bad_nonce`, get a new nonce from `GET /info` and try once more.

Why this works: the receiver ID in the message stops a device you send to from passing your proof on to a third device. The single-use nonce stops a proof from being used twice. The purpose line stops a proof made for one request from being used for another.

Mutual TLS would do the same job, but Python's `ssl` module can't accept unknown self-signed client certificates, so the proof is in the request instead.

**This design must be reviewed by someone with cryptography experience before version 2 is released.**

Test vectors: `proofs` in `docs/v2/vectors.json`, with a test sender key. ECDSA signatures are random, so you can't reproduce the signatures there. Check that your code accepts the valid ones and refuses the others.

## 5. `GET /info`

Who is this device, what can it do, and a fresh nonce.

```json info
{
  "protocol": 2,
  "protocols": [1, 2],
  "id": "087FDCA832B0338C25D59290E2579FDE49A21BD65A4DC05425020E245F8DD98D",
  "name": "Office PC",
  "model": "windows",
  "app": "KhanLabs LAN Share 1.3.0",
  "receiving": true,
  "features": ["proof", "resume", "batch", "pair", "hello"],
  "limits": {
    "max_streams": 6,
    "chunk_size": 8388608,
    "batch_max_file": 1048576,
    "batch_max_bytes": 33554432,
    "batch_max_files": 1000,
    "manifest_page": 5000
  },
  "nonce": "iW2Cyi9ssOUouhrR-B5oZA"
}
```

| Field | Meaning |
|---|---|
| `id` | Must equal the hash of the certificate this connection used. If not, don't trust the device. |
| `model` | `windows`, `android`, `androidtv`, `ios`, `macos` or `linux`. Show a generic icon for others. |
| `receiving` | False when the person turned receiving off. Sessions then get `403 not_receiving`. |
| `features` | `proof`, `resume`, `batch`, `pair` (accepts `POST /pair`), `hello` (accepts `POST /hello`), `p2p` (Wi-Fi Direct). |
| `limits.max_streams` | How many connections a sender may use at once (for example PC 6, phone 4, TV box 2). |
| `limits.chunk_size` | The chunk size the receiver will choose for new sessions: a power of two from 1 MiB to 64 MiB. |
| `limits.batch_max_file` | Files up to this size may go in a batch. 0 means batches are off. |
| `limits.batch_max_bytes`, `batch_max_files` | The most bytes and files in one batch request. |
| `limits.manifest_page` | The most file entries in one request. At most 5000. |
| `nonce` | Single use, for 120 seconds, from this client's IP address. Each `GET /info` makes a new one. A receiver keeps at most 32 unused nonces per IP address and forgets the oldest. |

`GET /info` needs no token and no proof.

## 6. Sessions

A session is one send: a list of files and folders, and maybe some text. The sender makes it, the receiver asks its person, and then the sender uploads.

Every request below except `POST /sessions` needs the session token in a header:

```
Authorization: Bearer <token>
```

A wrong or missing token gets `403 bad_token`. Tokens never go in the URL.

### 6.1 `POST /sessions`

```json sessionCreate
{
  "session": "q3V9xY2kL0aB7cD4eF6gH8",
  "sender": {"id": "03705D25472018B381A1C98835FC818751F2397F51DE1A899FC366D204B8DF76",
             "name": "Mueed's OnePlus", "model": "android", "app": "KhanLabs LAN Share Android 1.0", "port": 47820},
  "cert": "MIIBbjCCARSgAwIBAgICA+kwCgYIKoZIzj0EAwIw",
  "nonce": "iW2Cyi9ssOUouhrR-B5oZA",
  "proof": "MEUCIQDUcrGeTDPF8iRKKJqzWB3IETsrsCRFy1eq",
  "summary": {"count": 3, "bytes": 3800000, "names": ["Holiday photos"]},
  "files": [
    {"id": "0", "name": "Holiday photos/IMG_001.jpg", "size": 2000000, "mtime": 1791200000000, "mime": "image/jpeg"},
    {"id": "1", "name": "Holiday photos/day 2/IMG_002.jpg", "size": 1800000, "mtime": 1791200100000},
    {"id": "2", "name": "Holiday photos/empty folder", "size": 0, "kind": "dir"}
  ],
  "more": false,
  "text": "Gate B12, boarding 14:05"
}
```

(`cert` and `proof` are shortened here.)

| Field | Rules |
|---|---|
| `session` | Made by the sender: 128 random bits or more (section 1.3). The sender keeps it to resume. |
| `sender`, `cert`, `nonce`, `proof` | The proof from section 4, with purpose `session` and the session ID as the subject. `sender.port` is optional: the port of the sender's own API, so the receiver can send back. |
| `summary` | `count` is the number of entries in the whole list (files and folders), `bytes` their total size, and `names` up to 5 top-level names to show in the prompt. It covers every page, not just this one. |
| `files` | The first page of the list, at most `limits.manifest_page` entries. See section 12 for names. |
| `more` | True when more pages follow (section 6.3). |
| `text` | Optional, at most 64 KB of UTF-8. |

A session needs at least one entry or some text (`400 empty`).

The reply comes straight away. It is `202` while the person hasn't answered, and `200` if the session is already accepted, for example because the sender is a trusted device the receiver auto-accepts (section 10.3). The body is the session state (section 6.2) plus the token:

```json sessionCreated
{
  "session": "q3V9xY2kL0aB7cD4eF6gH8",
  "token": "1uXxu5bsYoJH74Ht46UL1Q",
  "state": "pending",
  "version": 1,
  "chunk_size": 8388608,
  "in_order": false,
  "manifest_complete": true,
  "expires_in": 600
}
```

**Sending the same session again** (the same session ID) is how a sender resumes. It needs a new nonce and proof. When the proven sender ID and the `summary` are the same as the first time, the reply is the current state and the same token, not a new prompt. Leave out the list on a resend (send `"files": []` and `"more": false`): the receiver already has it. A different sender gets `409 duplicate`, and the same sender with a different summary gets `409 changed`.

Other errors: `403 not_receiving`, `429 busy` (too many sessions waiting for an answer: 2 from one address, 8 in all, counted together with version 1 offers), `507 no_space`, `413 too_many` (more entries than the receiver allows), and the proof errors from section 4.

### 6.2 `GET /sessions/{sid}?wait=25&after=<version>`

Reads the state of a session. This is a long-poll: when `after` is given, the reply waits until `version` is greater than `after`, or until `wait` seconds pass (at most 25), then returns the current state. Without `after` it returns straight away. The status is `200` either way.

```json sessionState
{
  "session": "q3V9xY2kL0aB7cD4eF6gH8",
  "state": "accepted",
  "version": 4,
  "chunk_size": 8388608,
  "in_order": false,
  "manifest_complete": true,
  "wanted": "all",
  "files": {
    "0": {"state": "done", "name": "Holiday photos/IMG_001 (1).jpg"},
    "1": {"state": "partial", "chunks": [[0, 12], [14, 15]]}
  }
}
```

| Field | Meaning |
|---|---|
| `state` | `pending` (waiting for the person), `accepted` (send the files), `declined`, `cancelled` (by either side), `expired` (nobody answered in 10 minutes) or `done` (every wanted file is saved). |
| `version` | Goes up by at least 1 whenever `state`, `wanted`, `manifest_complete` or a file's `state` changes. Not on every chunk. |
| `chunk_size` | Chosen by the receiver when the session is made, and fixed for the whole session. |
| `in_order` | True when the receiver can't write at random offsets (some Android storage). Then send each file's chunks in order, one at a time (section 8). |
| `manifest_complete` | True once the last page of the list has arrived. |
| `expires_in` | While pending: seconds left before it expires. |
| `wanted` | Once accepted: `"all"`, or the list of file IDs the person chose. Send only these. |
| `files` | Only files with something to report: `partial` with the chunks received (half-open ranges: `[0, 12]` means chunks 0 to 11), `done` with the name it was saved as, or `skipped` with a `reason` (`too_large` when the receiving disk can't hold a file that big, `dropped` when the sender dropped it). A wanted file that isn't listed has nothing received yet. |

A text-only session goes straight from `pending` to `done` when accepted. The receiver shows the text when the person accepts.

Errors: `403 bad_token`, `404 no_session`.

### 6.3 `POST /sessions/{sid}/manifest`

Sends the next page of the file list, when `POST /sessions` had `"more": true`. Send the pages one after another, each up to `limits.manifest_page` entries.

```json manifestPage
{
  "files": [
    {"id": "5000", "name": "Camera/IMG_5000.jpg", "size": 3100000, "mtime": 1791200200000}
  ],
  "more": false
}
```

The reply gives the number of entries received so far:

```json manifestReply
{"ok": true, "count": 5001}
```

The person may accept before the whole list has arrived. A file ID can't be used before its page has arrived (`404 no_file`), and the session can't be `done` until `manifest_complete` is true.

When the last page arrives and the totals don't match the `summary`, the receiver answers `400 summary_mismatch` and cancels the session. A page after the last gets `409 manifest_complete`. A file ID used twice, or a name that breaks section 12, gets `400 bad_files`.

### 6.4 `DELETE /sessions/{sid}`

Cancels the session. Either side can cancel: the sender with this request, the receiver when its person clicks Cancel. The other side finds out from the long-poll (`state` becomes `cancelled`) or from `410 cancelled` on its next upload. Files already saved stay. The receiver deletes the parts of unfinished files.

The reply is `{"ok": true}`, also when the session was already over.

### 6.5 Dropping or restarting one file

- **`DELETE /sessions/{sid}/files/{fid}`**: the sender gives up on one file, for example because it was deleted or its size changed. The file becomes `skipped` with reason `dropped`, and the session can still reach `done`.
- **`DELETE /sessions/{sid}/files/{fid}/chunks`**: the receiver forgets the chunks it has for that file, so the sender can send it again from the start. Use this when the file changed (same size, different modified time) since the first try.

Both reply `{"ok": true}`. A file that is already saved gets `409 finished`.

### 6.6 How long the receiver keeps a session

| State | Kept for |
|---|---|
| `pending` | 10 minutes, then `expired`. The prompt can stay up as a notification all that time. |
| `accepted` | 7 days after the last upload, so a send can resume after a restart. Then it is cancelled and its parts are deleted. |
| `declined`, `cancelled`, `expired`, `done` | 1 hour, so a sender that was away can still read the result. After that, `404 no_session`. |

## 7. Uploading

Uploads need the session in state `accepted` (otherwise `409 not_accepted`, or `410` with the state as the code once it is over: `410 cancelled`, `410 declined`, `410 expired`), and a file ID that is in the list (`404 no_file`), wanted (`409 not_wanted`) and not saved yet (`409 finished`).

**Always read the whole request body,** even when you refuse it, so the sender gets your reply and the connection stays usable. If the body is bigger than the receiver would ever accept (more than 64 MiB for a chunk, more than `batch_max_bytes` for a batch), reply and then close the connection instead.

### 7.1 Chunks and digests

A file of `size` bytes is cut into chunks of `chunk_size` bytes (from the session). Chunk `n` covers bytes `n × chunk_size` up to, but not including, `min((n + 1) × chunk_size, size)`. The last chunk may be shorter. A 0-byte file has no chunks.

Each chunk's digest is the SHA-256 of its bytes. The digest of the whole file, `sha256-tree-v1`, is:

```
SHA-256( chunk_size as 8 bytes, big-endian
         || SHA-256 of chunk 0 (32 bytes)
         || SHA-256 of chunk 1 (32 bytes)
         || ... )
```

So the sender reads each file once, chunks can arrive in any order, and a browser can compute the same thing with WebCrypto. For a 0-byte file it is the SHA-256 of just the 8 size bytes. The digest depends on the chunk size, so always use the session's.

Test vectors: `file_digests` in `docs/v2/vectors.json`. The test files are byte `i` = `i mod 251`.

SHA-256 and not BLAKE3, because SHA-256 has hardware support on x86 and ARMv8 and is built into Python, Android and browsers. The `alg` field lets a later version change it.

### 7.2 `PUT /sessions/{sid}/files/{fid}/chunks/{n}`

The body is chunk `n`'s bytes (`Content-Type: application/octet-stream`), with these headers:

```
Authorization: Bearer <token>
Content-Length: <exactly the chunk's length>
KL-Chunk-SHA256: <the chunk's SHA-256, 64 hex digits>
```

The receiver reads the chunk, checks its SHA-256, writes it at its offset in a temporary file, and records that it has it (section 9). Then it answers `201`:

```json chunkReply
{"ok": true, "file": "1", "chunk": 12}
```

Progress on both sides counts these answers, not bytes written to the socket.

| Status | `error` | Meaning |
|---|---|---|
| 201 | | Saved. |
| 200 | | The receiver already had this chunk with this digest. Sending a chunk twice is safe. |
| 400 | `bad_chunk` | `n` is not a chunk of this file. |
| 400 | `wrong_size` | `Content-Length` is not this chunk's length. |
| 400 | `bad_digest` | `KL-Chunk-SHA256` is missing or not 64 hex digits. |
| 409 | `chunk_changed` | The receiver already has this chunk with a different digest, so the file changed. Restart it (section 6.5). |
| 422 | `digest_mismatch` | The bytes don't match `KL-Chunk-SHA256`. The chunk is thrown away. Send it again. |
| 503 | `slow_down` | The receiver's disk is behind. Wait for the seconds in `Retry-After`, then send it again, with one connection fewer (section 8). |
| 507 | `no_space` | The receiving disk is full. |

### 7.3 `POST /sessions/{sid}/files/{fid}/done`

When every chunk has a `201` or `200`, finish the file with its digest:

```json doneRequest
{"alg": "sha256-tree-v1", "digest": "848a5869673cb0202878b8d6354fd9e9eb66a6c920e8b45585997a3e04998450"}
```

The receiver checks that it has every chunk, works out the tree digest from the chunk digests it checked, and compares. Then it moves the file into place without replacing anything (section 12), sets its modified time, and answers with the name it was saved as:

```json doneReply
{"ok": true, "name": "Holiday photos/day 2/IMG_002.jpg"}
```

| Status | `error` | Meaning |
|---|---|---|
| 200 | | Saved. Calling `done` again for a saved file gives the same reply. |
| 400 | `bad_digest` | `alg` isn't `sha256-tree-v1`, or `digest` isn't 64 hex digits. |
| 409 | `missing_chunks` | Some chunks haven't arrived. The reply lists them in `missing`, as ranges like the session state. |
| 422 | `digest_mismatch` | The digests don't agree: the file changed while it was being sent. The receiver forgets its chunks. Send the file again from the start, or drop it. |
| 507 | `no_space` | |
| 500 | `failed` | The file couldn't be saved for another reason. The `message` says why. |

A 0-byte file needs only `done`, with the digest of no chunks. A folder entry (`"kind": "dir"`) needs no upload: the receiver makes the empty folder by the time the session is `done`.

### 7.4 `POST /sessions/{sid}/batch`

Sends many small files in one request, so 10,000 small files don't need 20,000 requests. A file may go in a batch when it is at most `limits.batch_max_file` bytes and none of its chunks have been sent. One batch holds at most `batch_max_files` files and `batch_max_bytes` bytes in all (the whole body, headers included).

The body (`Content-Type: application/x-kl-batch`) is a series of records, each:

1. the length of the header, 4 bytes, big-endian;
2. the header, UTF-8 JSON;
3. the file's bytes, exactly `size` of them.

```json batchHeader
{"file": "0", "size": 6, "sha256": "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"}
```

A header length of 0 (4 zero bytes) ends the batch. Headers are at most 4096 bytes.

The receiver saves each file as soon as its bytes arrive and its SHA-256 matches. A batch file needs no `done`. The reply has one result per file:

```json batchReply
{"results": {"0": {"ok": true, "name": "notes/a.txt"},
             "photos-1": {"ok": false, "error": "digest_mismatch"}}}
```

A file that fails in a batch can be sent again, in a batch or as chunks. A file that was already saved gets `{"ok": true, "name": ...}` again, so sending a whole batch again after a lost reply is safe.

The whole request fails with `400 bad_batch` when the format breaks (a header that isn't JSON or is too long, a file bigger than `batch_max_file`, too many files, or a body that ends early), and with `413 too_large` when the `Content-Length` is over `batch_max_bytes`. Files saved before the break stay saved: read the session state to see which.

Why not tar or zip: their formats have too many corner cases to parse safely.

Test vector: `batch` in `docs/v2/vectors.json`.

## 8. Parallel connections and order

- Start with 2 connections. Add one while the total speed keeps rising by 10% or more, up to `limits.max_streams`. Drop one after `503 slow_down` or a connection error.
- Each connection does one request at a time and is reused (keep-alive). Check the device ID on each new connection.
- **Hand out chunks roughly in order:** within a file, never more than 8 chunks after the first missing one. On NTFS and exFAT, writing far past the end of a file makes the file system fill the gap with zeros first, which is slow.
- When the session has `"in_order": true`, send each file's chunks strictly in order, one at a time. Other connections can send other files at the same time.
- Batches of small files can run next to the chunks of big files.
- Keep one long-poll (`GET /sessions/{sid}?wait=25&after=...`) running while you send, on its own connection, so you see a cancel at once.

## 9. Resume after a restart

**The receiver** keeps a journal for each accepted session: the sender ID, a hash of the token, the file list, the chunk size, `wanted`, the digest of each chunk received, where each part is stored, and the names of saved files. It reloads the journal when the app starts. The chunk data must be on disk before the journal says the chunk arrived: flush the file to disk before saving the journal. Doing that every few seconds rather than after every chunk is fine; at worst a few chunks are sent again.

Keep parts on the same disk as the receive folder (for example in a hidden `.klshare-parts` folder inside it), so moving a finished file into place is a rename, not a copy.

**The sender** keeps the session ID, the token, the receiver's ID and address, and for each entry its path, size and modified time, until the session ends or for 7 days.

**To resume:**
1. Connect, check the ID, `GET /info` for a nonce.
2. `POST /sessions` with the same session ID (and `"files": []`). This returns the current state and the token. `GET /sessions/{sid}` with the saved token also works.
3. For each wanted file that isn't done, compare its size and modified time with the journal. If the size changed, drop it (section 6.5) and tell the user. If only the modified time changed, restart it (section 6.5).
4. Send only the chunks that aren't in `files.<id>.chunks`, then `done`.

If the receiver answers `404 no_session`, it has forgotten the session: start a new one, with a new session ID. Its person will be asked again.

Each interruption costs at most one chunk per connection.

## 10. Pairing and trusted devices

### 10.1 The QR code

A device with a screen (a PC or a TV) shows a QR code with this address:

```
klshare://pair?v=2&id=<device ID>&name=<name>&ip=<IPv4>&port=<API port>&secret=<secret>
```

- `id` is the full 64-digit device ID, so the scanner knows the right ID before it connects. There is no first-contact risk.
- `ip` may appear more than once, one for each LAN address.
- `name` is percent-encoded UTF-8.
- `secret` is 16 random bytes in base64url (22 characters). Make a new one each time the code is shown. It is valid while the code is on screen, for at most 10 minutes, and for one pairing.
- Direct mode (section 11) adds `ssid` and `psk`.

Phones scan. TVs and PCs only need to show a code.

### 10.2 `POST /pair`

The scanner connects to an `ip` and `port` from the code, checks that the server's certificate hash equals `id`, gets a nonce from `GET /info`, and sends:

```json pairRequest
{
  "sender": {"id": "03705D25472018B381A1C98835FC818751F2397F51DE1A899FC366D204B8DF76",
             "name": "Mueed's OnePlus", "model": "android", "port": 47820},
  "cert": "MIIBbjCCARSgAwIBAgICA+kwCgYIKoZIzj0EAwIw",
  "nonce": "qQ7oHwuJvLzYhoAPNCBgqA",
  "proof": "MEUCIQDUcrGeTDPF8iRKKJqzWB3IETsrsCRFy1eq",
  "mac": "9717a4899f2e2c58d332e6970c8b8780b5627c25f8bce34e96a2705a7bb6252b"
}
```

- `proof` uses purpose `pair` and an empty subject (section 4).
- `mac` is HMAC-SHA256, keyed with the 16 secret bytes, over three lines of ASCII, each ending with `\n`: `KLv2-pair`, the ID of the device that showed the code, and the ID of the scanner. Lower-case hex. Test vector: `pair_mac` in `docs/v2/vectors.json`.

The device that showed the code checks the proof, then the MAC. If both are right, it saves the scanner as a trusted device, takes the code off the screen, and answers:

```json pairReply
{"ok": true, "id": "087FDCA832B0338C25D59290E2579FDE49A21BD65A4DC05425020E245F8DD98D", "name": "Office PC"}
```

The scanner then saves it as trusted too.

| Status | `error` | Meaning |
|---|---|---|
| 200 | | Paired. |
| 403 | `no_pairing` | No code is on screen, or it expired. |
| 403 | `bad_secret` | The MAC is wrong. After 5 wrong MACs the code is withdrawn and a new one must be shown. |
| 403 | `bad_nonce`, `bad_proof` | See section 4. |

**Pairing without a camera** (PC to PC): not in version 2.0. A short code typed on the other device is only safe with a PAKE such as SPAKE2 (RFC 9382) that also binds both IDs. A 6-digit code with a plain HMAC can be broken offline, so never do that. Until then, show both short IDs and let people compare them.

### 10.3 What trust changes

Each device keeps its own list of trusted devices, and its own receive policy: **Everyone**, **Everyone for 10 minutes**, **Trusted only**, or **Off**. Default to Trusted only on networks Windows marks as Public. For each trusted device, the person can turn on auto-accept for files and for text, separately.

When a proven sender is trusted and auto-accept is on, `POST /sessions` answers `200` with the session already `accepted`. Without a proof, nothing is auto-accepted, ever: version 1 offers always ask.

## 11. `POST /hello`: Wi-Fi Direct and hotspots

The API is the same on a Wi-Fi Direct group or a hotspot as on a router. Only the way the two devices find each other changes:

- by QR code (the most reliable), with `ssid` and `psk` so the scanner can join the network first;
- by Wi-Fi Direct service discovery (`_klshare._tcp` with a short TXT record);
- by normal DNS-SD when both are on the same LAN.

On Android, the Wi-Fi Direct group owner is always `192.168.49.1`, and a client learns that from `WifiP2pInfo`. The group owner can't see the client's address, so the client introduces itself:

```json helloRequest
{
  "sender": {"id": "03705D25472018B381A1C98835FC818751F2397F51DE1A899FC366D204B8DF76",
             "name": "Mueed's OnePlus", "model": "android", "port": 47820},
  "cert": "MIIBbjCCARSgAwIBAgICA+kwCgYIKoZIzj0EAwIw",
  "nonce": "qQ7oHwuJvLzYhoAPNCBgqA",
  "proof": "MEUCIQDUcrGeTDPF8iRKKJqzWB3IETsrsCRFy1eq"
}
```

`sender.port` is required, and the proof uses purpose `hello` with that port as the subject. The receiver adds the device to its list at the address the request came from and that port, for 10 minutes or until the group ends. It answers `{"ok": true}`.

An Android Wi-Fi Direct group owner is also an ordinary WPA2 access point, with the name and password from `WifiP2pGroup`. A PC, or a phone without Wi-Fi Direct, can join it as normal Wi-Fi. A phone can also start a `LocalOnlyHotspot` (Android 8 and newer) and show its name and password in the QR code. As a rule, the device with a screen and no camera hosts the network and the phone joins.

## 12. Names and metadata

**Senders** send each `name` as a relative path with `/` between folders, and must follow these rules. **Receivers** refuse a session or page that breaks them, with `400 bad_files`:

- no empty parts, no `.` or `..` parts, no leading `/`, and no `\`;
- no control characters (U+0000 to U+001F and U+007F);
- each part at most 255 bytes of UTF-8, and the whole name at most 4096 bytes;
- every `id` unique in the session, and folders (`"kind": "dir"`) with `size` 0.

Anything else is allowed, and **each receiver makes names safe by its own platform's rules**, then reports the name it used. On Windows that means: replace `: * ? " < > |` with `_`, remove dots and spaces at the end of a part, and add `_` to reserved names such as `CON` or `NUL`. Version 1 refused such names instead, so a file called `a:b.txt` from a phone failed; in version 2 it arrives as `a_b.txt`.

The receiver never replaces an existing file. If the name is taken, it saves as `name (1).ext`, `name (2).ext` and so on. Folders in a path are made as needed, and a folder that already exists is used as it is.

- `mtime`: the receiver sets the file's modified time when the platform allows it.
- `kind`: `file` (the default) or `dir`. A `dir` entry keeps an empty folder.
- `mime`: a hint, for example for Android's MediaStore. The receiver may ignore it.

## 13. What a receiver must enforce

Everything from version 1, section 5, plus:

- Before accepting, check that the wanted files fit on the disk, and that none is over 4 GiB on a FAT32 disk (skip those with reason `too_large`).
- At most 2 sessions waiting for an answer from one address and 8 in all, counted together with version 1 offers (`429 busy`). One prompt at a time.
- At most 32 unused nonces per address.
- JSON bodies at most 8 MiB (`413 too_large`).
- At most 16 connections per address, counted together with version 1.
- An upper limit on entries per session, for example 1,000,000 (`413 too_many`).
- Drop connections that send nothing for 60 seconds.

## 14. All endpoints

Base path `/api/kl/v2`.

| Method | Path | Token | Purpose | Section |
|---|---|---|---|---|
| GET | `/info` | no | Identity, versions, features, limits, nonce | 5 |
| POST | `/sessions` | no (proof) | Make or rejoin a session | 6.1 |
| GET | `/sessions/{sid}?wait=&after=` | yes | Session state, long-poll | 6.2 |
| POST | `/sessions/{sid}/manifest` | yes | Next page of the file list | 6.3 |
| DELETE | `/sessions/{sid}` | yes | Cancel | 6.4 |
| DELETE | `/sessions/{sid}/files/{fid}` | yes | Drop one file | 6.5 |
| DELETE | `/sessions/{sid}/files/{fid}/chunks` | yes | Restart one file | 6.5 |
| PUT | `/sessions/{sid}/files/{fid}/chunks/{n}` | yes | One chunk | 7.2 |
| POST | `/sessions/{sid}/files/{fid}/done` | yes | Finish and check one file | 7.3 |
| POST | `/sessions/{sid}/batch` | yes | Many small files | 7.4 |
| POST | `/pair` | no (proof) | Finish QR pairing | 10.2 |
| POST | `/hello` | no (proof) | Say where you are on a Wi-Fi Direct group or hotspot | 11 |

Any other path gets `404 not_found`.

## 15. Error codes

| Status | `error` | Meaning |
|---|---|---|
| 400 | `bad_json` | The body isn't JSON, or has the wrong shape. |
| 400 | `bad_session` | The session ID breaks the rules in section 1.3. |
| 400 | `bad_files` | A file entry breaks the rules in section 12. |
| 400 | `bad_text` | The text is over 64 KB. |
| 400 | `empty` | No entries and no text. |
| 400 | `bad_cert` | `cert` isn't a certificate with a P-256 key. |
| 400 | `summary_mismatch` | The list doesn't match its summary. The session is cancelled. |
| 400 | `bad_chunk`, `wrong_size`, `bad_digest` | See section 7.2. |
| 400 | `bad_batch` | See section 7.4. |
| 403 | `bad_nonce`, `bad_proof` | See section 4. |
| 403 | `bad_token` | Missing or wrong session token. |
| 403 | `not_receiving` | Receiving is turned off. |
| 403 | `no_pairing`, `bad_secret` | See section 10.2. |
| 404 | `no_session` | Unknown session, or forgotten (section 6.6). |
| 404 | `no_file` | The file ID isn't in the list, or its page hasn't arrived yet. |
| 404 | `not_found` | Unknown path. |
| 409 | `duplicate` | The session ID belongs to another sender. |
| 409 | `changed` | The same session ID with a different summary. |
| 409 | `not_accepted` | Upload before the person accepted. Wait on the long-poll. |
| 409 | `not_wanted` | The person didn't accept this file. |
| 409 | `finished` | The file is already saved. |
| 409 | `chunk_changed` | See section 7.2. |
| 409 | `missing_chunks` | See section 7.3. |
| 409 | `manifest_complete` | A list page after the last one. |
| 410 | `cancelled`, `declined`, `expired` | The session is over. Stop sending. |
| 411 | `length_required` | No `Content-Length`. |
| 413 | `too_large`, `too_many` | The body, or the number of entries, is over the receiver's limit. |
| 421 | `wrong_host` | `Host` isn't the IP address you connected to. |
| 422 | `digest_mismatch` | See sections 7.2 to 7.4. |
| 429 | `busy` | Too many sessions waiting for an answer. Try again after the person answers. |
| 503 | `slow_down` | Wait for `Retry-After` seconds. |
| 507 | `no_space` | The receiving disk is full, or would be. |
| 500 | `failed` | Anything else. The `message` says what. |

## 16. Security notes

- Everything is encrypted with TLS, and the device ID is pinned, as in version 1.
- The proof (section 4) tells the receiver who is sending. Only a proven sender can be trusted or auto-accepted.
- QR pairing pins the full ID from the code, so even the first contact is checked.
- Tokens are bearer tokens: anyone who has one can upload to that session. They only travel inside TLS and never in URLs. Store a hash of the token in the journal, not the token.
- Treat every name and every number in a request as hostile. Check names as in section 12 before joining them to a folder path, and check sizes against your limits before you allocate anything.
- Nothing is saved without the person accepting, or without a trusted device that the person chose to auto-accept. Saved files never replace existing ones.
- **The proof and pairing design must be reviewed by someone with cryptography experience before release.**

## 17. Versioning

`protocol` in `/info` and `v` in the TXT record are `2`. Once version 2 ships, it can only change in ways old devices can ignore: new optional fields, new `features`, new error codes (treat an unknown code like `failed`). Anything else will be version 3 at `/api/kl/v3`, and v3 devices will keep serving v2 and v1.

## 18. Not in version 2.0

- Relays over the internet.
- Receiving on iOS in the background.
- Folder sync.
- Pairing by typed code (needs a PAKE, section 10.2).
- "Skip identical files" (the sender would need each file's digest before sending).
- Chunked uploads from the shared folder web page. The tree digest was chosen so this can come later.

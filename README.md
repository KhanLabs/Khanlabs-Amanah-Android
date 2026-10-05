# KhanLabs LAN Share (Android)

Send large files, folders and text between Android phones, tablets, Android TV, Fire TV and Windows PCs. It works on the same Wi-Fi, or directly between two devices with no router.

**Status: planning. There is no code yet.** See [PROGRESS.md](PROGRESS.md).

## What it will do

- **Find devices.** Other devices running KhanLabs LAN Share show up by themselves, or you can scan a QR code.
- **Large files.** Transfers resume after a dropped connection or a restart. Every file is checked when it arrives.
- **Many small files** move as fast as big ones.
- **No router needed.** Wi-Fi Direct or a local hotspot connects two devices directly.
- **Android TV.** Works with just the remote. Received APKs can be installed, and videos open in VLC or Kodi.
- **Nothing arrives without consent.** Every incoming send asks first, unless it comes from a device you have paired and marked trusted.
- **Private.** Encrypted, with no account, no internet, no ads and no Google Play Services.
- **Portable.** One APK for phones and TVs, from GitHub Releases.

## Works with

The Windows app, [khanlabs-lanshare](https://github.com/KhanLabs/khanlabs-lanshare). Both apps speak the protocol in [docs/PROTOCOL.md](docs/PROTOCOL.md). Version 2 is being written in [docs/PROTOCOL-v2.md](docs/PROTOCOL-v2.md).

## Documents

| File | What it is |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Main instructions: rules, tech choices, module layout, Android points to follow |
| [PROGRESS.md](PROGRESS.md) | What is done, what is next, decisions |
| [docs/PLAN.md](docs/PLAN.md) | Full plan for both apps: review, protocol v2, Android design, roadmap, competitors |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | The protocol (copy; the master copy is in the Windows repo) |
| [docs/PROTOCOL-v2.md](docs/PROTOCOL-v2.md) | Protocol version 2, draft (copy) |
| [docs/v2/](docs/v2/) | v2 JSON Schema and test vectors (copies) |

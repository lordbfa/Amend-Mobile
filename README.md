# amend 🌱

**Private messages that grow a forest.**

amend is a decentralised, end-to-end encrypted messenger for Android with an
environmental heart. Every conversation grows a shared garden, your activity
grows a personal forest, and the app is honest about its own carbon footprint
— then helps you offset far more of it with small real-world actions.

amend is written from scratch. It is built on the open [Nostr](https://nostr.com)
protocol, so there are no accounts, phone numbers or company servers.

## Features

**Messaging**
- End-to-end encrypted private messages (NIP-17 gift wraps + NIP-44 v2 encryption).
  Relays can't see who you talk to, when exactly, or what you say.
- No sign-up: your identity is a key pair that lives only on your phone,
  encrypted at rest with the Android Keystore.
- Works through several independent relays at once; add or remove your own.
- Compatible with other NIP-17 apps (e.g. 0xchat, Amethyst) — same keys, same messages.
- Replies (drag a bubble sideways), reactions (long-press or double-tap), message requests
  with accept/block, pinning, retry for failed sends.

**Eco**
- **Personal forest** – points from mindful activity grow procedurally drawn trees
  (8 species). Message points are capped daily, so the forest rewards meaningful use, not spam.
- **Shared gardens** – every chat grows its own plant; both people see the exact same one
  (derived from both keys and the message count, nothing extra is synced).
- **Daily eco challenges** – 30 small actions (energy, transport, food, waste, water, digital,
  nature). Complete one, or send it to a friend as an interactive challenge card.
- **Impact dashboard** – amend counts the bytes it sends and receives and estimates the CO₂,
  with its assumptions shown, next to the CO₂ your challenges avoided.
- **Carbon-aware sync** – background checks only while charging on Wi-Fi.
- **Efficiency mode** – dark theme, calmer animations, less background activity.
- **Plant a real tree** – links to independent reforestation charities (no affiliation).

**Feel**
- Living sky that follows the real time of day (sunrise, sun/moon arc, stars, clouds).
- Procedural trees that sway, grow with spring physics and shake when tapped.
- Particle bursts (leaves, petals, sparks) on send, reactions and achievements.
- Haptic feedback everywhere; bouncy buttons; animated tabs and transitions.
- A "mycelium" view of the relay network with data packets flowing to each relay.

## Download

Every push to `main` builds APKs with GitHub Actions:
**Actions → Build amend → amend-apk**. `amend-debug.apk` is a test build (installs beside the
real app as `app.amend.messenger.debug`). `amend-release.apk` is only there when the release
key is configured (repository secrets `AMEND_RELEASE_KEYSTORE_B64`, `AMEND_RELEASE_STORE_PASSWORD`,
`AMEND_RELEASE_KEY_ALIAS`, `AMEND_RELEASE_KEY_PASSWORD`).

Pushing a tag like `v2.0.0` attaches the APKs to a GitHub release.

## Build locally

Requires Android Studio (or JDK 17 + Android SDK 35).

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest            # crypto test vectors, eco rules
./gradlew :app:testDebugUnitTest --tests "app.amend.messenger.LiveRelayTest" -Damend.liveRelays=true
```

## How it's built

| Layer | Where | Notes |
|---|---|---|
| Crypto | `core/crypto` | secp256k1 + BIP-340 Schnorr, ChaCha20, NIP-44 v2, bech32. Native libsecp256k1 on device, pure-Kotlin fallback. Tested against the official NIP-44 vectors and libsecp256k1. |
| Protocol | `core/nostr` | Events (NIP-01), gift wraps (NIP-59), private DMs (NIP-17), relay auth (NIP-42). |
| Network | `net` | OkHttp WebSocket relay pool with reconnect, auth, byte counting. |
| Data | `data` | Repository, JSON storage, Keystore-encrypted identity. |
| Eco | `eco` | Forest growth, gardens, challenges, footprint estimates. |
| UI | `ui` | Jetpack Compose, Material 3, custom Canvas graphics. |

See [docs/DECENTRALIZATION.md](docs/DECENTRALIZATION.md) for how the network works and how
to make it even more independent (in Bangla).

## Security notes

- This is a young app and hasn't had an independent security audit.
- `app/amend-dev.keystore` is public in this repo, so it only signs debug builds
  (`app.amend.messenger.debug`). Release builds are signed only with a private key supplied
  from outside the repository (CI secrets, or `amend.release.*` in `~/.gradle/gradle.properties`);
  without it they are unsigned.
- Gift-wrapped messages hide metadata from relays, but your IP address is visible to the
  relays you connect to (use a VPN or Tor/Orbot if that matters to you).

## Licences

- amend source code: GPL-3.0 (see `LICENSE`).
- Poppins font: SIL Open Font License 1.1 (`licenses/Poppins-OFL-1.1.txt`).
- `app/src/test/resources/nip44.vectors.json`: official NIP-44 test vectors (public domain).
- Uses OkHttp, ZXing, ACINQ secp256k1-kmp and AndroidX libraries under their own licences.

# amend threat model

This document says plainly what amend protects, against whom, and where its limits are. It
describes the app as it is in this repository. If you find that the code does something
different from what this says, that is a security bug: please report it (see
[SECURITY.md](../SECURITY.md)).

## What amend is

amend is an Android messenger built on the open [Nostr](https://nostr.com) protocol. There is
no amend account and no amend message server. Your identity is a secp256k1 key pair created
on your phone. Messages travel through independent Nostr relays run by other people.

## Who amend defends against

| Adversary | Can they read your messages? | What else can they learn? |
|---|---|---|
| A relay operator | No | The recipient's public key, a rough time, the size class of the message, and the IP address that connects. With Tor (Orbot) on, not your IP address. |
| A file (Blossom) server | No | That an encrypted file of a certain size was uploaded or downloaded, and from which IP address (hidden with Tor). |
| The TURN relay for calls (Cloudflare) | No (DTLS-SRTP) | That two IP addresses had a call, when, and how much data was sent. |
| The person you call | Yes, they're in the call | Your IP address, unless *Hide my IP address in calls* is on. |
| Someone on your network (Wi-Fi, ISP) | No (TLS, then end-to-end encryption) | Which relays and servers you connect to, unless you use Tor. |
| Someone who gets your unlocked phone | Only if the screen lock is off | With the screen lock on, they need your fingerprint, face or phone PIN. |
| Another app on your phone | No | Only what you allow in notifications (*Notification content* setting). |
| Someone pretending to be your contact | No, if you verified them | Compare the security code or scan their QR code to detect it. |
| amend's developer | No | amend has no servers that see messages, and no analytics. |

## How it works

### Messages

- Encrypted with **NIP-44 v2** (secp256k1 ECDH, HKDF-SHA256, ChaCha20, HMAC-SHA256, with
  length padding), checked against the official test vectors in the unit tests.
- Wrapped as **NIP-17 / NIP-59 gift wraps**: the real message (the "rumor") is unsigned and
  sealed, and the outer event is signed by a throwaway key made for that one message. Relays
  can't see who sent it.
- The outer event's time is moved back by a random amount of up to two days, so relays can't
  line up exact sending times.
- Every incoming wrap's signature is verified before it is used, and repeats are dropped.
- Group chats ("shared gardens") send a separate gift wrap to every member, so relays can't
  tell a group message from a direct one.
- Disappearing messages carry a **NIP-40 expiration**: relays that honour it delete them, and
  the app deletes its own copy.

### Calls

- WebRTC with **DTLS-SRTP**. The TURN relay (Cloudflare, through amend's credential worker)
  only ever handles encrypted media.
- By default calls try a direct phone-to-phone path, which shows each phone's IP address to
  the other. **Hide my IP address in calls** (Settings → Calls) forces relay-only calls and
  skips the STUN servers. If no relay can be reached, the call fails rather than leaking.

### Media

- Photos, videos, voice notes and files are encrypted with **AES-256-GCM** under a fresh
  random key before upload. The key travels only inside the encrypted message.
- Photos have their EXIF data (including location) removed before sending.
- Media from people you haven't accepted is hidden until you tap it, so a stranger can't learn
  your IP address by making your phone download a file from their server.

### On the phone

- The secret key is encrypted with an AES key that lives in the **Android Keystore**
  (hardware-backed where the phone supports it).
- The message store and settings are sealed with **AES-256-GCM** using a key derived from the
  identity key.
- Decrypted media is cached in amend's private app storage, protected by Android's app
  sandbox and the phone's file-based encryption.
- Android backups and device-to-device transfer are turned off (`allowBackup=false`,
  data-extraction rules), so nothing is copied off the phone.
- Release builds strip all logging, so nothing about you reaches the system log.
- Optional **screen lock** (fingerprint, face or phone PIN) and a blank preview in Recent apps.
- **Screenshot protection** blocks screenshots and screen recording of chats and other
  people's profiles.
- Only amend's own notifications can open a chat or answer a call: intents into the app are
  authenticated.

### Network

- Cleartext (non-TLS) traffic is disabled for the whole app.
- Optional **Tor routing through Orbot**: relays, file servers, weather and map tiles see a Tor
  exit instead of your IP address. It fails closed: if Orbot isn't running, amend stays offline
  instead of connecting directly. Calls can't use Tor (Tor carries TCP only).
- Rate limits protect against floods from other users (SOS alarms, offline-mesh frames, watch
  messages).

### Identity checks

- **Security codes**: 60 digits computed from both public keys with 5,200 rounds of SHA-512,
  the same on both phones. Compare them in person or on a call, or scan the contact's QR code.
  Verified contacts get a badge. The list of verified contacts is stored as keyed hashes, not
  as public keys.

## Known limits

We'd rather you know these than discover them.

1. **No forward secrecy.** NIP-44 messages are encrypted with your long-term key. If that key
   is ever stolen, old messages still stored on relays could be decrypted. Disappearing
   messages reduce how much is stored. A ratcheting protocol (such as MLS over Nostr) is being
   looked at for the future.
2. **No post-compromise security.** For the same reason, a stolen key keeps working until you
   move to a new identity (Settings → Privacy & security → burner identity or remove identity).
3. **Relays see the recipient's public key.** That is how they know who a message is for. They
   can count how many messages a key receives and when (within the two-day blur).
4. **IP addresses.** Without Tor, relays and file servers see your IP address. Without *Hide my
   IP address in calls*, the person you call sees it too.
5. **Public profile.** Your name, about text and photo are public Nostr data, as is anything
   you post to the community eco map.
6. **A compromised phone.** Malware with root access, or someone who knows your phone's PIN,
   can read what amend shows on screen. amend can't protect against that.
7. **Not independently audited.** amend has had an internal security review and automated
   tests, but no independent third-party audit yet. We welcome one and will publish the results.

## Supply chain

- The source code is public under GPL-3.0. Every build shows the exact commit it came from.
- CI actions are pinned to exact commits; Dependabot proposes updates. The default CI
  permission is read-only.
- Release files come with SHA-256 checksums and a signed GitHub build provenance attestation.
- The Play Store bundle is signed by the developer with a private upload key that never
  enters the repository or CI.

## Reporting

See [SECURITY.md](../SECURITY.md).

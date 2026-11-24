# Forward-secret chats (Signal Protocol inside NIP-17)

Status: in development on the `ratchet` branch, off by default until tested on two phones.

## Goal

| Who | Can read a fully protected message? |
|---|---|
| The sender and the recipient's phones | yes |
| Nostr relays | no (as today) |
| Someone holding only the nsec (secret key) | **no** — not past messages, and not future ones without the people in the chat seeing a security-code warning |
| Anyone else | no |

Today every message is encrypted to the long-term Nostr key (NIP-44), so the nsec opens all of
them, past and future. With this change the nsec only opens the *outer* gift wrap; the message
inside is encrypted with the Signal Protocol (PQXDH + Double Ratchet, via libsignal), whose
keys never leave the phone and change with every message (forward secrecy and
post-compromise security).

## Keys

* **Nostr identity** (nsec / npub) — unchanged. Signs the outer seal and the prekey bundle
  events, and opens the outer gift wrap.
* **Ratchet identity key** — one Curve25519 key pair per *account*, made by libsignal. It is
  sealed on the phone with a non-exportable Android Keystore key and is never in a backup.
  "Link another phone" hands it to the new phone inside the link QR code, phone to phone.
  The nsec alone cannot produce it.
* **Per phone (device id 1–127, registration id):** a signed prekey, a last-resort Kyber-1024
  prekey (post-quantum, required by libsignal) and 20 one-time prekeys, all signed with the
  ratchet identity key.

## Prekey bundles on Nostr

One parameterised replaceable event per phone: `kind 30443`, `d` = device id, signed with the
nsec. Its content holds the public keys and a second signature made with the **ratchet
identity key** over `npub | device id | created_at | keys`. Peers accept a bundle only if that
signature checks out against the ratchet identity they already trust for that npub (trust on
first use). So someone holding only the nsec can't add a phone, replace a bundle or retire one
without the ratchet identity key.

Bundles are refreshed weekly and expire after 45 days (NIP-40), so a lost phone drops out. A
phone that turns the feature off publishes an identity-signed "retired" bundle. Bundles are
published to the user's relays, their inbox relays and the default relays, and looked up in the
same places.

One-time prekeys on public relays: two people can pick the same one at the same time. A used
one is left out of the next bundle at once, but its private half is kept for 72 hours so the
second person's first message still opens. Senders pick one at random to make that rare.

## Sending

All eight places that used to call `GiftWrap.wrap` go through one function. For each recipient
(and for each of my own other phones, replacing the old copy-to-self):

* the recipient has trusted bundles or existing sessions → for every phone: libsignal encrypts
  the full inner rumor (the NIP-17 kind 14 / 15 / … event, unchanged), it is carried in an
  inner rumor of kind 1443 (`device`, `from`, `type` tags, base64 content), and that is sealed and
  gift-wrapped exactly as today, one wrap per phone;
* the recipient has never had a bundle (old amend, other Nostr apps) → plain NIP-17 as today,
  and the chat says *Not fully protected — their app doesn't support it yet*;
* the recipient has had a bundle before but none can be found now → **no silent fallback**
  (a relay could hide the bundle to force a downgrade). Existing sessions are still used; with
  none, the message waits and the chat says *Can't reach their secure keys*. The one exception
  is SOS and live location: they go out as plain NIP-17 rather than not at all, and say so.

## Receiving

1. Open the gift wrap and seal with the nsec, as today (the seal signer must equal the rumor
   author).
2. Kind 1443 for another of my phones → ignore quietly.
3. Decrypt with libsignal. The decrypted inner rumor must have the same author as the seal and
   a valid id, then it is handled exactly like a NIP-17 rumor today.
4. Ratchet state changes are written to disk only after the message has been saved, so a crash
   in between lets the re-delivered wrap be decrypted again.
5. Duplicates and re-deliveries are dropped quietly. A real failure shows *A message couldn't be
   decrypted* and, at most once an hour per phone, starts a fresh session with that phone.

## Identity changes and the security code

* The 60-digit security code also covers both ratchet identity keys (`amend-safety-code-v2`).
* When a contact's ratchet identity changes (they restored from their backup key or reinstalled),
  the chat shows *{name}'s security code changed*, and a verified contact is un-verified.
* Restoring from the backup key alone creates a new ratchet identity: old messages can't be
  re-downloaded (that is the point), and contacts see the security-code notice.

## What this does not change

* Relays still see who receives a gift wrap and its rough time, as today.
* Chat history on the phone stays in amend's encrypted files (sealed with a key derived from the
  nsec, in app-private storage). Someone with the phone's files *and* the nsec can read that
  local history; the promise is about messages in transit and on relays.
* Shared gardens use pairwise sessions with every member's phones (no group ratchet yet).

## Licence

libsignal is AGPL-3.0; amend is GPL-3.0, which allows combining them (GPL-3.0 §13).

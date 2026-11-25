# Forward-secret chats (Signal Protocol inside NIP-17)

Status: in amend 4.7.0, on by default (Settings → Security & transparency → Full protection).

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
phone that turns the feature off publishes an identity-signed "retired" bundle; a retirement
from an identity that is less than a week old removes that phone but doesn't unpin the person
(otherwise "new identity, then retire" would turn a visible takeover into a quiet downgrade).
Bundles are published to the user's relays, their inbox relays and the default relays, and
looked up on the reader's own relays (with the metadata of everyone in their chats).

A bundle for *my own* npub with a different ratchet identity (my second phone set up
separately, or someone with my nsec) switches nothing off: this phone keeps its keys and shows
a warning; linking the phones settles it.

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
  none, the message isn't sent and the chat says *Can't reach their protection keys*. This holds
  even with the setting switched off on my phone.
* SOS is the one exception: it goes out protected *and* as plain NIP-17, so a session broken on
  the other side can't swallow it.
* a protection file that can't be opened (Keystore failure) locks: nothing protected or plain is
  sent (except SOS) until the user restarts the phone or chooses to start over with new keys.

## Receiving

1. Open the gift wrap and seal with the nsec, as today (the seal signer must equal the rumor
   author).
2. Kind 1443 for another of my phones → ignore quietly.
3. Decrypt with libsignal. The decrypted inner rumor must have the same author as the seal and
   a valid id, then it is handled exactly like a NIP-17 rumor today.
4. Ratchet state changes are written to disk after the message has been handled, so a crash in
   between usually lets the re-delivered wrap be decrypted again (a send or another receive at
   the same moment can write the state first; then that one message is lost, never a key reused).
5. Duplicates and re-deliveries are dropped quietly. A real failure shows *A message couldn't be
   decrypted* and, at most once an hour per phone, starts a fresh session with that phone.

## Identity changes and the security code

* The 60-digit security code also covers both ratchet identity keys (`amend-safety-code-v2`).
* When a contact's ratchet identity changes (they restored from their backup key or reinstalled),
  the chat shows *{name}'s security code changed*, and a verified contact is un-verified.
* Restoring from the backup key alone creates a new ratchet identity: old messages can't be
  re-downloaded (that is the point), and contacts see the security-code notice.

## Mailboxes: hiding who a message is for

A plain NIP-17 gift wrap names its recipient's npub in its `p` tag, so relays know who gets
what. Protected gift wraps don't, once two people have exchanged one message each:

* The mailbox key for messages **to R in the chat between A and R** in week *e* is a secp256k1
  key from `HKDF-SHA256(salt "amend-mailbox-v1", X25519(IK_A, IK_R))` with info `R:e:counter`.
  Only A and R can work it out: it needs one of their ratchet identities (not the nsec).
* The gift wrap is addressed and encrypted to that key; the seal inside is still signed by the
  sender and checked as before, and a mailbox only ever accepts protected messages from the one
  person it is shared with.
* Only normal ("msg") Signal messages go to a mailbox: libsignal sends those only after that very
  phone has answered in the session, so it knows the sender's current identity and reads that
  mailbox. A first ("prekey") message, plain chats and older apps still use the npub, and so does
  a phone whose bundle doesn't say `"mb": 1`.
* Phones read their mailboxes for every known identity, for the weeks since the last sync (at
  most eight back) and the next week, in filters of 100 keys. The offline mesh recognises them.
* Weekly rotation means a relay can't follow one mailbox for long.

What it hides, and from whom: anyone who reads events stored on relays (scrapers, other relay
users, relays the recipient doesn't connect to) can no longer see who protected messages are for
or count them per person. **It does not hide this from the recipient's own relays:** a phone asks
for its npub and its mailboxes in one subscription (and logs in as the npub on NIP-42 relays), so
those relays can link the mailboxes to the npub. Reading mailboxes over separate connections
without the npub would close that too; it isn't done yet.

Relays that hand gift wraps only to the person named in them after NIP-42 login (in amend's
defaults, `auth.nostr1.com`) don't return mailbox wraps; the other default relays do (checked by
the live relay test in CI). Someone whose relays are all like that would miss mailbox messages.

## What this does not change

* For plain chats and first messages, relays still see who receives a gift wrap and its rough time.
* Chat history on the phone stays in amend's encrypted files (sealed with a key derived from the
  nsec, in app-private storage). Someone with the phone's files *and* the nsec can read that
  local history; the promise is about messages in transit and on relays.
* Shared gardens use pairwise sessions with every member's phones (no group ratchet yet).

## Licence

libsignal is AGPL-3.0; amend is GPL-3.0, which allows combining them (GPL-3.0 §13).

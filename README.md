# amend for Android

**amend** is a private, end-to-end encrypted messenger with a bold, playful interface.

It is a fork of [Session Android](https://github.com/session-foundation/session-android) by the
Session Technology Foundation. amend keeps Session's messaging core, encryption and onion-routed
network unchanged, so amend users can chat with Session users using the same Account IDs.
amend is an independent project and is **not affiliated with or endorsed by** the Session
Technology Foundation or Session Foundation.

## What's different from Session

- **New identity** – app name, launcher icon, logo, notification icon and wordmark.
  Application ID `app.amend.messenger`, so it installs side by side with Session.
- **Grape & Bubblegum themes** – a deep-violet dark theme and a soft-pink light theme,
  with hot-pink as the default accent. Grape is the default for new installs.
- **More accents** – amend pink and lime join the existing colours in *Settings → Appearance*.
  All of Session's original themes (Classic, Ocean) are still available.
- **Swipe gestures on the home screen** – swipe right to pin / unpin a conversation,
  swipe left to mark it read / unread.
- **Message animations** – new messages pop in from the bubble's corner while a chat is open.
- **Haptic feedback** – on sending, reactions, swipe thresholds and incoming messages
  (follows the phone's system touch-feedback setting).
- **Rounder bubbles and dialogs.**

## Download / build

Every push builds APKs with GitHub Actions (**Actions → Build amend APK → amend-apks**).
Pushing a tag such as `v1.0.0` attaches the APKs to a GitHub release.

- `*-website-qa-*universal*.apk` – optimised build, use this one.
- `*-website-debug-*.apk` – debug build for testing.

To build locally (JDK 21 and the Android SDK required):

```sh
./gradlew :app:assembleWebsiteQa
```

### Push notifications

amend builds the `website` flavour by default, which receives messages by background polling
instead of Firebase push. Session's Firebase project is tied to Session's own app ID, so the
`play`/`fdroid` flavours need your own Firebase project and `app/google-services.json` before
they will build. Polling can delay notifications compared with push.

### Release signing

The QA build is signed with the debug key in `etc/debug.keystore`, which is public. Before
distributing amend widely, create your own keystore and pass it with the
`SESSION_STORE_FILE`, `SESSION_STORE_PASSWORD`, `SESSION_KEY_ALIAS` and `SESSION_KEY_PASSWORD`
Gradle properties to build a `websiteRelease`.

## Licence

amend is free software, licensed under the **GNU General Public License v3.0**, the same licence
as Session Android. See [LICENSE](LICENSE). If you distribute amend, you must also make its
complete source code available under the GPLv3.

- Original work © Session Technology Foundation and contributors, and earlier Signal / Loki
  contributors as noted in the source.
- amend modifications © 2026 Ahmed Bhuiyan Faisal.
- The amend wordmark uses glyphs from Poppins Bold (SIL Open Font Licence 1.1).
- "Session" is a trademark of its respective owners. It appears in amend only where the app
  refers to the Session network, Session Pro, or Session's foundation.

Third-party licences are listed in [third_party_licenses](third_party_licenses).

## Architecture

The codebase is unchanged in structure from Session; see [ARCHITECTURE.md](ARCHITECTURE.md).
Kotlin packages still use the upstream `network.loki.messenger` / `org.thoughtcrime.securesms`
namespaces to keep merging upstream fixes simple.

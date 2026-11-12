# Security policy

amend is a private messenger, so security problems matter more than any other kind of bug.
Thank you for helping keep the people who use it safe.

## Reporting a vulnerability

**Please don't open a public issue for a security problem.** Tell us privately first, so it
can be fixed before anyone misuses it:

- **GitHub:** use *Report a vulnerability* on the [Security tab](https://github.com/lordbfa/Amend-Mobile/security/advisories/new) of this repository, or
- **Email:** lordbfa@getamend.app with the subject `amend security report`.

Please include:

- what the problem is and what an attacker could do with it,
- the steps to reproduce it (a proof of concept helps a lot),
- the amend version (Settings → App info shows the version and the exact source commit),
- your Android version and phone model, if it matters.

## What happens next

- We confirm we received your report as soon as we can, normally within a few days.
- We keep you updated while we work on a fix and tell you when it ships.
- Once a fix is out, we publish what happened in the release notes. We credit you by name
  unless you'd rather stay anonymous.

amend is built by one developer, so please allow reasonable time for a fix before you
publish details. We'll agree a disclosure date with you.

## Safe harbour

We won't take legal action against research done in good faith that:

- only uses your own accounts and test devices, or accounts whose owners agreed,
- doesn't read, change or delete other people's data, and stops as soon as it reaches any,
- doesn't disrupt relays, file servers, the TURN service or the website for others
  (no denial-of-service or spam), and
- gives us reasonable time to fix the problem before it is made public.

## In scope

- The amend Android app and the Wear OS app in this repository.
- The TURN credential worker in `turn-worker/`.
- The website plugins in `site/`.
- The build and release workflows in `.github/`.

Third-party services amend talks to (Nostr relays, Blossom file servers, Cloudflare, Open-Meteo,
OpenStreetMap) are run by other people. Report problems in them to their operators. If a flaw
in one of them puts amend users at risk, tell us too.

## Supported versions

Only the latest release on Google Play and on the
[Releases page](https://github.com/lordbfa/Amend-Mobile/releases) gets security fixes.

## How amend is protected, and its limits

See the [threat model](docs/THREAT_MODEL.md). It says plainly what amend protects, what it
can't hide, and what hasn't been done yet (such as an independent audit).

## Checking a release

Every tagged release lists the SHA-256 checksum of each file in `SHA256SUMS.txt`, and comes
with a signed build provenance attestation from GitHub Actions:

```bash
sha256sum -c SHA256SUMS.txt
gh attestation verify amend-release.apk --repo lordbfa/Amend-Mobile
```

The app itself shows the source commit it was built from under Settings → App info and
Settings → Security & transparency.

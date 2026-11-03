# getamend.app — files to upload

1. **Privacy policy** — `privacy/index.html` → publish at `https://getamend.app/privacy/`
   (WordPress: create a page with slug `privacy`, add a *Custom HTML* block and paste the `<main>` section).
   Then in Play Console → App content → Privacy policy, set `https://getamend.app/privacy/`.
2. **NIP-05 names** — upload `.well-known/nostr.json` (and `.htaccess` on Apache hosts) to the site root so
   `https://getamend.app/.well-known/nostr.json` returns JSON with `Access-Control-Allow-Origin: *`.
   Replace the placeholder with your hex public key (amend → You → Share profile → copy key, convert npub → hex).
   `_@getamend.app` shows as just `getamend.app`.

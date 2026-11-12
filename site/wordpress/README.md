# WordPress plugins for getamend.app

- `amend-facebook-link/` — adds amend's Facebook page to the footer's social links (`.amend-social`), styled like the others. `amend-facebook-link.zip` is the installable copy.

## amend-security-txt

Serves `/.well-known/security.txt` (RFC 9116) on getamend.app: where to report a security
problem privately (GitHub advisory or email), the security policy, and an `Expires` date that
is always 180 days ahead. Install `amend-security-txt.zip` under Plugins → Add new → Upload.

## amend-eff-motion

Animates the Efficiency mode switch. Turning it on folds the light page into the switch
behind a glowing green ring, then leaves burst out of the switch. Turning it off opens the
light page out of the switch behind a golden ring with a sun flare. The page edge and ring
move from one frame clock (View Transitions API with a per-frame `clip-path`); browsers
without View Transitions get the overlay over the usual colour fade, and reduced-motion
users get the plain switch. Install `amend-eff-motion.zip` under Plugins → Add new → Upload.

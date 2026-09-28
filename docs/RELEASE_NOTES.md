# NextBench Android — Release Notes (release candidate)

**Version:** 1.0.0 (`versionCode` 1) · **Package:** `com.nextbench.app` · **Variant:** debug
**Status:** release candidate — engineering complete and gated; deployment pending.

## Release candidate status

All requested engineering work for this release is implemented, tested, and passing its gates in
this repository and in `nextbench-1` (website/backend). No production system has been touched:
Firebase rules, Cloud Functions, the website, migrations, and purges are all still **pending
deployment** and are documented, command by command, in
`docs/deployment/release-runbook.md`.

## Major engineering changes

- **Account deletion** — in-app, typed confirmation, server-authoritative callable with a
  recent-sign-in gate; a completable re-auth flow instead of a dead end; full backend cascade
  (profile, private documents, evidence objects, relationships, moderation records handled
  explicitly).
- **Reporting** — in-app reporting of users, posts, products, and chat behaviour with
  server-side records.
- **Blocking** — block/unblock with enforcement across profiles, direct chats, club transcripts,
  and the marketplace; read paths stay block-consistent.
- **Verification failure handling** — server responses fail closed; per-failure-class user copy;
  timeouts and transport failures never report a false "submitted".
- **Privacy** — hide-last-seen, chat-privacy toggle, owner-only presence writes.
- **Block-aware push notifications** — blocked senders produce no notification (server-side
  filtering, `functions-android`).

## Security hardening completed

- **Student ID cards and selfies** moved off the publicly readable profile document to owner-only
  Storage (`identity/{uid}/**`) with an owner-only pointer; admins view via 5-minute signed URLs
  only; no public URL is ever stored.
- **Organization registration documents** (GSTIN / UDISE / registration certificates) moved off a
  public Cloudinary URL on `users/{uid}` to the same private pattern (`organization/{uid}/**` +
  `users/{uid}/private/organization`); Android and website signups upload privately; account
  deletion removes the evidence; idempotent migration and a separately scoped Cloudinary purge are
  prepared but require credentials to run.
- **Rules** — Firestore and Storage rules updated and covered by emulator test suites; clients can
  no longer plant identity or organization-document URLs on profile documents.

## Tests / build status

- Android unit tests: **295 tests / 52 classes, 0 failures** (`:data:firebase` 156, `:data:model` 5,
  `:app` 134)
- Android lint: **0 errors**; `:app:assembleDebug`: **BUILD SUCCESSFUL**
- Push functions: **11/11**
- Website/backend: unit **111/111**, Firestore rules **74/74**, Storage rules **14/14**, typecheck clean

## Known deployment-only tasks (not application engineering)

Firebase rules/functions deploys, Vercel deploy, identity + organization migrations, Cloudinary
purges (irreversible), `FIREBASE_STORAGE_BUCKET` confirmation, release signing/AAB/Play upload,
Play Data Safety answers, public privacy-policy and account-deletion URLs, reviewer/demo account.
Full checklist with commands: `docs/deployment/release-runbook.md` §4 and §7.

## Operational warnings

- **Deploy in the documented order** (Storage rules → Functions → website → Firestore rules →
  migration → purge → Android release). Reordering breaks verification signup or deletion.
- **Verify the Storage bucket env vars** before deploying functions or the website.
- **The purge steps are irreversible.** Run the dry-run first and spot-check one asset.
- **Old clients** cannot submit verification (and cannot register organizations) once the new
  Firestore rules are live; plan the rollout window.
- Migration audit reports contain legacy public URLs — keep them out of version control
  (already git-ignored).

## Android artifact

- **Path:** `app/build/outputs/apk/debug/app-debug.apk` (this repository)
- **Variant:** debug · **versionName:** `1.0.0` · **versionCode:** `1` · **package:**
  `com.nextbench.app`
- Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and run the smoke test in
  `docs/phone-smoke-test.md` before wider distribution.

*Note: the debug artifact is for internal testing only; Play distribution requires the signed
release/AAB flow (still outstanding, see deployment-only tasks).*

# NextBench Android — Engineering Handoff

Status: **release candidate.** The major requested engineering work is implemented and tested in
this repository and in the website/backend repository (`nextbench-1`). Nothing is deployed yet;
deployment is a separate, credential-gated process — see §D.

## A. Project overview

**NextBench** is a verified student marketplace and campus community: students buy, sell, and
exchange books, notes, uniforms, and more within a trusted campus ecosystem. Organizations
(companies, schools, coaching centres, NGOs) can register and be verified separately.

| Component | Stack |
| --- | --- |
| Android app (this repo) | Kotlin, Jetpack Compose, MVVM/UDF, Hilt, Coroutines/Flow, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, JDK 21 |
| Website/backend (`nextbench-1`) | React 19 + TypeScript + Vite, Tailwind, Firebase Web SDK; Vercel hosting; `api/verify.ts` serverless endpoint |
| Firebase | Auth (email OTP + Google), Firestore, Cloud Storage, Cloud Functions (`default` codebase in `nextbench-1/functions`; separate `android-push` codebase in this repo's `functions-android/`), FCM |
| Media | Cloudinary for general media (posts, products, chat attachments, avatars, voice, files); **private Firebase Storage** for verification evidence and organization registration documents |

### Architectural boundaries (do not cross)

- **UI/screens** (`app/`) — Compose only. No direct Firebase SDK calls; screens talk to ViewModels.
- **Repositories** (`data/firebase/`) — the only layer that touches Firebase; all backend access is
  behind these abstractions, injected via Hilt (`Provider<...>` getters to avoid eager init).
- **Models** (`data/model/`) — plain Kotlin, mirrors the Firestore schema; no Android dependencies.
- **`functions-android/`** — an isolated Firebase Functions `android-push` codebase (Node/TS); not
  the main backend. The main backend lives in `nextbench-1/functions`.
- **Backend contract parity** — field names and callable request/response shapes must match the
  website and `firestore.rules` exactly. The website repo is the source of truth for product
  behavior and contracts (see `AGENTS.md` §2 for the decision precedence).

## B. Current engineering status

The requested engineering work has been **implemented and tested**. Unit-test, lint, and build
gates pass in both repositories (exact numbers in §F). Nothing here is deployed to production;
the deployment steps and their prerequisites are in
`docs/deployment/release-runbook.md`.

## C. Completed work

- [x] **Account deletion** — server-authoritative callable, typed confirmation, recent-sign-in
      gate with a completable re-auth flow on both clients, full backend cascade
      (Firestore + Storage + Auth), deletion-state document server-owned.
- [x] **Reporting** — in-app report sheet + repository + state tests; server-side report records.
- [x] **Blocking** — block repository, profile/chat/club enforcement, block-consistent
      notifications, club transcript filtering (client-side; rules cannot filter — see
      `docs/firestore-club-message-block-enforcement.md`).
- [x] **Verification failure handling** — fail-closed status parsing, per-HTTP-class user copy,
      transport-failure handling, no false "submitted" states.
- [x] **Privacy / presence** — hide-last-seen, chat privacy toggle, owner-only presence writes.
- [x] **Block-aware notifications** — `functions-android` filtering with tests.
- [x] **Identity document hardening** — ID card + selfie moved off the public profile document to
      owner-only Storage `identity/{uid}/**` + owner-only pointer
      `users/{uid}/private/verification`; admin access via short-lived signed URLs; legacy
      migration script; see `docs/security/identity-document-exposure.md` and
      `docs/security/identity-asset-hardening-plan.md`.
- [x] **Organization document hardening** — registration certificates (GSTIN/UDISE/…) moved off
      the public Cloudinary URL on `users/{uid}` to owner-only Storage
      `organization/{uid}/**` + pointer `users/{uid}/private/organization`; admin access via the
      `getOrganizationDocumentUrl` callable (5-minute signed URL); account deletion removes the
      objects; idempotent migration + separately scoped purge;
      see `docs/security/organization-document-hardening.md`.
- [x] **Club consistency** — block-aware club messages, role-scoped club update rules.
- [x] **Tests/rules/functions** — Android unit tests, Firestore rules suite, Storage rules suite,
      website unit suite, functions build, push-function tests (numbers in §F).

## D. Known external / deployment-only work

These are **not** unfinished application engineering. They require startup credentials, console
access, or product decisions:

| Item | Blocked on |
| --- | --- |
| Firebase deploys (Storage rules → Functions → Firestore rules) | Firebase CLI login for `nextbench-a11ed`; runbook §4 STEP 1–4 |
| Vercel deploy (website + `api/verify.ts`) | Vercel access; STEP 3 |
| Legacy migrations (identity + organization documents) | Admin credentials (`GOOGLE_APPLICATION_CREDENTIALS` or service-account env); runbook STEP 5–6 |
| Cloudinary purge of pre-migration public originals | `CLOUDINARY_CLOUD_NAME`/`API_KEY`/`API_SECRET`; runbook STEP 7 (irreversible) |
| `FIREBASE_STORAGE_BUCKET` confirmation for Functions + Vercel | Firebase/Vercel console; runbook §2 preconditions |
| Release signing / AAB / Play upload | Keystore + Play Console access |
| Play Console Data Safety answers | Play Console; inventory prepared in `docs/play/data-safety-inventory.md` |
| Public privacy policy + account-deletion URLs | Domain/production deploy; routes exist (`/privacy`, `/delete-account`) |
| Reviewer/demo account | Product decision; no bypass was built |

## E. Security and data handling notes

Key architecture decisions a maintainer must not undo casually:

- **Evidence never lives on publicly readable documents.** `users/{uid}` is readable by any
  signed-in non-blocked user, so ID cards, selfies, and organization registration documents are
  stored as **private Storage objects** with **owner-only pointer documents**:
  - identity: `identity/{uid}/**` + `users/{uid}/private/verification`
  - organization: `organization/{uid}/**` + `users/{uid}/private/organization`
- **Owner/admin access model.** Owners read/write their own evidence and pointers. Admins never get
  a rules carve-out on the pointers; they go through admin-only callables that mint **5-minute
  signed URLs** (`getVerificationAssetUrl`, `getOrganizationDocumentUrl`). No public URL for
  evidence is ever created or persisted.
- **No server secrets in clients.** Uploads use the storage rules for authorization; admin
  functions use the Admin SDK server-side. Cloudinary keys are server-side only.
- **Account deletion removes evidence** — `userStoragePrefixes(uid)` =
  `['identity/{uid}/', 'organization/{uid}/']` are deleted by the server-authoritative cascade.
- **Third-party verification processing.** `api/verify.ts` sends verification images to Google
  Gemini (via Hack Club proxy or the Generative Language API). This must be disclosed in the
  privacy policy and Play Data Safety.
- **Legacy migration/purge still pending** (deployment step, not engineering): both one-time
  migrations are written and tested but have never run against the live project; the Cloudinary
  purges are separately scoped (`ids/` for identity, `org_documents/` for organization) and must be
  run last. See `docs/deployment/release-runbook.md` §4 STEP 5–7.

## F. Testing status

Latest results from this working tree (all pass):

| Suite | Command | Result |
| --- | --- | --- |
| Android unit tests | `./gradlew :data:firebase:testDebugUnitTest :app:testDebugUnitTest` | 295 tests / 52 classes (data:firebase 156/23, data:model 5/3, app 134/26), 0 failures |
| Android lint | `./gradlew :app:lintDebug` | 0 errors |
| Android build | `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| Push functions | `npm test --prefix functions-android` | 11/11 |
| Website unit | `npm --prefix tests run test:unit` (in `nextbench-1`) | 111/111 |
| Firestore rules | `npm --prefix tests test` (in `nextbench-1`) | 74/74 |
| Storage rules | `npm --prefix tests run test:storage` (in `nextbench-1`) | 14/14 |
| Website typecheck | `npm run lint` (in `nextbench-1`) | clean |

Build environment: `export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"; export ANDROID_SDK_ROOT="$ANDROID_HOME"`.

## G. Known remaining caveats

- Nothing is deployed; the exposure the hardening fixes is still live in production until the
  rules + migrations + purges run (documented in the runbook and security docs).
- `FIREBASE_STORAGE_BUCKET` naming (`<project>.appspot.com` vs `<project>.firebasestorage.app`)
  can silently redirect evidence uploads to the wrong bucket if unset — runbook §2 item 4.
- Old clients (cached web bundle / old APK) cannot submit verification after the Firestore rules
  deploy, and cannot create organization profiles (they still write the now-denied
  `orgDocumentUrl`); plan the release window accordingly.
- `functions/lib` is committed build output in `nextbench-1` (pre-existing repo convention); its
  `predeploy` rebuilds it, but keep it regenerated when `functions/src` changes.
- No live Cloudinary purge has ever been executed (no credentials locally); the purge scripts are
  unit-tested only.

## H. What a future AI coding agent should read first

1. `AGENTS.md` — module ownership, secrets policy, error-copy rules, architecture constraints.
2. `docs/HANDOFF.md` (this file) — what is done, what is deployment-only.
3. `docs/deployment/release-runbook.md` — deployment order, migration/purge commands, rollback.
4. `docs/security/organization-document-hardening.md` and
   `docs/security/identity-document-exposure.md` — the security model and its rationale.
5. `docs/backend/account-deletion-implementation.md` — the deletion cascade contract.
6. `docs/play/data-safety-inventory.md` — what must be true before Play answers are submitted.
7. `docs/phone-smoke-test.md` — release smoke test + crash capture procedure.
8. Only then inspect code for a specific task.

## I. Instructions for the future AI coding agent

- **Do not repeat the completed audit** unless a new concrete issue is reported. The security
  reviews in `docs/security/` are done; re-litigating them wastes the team's time.
- **Do not redesign completed architecture** (private evidence storage, pointer model, deletion
  cascade, block/report flows) without concrete evidence — a failing test, a build error, or a
  reproduced crash — that something is actually wrong.
- **Preserve backend contract parity.** If you rename a Firestore field, a callable argument, or a
  Storage path, you must update both clients, the rules, and the functions in the same change — or
  not do it.
- **Follow existing repository patterns** (MVVM/UDF, repositories as the only Firebase access,
  error copy in the app layer, expected states per screen).
- **Run targeted tests before and after changing unrelated code**; the suites in §F are the gate.
- **Distinguish deployment/configuration work from application engineering.** Missing credentials,
  an undeployed ruleset, or an unrun migration is not a code bug; do not "fix" it in code.

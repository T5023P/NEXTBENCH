# NextBench deployment runbook — identity + organization-document hardening, account deletion, block consistency

Status: **not deployed**. This is a pre-deployment review of the uncommitted engineering work
in both repositories. Every command below is written out but **none has been run**.

| Repository | Path | Project |
| --- | --- | --- |
| Android client | `/f/nextbench` (branch `main`) | `nextbench-a11ed` |
| Website + backend + Firestore/Storage rules + `default` Cloud Functions | `/f/nextbench-1` (cloned during this pass) | `nextbench-a11ed` |

Both repos are dirty and **uncommitted** by design. Nothing here should be deployed until the
review items in [§6 Findings](#6-findings-that-affect-deployment) are accepted.

---

## 1. What is being deployed

| Change | Repo | Kind |
| --- | --- | --- |
| `identity/{uid}/**` private Storage rules | `/f/nextbench-1` | Storage rules (additive) |
| `users/{uid}` can no longer carry `idCardUrl`/`selfieUrl`; `deletionRequests/{uid}` added | `/f/nextbench-1` | Firestore rules (tightening) |
| `requestAccountDeletion`, `getVerificationAssetUrl` | `/f/nextbench-1` | Cloud Functions (`default` codebase) |
| Private-Storage upload + pointer, `/delete-account` page, admin signed URLs | `/f/nextbench-1` | Website (Vercel) + `api/verify.ts` |
| Path-based review contract (private object paths instead of public URLs) | `/f/nextbench-1` | `api/verify.ts` (same Vercel deploy) |
| Legacy identity migration + Cloudinary originals purge | `/f/nextbench-1` | one-off scripts, run locally |
| `organization/{uid}/**` private Storage rules for registration documents | `/f/nextbench-1` | Storage rules (additive) |
| `users/{uid}` can no longer carry `orgDocumentUrl` (create **and** update) | `/f/nextbench-1` | Firestore rules (tightening) |
| `getOrganizationDocumentUrl`; organization objects added to the deletion cascade | `/f/nextbench-1` | Cloud Functions (`default` codebase) |
| Private-Storage organization signup upload + pointer, admin signed-URL open | `/f/nextbench-1` | Website (Vercel) |
| Legacy organization-document migration + Cloudinary originals purge | `/f/nextbench-1` | one-off scripts, run locally |
| Club block filtering in shared transcripts | `/f/nextbench-1` | website |
| Storage/pointer upload for organization documents, deletion callable, club-message block filter | `/f/nextbench` | Android app release |

## 2. Preconditions

1. Firebase CLI logged in on the account that owns `nextbench-a11ed`, with permission to deploy
   rules and functions: `firebase login` (check with `firebase projects:list`).
2. A terminal where `firebase deploy` is run from the **correct repo root** — both repos contain a
   `firebase.json` and both target `nextbench-a11ed`, so the working directory decides what ships.
3. **Copy the currently deployed rulesets to files before changing anything.** They are the only
   reliable rollback artefact:
   - Firebase Console → Firestore Database → Rules → copy the published ruleset →
     `firestore.rules.deployed-backup`
   - Firebase Console → Storage → Rules → copy → `storage.rules.deployed-backup`
   These are needed because the repo `storage.rules` warns it used to be console-managed
   (see [§6.5](#65-storage-rules-console-drift)), and because a console edit made after the last
   repo commit would be silently reverted by the next deploy.
4. Functions runtime environment variables must be set **before** the functions deploy, since the
   new code reads them at runtime:
   - `FIREBASE_STORAGE_BUCKET` — the bucket clients actually upload to. Must match the website's
     `VITE_FIREBASE_STORAGE_BUCKET` and the Android client's Storage bucket. If it is unset the
     Admin SDK targets the default `<project>.appspot.com` bucket, which is a *different bucket*
     on projects using the newer `<project>.firebasestorage.app` naming, so identity uploads,
     deletion cleanup and admin signed URLs would silently operate on the wrong bucket.
     Set it in the Functions environment and in Vercel (used by `api/verify.ts`).
   - Pre-existing and already required: `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`,
     `FIREBASE_PRIVATE_KEY`, `GEMINI_API_KEY`/`HACKCLUB_API_KEY`.
5. Local-only (migration, never committed): `GOOGLE_APPLICATION_CREDENTIALS` (or
   `FIREBASE_CLIENT_EMAIL` + `FIREBASE_PRIVATE_KEY`) and, for the purge step,
   `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` — all three are already
   documented as server-side values in the website `.env.example`.
6. Local toolchain verified in this pass: Node v24.19.0, Java 21, Gradle wrapper 8.9, Android SDK
   via `ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`.

## 3. Why this order

- **Storage rules before the website**: the new client uploads to `identity/**`. That rule does not
  exist in production yet, so a website deploy first would have every submission fail.
- **Functions before the Android release**: the app calls `requestAccountDeletion`, which does not
  exist until this deploy (a call to a missing callable fails with `NOT_FOUND`).
- **Website + `api/verify.ts` before the Android release**: the new app sends `idCardPath` /
  `selfiePath`. The currently deployed endpoint only understands `idCardUrl` / `selfieUrl`, so a
  new APK against the old endpoint would 400. The reverse direction is safe: the new endpoint still
  accepts legacy URLs for one release.
- **Firestore rules after the website**: the rules stop clients from writing `idCardUrl`/`selfieUrl`.
  The currently deployed website does write those fields on submit, so tightening first would break
  live verification submissions for the duration of the gap.
- **Migration after the rules**: rules cannot block the migration. It runs with the Admin SDK,
  which bypasses Firestore rules entirely, so it can also be run later without re-deploying.
  Migration is placed after the website deploy so new submissions are already writing pointers.
- **Cloudinary purge last within the identity work**: it is irreversible, and it is the step that
  actually removes the public exposure.
- **Organization documents ride along with the identity steps**, in the same order and for the same
  reasons: the org Storage rule (STEP 1) must exist before the website uploads to it (STEP 3), the
  org signup bundle must be live before the rules stop clients from writing `orgDocumentUrl`
  (STEP 4), and the migration (STEP 5) and purge (STEP 7) come last. The one extra constraint:
  the org rules deploy denies `orgDocumentUrl` on **create** as well as update, so an old cached
  bundle (or an APK older than STEP 8) can no longer register an organization at all until it is
  updated — see [§6.7](#67-clients-that-are-not-updated-yet).

## 4. Steps

### STEP 1 — Storage rules (additive, lowest risk)

- **Command**
  ```bash
  cd /f/nextbench-1
  firebase deploy --only storage
  ```
- **Repository**: `/f/nextbench-1`
- **Expected result**: `Storage Rules ... Deployed`. The new `identity/{userId}/{file}` and
  `organization/{userId}/{file}` blocks are owner-only (the latter accepts an image content type or
  `application/pdf`, <15 MB); `stories/**`, `voice/**` and `nextbench/**` are unchanged.
- **Verify**: in the console Rules tab, the deployed ruleset contains the `identity/` match, and
  the previously deployed grants are still present (diff against `storage.rules.deployed-backup`).
- **Rollback**: `firebase deploy --only storage` after restoring
  `storage.rules.deployed-backup` over `storage.rules` (no client depends on the new block yet).

### STEP 2 — Cloud Functions (`default` codebase)

- **Command**
  ```bash
  cd /f/nextbench-1/functions && npm run build        # tsc -> lib/, verified clean in this pass
  cd /f/nextbench-1
  firebase deploy --only functions:default
  ```
- **Repository**: `/f/nextbench-1`
- **Expected result**: `requestAccountDeletion`, `getVerificationAssetUrl` and
  `getOrganizationDocumentUrl` are created; existing functions are updated. The deletion cascade now
  removes both `identity/{uid}/**` and `organization/{uid}/**` (see `userStoragePrefixes`). `predeploy` in `firebase.json` rebuilds `functions/lib` automatically, so
  the committed `lib/` staleness cannot ship (see [§6.4](#64-committed-functionslib-was-stale)).
- **Verify**: `firebase functions:list | grep -E "requestAccountDeletion|getVerificationAssetUrl|getOrganizationDocumentUrl"`;
  then call `requestAccountDeletion` from a throwaway account with a wrong confirmation and confirm
  it returns `invalid-argument` (proves the gate works without deleting anything).
- **Do not** run `firebase deploy --only functions` from `/f/nextbench` at this stage — that deploys
  the separate `android-push` codebase (STEP 9).
- **Rollback**: `git checkout -- functions/src functions/lib` on the previous revision, rebuild, and
  redeploy. Deleting an unintended function is also possible from the console. The new callables are
  additive, so leaving them deployed is harmless.

### STEP 3 — Website + `api/verify.ts` (Vercel)

- **Command**: deploy a **preview** build first, verify, then promote to production through the
  normal Vercel flow (the repo has no `vercel.json` build override; `api/` is bundled by
  `@vercel/node`).
- **Repository**: `/f/nextbench-1`
- **Expected result**: `/delete-account` resolves to the real page instead of the SPA catch-all, and
  `/api/verify` accepts the path contract.
- **Verify (on the preview URL)**
  1. `curl -s -o /dev/null -w "%{http_code}" https://<preview>/delete-account` → `200` **and** the
     served HTML is the app shell (the route is client-side), while `/delete-account` in the SPA
     renders the delete page rather than the 404/redirect path.
  2. Sign in on the preview with a non-production test account, submit a verification with the new
     flow, and confirm the object lands at `identity/{uid}/...` in Storage and that a pointer exists
     at `users/{uid}/private/verification`.
  3. Confirm `POST /api/verify` with `idCardPath`/`selfiePath` reaches the review step (a 400 with
     "Missing required parameters" means the deployed function is still the old bundle).
  4. Register a throwaway organization on the preview and confirm the object lands at
     `organization/{uid}/document-<submissionId>.<ext>`, the pointer exists at
     `users/{uid}/private/organization`, and `users/{uid}` carries **no** `orgDocumentUrl`. Then open
     it from the admin **Org Verifications** tab and confirm it renders through the signed URL.
- **Rollback**: Vercel instant rollback to the previous deployment. Because STEP 1 is already live,
  the previous website build (Cloudinary uploads) still satisfies its own rules.
- **Note**: the new bundle imports `functions/src/identityPaths` from `api/verify.ts`. `tsc --noEmit`
  on `api/verify.ts` passes locally, and Vercel bundles `api/` with esbuild (which follows the
  relative import), but this import crosses outside `api/` — treat the preview build as the proof
  (see [§6.3](#63-the-new-shared-import-in-apiverifyts-is-outside-tsconfig-include)).

### STEP 4 — Firestore rules (tightening)

- **Command**
  ```bash
  cd /f/nextbench-1
  firebase deploy --only firestore:rules
  ```
- **Repository**: `/f/nextbench-1`
- **Expected result**: `users/{uid}` self-updates can no longer touch `idCardUrl`/`selfieUrl` or
  `orgDocumentUrl`; the new `deletionRequests/{uid}` block is server-owned.
- **Verified in this pass**: the diff removes only the two legacy URL validations and the
  `orgDocumentUrl` validation from `isValidUser`, removes those keys from the update allowlist, adds
  `!incoming().keys().hasAny(['orgDocumentUrl'])` to **create**, and adds the `deletionRequests`
  block. No existing branch was removed, so presence updates and profile edits are unaffected. The
  site's signup path writes `idCardUrl: null` on **create** (`!docSnap.exists()` → `batch.set`), and
  `create` still accepts that field, so student/org signup is not broken by this deploy — but note
  the deliberate asymmetry: `orgDocumentUrl` is now denied on create, which is what stops a client
  from planting a public registration-document URL on its own profile (see §6.7 for the old-client
  consequence).
- **Verify**: as a normal signed-in user, submit a verification on production and confirm the
  pointer write succeeds and no `idCardUrl` appears on `users/{uid}`. Then register an organization
  and confirm no `orgDocumentUrl` appears either. Then confirm the rules suites locally:
  `npm --prefix tests test` (74/74 in this pass).
- **Rollback**: redeploy `firestore.rules.deployed-backup`. This is the highest-consequence rollback
  because the tightened rules are the security boundary — rolling back re-opens client writes of
  identity fields, so roll back only to fix a functional regression.

### STEP 5 — Migration (dry run, then real)

- **Command**
  ```bash
  cd /f/nextbench-1
  node scripts/migrate-identity-assets.mjs --project nextbench-a11ed --dry-run --verbose
  node scripts/migrate-identity-assets.mjs --project nextbench-a11ed
  ```
- **Repository**: `/f/nextbench-1` (script), writes to Storage + Firestore in `nextbench-a11ed`
- **Expected result**: a per-user line for every legacy record, a final stats object, and an
  `identity-migration-report-<timestamp>.json` **which must not be committed** (it lists legacy
  identity URLs).
- **Safety properties (tested in `tests/identityMigration.test.ts`, 16 tests)**
  - Target selection unions `idCardUrl != null` **and** `selfieUrl != null`. A single
    `idCardUrl` query permanently misses selfie-only users — verified against the Firestore
    emulator: `idCardUrl != null → [u1,u3]`, `selfieUrl != null → [u2,u3,u5]`.
  - Rerun-safe: a user whose evidence is already private but whose public document still carries
    URLs (crash between the pointer write and the field clear) is repaired on the next run without
    re-uploading.
  - Evidence is never destroyed: public fields are cleared only after the object upload **and** the
    pointer write both succeeded.
  - Failures are per-user, reported, and leave that user's legacy fields in place for a retry.
- **Organization registration documents (same sitting, separate script and report)**
  ```bash
  cd /f/nextbench-1
  node scripts/migrate-organization-documents.mjs --project nextbench-a11ed --dry-run --verbose
  node scripts/migrate-organization-documents.mjs --project nextbench-a11ed
  ```
  Writes `organization/{uid}/document-{submissionId}.{ext}` in Storage (content type taken from the
  legacy URL, so a PDF stays a PDF), writes the owner-only pointer
  `users/{uid}/private/organization`, then clears `orgDocumentUrl`, and emits
  `organization-migration-report-<timestamp>.json` — also **must not be committed**. The safety
  properties are the same and are covered by `tests/organizationMigration.test.ts` (22 tests), with
  two differences worth knowing: a profile carries at most one registration document, so target
  selection is a single `orgDocumentUrl != null` query (no two-field union needed), and the rerun
  path additionally refuses to clear the field unless the private object the pointer names exists in
  Storage — otherwise clearing it would delete the last reference to a certificate the review team
  still needs.
- **Verify**: re-run the dry run — candidates should now be `0`. Optionally spot-check one migrated
  user in the console (`users/{uid}/private/verification` present, `idCardUrl` absent) and one
  organization (`users/{uid}/private/organization` present, `orgDocumentUrl` absent).
- **Rollback**: not needed for correctness — the migration is additive (objects uploaded, pointer
  written) and only removes *references* to the public URLs. If a user's evidence must be
  re-exposed for a support case, the pointer's `idCardPath`/`selfiePath` identify the private
  objects; the public originals still exist until STEP 7.

### STEP 6 — Confirm the public exposure is actually gone from the database

- **Command** (Firestore console query, or)
  ```bash
  node scripts/migrate-identity-assets.mjs --project nextbench-a11ed --dry-run
  ```
- Organization documents:
  ```bash
  node scripts/migrate-organization-documents.mjs --project nextbench-a11ed --dry-run
  ```
- **Expected result**: `0 candidate user(s)` and `0 candidate organization(s)`.
- **Rollback**: none (read-only).

### STEP 7 — Purge the legacy Cloudinary originals (irreversible)

Clearing the Firestore fields does **not** un-publish the assets. Anyone holding the old URL can
still download the ID card or selfie until the Cloudinary objects are destroyed, so this step is
what closes the exposure.

- **Command**
  ```bash
  cd /f/nextbench-1
  node scripts/migrate-identity-assets.mjs --purge-originals --from-report "<report>.json" --dry-run
  node scripts/migrate-identity-assets.mjs --purge-originals --from-report "<report>.json"
  ```
- **Expected result**: one `destroyed <public_id> (ok)` line per asset and a final
  `purge-cloudinary-originals: done { purged, skipped, failed }`. The request is a signed
  `POST .../image/destroy` with `invalidate=true`, which also purges cached CDN copies (deleting the
  asset without `invalidate` leaves the CDN serving it for up to 30 days).
- **Organization registration documents (same two commands, org script and org report)**
  ```bash
  cd /f/nextbench-1
  node scripts/migrate-organization-documents.mjs --purge-originals --from-report "<org report>.json" --dry-run
  node scripts/migrate-organization-documents.mjs --purge-originals --from-report "<org report>.json"
  ```
  Use the **organization** report and script: the two purges have mutually exclusive folder guards,
  so the identity run cannot destroy a registration certificate and the organization run cannot
  destroy an ID card or selfie.
- **Safety**: only records whose evidence is confirmed private are eligible, and only public ids
  matching this project's identity folder (`ids/<name>` or `<preset-folder>/ids/<name>`) are
  destroyed — `products/`, `profiles/`, `clubs/`, `org_documents/` and every other folder are
  refused. The organization purge mirrors this with `org_documents/<name>` (optionally under a preset
  folder), so `ids/` and `school_requests/` are refused in turn.
- **NOT VERIFIED**: the signing and the folder match were written against Cloudinary's documented
  destroy API and unit tested (`cloudinarySignature`, `isPurgeablePublicId`), but no live Cloudinary
  credentials were available, so no request has ever been executed. Run the `--dry-run` first and
  then purge **one** user's asset, confirm `result: ok` in the response and that the old delivery URL
  now 404s, before running the rest.
- **If credentials are unavailable**: delete `ids/**` from the Cloudinary console (Media Library →
  folder `ids`, i.e. `nextbench/ids`) and use its "invalidate" option, then do the same for
  `org_documents/**` (`nextbench/org_documents`). Record which assets were removed. Correcting an
  earlier note in this runbook: the legacy **verification** uploads are the ones under `ids/`;
  school-request ID cards are under `nextbench/school_requests/` (see
  [§6.2](#62-school_requestsidcardurl--same-class-of-exposure-different-scope-unchanged)).
- **Rollback**: none. The bytes remain in private Storage and the pointer points at them, so
  verification reviews continue to work; only the public copy is gone.

### STEP 8 — Android release

- **Command**
  ```bash
  cd /f/nextbench
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"; export ANDROID_SDK_ROOT="$ANDROID_HOME"
  ./gradlew :data:firebase:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
  # then the normal signed release/AAB + Play upload flow (still outstanding, see §7)
  ```
- **Repository**: `/f/nextbench`
- **Expected result**: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`.
- **Depends on**: STEP 1 (identity uploads), STEP 2 (`requestAccountDeletion`), STEP 3 (path-based
  `api/verify`). Shipping before those would break verification and account deletion.
- **Recent-sign-in handling** (see [§6.1](#61-account-deletion-needs-a-fresh-sign-in--now-handled-not-a-dead-end-closed)):
  a user who has been signed in longer than 5 minutes must sign in again before the deletion is
  accepted. The app explains this and returns them to the confirmation, so the flow completes
  rather than dead-ends. Verified on a fresh install during post-deploy checks (STEP 10).
- **Rollback**: halt the staged rollout in Play Console; the previous APK keeps working against the
  tightened rules (it never writes identity fields except at submit time).

### STEP 9 — Android push functions (only if `functions-android` changed)

- **Command**
  ```bash
  cd /f/nextbench
  npm test --prefix functions-android
  firebase deploy --only functions:android-push
  ```
- **Repository**: `/f/nextbench` (separate `android-push` codebase, same project)
- **Expected result**: 11/11 function tests pass, then the block-aware push filtering is live.
- **Rollback**: redeploy the previous revision; the codebase is isolated from `default`, so this
  cannot affect the website functions.

### STEP 10 — Post-deploy verification

- [ ] `identity.rules.test.mjs` + `organization.rules.test.mjs` (74) and
      `identityStorage.rules.test.mjs` + `organizationStorage.rules.test.mjs` (14) pass locally.
- [ ] A third signed-in account **cannot** download another user's `identity/**` object (attempt a
      direct Storage `getDownloadURL`/GET and expect denial).
- [ ] `users/{uid}` for a fresh signup contains no `idCardUrl`/`selfieUrl`.
- [ ] A new verification submission writes the pointer, and the review runs end to end.
- [ ] A third signed-in account **cannot** download another organization's
      `organization/**` object, and `users/{uid}` for a fresh organization signup contains no
      `orgDocumentUrl`.
- [ ] The admin **Org Verifications** tab opens a registration document through the signed URL
      (and the URL stops working after ~5 minutes).
- [ ] Account deletion on a throwaway account: callable returns `completed`, `users/{uid}`,
      `users/{uid}/private/**`, `identity/{uid}/**` and push tokens are gone, and the Auth record is
      deleted last.
- [ ] The recent-sign-in path, on a session older than 5 minutes, on **both** clients: the user is
      told to sign in again, is returned to the deletion request afterwards, confirms once more, and
      only then is the account deleted. Also confirm that backing out at the sign-in screen deletes
      nothing and that a second tap during the request sends no second request.
- [ ] Blocking a club member hides their club messages in the website transcript and the app.
- [ ] Blocked senders produce no push notification for the blocker.

## 5. Rollback summary

| Step | Rollback | Cost |
| --- | --- | --- |
| 1 Storage rules | redeploy prior ruleset | low |
| 2 Functions | redeploy prior revision | low (additive) |
| 3 Website | Vercel instant rollback | low |
| 4 Firestore rules | redeploy prior ruleset | **re-opens identity/org-document field writes** |
| 5 Migration | none required (additive) | — (identity **and** organization documents) |
| 7 Purge | **irreversible** | public copy only; private copy retained |
| 8 Android | halt staged rollout | low |

## 6. Findings that affect deployment

### 6.1 Account deletion needs a fresh sign-in — now handled, not a dead end (closed)

`requestAccountDeletion` requires `auth_time` within 5 minutes, which is the correct guard for an
irreversible action. But `auth_time` is the *original* sign-in time — a forced ID-token refresh
(`getIdToken(true)`) does **not** update it, and no silent refresh does either; that is what makes it
usable as a recent-auth signal. Since this product signs in with email OTP / custom tokens (and
Google), there is no silent reauthentication available.

That used to be a dead end on both clients. It is now an explicit, completable flow:

1. The client checks the sign-in age before spending a request the callable would refuse
   (`deletionSessionIsStale`, mirroring the callable's window), and still handles the server's
   `failed-precondition` if the two ever disagree — the server stays authoritative either way.
2. On refusal the user is told *why* and the session is cleared, so the existing auth flow takes
   over: the web page routes to `/login?next=%2Fdelete-account%3Freauth%3D1`, and on Android the
   session gate moves the user to the sign-in screen (which shows a "Deleting your account" notice).
3. The intended deletion is not lost: after signing in again the user is returned to the deletion
   request (Android reopens the confirmation sheet; the web page shows a "signed in again" banner).
4. The typed `DELETE` confirmation is required once more and sent again. Nothing is retried
automatically, one request may be in flight at a time, and abandoning the sheet or the sign-in
   screen clears the intent.

The gate itself was not weakened, `auth_time` is never faked, and no second authentication system
was introduced.

### 6.2 `school_requests.idCardUrl` — same class of exposure, different scope (unchanged)

- Read path: `school_requests/{id}` is `allow read: if isAdmin()`, so metadata is **not** readable by
  normal users — unlike the verification case.
- Write path: `allow create` is **unauthenticated** (deliberately, because it happens during signup),
  and the ID card goes through the same **unsigned Cloudinary preset**, so the asset is a permanent
  public delivery URL with no authentication on the object itself. Anyone who obtains or retains the
  URL can download the student ID.
- **Correction (this pass) — the folder recorded here was wrong.** Earlier revisions of this runbook
  stated that `uploadSchoolIdCard` uploads to `folder: 'ids'`. It does not. `src/lib/storage.ts`:
  ```ts
  export async function uploadSchoolIdCard(file: File): Promise<string> {
    const randomId = Math.random().toString(36).substring(2, 15);
    return uploadToCloudinary(file, `nextbench/school_requests/${randomId}`);
  }
  ```
  So school-request ID cards live under `nextbench/school_requests/<randomId>`; only the **unsigned
  preset** is shared with the legacy verification uploads, not the path. This matters in both
  directions and is why the correction is worth making before anyone acts on it: an `ids/**` purge
  could never have cleaned these assets up, and — equally important — the identity purge's folder
  guard means it will never touch them, so there is no cross-contamination risk either way.
- Relationship to the original identity/security requirement: this is a *school-request* artefact
  collected at signup for onboarding, not verification evidence, and it was not part of the
  verification-evidence hardening described in `docs/security/identity-document-exposure.md`. It is
  therefore **out of the scope that was implemented**, but it is the same underlying pattern
  (identity document → unsigned upload → permanent public URL) and the same class of risk.

**Consequence if left as-is**: student ID cards collected for school requests remain publicly
fetchable by URL, and the collection accepts unauthenticated creates (spam/abuse vector).

**If it should be brought into scope** (a product decision, not a code decision): move it to the same
private-Storage + admin-signed-URL path, require authentication (or an OTP check) on create, and
purge/migrate existing `nextbench/school_requests/**` assets. The purge can be scoped to that folder
and nothing else: unlike the `ids/` prefix it is not shared with any other uploader, so no
"careful" overlap analysis is needed — a folder-scoped guard (the same shape as
`isPurgeableOrganizationPublicId`) would be exact.

### 6.3 The new shared import in `api/verify.ts` is outside `tsconfig.include`

`api/verify.ts` now imports `../functions/src/identityPaths`, and the root `tsconfig.json` only
includes `src`, so `npm run lint` (`tsc --noEmit`) never covered it. It was typechecked explicitly
with an equivalent invocation and passes; Vercel's `@vercel/node` bundles `api/` with esbuild, which
follows the relative import. Still: **treat the preview deployment in STEP 3 as the verification**,
and if the preview build fails, either inline the helper or move it into a path included by
`tsconfig.json`.

### 6.4 Committed `functions/lib` was stale

`functions/package.json` `main` is `lib/index.js`, and `lib/` is committed. Before this pass, `lib/`
contained no `identityPaths.js`, no `accountDeletion.js` and no `linkPreview.js`, and `lib/index.js`
had none of the new exports — the committed build output predates the current `src`. `firebase.json`
runs `npm --prefix "$RESOURCE_DIR" run build` as `predeploy`, and a real `tsc` run now emits
`lib/index.js`, `lib/identityPaths.js`, `lib/accountDeletion.js`, so a normal `firebase deploy`
ships correct code. Two residual points:

- Never deploy functions with `--force` or from the console in a way that skips `predeploy`.
- `functions/lib/src/index.js` is a leftover from the old `rootDir` layout and is uploaded as dead
  weight; it is not `main`, so it cannot be executed. Removing it is safe but out of scope here.

### 6.5 Storage rules console drift

`storage.rules` carries a standing warning that it was previously managed in the Firebase console,
and the deploy-time risk is that the repo file is missing a grant some client depends on — the
default deny would then break that upload. Every Firebase Storage path used by either client was
enumerated in this pass — website: `voice/{chatId}/{file}`, `stories/{authorId}/{storyId}/{file}`,
`identity/{uid}/...`, `nextbench/**`; Android: `voice/**`, `stories/**`, `identity/**` — and all are
covered. Do the console diff in STEP 1 anyway, before deploying.

### 6.6 Rules cannot filter club transcripts (already handled client-side)

A `list` rule referencing `resource.data.senderId` would deny every club-message query for everyone
("rules are not filters"), so block enforcement for club transcripts lives in the read paths
(website transcript derivation and Android `ClubRepository.observeMessages`) plus the block-aware
push function, not in a rule. See `docs/firestore-club-message-block-enforcement.md`. No rule change
is required or possible here.

### 6.7 Clients that are not updated yet

Between the Firestore rules deploy and users updating the app, an **old** APK cannot submit
verification at all (its writes of `idCardUrl`/`selfieUrl` are now denied), and a browser with a
cached old bundle behaves the same until it reloads. Old clients can still read their own profile
and everything else. If a build is already installed on user devices, plan the release around this
window; if not, it is moot.

Organization signup is affected too, and more sharply: `create` now denies `orgDocumentUrl`, which
both old clients still write, so an old bundle or an APK older than STEP 8 cannot create an
organization profile at all. Sign-in, browsing, chat, marketplace, and student signup are
unaffected. Nobody could register an organization twice, so this is a "wait for the update" window,
not silent data loss — but say so in the release notes if a build is already installed.

### 6.8 Organization registration documents were on the same public document (fixed in this pass)

`users/{uid}.orgDocumentUrl` held a permanent public Cloudinary URL to an organization's
registration certificate (GSTIN / UDISE / society registration). Because `canAccessUser(userId)`
admits any signed-in, non-blocked user — the same rule quoted in
`docs/security/identity-document-exposure.md` — every organization's registration document was
readable by every other user: the same architectural defect as the identity finding, on a document
the platform is obligated to collect, protect and erase with the account.

It now follows the identity model exactly: bytes at `organization/{uid}/**` (owner-only per
`storage.rules`), an owner-only pointer at `users/{uid}/private/organization`, admin access only
through the `getOrganizationDocumentUrl` callable (5-minute signed URL), account deletion removes
the objects, and the profile document can no longer carry the field on **create or update**. The
exact file list, the old → new flow and the test coverage are in
`docs/security/organization-document-hardening.md`.

## 7. External actions still required (not source-controlled)

| Item | Blocked on |
| --- | --- |
| Deploying any of the above | Firebase/Vercel credentials and the go-ahead |
| Release signing + AAB + Play upload | keystore, Play Console access |
| Data Safety form submission | Play Console; inventory prepared in `docs/play/data-safety-inventory.md` |
| Privacy policy / deletion URL listing in Play | console; routes exist (`/privacy`, `/delete-account`) |
| Reviewer/demo access | product decision; no bypass built |
| Purge verification **and organization documents** against live Cloudinary | Cloudinary API key/secret |
| `FIREBASE_STORAGE_BUCKET` confirmation for Functions + Vercel | project console / dashboard |

## 8. Local verification performed in this pass

| Suite | Command | Result |
| --- | --- | --- |
| Website/backend unit | `npm --prefix tests run test:unit` | 111/111 pass (78 before the organization work; +33 organization tests) |
| Firestore rules (emulator) | `npm --prefix tests test` | 74/74 pass (68 before; +6 organization tests) |
| Storage rules (emulator) | `npm --prefix tests run test:storage` | 14/14 pass (7 before; +7 organization tests) |
| Website typecheck | `npm run lint` | clean |
| Functions build | `npx tsc` in `functions/` | clean (`lib/` regenerated in this pass) |
| `api/verify.ts` typecheck | explicit `tsc --noEmit` | clean |
| Firestore `!= null` semantics | emulator probe | `idCardUrl → [u1,u3]`, `selfieUrl → [u2,u3,u5]` |
| Android gate | see STEP 8 | re-run in this pass: 156 + 5 + 134 tests, lint 0 errors, APK 22,117,375 bytes |
| `functions-android` | `npm test --prefix functions-android` | 11/11 pass |

No deployment, no commit, and no production resource was touched.

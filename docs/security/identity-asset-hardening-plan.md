# Identity asset hardening plan (ID card + selfie)

Companion to `docs/security/identity-document-exposure.md`.

Status: **IMPLEMENTED in both repositories; deployment still required.**

The plan below is now code, not a proposal. Nothing has been deployed and no
`firebase deploy` has been run, so every deployment step remains **NOT VERIFIED**.

Where it landed:

| Piece | File |
| --- | --- |
| Owner-only Storage folder for evidence | `nextbench-1/storage.rules` (`match /identity/{userId}/{file}`) |
| Public profile document can no longer carry identity fields | `nextbench-1/firestore.rules` (`isValidUser` + the self-update allowlist) |
| Review endpoint reads private objects, path re-scoped to the caller | `nextbench-1/api/verify.ts` (`loadIdentityImage`) |
| Admin-only, 5-minute signed URL for moderation | `nextbench-1/functions/src/index.ts` (`getVerificationAssetUrl`) |
| Pure path-ownership predicate (shared by endpoint + tests) | `nextbench-1/functions/src/identityPaths.ts` |
| Website upload + private pointer | `nextbench-1/src/lib/storage.ts`, `src/lib/verification.ts`, `src/lib/verificationPaths.ts`, `src/pages/Auth/Verification.tsx` |
| Admin panel reads evidence through the callable | `nextbench-1/src/pages/Dashboard/AdminPanel.tsx` |
| One-time migration of legacy records | `nextbench-1/scripts/migrate-identity-assets.mjs` |
| Android upload + pointer + resume signal | `data/firebase/.../VerificationRepository.kt`, `AuthRepository.kt`, `FirestoreRefs.kt`, `app/.../verification/VerificationViewModel.kt` |
| Tests | `nextbench-1/tests/identity.rules.test.mjs`, `tests/identityStorage.rules.test.mjs`, `tests/identityPaths.test.ts`, `tests/verificationPaths.test.ts`; Android `VerificationRepositoryTest`, `AuthRepositoryContractTest`, `VerificationStateTest` |

Verified locally: Firestore rules suite **68/68**, Storage rules suite **7/7**,
website unit suite **54/54**, `tsc --noEmit` clean in both `functions/` and the app,
Android full gate green.


Design constraints taken from the product's own rules:

- No new Firestore schema invented from Kotlin models; reuse the existing
  `users/{uid}/private/{docId}` subcollection, which the canonical rules already
  restrict to the owner (`firestore.rules:239`).
- Android must not contain server secrets (AGENTS.md §8). Unsigned uploads only,
  or an authenticated Flowthrough that never exposes a key.
- Existing successful verification must keep working, and existing verified users
  must not break.
- The review endpoint must still be able to read the images.

## Root cause, restated

`idCardUrl` / `selfieUrl` are ordinary fields on `users/{uid}`, which rule 182
makes readable by every signed-in non-blocking user, and the values are public
Cloudinary URLs. Two independent defects have to be fixed together:

1. **Location** — identity fields must leave the publicly readable document.
2. **Deliverability** — the asset must not have a public, unsigned, permanent URL.

Fixing only (1) leaves the URLs leaked wherever they were already read; fixing
only (2) still leaves admin-readable copies on a public document. Both.

## Target architecture

```text
Firebase Storage
  identity/{uid}/id-<submissionId>.jpg
  identity/{uid}/selfie-<submissionId>.jpg
        │  storage.rules: read/write = owner only. No public objects, no
        │  client-reachable download URL for anyone but the owner.
        │  Admins and the review endpoint use the Admin SDK, which bypasses
        │  storage rules, so moderation keeps working.
        ▼
Firestore  users/{uid}/private/verification
  { idCardPath, selfiePath, submissionId, updatedAt }
        │  rules already: allow read, write: if request.auth.uid == userId
        ▼
POST /api/verify   (Bearer idToken)
  body: { uid, profileName, schoolName, idCardPath, selfiePath }
        │  server downloads bytes with the Admin SDK and never needs a URL
        ▼
users/{uid}        → verified / verificationStatus / verificationRejectionReason
                     (server-owned, unchanged)
```

Why Firebase Storage rather than Cloudinary:

- The Android app already depends on Firebase Storage (`FirebaseStorage` is
  injected in `ChatRepository`), so no new technology is introduced.
- Storage rules can express owner-only read *and* deny every other client read
  while still allowing the Admin SDK. Cloudinary's unsigned preset cannot make an
  asset private: `type=private` / authenticated delivery is configured on the
  upload preset in the Cloudinary dashboard, which is an external console change
  we cannot verify from source (**NOT VERIFIED**, see "Option B" below).

`users/{uid}/private/*` is already owner-only in the deployed rules, so no new
subcollection concept is created. Admins do **not** need client read of the
pointer because moderation goes through a callable (below).

## 1. Storage rules (website repo `storage.rules`)

Add an explicit block. Note the file's own header warning: these rules were
previously console-managed and must be diffed against the console before deploy.

```diff
 service firebase.storage {
   match /b/{bucket}/o {
     function isSignedIn() { return request.auth != null; }

+    // ── Identity verification evidence ─────────────────────
+    // Layout: identity/{uid}/{id|selfie}-{submissionId}.{ext}
+    // Owner-only. No public read, no other client read. The review endpoint and
+    // the admin moderation queue use the Admin SDK, which bypasses these rules.
+    match /identity/{userId}/{file} {
+      allow read: if isSignedIn() && request.auth.uid == userId;
+      allow write: if isSignedIn()
+        && request.auth.uid == userId
+        && request.resource != null
+        && request.resource.contentType.matches('image/.*')
+        && request.resource.size < 15 * 1024 * 1024;
+      allow delete: if isSignedIn() && request.auth.uid == userId;
+    }
+
     // ── Stories media ─────────────────────────────────────
```

Anything not matched is denied by default, and the catch-all is absent, so an
`identity/**` object cannot be read through the client SDK by a third party.

## 2. Firestore rules (website repo `firestore.rules`)

Two changes, both strengthening:

**(a)** Stop clients writing identity URLs to the public document. Remove
`idCardUrl` / `selfieUrl` from the self-update allowlist and from `isValidUser`
so the fields can never be (re)written there once migrated:

```diff
-        (incoming().diff(existing()).affectedKeys().hasOnly(['username', 'lastUsernameChange', 'name', 'city', 'profilePicture', 'coverPhoto', 'about', 'idCardUrl', 'selfieUrl', 'verificationStatus', 'updatedAt', 'anonymousPersonaName', 'orgName', 'orgWebsite', 'orgDescription', 'referralCode'])
+        (incoming().diff(existing()).affectedKeys().hasOnly(['username', 'lastUsernameChange', 'name', 'city', 'profilePicture', 'coverPhoto', 'about', 'verificationStatus', 'updatedAt', 'anonymousPersonaName', 'orgName', 'orgWebsite', 'orgDescription', 'referralCode'])
```

and drop the two `isValidUser` clauses (lines 164–165) — both done.

**Correction to an earlier draft of this document:** removing the fields from the
self-update allowlist is safe *immediately*, and does not need to wait for the
migration. `incoming().diff(existing()).affectedKeys()` contains only keys whose
value changed (or was added/removed), not keys merely resent. A user document that
still carries a legacy `idCardUrl` therefore passes the update rule for an ordinary
`name`/`about`/`profilePicture` edit, because `idCardUrl` is unchanged and not an
affected key. Removing the two `isValidUser` clauses is also safe: they were
permissive validators (`key missing == fine`).

This is now covered by an emulator regression test
(`tests/identity.rules.test.mjs` → *ordinary profile edits still succeed after
identity fields were removed*), so the claim is evidence-backed rather than assumed.
The migration still matters for a different reason: it removes the pre-existing
exposure of already-stored legacy values.

**(b)** Add the pointer document to the same owner-only `private` match (already
covered by `firestore.rules:239`; no change needed, listed for clarity):

```text
match /private/{docId} {
  allow read, write: if isSignedIn() && request.auth.uid == userId;
}
```

Because the pointer is owner-only, the admin moderation queue must not rely on
client reads of `private/verification` — it uses the callable in step 4.

## 3. Review endpoint (`api/verify.ts`)

`/api/verify` currently receives `idCardUrl` / `selfieUrl` in the body and fetches
them with plain `fetch()`:

```ts
const { uid, profileName, schoolName, idCardUrl, selfieUrl } = req.body;
if (!uid || !profileName || !schoolName || !idCardUrl || !selfieUrl) return 400;
const [idImgBase64, selfieImgBase64] = await Promise.all([
  downloadImageAsBase64(idCardUrl),
  downloadImageAsBase64(selfieUrl),
]);
```

Change: accept **paths**, download through the Admin SDK, and keep accepting
legacy URLs for one release so in-flight/older clients cannot break.

```diff
-  const { uid, profileName, schoolName, idCardUrl, selfieUrl } = req.body;
+  const { uid, profileName, schoolName, idCardUrl, selfieUrl, idCardPath, selfiePath } = req.body;
   ...
-  if (!uid || !profileName || !schoolName || !idCardUrl || !selfieUrl) {
-    return res.status(400).json({ error: 'Missing required parameters (uid, profileName, schoolName, idCardUrl, selfieUrl)' });
-  }
+  const hasPaths = typeof idCardPath === 'string' && typeof selfiePath === 'string';
+  const hasLegacyUrls = typeof idCardUrl === 'string' && typeof selfieUrl === 'string';
+  if (!uid || !profileName || !schoolName || (!hasPaths && !hasLegacyUrls)) {
+    return res.status(400).json({ error: 'Missing required parameters (uid, profileName, schoolName, idCardPath, selfiePath)' });
+  }
+  // Paths are the hardened contract: the object is private, so the server reads the bytes
+  // itself instead of trusting a client-supplied URL. Legacy URLs are still accepted so a
+  // client that has not updated yet is not broken; remove after one release.
+  const bucket = admin.storage().bucket();
+  async function loadImage(path?: string, url?: string): Promise<string> {
+    if (path) {
+      // Reject traversal and objects outside this user's identity folder.
+      if (!path.startsWith(`identity/${uid}/`)) {
+        throw new Error('Invalid identity path');
+      }
+      const [buffer] = await bucket.file(path).download();
+      return buffer.toString('base64');
+    }
+    return downloadImageAsBase64(url as string);
+  }
```

and replace the download pair:

```diff
-    const [idImgBase64, selfieImgBase64] = await Promise.all([
-      downloadImageAsBase64(idCardUrl),
-      downloadImageAsBase64(selfieUrl),
-    ]);
+    const [idImgBase64, selfieImgBase64] = await Promise.all([
+      loadImage(idCardPath, idCardUrl),
+      loadImage(selfiePath, selfieUrl),
+    ]);
```

The `uid` ownership check (`verifiedUid !== uid → 403`) already exists and stays;
the `path.startsWith('identity/' + uid + '/')` check adds the second half — a
caller cannot ask the server to read another user's object.

## 4. Admin moderation access (new callable)

The admin queue (`AdminPanel.tsx`) currently renders
`getOptimizedImageUrl(item.idCardUrl)` from the public document. After migration
there is no public URL. Add an admin-only callable in the website Functions:

```ts
export const getVerificationAssetUrl = onCall(
  { invoker: "public", cors: CORS_ORIGINS },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
    if (request.auth.token.admin !== true) throw new HttpsError("permission-denied", "Admins only.");
    const { userId, kind } = request.data as { userId?: string; kind?: string };
    if (!userId || (kind !== "idCard" && kind !== "selfie")) {
      throw new HttpsError("invalid-argument", "userId and kind are required.");
    }
    const priv = await db.collection("users").doc(userId).collection("private").doc("verification").get();
    const path = priv.get(kind === "idCard" ? "idCardPath" : "selfiePath");
    if (typeof path !== "string" || !path) throw new HttpsError("not-found", "No asset on file.");
    const [url] = await admin.storage().bucket().file(path).getSignedUrl({
      action: "read",
      expires: Date.now() + 5 * 60 * 1000,
    });
    return { url };
  }
);
```

Short-lived signed URLs are generated server-side, only for an admin, and never
persisted. `AdminPanel.tsx` then calls this instead of reading a stored URL.

## 5. Android changes (this repository)

1. `VerificationRepository.submit()`
   - Upload `idCard` / `selfie` to `identity/{uid}/id-{submissionId}.jpg` and
     `identity/{uid}/selfie-{submissionId}.jpg` via `FirebaseStorage` (already a
     dependency) instead of Cloudinary `nextbench/ids`. `submissionId` is a fresh,
     client-generated id (or the document id of the new pointer) so resubmissions
     do not overwrite the object an in-flight review is reading.
   - Send `idCardPath` / `selfiePath` (not URLs) to `/api/verify`.
   - Persist the pointer to `users/{uid}/private/verification` and stop calling
     `AuthRepository.updateVerificationSubmission` for the public document.
2. `VerificationViewModel` — the "already submitted" signal currently reads
   `user.idCardUrl`; it must read the private pointer instead. That pointer is
   owner-readable, so an owner-side snapshot listener on
   `refs.user(uid).collection("private").document("verification")` works without a
   callable.
3. `AuthRepository.verificationSubmissionPayload` and its contract test
   (`AuthRepositoryContractTest.verificationSubmissionNeverAssertsAServerOwnedVerdict`)
   are the public-document write; they become obsolete for the new path and should
   be replaced by a `verificationPointerPayload` contract test asserting the
   pointer keys (`idCardPath`, `selfiePath`, `submissionId`, `updatedAt`) and that
   no `verificationStatus` / `verified` is written.
4. Uploaded object lifecycle: on a successful resubmission, delete the previous
   pair (owner-allowed by the storage rule) so rejections/resubmissions do not
   accumulate identity documents.

## 6. Migration of existing records

Existing verified users hold `idCardUrl` / `selfieUrl` in the public document, and those objects
live in the Cloudinary `ids` folder — the legacy flow called
`uploadToCloudinary(file, 'ids')`, so the public id is `ids/<random>` (or
`<preset-folder>/ids/<random>` if the unsigned preset sets a folder), **not** `nextbench/ids`.
Migration is **server-side only** (Admin SDK), one-time, idempotent:

```text
for each users/{uid} where idCardUrl != null || selfieUrl != null:
  1. if users/{uid}/private/verification does not exist:
       download the legacy URL bytes with the Admin SDK
       upload to identity/{uid}/id-{uid}.jpg and identity/{uid}/selfie-{uid}.jpg
       write the private pointer { idCardPath, selfiePath, submissionId, updatedAt }
  2. delete idCardUrl / selfieUrl / verificationRejectionReason-not-touched from users/{uid}
     (FieldValue.delete()) so the public document no longer carries the exposure
  3. log uid + outcome; do not touch verificationStatus, verified, or
     verificationRejectionReason
```

Notes:

- Step 1 completes before step 2 for each user, so there is no window where the
  evidence exists only in the private store and the review path cannot read it.
**Clearing the Firestore fields does not un-publish the assets.** The Cloudinary objects stay
publicly downloadable by URL until they are destroyed, so the migration is only half the fix. The
script therefore records every legacy URL it saw in an audit report, and a second opt-in phase
(`--purge-originals --from-report <file>`) destroys those public ids through Cloudinary's signed
destroy API with `invalidate=true` so cached CDN copies are purged too. It is irreversible, so it
is deliberately separate, reviewable, and guarded to this project's identity folder only. The
upload preset should also stop accepting that folder. The purge has never been executed against
the live account — **NOT VERIFIED** (no Cloudinary credentials were available); see
`docs/deployment/release-runbook.md` STEP 7.
- Users whose legacy URL has expired or 404s must be recorded and left with
  `idCardUrl` cleared and no pointer, so they are asked to resubmit; do not fail
  the whole migration for one bad asset.
- Target selection must query **both** fields. Firestore `!=` excludes a document where the field is
  missing, so a single `idCardUrl != null` sweep silently and permanently misses every user whose
  ID-card field is absent/null while their **selfie** is still a public URL. Verified against the
  Firestore emulator: `idCardUrl != null → [u1,u3]`, `selfieUrl != null → [u2,u3,u5]`.
- A rerun must also repair the interrupted case: if the pointer exists but the public document still
  carries URLs (a crash between the pointer write and the field clear), clear those fields without
  re-uploading. Otherwise a crash mid-run leaves evidence public forever.
- `isValidUser` clauses for the two fields are removed only after the sweep
  reports zero remaining occurrences.

## 7. Account deletion interaction

The deletion cascade (see `docs/backend/account-deletion-implementation.md`) must
delete `identity/{uid}/**` from Storage and `users/{uid}/private/**` from
Firestore. Because Storage has no server-side "delete prefix" primitive, enumerate
`bucket.getFiles({ prefix: 'identity/' + uid + '/' })` and delete each.

## 8. Tests

Testable from source, per repository:

- **Website rules emulator** (`tests/*.rules.test.mjs` style): a third user cannot
  `get` another user's `private/verification` (`assertFails`); the owner can; a
  signed-in user cannot read `identity/{otherUid}/...` via Storage rules
  (`assertFails` on the Storage emulator); `users/{uid}` update by the owner no
  longer accepts `idCardUrl`/`selfieUrl`.
- **Website endpoint unit test**: `loadImage` rejects a path outside
  `identity/{uid}/`; a legacy URL still resolves; a 2xx path requires a recognised
  `verificationStatus`.
- **Android** (`data:firebase`): pointer payload contract (keys, no server-owned
  verdict), and the outcome-mapping tests that already exist continue to hold.

## 9. Option B — keep Cloudinary (not recommended)

If the team prefers to keep Cloudinary, private delivery requires, in the
Cloudinary dashboard: an **authenticated/signed** upload (not the current
unsigned client preset) and either "authenticated" delivery type or strict
transformations, plus signed URL generation using the Cloudinary API secret in
the review endpoint. That means:

- the current unsigned Android preset can no longer be used for identity uploads,
  so Android would need an authenticated upload either brokered by a server
  endpoint (a new endpoint, plus multipart handling) or a signed-upload flow;
- delivery becomes access-controlled by Cloudinary rather than by Firebase rules;
- the Cloudinary API secret enters the backend, and asset deletion requires the
  Cloudinary admin API.

None of this is verifiable from source, and it leaves the identity fields on the
public Firestore document unless step 2 of this plan is also applied. Recommendation:
**use Firebase Storage.**

## 10. External actions required (all NOT VERIFIED)

1. Review the Storage rules against the current console ruleset before deploying
   (the file itself warns that it replaced console-managed rules).
2. Deploy `storage.rules`, `firestore.rules`, and the Functions codebase from the
   website repository, in the rollout order of §2.
3. Run the migration §6 against project `nextbench-a11ed` (one-time, needs Admin
   credentials).
4. Delete the legacy `ids` objects and update the Cloudinary preset (Cloudinary admin API /
   dashboard). Use `--purge-originals` with the migration report, or the Media Library's
   invalidate option.
5. Release the Android client change in the same window; until it ships, the
   legacy URL acceptance in §3 keeps verification working.

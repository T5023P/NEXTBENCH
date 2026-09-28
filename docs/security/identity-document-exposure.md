# Security finding: verify identity documents (ID card + selfie) are readable by any signed-in user

Status: **fixed in source across both repositories; not yet deployed.**
Severity was **High** (sensitive identity data), now mitigated in code:

- identity evidence moved to owner-only Storage at `identity/{uid}/**`, with the
  only pointer in `users/{uid}/private/verification` (owner-only);
- `users/{uid}` can no longer be made to carry `idCardUrl`/`selfieUrl`
  (`firestore.rules` allowlist + `isValidUser`);
- the review endpoint takes private object paths, re-scoped to the authenticated
  uid, and no public URL is ever created or persisted;
- a one-time migration clears the legacy values for existing users.

See `docs/security/identity-asset-hardening-plan.md` for the exact files and the
remaining deployment steps. The exposure is **still live in production** until
that migration and those rule deployments run.

The identical defect existed for **organization registration documents** (`users/{uid}.orgDocumentUrl`,
public Cloudinary link to a GSTIN / UDISE / registration certificate). It is fixed in the same shape
and documented separately: `docs/security/organization-document-hardening.md`.

Owner: backend/website repository `maryamfatimadesigns-ux/nextbench-1`.

Verified against website `main`, `firestore.rules` (1029 lines), and the current
Android source in this repository.

## Finding

Every user's `users/{uid}` document is readable by any other signed-in user who
has no block relationship with them:

```text
firestore.rules:24   function canAccessUser(userId) {
                       return isSignedIn() && (request.auth.uid == userId || isAdmin()
                         || !hasBlockRelationship(request.auth.uid, userId));
                     }

firestore.rules:181  match /users/{userId} {
firestore.rules:182    allow get: if canAccessUser(userId);
firestore.rules:183    allow list: if isAdmin();
```

That document is also where the student ID card and selfie are stored:

```text
firestore.rules:164  && (!data.keys().hasAny(['idCardUrl']) ||
                          (data.idCardUrl == null || (data.idCardUrl is string && data.idCardUrl.size() <= 2000)))
firestore.rules:165  && (!data.keys().hasAny(['selfieUrl']) || ...)
firestore.rules:204  ... hasOnly(['username', ..., 'idCardUrl', 'selfieUrl', 'verificationStatus', ...])
```

And the URLs are **public Cloudinary CDN URLs**, because the Android app uploads
them with the unsigned (client-safe) upload preset into the shared folder
`nextbench/ids`:

```kotlin
// data/firebase/.../VerificationRepository.kt
val id     = async { uploader.upload(idCard, "nextbench/ids", CloudinaryResourceType.Image) }
val selfieResult = async { uploader.upload(selfie, "nextbench/ids", CloudinaryResourceType.Image) }
// ...
authRepository.updateVerificationSubmission(user.uid, idUpload.url, selfieUpload.url)
```

```kotlin
// data/firebase/.../AuthRepository.kt
internal fun verificationSubmissionPayload(idCardUrl: String, selfieUrl: String) = mapOf(
    "idCardUrl" to idCardUrl,
    "selfieUrl" to selfieUrl,
    "updatedAt" to FieldValue.serverTimestamp(),
)
```

## Exploitation path

1. Sign in as any verified student account.
2. Obtain any other user's uid. Uids are not secret and are exposed through
   ordinary product data: `posts[].authorId`, `products[].sellerId`,
   `clubs[].memberIds`/`leadId`, `post_replies[].authorId`, `follows`,
   `notifications`, and so on.
3. `firebase.firestore().collection('users').doc(uid).get()` — permitted by rule
   182, since the attacker is signed in and has not blocked the victim.
4. Read `idCardUrl` and `selfieUrl` from the returned document. They are public
   Cloudinary URLs (no signature, no expiry, no auth), so the images download
   without any further credential.

There is no enumeration through `list` (`allow list: if isAdmin()`), but
enumeration is unnecessary: the attacker only needs a uid, and uids are
distributed throughout public content.

## Blast radius

Every user who has ever submitted verification. On this product that is
effectively **the entire verified population** — the trust model of the app
depends on verification, so the exposed set is the set of real, active users.
The exposed artifacts are a student/government-style ID document and a
biometric-adjacent selfie, which are the highest-sensitivity data the product
holds.

This also directly conflicts with the app's own legal copy, which claims
"Restricted admin access to verification documents"
(`app/.../legal/LegalDocumentScreen.kt`), and with a truthful Play **Data Safety**
declaration for "Government ID" and "Photos".

## Why a rules-only fix is not safe

The obvious mitigation is to stop allowing cross-user reads of documents that
carry identity fields:

```text
allow get: if isSignedIn() && (
  request.auth.uid == userId
  || isAdmin()
  || !hasBlockRelationship(request.auth.uid, userId)
);
```

Firestore rules cannot project fields, so the only rules-level lever is to deny
the whole document. That breaks every client that reads *another* user's
`users/{uid}` document for legitimate public data. In this repository alone:

- `ChatRepository.kt:163` — `refs.user(otherId).snapshotFlow()` (DM peer identity)
- `ChatRepository.kt:621` — `refs.user(otherUserId).get()` (DM peer identity)
- `ProductDetailRepository.kt:189` — `refs.user(product.sellerId).get()` (seller card)

and the website has its own cross-user reads (e.g. the admin verification queue
in `src/pages/Dashboard/AdminPanel.tsx`). Because the identity fields live on the
same document as the public profile, denying the read removes the public profile
of every verified user — i.e. almost every user.

## Conclusion

The defect is architectural: **identity evidence is stored as plain fields on a
document whose whole purpose is to be publicly readable.** It must be separated.
The exact remediation (storage location, rules, endpoint, migration, cleanup, and
account-deletion interaction) is specified in
`docs/security/identity-asset-hardening-plan.md`.

Until that plan is deployed, the exposure remains live and must be recorded as an
open high-severity security item, and the Play Data Safety answers must not claim
that ID documents are protected.

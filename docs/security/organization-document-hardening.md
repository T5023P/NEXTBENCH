# Security finding: organization registration documents were readable by any signed-in user

Status: **fixed in source across both repositories; not yet deployed.**
Severity was **High** (registration certificates for businesses and institutions, i.e. the
identity evidence of an organization account), now mitigated in code:

- registration documents moved to owner-only Storage at `organization/{uid}/**`, with the only
  pointer at `users/{uid}/private/organization` (owner-only);
- `users/{uid}` can no longer carry `orgDocumentUrl` — denied on **update** (allowlist) and on
  **create**;
- the admin verification queue opens a document through a 5-minute signed URL minted by the
  `getOrganizationDocumentUrl` callable, so no public link is stored or rendered anywhere;
- account deletion removes `organization/{uid}/**` with the account;
- a one-time migration moves legacy documents off the public Cloudinary URLs for existing records.

The exposure is **still live in production** until the migration and the rule deployments run.
This is the same defect class, and the same remediation shape, as
`docs/security/identity-document-exposure.md`; that document's analysis of why a rules-only fix is
unsafe applies verbatim here.

## Finding

`users/{uid}` is readable by any signed-in user who has no block relationship with the owner:

```text
firestore.rules   function canAccessUser(userId) {
                    return isSignedIn() && (request.auth.uid == userId || isAdmin()
                      || !hasBlockRelationship(request.auth.uid, userId));
                  }
```

That document was also where an organization's registration document URL lived:

```kotlin
// data/firebase/.../AuthRepository.kt (before)
"orgDocumentUrl" to data.documentUrl,
```

and the URL was a **permanent public Cloudinary delivery URL**, because the Android client uploaded
through the unsigned (client-safe) preset into a shared folder:

```kotlin
// app/.../auth/OrganizationSignupViewModel.kt (before)
val upload = uploader.upload(
    file = document.file,
    folder = "nextbench/org_documents",
    resourceType = CloudinaryResourceType.Auto,
)
```

The website had the same flow (`uploadOrgDocument` → `nextbench/org_documents/${randomId}` →
`orgDocumentUrl` on the profile document), and `firestore.rules` actively accepted the field on both
create and update.

## Blast radius

Every organization that had completed signup. Uids are not secret — they appear in
`posts[].authorId`, `products[].sellerId`, `clubs[].memberIds`, and so on — so a single
`users/{uid}.get()` returned a public URL to a GSTIN certificate, UDISE proof, or society
registration, downloadable with no further credential. The affected set is small (organization
accounts only) but the artifacts are government-registration documents, and the same document is
what the platform relies on to decide whether an organization is legitimate.

## Old → new flow

| | Before | After |
| --- | --- | --- |
| Upload target | Cloudinary, unsigned preset, `nextbench/org_documents/<random>` | Firebase Storage, `organization/{uid}/document-{submissionId}.{ext}` |
| Object ACL | Public CDN URL, no auth | Owner-only (`storage.rules`); Admin SDK for review |
| Pointer | `users/{uid}.orgDocumentUrl` (readable by every signed-in user) | `users/{uid}/private/organization` (owner-only) |
| Pointer contents | a URL | `documentPath`, `contentType`, `submissionId`, timestamp — **never a URL** |
| Admin access | `<a href>` to the stored public URL | `getOrganizationDocumentUrl` callable → 5-minute signed URL |
| Ordering at signup | upload → sign in → write profile | sign in → upload → write profile + pointer in one batch |
| Failed signup | orphaned public asset | freshly uploaded object deleted again |
| Replacement | new public URL overwrote the old one | new object + pointer; superseded object deleted **after** the write is confirmed |
| Account deletion | the public asset survived | `organization/{uid}/**` removed with the account |
| Source of truth | the URL | the object path |

Signup behaviour is otherwise unchanged: sign-in still happens with the Google identity, the profile
document still carries `orgName` / `orgType` / `orgWebsite` / `orgDescription`, referrals still
apply, and the review verdict fields stay server-owned.

### Why sign-in moved before the upload

The object path is derived from the uid (`organization/{uid}/...`) so that the storage rule can be
owner-only. The uid does not exist before authentication, so the upload has to happen after
`signInWithPopup` / `GoogleSignIn`. The consequence is handled explicitly: if the profile write
fails after a successful upload, the client deletes the object it just wrote, so a failed
registration cannot leave an orphaned certificate in Storage.

## Rules changes

`storage.rules` (new block, additive):

```text
match /organization/{userId}/{file} {
  allow read:   if isSignedIn() && request.auth.uid == userId;
  allow write:  if isSignedIn() && request.auth.uid == userId
                && (contentType matches 'image/.*' or == 'application/pdf')
                && size < 15 MB;
  allow delete: if isSignedIn() && request.auth.uid == userId;
}
```

`firestore.rules`:

- `isValidUser` no longer validates `orgDocumentUrl` (a comment explains why, mirroring the identity
  fields) — so the one-time migration can clear it with the Admin SDK;
- `orgDocumentUrl` was removed from the self-update `affectedKeys().hasOnly([...])` allowlist, so a
  client can never introduce it after create;
- `create` now additionally requires `!incoming().keys().hasAny(['orgDocumentUrl'])`. Neither client
  writes the field any more, so this closes the last path by which a client could plant a public
  registration-document URL on its own (publicly readable) profile.

The pointer needs no rule change: `users/{uid}/private/{docId}` was already owner-only, which is
exactly why the pointer lives there. There is deliberately **no admin carve-out** on that document —
admins read it through the callable, which keeps the access path auditable and signed-URL-scoped.

## Migration

```bash
cd /f/nextbench-1
node scripts/migrate-organization-documents.mjs --project nextbench-a11ed --dry-run --verbose
node scripts/migrate-organization-documents.mjs --project nextbench-a11ed
```

Per legacy record: download the Cloudinary bytes → upload to
`organization/{uid}/document-{submissionId}.{ext}` (content type taken from the legacy URL, so a PDF
stays a PDF and the storage rule accepts it) → write `users/{uid}/private/organization` → **only
then** delete `orgDocumentUrl` from the public document → append a line to the audit report.

- **Idempotent / rerun-safe.** A document already private is not re-uploaded, but a public field left
  behind by an interrupted run is still cleared.
- **Evidence is never lost.** The field is cleared only after the upload and pointer write both
  succeeded; on the rerun path the migration first confirms the private object the pointer names
  exists in Storage and *holds* the record (leaving the public URL in place) if it does not.
- **Per-record failure isolation**, with the legacy field left in place for a retry, mirroring the
  identity migration.
- **Explicit report**: `organization-migration-report-<timestamp>.json`, git-ignored
  (`.gitignore`), and it lists the legacy public URLs, so treat it as sensitive.
- The Cloudinary signing/parsing helpers are now shared with the identity migration through
  `scripts/lib/cloudinaryAssets.mjs`, so a fix to the destructive-API signature cannot land in one
  purge path and miss the other.

## Purge (separate, explicit, irreversible)

```bash
node scripts/migrate-organization-documents.mjs --purge-originals \
  --from-report "<organization-migration-report>.json" --dry-run
node scripts/migrate-organization-documents.mjs --purge-originals \
  --from-report "<organization-migration-report>.json"
```

Only public ids matching `org_documents/<name>` (optionally behind a preset folder) are destroyed,
and only for records whose document was confirmed private. `ids/`, `school_requests/`, `products/`,
`profiles/`, `clubs/` — every other folder, including the other folders this project owns — is
refused. The two migration scripts therefore have **mutually exclusive** folder guards: the identity
purge cannot destroy a registration certificate, and the organization purge cannot destroy an ID
card or selfie.

## Tests

| Suite | File | Covers |
| --- | --- | --- |
| Website unit (+11) | `tests/organizationDocumentPaths.test.ts` | path/pointer contract, type coercion, replacement, no URL/verdict in the pointer, migration↔app agreement, and a source guard that no new code writes `orgDocumentUrl` |
| Website unit (+22) | `tests/organizationMigration.test.ts` | selection, ordering (upload → pointer → clear), idempotency, crash repair, dangling-pointer hold, dry run, failure isolation, `--limit`, purge scope and signing |
| Firestore rules (+6) | `tests/organization.rules.test.mjs` | third party cannot read the pointer (nor can an admin client), owner can, the legacy field cannot be written on create or update, ordinary org edits still work |
| Storage rules (+7) | `tests/organizationStorage.rules.test.mjs` | owner read/write/delete, third-party and anonymous read denied, no cross-owner overwrite/delete, unsupported types denied, not covered by the permissive `nextbench/**` grant |
| Android (+8) | `data/firebase/.../OrganizationDocumentsTest.kt` | path prefix, extension from resolved type, coercion, unique submission ids, pointer payload contents, supersede semantics, and (by reflection) that `OrganizationSignupData` has no document-URL field |

The "old public URL is no longer used by new code" requirement is guarded by a source scan rather
than a comment: `storage.ts`, `organizationDocumentPaths.ts`, `OrgSignup.tsx`, `AdminPanel.tsx` and
`functions/src/index.ts` may not mention `orgDocumentUrl` or `org_documents` at all, the legacy
uploader `uploadOrgDocument` must be gone, and the admin panel must call
`getOrganizationDocumentUrl` instead of rendering `org.orgDocumentUrl`.

## Account deletion

`functions/src/accountDeletion.ts` now exports `userStoragePrefixes(uid)` →
`['identity/{uid}/', 'organization/{uid}/']`, and the deletion cascade loops it, deleting every
object under each prefix. Leaving registration certificates behind after a deletion request would
retain government-adjacent documents for a user who asked to be erased; Storage cannot enforce this
on its own (the objects are owner-writable), so it belongs in the server-authoritative cascade.

## Still externally controlled / NOT VERIFIED

- **No live purge has been executed.** No `CLOUDINARY_CLOUD_NAME` / `CLOUDINARY_API_KEY` /
  `CLOUDINARY_API_SECRET` were available locally, so both migrations have only ever been run against
  test doubles. No Cloudinary SDK was added to the app or the website.
- No migration has been run against the real project, so the candidate counts are unknown.
- The signed-URL path is exercised only by the rules/unit tests; the callable itself was not invoked
  against a deployed project.
- Deployment, credentials and the Play Data Safety answers remain blocked exactly as listed in
  `docs/deployment/release-runbook.md` §7.

# Play release readiness — policy URLs, reviewer access, deployment handoff

Status: **engineering-side preparation. No Play Console state has been inspected
or changed. Every console interaction is NOT VERIFIED.**

## 1. Privacy policy URL

- In-app: `app/.../legal/LegalDocumentScreen.kt` renders Terms and Privacy content
  in-app (no browser hop).
- Public URL: `nextbench-1/src/App.tsx:200` → `<Route path="/privacy" …>` (not
  behind `ProtectedRoute`, so it is publicly reachable). Candidate:
  `https://nextbench.in/privacy` (and `https://www.nextbench.in/privacy`).
- The host answers `200` for that path, but a Vite SPA answers `200` for *every*
  path (`<Route path="*" element={<NotFound />} />`), so the status code alone is
  **not** evidence the page exists. Confirm by loading the URL in a browser and
  seeing the policy content.
- Action: paste the confirmed URL into Play Console → App content → Privacy policy.

## 2. Account deletion URL

- **Implemented in source, not deployed.** `nextbench-1` now has a real route:
  `<Route path="/delete-account" element={<DeleteAccountPage />} />`
  (`src/pages/Legal/DeleteAccountPage.tsx`), plus the `requestAccountDeletion`
  callable that performs the cascade server-side.
- In-app path: Profile → Settings → **Delete account**, with a typed `DELETE`
  confirmation (`app/.../profile/ProfileScreen.kt`, `ProfileSettingsSheet.kt`).
- Candidate public URL: `https://nextbench.in/delete-account`.
- Before recording it in Play Console: deploy the website, then open the URL and
  confirm the real page renders. A `200` alone is not evidence — the SPA answers
  `200` for every path via its `NotFound` catch-all.
- Play requires both the data-deletion URL and, where deletion is offered in-app,
  the in-app path.

## 3. Reviewer / demo access

Sign-in facts from source:

- Email sign-in is **passwordless OTP**: `sendAuthOtpEmail` mails a 6-digit code
  (`functions/src/index.ts`), and `verifyAuthOtpEmail` signs the user in with a
  custom token (or a rotated password). A Play reviewer cannot receive that OTP, so
  **an email-OTP account cannot be handed over as reviewer credentials.**
- Google Sign-In exists (`signInWithGoogleIdToken`) but `AuthRepository` throws
  `NoProfileException` and signs out when no profile document exists, so a
  reviewer's own Google account cannot get in either.
- Reaching Create, Messages, clubs, and wishlist additionally requires a
  **verified** account (`AuthGate`; `isVerified()` in rules).

Therefore reviewer access needs a **seeded, pre-verified demo account plus
credentials the reviewer can actually use** — an operations/product action, not a
code change:

1. Create a demo account through the normal signup path, or create the Auth user
   directly in the console.
2. Seed it with visible content: at least one post, one product listing, one story,
   one club, one conversation.
3. Mark it verified the supported way — through the admin panel / admin token
   (`verificationStatus: 'approved'`, `verified: true`). Do **not** add a
   verification bypass, a hardcoded admin flag, or a debug back door to the app;
   the 2026 AGENTS.md rules forbid mobile admin surfaces and insecure bypasses.
4. Give the reviewer working credentials. Because email sign-in is OTP-based, the
   practical route is a Google account the team owns and can share, with the demo
   profile already created against it.
5. Note the demo account in Play Console's review instructions, including which
   areas need a verified account.

This step is **PRODUCT DECISION / ops**, not engineering: no source change is
required, and no source change is safe.

## 4. Deployment commands still required

None of these have been run. They must be run by someone with project access, and
each is explicit, separate, and gated on a review of the diff.

```bash
# Website repo (maryamfatimadesigns-ux/nextbench-1) — rules, storage, functions
firebase deploy --only firestore:rules --project nextbench-a11ed
firebase deploy --only storage        --project nextbench-a11ed
firebase deploy --only functions      --project nextbench-a11ed

# Android repo — the isolated push codebase only (never rules, never the website functions)
firebase deploy --only functions:android-push --project nextbench-a11ed

# Android release artifacts
./gradlew :app:assembleRelease     # requires google-services.json, Cloudinary config, signing
./gradlew :app:bundleRelease       # AAB for Play
```

Ordering constraints:

- Storage/Firestore rules for identity hardening must be deployed **after** the
  migration that clears legacy `idCardUrl`/`selfieUrl` (see
  `docs/security/identity-asset-hardening-plan.md` §2 and §6), otherwise profile
  updates from users still carrying those fields are rejected.
- `requestAccountDeletion` and the Android client that calls it must ship in that
  order (server first), or the in-app entry point fails.
- The Android client for identity hardening and the backend change are coupled;
  the endpoint accepts legacy URLs for one release to keep verification working
  through the transition.

## 5. Security issues remaining (source-level, confirmed)

| # | Issue | Severity | Where fixed | Status |
| --- | --- | --- | --- | --- |
| 1 | Any signed-in user could read any other user's ID card + selfie via `users/{uid}` (`idCardUrl`/`selfieUrl`), which were public Cloudinary URLs | was High | `docs/security/identity-document-exposure.md`, `identity-asset-hardening-plan.md` | **Fixed in source (both repos); OPEN IN PRODUCTION until deployed + migrated** |
| 2 | ID card + selfie are sent to a third-party AI model for review; not disclosed in the privacy policy | Medium | `api/verify.ts`; policy copy | **Open — PRODUCT/POLICY + website** |
| 3 | Website club chat did not filter blocked members' messages (rules cannot express it; see `firestore-club-message-block-enforcement.md`) | Medium | `nextbench-1/src/hooks/useChatEngine.ts`, `src/components/chat/ChatView.tsx` | **Fixed in source (both repos); deploy to apply** |
| 4 | No account deletion anywhere | was High (Play blocker) | `docs/backend/account-deletion-implementation.md` | **Fixed in source (both repos); deploy to apply** |
| 5 | `firebase.json` in this repo declares only `android-push`; the website's own deploy config and console-managed Storage rules must be reconciled before any Storage deploy (`storage.rules` header warns about this) | Low | website repo | **Open — deploy-time check** |

## 6. Verification that cannot be claimed from this repository

Device/console facts that remain unproven and must not be reported as passing:

- FCM delivery in foreground, background, and killed states on a real device.
- Deployed state of `functions-android`.
- Play Console Data Safety form state and any console-managed ruleset.
- Release signing, AAB generation, and store listing acceptance.
- TalkBack traversal, large-text layout, contrast, cold start, and jank.

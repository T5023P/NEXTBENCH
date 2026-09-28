# Play Data Safety inventory (derived from source)

Status: **prepared for the Play Console form. Nothing here is Play Console state —
the form itself has not been inspected or submitted. NOT VERIFIED.**

Derived from: `app/src/main/AndroidManifest.xml`, the Android `data:firebase`
repositories, `functions-android/src/*`, and the website `nextbench-1`
(`firestore.rules`, `api/verify.ts`, `functions/src/index.ts`).

## Recognised permissions (`AndroidManifest.xml`)

| Permission | Why |
| --- | --- |
| `INTERNET` | all backend access |
| `POST_NOTIFICATIONS` | message and verification notifications (Android 13+) |
| `RECORD_AUDIO` | voice messages in direct and club chat |
| `VIBRATE` | notification feedback |

Declared as a **feature, not a permission**: `android.hardware.camera`
(`required="false")`. Photos are captured through system picker/camera intents, so
no `CAMERA`, `READ_MEDIA_*`, or storage permission is requested. There is **no**
location, contacts, calendar, SMS, call-log, or advertising-ID permission.
`android:allowBackup="false"`, so app data is excluded from Android cloud backup.

## Data collected

| Data type (Play category) | What exactly | Purpose | Where it lives | Shared with |
| --- | --- | --- | --- | --- |
| Personal info → Email | sign-in email | App functionality; account management | Firebase Auth; `users/{uid}.email` | Firebase (Google) |
| Personal info → Name | profile name, club/author/sender display names | App functionality; personalisation | `users/{uid}.name`, denormalised into posts/messages | Firebase, Cloudinary (media) |
| Personal info → User IDs | Firebase uid | App functionality; account management | throughout | Firebase |
| Personal info → Other | school, city, username, about, org fields, reputation | App functionality; personalisation | `users/{uid}` | Firebase |
| **Photos and videos → Photos** | profile picture, cover photo, post/product/story images, chat image attachments, and **student ID card + selfie** for verification, and an **organization registration document** when it is an image | App functionality | Cloudinary (media); Firebase Storage (stories/voice, and all verification/organization evidence) | Firebase, **Cloudinary**, **third-party AI model** (see below) |
| Videos | post/product/club videos | App functionality | Cloudinary | Cloudinary |
| Audio → Voice or sound recordings | chat voice messages (`RECORD_AUDIO`) | App functionality | Cloudinary / Firebase Storage | Cloudinary |
| Files and docs | chat documents (e.g. PDFs) | App functionality | Cloudinary | Cloudinary |
| Messages → In-app messages | direct and club message content, including shared posts | App functionality | `chatRooms/{roomId}/messages`, `clubs/{clubId}/messages` | Firebase |
| Messages → Other in-app messages | in-app notifications | App functionality | `notifications/{notifId}` | Firebase |
| **Personal info → Government ID / identity documents** | **student ID card image and a selfie holding it** | App functionality — identity verification (a core product requirement) | **after the fix**: owner-only Storage `identity/{uid}/**`, referenced only by `users/{uid}/private/verification`. Legacy: `users/{uid}.idCardUrl/.selfieUrl` + Cloudinary `nextbench/ids` | Firebase; third-party AI model; Cloudinary only for pre-migration assets (purge step pending) |
| **Personal info → Government ID / identity documents** | **organization registration certificate (GSTIN / UDISE / society registration / trade licence)** | App functionality — organization verification | **after the fix**: owner-only Storage `organization/{uid}/**`, referenced only by `users/{uid}/private/organization`. Legacy: `users/{uid}.orgDocumentUrl` + Cloudinary `nextbench/org_documents` | Firebase; Cloudinary only for pre-migration assets (purge step pending) |
| Device or other IDs | FCM registration token | App functionality — push notifications | `users/{uid}.androidFcmTokens`, `androidPushTokens/{tokenId}` | Firebase |
| App activity → Other actions | posts, replies, reactions, votes, follows, blocks, reports, wishes, saves, club membership, buy requests | App functionality; safety (block/report) | Firestore | Firebase |
| Purchases? | **No payment processing.** Marketplace "reserve/request" is an in-app request record, not a transaction. No financial info is collected. | — | `buyRequests` | Firebase |

## Third-party sharing (important)

1. **Cloudinary** — general user media (profile pictures, posts, products, clubs,
   chat attachments, videos, voice notes, files) is uploaded with an unsigned preset
   and served from public CDN URLs; Cloudinary's own privacy policy is referenced in
   the app's legal copy (`LegalDocumentScreen.kt`). Verification evidence is **no
   longer** uploaded there: ID cards/selfies now go to private Firebase Storage, and
   organization registration documents followed in the same pass. Legacy Cloudinary
   copies of both still exist until the purge steps run, which is why this row stays.
   School-addition request ID cards are still uploaded to Cloudinary
   (`nextbench/school_requests/<id>`) and remain the one outstanding item in this area
   — see `docs/deployment/release-runbook.md` §6.2.
2. **Third-party AI verification** — `nextbench-1/api/verify.ts` downloads
   `idCardUrl` and `selfieUrl` and sends both images, base64-encoded, to
   **Google Gemini 2.5 Flash**, either through the Hack Club AI proxy
   (`https://ai.hackclub.com/proxy/v1/chat/completions`) or the Google
   Generative Language API. The images are therefore disclosed to a third-party
   model provider as part of verification. Any Data Safety answer and privacy
   policy must state that identity images are processed by a third-party AI
   service, and the policy must name the categories (photos, government ID).
3. **Firebase / Google Cloud** — all Firestore, Auth, Storage, Functions, and FCM
   data.

No advertising or analytics SDK is present in the Android dependency set
(`gradle/libs.versions.toml`), so "data collected for advertising or marketing" and
"data shared for advertising" can be answered No, subject to console confirmation.

## Data deletion

- In-app account deletion: **implemented in source** (Profile → Settings → Delete
  account, typed `DELETE` confirmation), backed by the server-authoritative
  `requestAccountDeletion` callable. Not deployed, so the answer must not be
  recorded in Play Console until the website is released.
- Web deletion URL: `/delete-account` now exists in `nextbench-1/src/App.tsx`.
  Deploy, then confirm the page renders before submitting the URL.
- The app's legal copy claims erasure on request (`LegalDocumentScreen.kt:232`) and
  a 30-day retention for identity documents (`LegalDocumentScreen.kt:224`). The
  erasure path now exists in code; the retention wording still needs a policy check
  against what the cascade actually does (moderation records are retained with the
  identity stripped, not deleted).

## Security (the form asks about encryption and protection)

- Data in transit: TLS/HTTPS for Firebase and Cloudinary.
- Access control: Firestore security rules; Firebase Auth.
- Identity evidence **and organization registration documents**: fixed in source.
  Both now live in owner-only Storage (`identity/{uid}/**`,
  `organization/{uid}/**`), referenced only by owner-only pointers, read by the backend
  and admins through the Admin SDK, and removed with the account. **Still exposed in
  production until the migrations and rule deployments run**
  (`docs/security/identity-document-exposure.md`,
  `docs/security/organization-document-hardening.md`), so the restricted-access claim
  cannot be made yet.

## Summary of answers that are blocked on engineering

| Data Safety question area | Blocked by |
| --- | --- |
| Deletion → in-app deletion path | `requestAccountDeletion` not implemented/deployed |
| Deletion → deletion request URL | `/delete-account` page not implemented/deployed |
| Data collected → Government ID, protection claim | identity **and** organization-document exposure not yet deployed (`identity-asset-hardening-plan.md`, `organization-document-hardening.md`) |
| Photos shared with third party | must disclose AI verification processing (verified in `api/verify.ts`) |

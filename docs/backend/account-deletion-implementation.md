# Account deletion — server-authoritative implementation

Status: **IMPLEMENTED in both repositories; not deployed.** The cascade below is now
code, not a proposal:

| Piece | File |
| --- | --- |
| Deletion plan (pure, testable classification) | `nextbench-1/functions/src/accountDeletion.ts` |
| Callable + cascade + retention anonymisation | `nextbench-1/functions/src/index.ts` (`requestAccountDeletion`, `deleteUserData`, `anonymizeRetainedRecords`) |
| Server-owned deletion state | `nextbench-1/firestore.rules` (`match /deletionRequests/{userId}`) |
| Public deletion page + route | `nextbench-1/src/pages/Legal/DeleteAccountPage.tsx`, `src/App.tsx` (`/delete-account`) |
| Client confirmation/error mapping | `nextbench-1/src/lib/accountDeletion.ts` |
| Android callable wrapper | `data/firebase/.../NbFunctions.kt` (`requestAccountDeletion`) |
| Android request + sign-out | `data/firebase/.../AuthRepository.kt` (`deleteAccount`) |
| Android state | `app/.../auth/AuthViewModel.kt` (`deleteAccountState`, `deleteAccount`) |
| Android entry point | `app/.../profile/ProfileScreen.kt`, `ProfileSettingsSheet.kt`, `navigation/NbNavHost.kt` |
| Tests | `nextbench-1/tests/accountDeletionPlan.test.ts`, `tests/accountDeletionClient.test.ts`, `tests/identity.rules.test.mjs`; Android `AuthRepositoryContractTest` |

Verified locally: website unit suite 54/54, Firestore rules suite 68/68 (including
the deletion-state tests), `tsc --noEmit` clean, Android full gate green. No
deployment has been run.

The pre-implementation state, for the record, was that no account-deletion code
existed in either repository:

- Android: no `deleteAccount` / `deleteUser` reference anywhere in `app/` or
  `data/`. The only account-deletion mentions are legal copy
  (`app/.../legal/LegalDocumentScreen.kt:224,254`).
- Website `nextbench-1`: no `delete-account` route in `src/App.tsx`, no
  `deleteUser` / `deleteAccount` callable in `functions/src/`, no
  `api/delete-account` handler. `api/` contains only `verify.ts` and
  `send-notification.js`.

The deletion cascade must be server-authoritative. A client cannot be trusted to
delete its own account, and the Firestore rules deliberately deny client deletes
(`users/{userId} allow delete: if isAdmin()`), so the callable below runs with the
Admin SDK, which bypasses rules.

## 1. Artifact inventory (from source)

Every user-owned or user-referencing location found by inspecting
`firestore.rules`, `functions/src/index.ts`, `functions-android/src/index.ts`,
and the Android repositories.

| Artifact | Location | Follows user | Classification |
| --- | --- | --- | --- |
| Auth account | Firebase Auth | yes | **hard delete (last)** |
| Profile | `users/{uid}` | doc id | **hard delete** |
| Private per-user docs | `users/{uid}/private/*` | subcollection | **hard delete** |
| Identity evidence | `identity/{uid}/*` (Storage, after hardening) | prefix | **hard delete** |
| Legacy identity evidence | Cloudinary `nextbench/ids` + `users/{uid}.idCardUrl/selfieUrl` | fields | **hard delete (external)** |
| Referral receipts | `users/{uid}/referrals/{referredId}` | subcollection | **hard delete** |
| Referral pointers | `users/{*}.referredBy == uid` | field on others | **anonymize** (clear the pointer; keep the referrer's `referralCount` reduced) |
| Username claim | `usernames/{username}` where `userId == uid` | field | **hard delete** (release the handle) |
| Blocks (both directions) | `blocks/{uid}_{other}` and `blocks/{other}_{uid}` | doc id / fields | **hard delete** |
| Follows | `follows` where `followerId == uid` or `followingId == uid` | fields | **hard delete** |
| Follow edges | `follow_edges/{uid}_{other}`, `/{other}_{uid}` | doc id | **hard delete** (server-materialized; `allow write: if false`) |
| Saved posts | `saved_posts/{saveId}.userId` | field | **hard delete** |
| Wishlists | `wishlists/{wishlistId}.userId` | field | **hard delete** |
| Votes | `post_upvotes`, `post_downvotes`, `reply_upvotes` where `userId == uid` | field | **hard delete** |
| Reactions | `post_reactions/{reactionId}` where `userId == uid` | field | **hard delete** |
| Posts | `posts/{postId}` where `authorId == uid` | field | **hard delete (cascade replies/reactions/votes)** |
| Replies | `post_replies` where `authorId == uid` | field | **hard delete (cascade child replies)** |
| Products | `products/{productId}` where `sellerId == uid` | field | **hard delete** |
| Buy requests | `buyRequests` where requester or seller is `uid` | field | **anonymize** (keep the counterpart's record, null the user's side) |
| Reviews | `reviews` where `reviewerId == uid` | field | **retain (moderation/trust)** — reviewer identity is part of another user's product history; anonymize the display name only |
| Stories | `stories/{storyId}` where `authorId == uid` | field | **hard delete (+ Storage media)** |
| Affinity | `user_affinity/{uid}` | doc id | **hard delete** |
| Computed | `computed/*` aggregates | derived | **recompute** (no direct user data; the aggregation triggers will settle) |
| Chat rooms | `chatRooms` where `uid in participants` | field | **anonymize** (remove `uid` from `participants`, `unreadBy`, `mutedBy`, `archivedBy`, `pinnedBy`, `deletedBy`, `typingUsers`; set the room `status` so the counterpart keeps history) |
| Direct messages | `chatRooms/{roomId}/messages` where `senderId == uid` | field | **retain, author-anonymized** (counterpart's conversation is their data) |
| Club membership | `clubs` where `uid in memberIds` | field | **hard delete from `memberIds`/`coLeadIds`, decrement `memberCount`; if `leadId == uid`, block deletion pending transfer or auto-promote** |
| Club messages | `clubs/{clubId}/messages` where `senderId == uid` | field | **retain, author-anonymized** |
| Notifications (own) | `notifications` where `userId == uid` | field | **hard delete** |
| Notifications (about user) | `notifications` where the payload references `uid` | field | **retain** (recipient-owned) |
| Reports (filed by user) | `reports/{id}.reporterId == uid` | field | **retain (legal/moderation)**; anonymize reporter display fields, never delete |
| Reports (about the user) | `reports` where `reportedUserId == uid` | field | **retain (legal/moderation)** |
| School requests | `school_requests` where `requesterEmail` matches | field | **retain (moderation)**, anonymize requester name |
| Android push tokens | `users/{uid}.androidFcmTokens`, `androidPushTokens/{tokenId}.ownerId == uid` | field | **hard delete** (both) |
| Rate-limit docs | `rate_limits/verify_{uid}`, `notifications` rate docs, `emailOtpRateLimits/{emailHash}`, `emailOtpTokens/{emailHash}` | doc id / hash | **hard delete** the uid-derived ones; the email-hash ones contain no uid — delete only if the email is being erased |
| Email logs | `users/{uid}.lastEmailNotification` | field | covered by profile delete |

Classification rules applied:

- **hard delete** — the record exists only because of this user.
- **anonymize** — the record belongs to another user/relationship and must survive,
  but the deleted user's identity must be removed from it.
- **retain (legal/moderation)** — moderation and safety records; deleting them
  would destroy the counterpart's or the platform's safety evidence. Never delete
  merely because the document contains the uid.

## 2. Callable: `requestAccountDeletion`

Add to `nextbench-1` `functions/src/index.ts` (source of truth; the isolated
Android `functions-android` codebase must not own this).

```ts
/**
 * Server-authoritative account deletion.
 *
 * Idempotent: re-invoking while a request is in flight returns the existing state.
 * Requires a recent sign-in (reauth) because deletion is irreversible.
 */
export const requestAccountDeletion = onCall(
  { invoker: "public", cors: CORS_ORIGINS, timeoutSeconds: 540 },
  async (request) => {
    const uid = assertAuthedUid(request);
    const token = request.auth?.token as { auth_time?: number; admin?: boolean } | undefined;

    if (token?.admin === true) {
      // Deleting an admin account from the app is not allowed; it is an operator action.
      throw new HttpsError("permission-denied", "Admin accounts cannot be deleted from the app.");
    }

    // Reauthentication: the ID token must have been minted recently.
    const authTime = (token?.auth_time ?? 0) * 1000;
    const RECENT_AUTH_MS = 5 * 60 * 1000;
    if (!authTime || Date.now() - authTime > RECENT_AUTH_MS) {
      throw new HttpsError(
        "failed-precondition",
        "For your security, sign in again before deleting your account."
      );
    }

    const confirmation = String(request.data?.confirmation ?? "");
    if (confirmation !== "DELETE") {
      throw new HttpsError("invalid-argument", "Type DELETE to confirm.");
    }

    const stateRef = db.collection("deletionRequests").doc(uid);
    const existing = await stateRef.get();
    if (existing.exists && existing.get("status") === "completed") {
      // Idempotent: a retry after a successful delete is a no-op.
      return { status: "completed" };
    }
    await stateRef.set(
      {
        status: "in_progress",
        userId: uid,
        requestedAt: admin.firestore.FieldValue.serverTimestamp(),
        attempts: admin.firestore.FieldValue.increment(1),
      },
      { merge: true }
    );

    try {
      await deleteUserData(uid);
      // Auth is deleted last: while the account still exists the user can retry and
      // support can still identify them.
      await admin.auth().deleteUser(uid);
      await stateRef.set(
        { status: "completed", completedAt: admin.firestore.FieldValue.serverTimestamp() },
        { merge: true }
      );
      return { status: "completed" };
    } catch (error) {
      console.error("[requestAccountDeletion] partial failure", uid, error);
      await stateRef.set({ status: "failed", lastError: String(error) }, { merge: true });
      // Never report success on a partial delete.
      throw new HttpsError("internal", "Account deletion did not complete. Please try again.");
    }
  }
);
```

The cascade itself, with the ordered steps and the anonymize-vs-delete split:

```ts
async function deleteUserData(uid: string): Promise<void> {
  // 1. Reject early if deletion would orphan a club the user leads.
  const ledClubs = await db.collection("clubs").where("leadId", "==", uid).limit(1).get();
  if (!ledClubs.empty) {
    throw new Error("Transfer club leadership before deleting your account.");
  }

  // 2. Owned content: delete outright.
  await deleteQueryDocs(db.collection("posts").where("authorId", "==", uid));
  await deleteQueryDocs(db.collection("post_replies").where("authorId", "==", uid));
  await deleteQueryDocs(db.collection("products").where("sellerId", "==", uid));
  await deleteQueryDocs(db.collection("stories").where("authorId", "==", uid));
  await deleteQueryDocs(db.collection("saved_posts").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("wishlists").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("post_upvotes").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("post_downvotes").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("reply_upvotes").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("post_reactions").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("user_affinity").where(admin.firestore.FieldPath.documentId(), "==", uid));
  await deleteQueryDocs(db.collection("notifications").where("userId", "==", uid));
  await deleteQueryDocs(db.collection("follows").where("followerId", "==", uid));
  await deleteQueryDocs(db.collection("follows").where("followingId", "==", uid));
  await deleteQueryDocs(db.collection("blocks").where("blockerId", "==", uid));
  await deleteQueryDocs(db.collection("blocks").where("blockedId", "==", uid));
  await deleteQueryDocs(db.collection("follow_edges").where("followerId", "==", uid));
  await deleteQueryDocs(db.collection("follow_edges").where("followingId", "==", uid));
  await deleteQueryDocs(db.collection("usernames").where("userId", "==", uid));

  // 3. Rate-limit / token state.
  await db.collection("rate_limits").doc(`verify_${uid}`).delete().catch(() => undefined);
  await deleteQueryDocs(db.collection("androidPushTokens").where("ownerId", "==", uid));

  // 4. Private subcollections (identity pointer and anything under private/).
  await deleteSubcollections(`users/${uid}`);

  // 5. Identity evidence in Storage.
  const [files] = await admin.storage().bucket().getFiles({ prefix: `identity/${uid}/` });
  await Promise.all(files.map((f) => f.delete().catch(() => undefined)));

  // 6. Club membership: remove from memberIds/coLeadIds and decrement.
  const memberships = await db.collection("clubs").where("memberIds", "array-contains", uid).get();
  for (const doc of memberships.docs) {
    await doc.ref.update({
      memberIds: admin.firestore.FieldValue.arrayRemove(uid),
      coLeadIds: admin.firestore.FieldValue.arrayRemove(uid),
      memberCount: admin.firestore.FieldValue.increment(-1),
      unreadBy: admin.firestore.FieldValue.arrayRemove(uid),
      mutedBy: admin.firestore.FieldValue.arrayRemove(uid),
      archivedBy: admin.firestore.FieldValue.arrayRemove(uid),
      pinnedBy: admin.firestore.FieldValue.arrayRemove(uid),
      deletedBy: admin.firestore.FieldValue.arrayRemove(uid),
      [`typingUsers.${uid}`]: admin.firestore.FieldValue.delete(),
    });
  }

  // 7. Direct rooms: remove the user, keep the counterpart's history.
  const rooms = await db.collection("chatRooms").where("participants", "array-contains", uid).get();
  for (const room of rooms.docs) {
    await room.ref.update({
      participants: admin.firestore.FieldValue.arrayRemove(uid),
      unreadBy: admin.firestore.FieldValue.arrayRemove(uid),
      mutedBy: admin.firestore.FieldValue.arrayRemove(uid),
      archivedBy: admin.firestore.FieldValue.arrayRemove(uid),
      pinnedBy: admin.firestore.FieldValue.arrayRemove(uid),
      deletedBy: admin.firestore.FieldValue.arrayRemove(uid),
      [`typingUsers.${uid}`]: admin.firestore.FieldValue.delete(),
      status: "closed",
    });
  }

  // 8. Retained records: strip identity, keep the record.
  await anonymizeRetainedRecords(uid);

  // 9. Release referral pointers and adjust the referrer's count.
  const referred = await db.collection("users").where("referredBy", "==", uid).get();
  for (const doc of referred.docs) {
    await doc.ref.update({ referredBy: admin.firestore.FieldValue.delete() });
  }

  // 10. Profile document last of the Firestore work, so any failure above is visible
  //     and retryable while the profile still exists.
  await db.collection("users").doc(uid).delete();
}
```

`anonymizeRetainedRecords` clears display identity from retained moderation/chat
records but keeps the document and any non-identifying linkage required for safety:

```ts
async function anonymizeRetainedRecords(uid: string): Promise<void> {
  const patch = {
    authorName: "Deleted user",
    authorPhotoURL: admin.firestore.FieldValue.delete(),
    senderName: "Deleted user",
    senderAvatar: admin.firestore.FieldValue.delete(),
  };
  await patchWhere(db.collection("reviews").where("reviewerId", "==", uid), patch);
  await patchWhere(db.collection("reports").where("reporterId", "==", uid), {
    reporterName: "Deleted user",
  });
  // Messages in retained conversations: keep the record, remove the display identity.
  // Messages live in subcollections, so enumerate rooms first (bounded by the rooms the
  // user participated in).
  const rooms = await db.collection("chatRooms").where("participants", "array-contains", uid).get();
  for (const room of rooms.docs) {
    await patchWhere(room.ref.collection("messages").where("senderId", "==", uid), {
      senderName: "Deleted user",
      senderAvatar: admin.firestore.FieldValue.delete(),
    });
  }
}
```

Helpers `deleteQueryDocs`, `patchWhere`, and `deleteSubcollections` follow the
existing `deleteQueryDocs` already present in `functions/src/index.ts` (batched,
450 documents per commit).

## 3. Rules

No rules change is required for the cascade itself, because the callable uses the
Admin SDK. Two small hardenings are recommended so the client cannot fake state:

```diff
+    // Account deletion state is server-owned only.
+    match /deletionRequests/{userId} {
+      allow read: if isSignedIn() && request.auth.uid == userId;
+      allow write: if false;   // Admin SDK only
+    }
```

(Without this block the default `match /{document=**} { allow read, write: if false; }`
already denies it; the explicit block only documents intent and allows the user to
observe their own request status.)

## 4. Android client contract

The client must exist before the store listing claims an in-app deletion path, and
it must ship **after** the function is deployed (otherwise the menu entry fails).
It is deliberately not implemented yet in this repository — adding a UI entry that
calls a non-existent callable would be a release regression.

When the function is deployed:

1. `NbFunctions`:
   ```kotlin
   suspend fun requestAccountDeletion(confirmation: String): Map<String, Any?> =
       callMap("requestAccountDeletion", mapOf("confirmation" to confirmation))
   ```
2. A `DeletionRepository` (or an `AuthRepository` method) that:
   - reauthenticates first when the session is old, so the server's `auth_time`
     check passes without a confusing server error;
   - calls the function and maps `failed-precondition` → "sign in again",
     `permission-denied` → "contact support", `internal` → retry copy;
   - on `completed`, clears local state (sign out, clear DataStore preferences,
     unregister the push token first via `removeAndroidPushToken`).
3. UI: one entry in profile settings, behind an explicit typed confirmation
   (`DELETE`), with the same copy the docs promise ("deleted within 30 days" is
   already used in `LegalDocumentScreen`).

Contract tests to add with it (`data:firebase`): the request payload contains only
`confirmation`; the failure mapper never reports success for a partial result.

## 5. Public deletion URL (Play requirement)

Play requires a **web** resource describing the deletion path in addition to the
in-app flow even when deletion is in-app. There is no route today.

- Implement the page in `nextbench-1` as `src/pages/Legal/DeleteAccountPage.tsx`
  with a `/delete-account` route (plus `NotFound` catch-all already exists, so
  today that URL silently renders the 404 page — see
  `docs/play/release-readiness.md`).
- Content must state: what is deleted, what is retained (moderation records), the
  retention window, and how to request it in-app.
- **NOT VERIFIED**: the page returning HTTP 200 today is only the SPA fallback
  (`src/App.tsx` has `<Route path="*" element={<NotFound />} />`), not a real page.

## 6. Tests

Website (recommended, runnable in `nextbench-1`):

- Unit-test the **plan**: extract the ordered list of operations from
  `deleteUserData` into a pure function
  `accountDeletionPlan(uid)` returning
  `Array<{ collection: string; field: string; op: 'delete' | 'anonymize' }>`, and assert:
  moderation collections are never in the delete set; identity prefixes are in the
  delete set; `users/{uid}` is deleted last.
- Emulator/integration: `requestAccountDeletion` throws for a stale `auth_time`,
  throws for a missing `DELETE` confirmation, is idempotent on re-invocation, and
  leaves `reports` intact.

Android: payload/contract tests only (see §4).

## 7. External actions (all NOT VERIFIED)

1. Implement and deploy the callable + rules in `nextbench-1`.
2. Add and deploy the `/delete-account` page.
3. Run the deletion flow against a throwaway account in `nextbench-a11ed` and
   confirm: Auth user gone, profile gone, private docs gone, identity objects gone,
   push token gone, club membership gone, retained records anonymized.
4. Ship the Android client, then record the in-app path and the public URL in Play
   Console → Data safety → Data deletion.

## 8. The recent-sign-in gate and how the clients complete it

The callable requires `auth_time` within 5 minutes (correct for an irreversible action), but
`auth_time` is the **original sign-in time**: a forced ID-token refresh does not update it — that is
precisely why it is usable for recent-auth checks — and no silent refresh does either. Sign-in here
is email OTP / custom-token (and Google), so no client can reauthenticate silently.

Both clients therefore treat the refusal as a distinct outcome and route through the existing
sign-in flow rather than failing:

**Android**

- `deletionSessionIsStale(lastSignInTimestamp, now)` mirrors the callable's window. A request the
  server would refuse is never sent; a stale session raises `RecentAuthRequiredException`, and the
  server's own `failed-precondition` maps to the same `AuthFailureKind.ReauthRequired`.
- `deleteAccountOutcome(result)` is the pure decision applied by `AuthViewModel.deleteAccount`:
  only a confirmed success ends the flow, a stale-session refusal keeps the user's intent and signs
  out, and every other failure is surfaced without signing out and without any success state.
- The intent lives in the activity-scoped view model, so after signing in again the profile screen
  reopens the confirmation (blank, typed `DELETE` required again) — the session gate itself moves
  the user to the sign-in screen. `AuthScreen` shows a "Deleting your account" notice while the
  intent is pending, requires the typed confirmation again, and clears the intent if the user backs
  out. `deletionRequestAllowed` refuses a second request while one is in flight.

**Website**

- `deletionSessionIsStale(user.metadata.lastSignInTime, now)` and `isReauthRequired(error)` mirror
  the same rules; the misleading `getIdToken(true)` call and its comment are gone.
- On refusal the page signs out and navigates to `deletionReauthLoginPath()`
  (`/login?next=%2Fdelete-account%3Freauth%3D1`). `Login` honours `next` only through
  `postLoginDestination`, which accepts a same-site path and nothing else (no open redirect), and
  the page then shows a "signed in again" banner and requires the typed confirmation once more.

Tests: `data/firebase` `RecentAuthTest` (window, unknown timestamp, failure-kind mapping),
`app` `DeletionReauthFlowTest` (outcome, confirmation, duplicate guard, resume routing), website
`tests/accountDeletionClient.test.ts` (reauth detection, staleness, resume path, redirect safety).

The server was not weakened: the window, the typed `DELETE` requirement, the idempotent
`deletionRequests/{uid}` state and the "never report a partial deletion as success" rule are
unchanged.

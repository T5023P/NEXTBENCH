# Club-message block enforcement (corrected analysis)

Status: **analysis only. No rule change is proposed here, because the change that
was previously drafted in this file was wrong and would have broken all club
chat.**

This file supersedes `docs/firestore-club-message-block-patch.md` (same content
location, renamed). The previous draft proposed adding a block condition to the
`allow list` rule of `clubs/{clubId}/messages`:

```diff
         allow list: if isSignedIn()
-          && request.auth.uid in get(.../clubs/$(clubId)).data.memberIds;
+          && request.auth.uid in get(.../clubs/$(clubId)).data.memberIds
+          && !hasBlockRelationship(request.auth.uid, resource.data.senderId);
```

**That patch must not be applied.** See "Why it cannot work" below.

The canonical rules are **not** part of this Android workspace. They live in the
website repository `maryamfatimadesigns-ux/nextbench-1` at `firestore.rules`
(the Android `firebase.json` intentionally declares only the isolated
`android-push` Functions codebase and never owns rules).

Verified against website `main` (`firestore.rules`, 1029 lines).

## The gap (re-verified against current source)

Direct messages are block-scoped through the room:

```text
function canAccessRoom(data) {
  return isSignedIn()
    && request.auth.uid in data.participants
    && !hasBlockRelationship(request.auth.uid, otherParticipant(data.participants));
}

match /chatRooms/{roomId}/messages/{messageId} {
  allow list:   if canAccessRoom(get(.../chatRooms/$(roomId)).data);
  allow create: if ... && canAccessRoom(get(.../chatRooms/$(roomId)).data) && ...;
}
```

Club messages have **no** block condition:

```text
match /clubs/{clubId}/messages/{messageId} {
  allow list:   if isSignedIn() && request.auth.uid in get(.../clubs/$(clubId)).data.memberIds;
  allow create: if isVerified() && request.auth.uid == incoming().senderId && ... ;
}
```

Club membership is not block-scoped, so a blocked pair who share a club can still
write and read each other's club messages. The push for it is already suppressed
server-side (`functions-android`, `notifyAndroidOnNewClubMessage`), but the
shared message document stays reachable.

## Why it cannot work: rules are not filters

From the Firestore documentation ("Securely query data", *Rules are not
filters*):

> Rules evaluates a query against its potential result set instead of the actual
> field values for all of your documents. If a query could potentially return
> documents that the client does not have permission to read, the entire request
> fails.

A `list` rule may reference `resource.data` only when the incoming query
constrains that same field, so the engine can prove the rule holds for every
potential result. The documented example: a rule
`allow read: if resource.data.published == true` is satisfied only by a query
that includes `.where("published", "==", true)`.

Applying that to the drafted patch:

1. The club chat query is
   `refs.clubMessages(clubId).orderBy("createdAt").limitToLast(100)` — it places
   **no** constraint on `senderId`.
2. The patched rule depends on `resource.data.senderId`, which the query does not
   constrain. The engine cannot prove the rule for the potential result set, so
   **the query is denied in full**.
3. The denial applies to *every* member, including members with no blocks at all,
   because the rule is evaluated against the query, not against the documents
   that happen to exist.
4. `hasBlockRelationship` is built on `exists()`, which adds two more
   document reads per evaluation and is likewise not decidable from an
   unconstrained query.

Net effect: the drafted patch would have turned club chat into a hard
`PERMISSION_DENIED` for all users. It is not a stricter rule; it is an outage.

The same reasoning rules out the obvious follow-up of adding a block conjunct to
`chatRooms`:

```text
allow list: if isSignedIn() && request.auth.uid in resource.data.participants;
```

This rule is decidable today only because the client query includes
`whereArrayContains("participants", uid)`. `otherParticipant(resource.data.participants)`
depends on a value the query does not constrain, so adding
`!hasBlockRelationship(request.auth.uid, otherParticipant(...))` here would deny
every inbox query, not just blocked rooms.

## Where the invariant is enforced instead

A block must hold across four surfaces. Each is enforced at a layer that can
actually express it:

| Surface | Enforcement layer | Status |
| --- | --- | --- |
| Direct room create/get/messages | `firestore.rules` (`canAccessRoom`) | Present in canonical rules |
| Direct inbox listing (blocked room visible in list) | Client query filter, because rules cannot filter a query | Android: implemented (`ChatRepository.observeRooms` drops rooms whose other participant is in a block relationship) |
| Push notification for direct/club message | Server trigger: `functions-android` `findBlockedRecipientIds` / `excludeBlockedRecipients` | Implemented |
| In-app notification document | Server callable: website `createNotification` throws `permission-denied` when `hasBlockRelationship(uid, userId)` | Present in canonical source |
| **Club message transcript** | Client read filter (rules cannot filter; a rule would deny the whole query) | Android: implemented (`ClubRepository.observeMessages` + `visibleClubMessages`). Website: **implemented** (`src/hooks/useChatEngine.ts` `sortedFromMap` + `hiddenSenderIds`, fed by `useAllBlockedUserIds` in `src/components/chat/ChatView.tsx`) |

### Android implementation (this repository)

`ClubRepository.observeMessages(clubId, uid)` now combines the club-message
window with the viewer's block relationships in both directions — the same two
`blocks` queries `ChatRepository.observeRooms` already uses — and passes them
through the pure helper `visibleClubMessages(messages, blockedSenderIds, viewerId)`:

- messages the viewer deleted for themselves stay hidden;
- a message authored by the viewer is never hidden by the viewer's own blocks
  (a block is one-directional in intent even though the document is a pair);
- chronological order is preserved.

The `blocks` list rule permits both directions:
`resource.data.blockerId == request.auth.uid || resource.data.blockedId == request.auth.uid`,
so no rule change is needed to read the viewer's own block set.

Covered by `data:firebase` tests
(`ClubRepositoryContractTest`: blocked sender hidden, own messages never hidden,
deleted-for-self preserved, ordering preserved).

### Website implementation (done)

Both platforms now filter the same way, so behavior is consistent:

- `useChatEngine` accepts `hiddenSenderIds` and excludes those senders in
  `sortedFromMap`, alongside the existing deleted-for-me filter. The viewer's own
  messages are never hidden.
- `ChatView` supplies it from `useAllBlockedUserIds(isClub)` — the union of both
  block directions, read through the existing `blocks` hooks — and only for the
  club transcript, since a DM block already makes the whole room inaccessible.
- Because the message map is held in a ref, a second effect re-derives the visible
  list when the viewer's block set changes, so blocking someone while their
  messages are on screen removes them without re-subscribing.
- `useBlockedIds` / `useBlockedByIds` / `useAllBlockedUserIds` gained an `enabled`
  flag so DM chat views do not open block listeners they never use.

Note the two other `clubs/*/messages` reads in `src/lib/clubs.ts` (`deleteClub`)
and `src/pages/Dashboard/ClubChat.tsx` (`handleClearChat`) are owner-initiated
bulk operations on the caller's own club, not transcript rendering, so they are
correctly left unfiltered.

## Residual risk if only read filtering is used

Read filtering is defence in depth, not a boundary: a client that chooses not to
filter can still read the message documents, because the rules permit the query.
If a hard guarantee is required, the only options that are expressible are:

1. **Membership-level invariant** — remove both members from any shared club when
   a block is created (server-side, in the same transaction/trigger family as the
   existing follow cleanup). This is a product decision: it changes club
   membership as a side effect of blocking.
2. **Broker club reads through a callable** — the server filters blocked senders
   and returns only allowed messages; the direct subcollection read is then
   denied for clients. This costs a callable per transcript and loses the live
   snapshot on the club-message path.

Neither should be started without an explicit product decision. Recorded here so
the trade-off is not rediscovered later.

## Tests

Android: `data/firebase/src/test/.../ClubRepositoryContractTest.kt`
(four focused tests on `visibleClubMessages`).

Website (recommended, when the club chat filter is added): an emulator/unit test
asserting that a viewer in a block relationship does not receive the blocked
sender's messages from the club-chat loader, plus a regression test asserting the
club-message `list` query is **still allowed** (guarding against re-introducing
the query-denying rule described above).

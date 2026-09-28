import { createHash } from "node:crypto";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging, MulticastMessage } from "firebase-admin/messaging";
import { initializeApp } from "firebase-admin/app";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import {
  blockDocIds,
  blockedRecipientIds,
  excludeBlockedRecipients,
  messagePreview,
  messageRecipients,
  stringList,
  stringValue,
} from "./message";

initializeApp();

export * from "./search";

const db = getFirestore();
const messaging = getMessaging();
const MAX_TOKEN_LENGTH = 4096;
const MAX_TOKENS_PER_USER = 10;
const MAX_RECIPIENTS = 50;

export const registerAndroidPushToken = onCall(async (request) => {
  const uid = requireAuth(request.auth?.uid);
  const token = validateToken(request.data?.token);

  const userRef = db.collection("users").doc(uid);
  const registryRef = db.collection("androidPushTokens").doc(tokenId(token));
  await db.runTransaction(async (transaction) => {
    const registrySnap = await transaction.get(registryRef);
    const previousOwnerId = stringValue(registrySnap.data()?.userId);
    const previousOwnerRef = previousOwnerId && previousOwnerId !== uid
      ? db.collection("users").doc(previousOwnerId)
      : null;
    const [userSnap, previousOwnerSnap] = await Promise.all([
      transaction.get(userRef),
      previousOwnerRef ? transaction.get(previousOwnerRef) : Promise.resolve(null),
    ]);
    const nextTokens = [
      ...stringList(userSnap.data()?.androidFcmTokens).filter((existing) => existing !== token),
      token,
    ].slice(-MAX_TOKENS_PER_USER);

    transaction.set(userRef, { androidFcmTokens: nextTokens }, { merge: true });
    if (previousOwnerRef && previousOwnerSnap?.exists) {
      transaction.set(
        previousOwnerRef,
        { androidFcmTokens: FieldValue.arrayRemove(token) },
        { merge: true },
      );
    }
    transaction.set(registryRef, {
      userId: uid,
      updatedAt: FieldValue.serverTimestamp(),
    });
  });

  return { success: true };
});

export const removeAndroidPushToken = onCall(async (request) => {
  const uid = requireAuth(request.auth?.uid);
  const token = validateToken(request.data?.token);

  const userRef = db.collection("users").doc(uid);
  const registryRef = db.collection("androidPushTokens").doc(tokenId(token));
  await db.runTransaction(async (transaction) => {
    const registrySnap = await transaction.get(registryRef);
    transaction.set(
      userRef,
      { androidFcmTokens: FieldValue.arrayRemove(token) },
      { merge: true },
    );
    if (stringValue(registrySnap.data()?.userId) === uid) transaction.delete(registryRef);
  });

  return { success: true };
});

/** Returns relationship summaries for the Android public-profile surface. */
export const getAndroidPublicProfileStats = onCall(async (request) => {
  const viewerId = requireAuth(request.auth?.uid);
  const targetId = stringValue(request.data?.userId);
  if (!targetId) throw new HttpsError("invalid-argument", "Missing userId.");
  if (viewerId !== targetId && await hasBlockRelationship(viewerId, targetId)) return emptyProfileStats();

  const [followersSnap, followingSnap, viewerFollowingSnap, viewerFollowersSnap] = await Promise.all([
    db.collection("follows").where("followingId", "==", targetId).limit(100).get(),
    db.collection("follows").where("followerId", "==", targetId).limit(100).get(),
    db.collection("follows").where("followerId", "==", viewerId).limit(100).get(),
    db.collection("follows").where("followingId", "==", viewerId).limit(100).get(),
  ]);
  const followerIds = uniqueIds(followersSnap.docs.map((doc) => stringValue(doc.get("followerId"))));
  const followingIds = uniqueIds(followingSnap.docs.map((doc) => stringValue(doc.get("followingId"))));
  const viewerFollowingIds = new Set(uniqueIds(viewerFollowingSnap.docs.map((doc) => stringValue(doc.get("followingId")))));
  const viewerFollowerIds = new Set(uniqueIds(viewerFollowersSnap.docs.map((doc) => stringValue(doc.get("followerId")))));
  const targetNetwork = new Set([...followerIds, ...followingIds]);
  const mutualIds = [...targetNetwork].filter((id) => id !== viewerId && (viewerFollowingIds.has(id) || viewerFollowerIds.has(id)));
  const displayIds = uniqueIds([...followerIds.slice(0, 50), ...followingIds.slice(0, 50), ...mutualIds.slice(0, 3)]);
  const userDocs = displayIds.length > 0 ? await db.getAll(...displayIds.map((id) => db.collection("users").doc(id))) : [];
  const users = new Map(userDocs.map((doc) => [doc.id, publicProfileUser(doc)]));

  return {
    followersCount: followerIds.length,
    followingCount: followingIds.length,
    followers: followerIds.slice(0, 50).map((id) => users.get(id)).filter(Boolean),
    following: followingIds.slice(0, 50).map((id) => users.get(id)).filter(Boolean),
    mutuals: mutualIds.slice(0, 3).map((id) => users.get(id)).filter(Boolean),
    mutualCount: mutualIds.length,
    isFollowing: followerIds.includes(viewerId),
    isFollowedBy: followingIds.includes(viewerId),
  };
});

/** Sends Android chat pushes and creates the matching in-app notifications. */
export const notifyAndroidOnNewMessage = onDocumentCreated(
  "chatRooms/{roomId}/messages/{messageId}",
  async (event) => {
    const message = event.data?.data();
    if (!message) return;

    const senderId = stringValue(message.senderId);
    const roomId = event.params.roomId;
    const messageId = event.params.messageId;
    if (!senderId || !roomId || !messageId) return;

    const roomSnap = await db.collection("chatRooms").doc(roomId).get();
    if (!roomSnap.exists) return;
    const room = roomSnap.data() ?? {};
    const participants = messageRecipients(room.participants, senderId, room.mutedBy, MAX_RECIPIENTS);
    if (participants.length === 0) return;

    const senderSnap = await db.collection("users").doc(senderId).get();
    const senderName = stringValue(message.senderName) || stringValue(senderSnap.data()?.name) || "Someone";
    const preview = messagePreview(message);
    const title = `${senderName} sent you a message`;
    const link = `/chat/${roomId}`;

    const recipientDocs = await Promise.all(
      participants.map(async (recipientId) => {
        const notificationId = `chat_${messageId}_${recipientId}`;
        const ref = db.collection("notifications").doc(notificationId);
        await ref.create({
          userId: recipientId,
          type: "new_message",
          title,
          message: preview,
          link,
          read: false,
          createdAt: FieldValue.serverTimestamp(),
        }).catch((error: unknown) => {
          if (!isAlreadyExists(error)) throw error;
        });
        return { recipientId, notificationId };
      }),
    );

    const users = await Promise.all(
      recipientDocs.map(({ recipientId }) => db.collection("users").doc(recipientId).get()),
    );
    const tokenOwners: Array<{ uid: string; token: string }> = [];
    users.forEach((userSnap, index) => {
      stringList(userSnap.data()?.androidFcmTokens).forEach((token) => {
        tokenOwners.push({ uid: recipientDocs[index].recipientId, token });
      });
    });
    if (tokenOwners.length === 0) return;
    const deliveryTargets = tokenOwners.slice(0, 500);

    const payload: MulticastMessage = {
      tokens: deliveryTargets.map(({ token }) => token),
      // notification block lets Android show the message even when the app is killed
      notification: { title, body: preview },
      data: {
        title,
        body: preview,
        message: preview,
        type: "new_message",
        link,
        roomId,
        senderId,
        senderName,
        notificationId: `chat_${roomId}`,
      },
      android: {
        priority: "high",
        notification: { channelId: "nextbench_messages" },
      },
    };
    const result = await messaging.sendEachForMulticast(payload);
    const invalidTokens = result.responses
      .map((response, index) => response.success ? null : ({
        uid: deliveryTargets[index].uid,
        token: deliveryTargets[index].token,
        code: response.error?.code ?? "",
      }))
      .filter((value): value is { uid: string; token: string; code: string } => value !== null)
      .filter(({ code }) => code.includes("registration-token-not-registered") || code.includes("invalid-registration-token"));

    await Promise.all(invalidTokens.map(async ({ uid, token }) => {
      const batch = db.batch();
      batch.set(
        db.collection("users").doc(uid),
        { androidFcmTokens: FieldValue.arrayRemove(token) },
        { merge: true },
      );
      batch.delete(db.collection("androidPushTokens").doc(tokenId(token)));
      await batch.commit();
    }));
  },
);

/** Sends Android pushes for club messages without exposing private club data. */
export const notifyAndroidOnNewClubMessage = onDocumentCreated(
  "clubs/{clubId}/messages/{messageId}",
  async (event) => {
    const message = event.data?.data();
    if (!message) return;

    const senderId = stringValue(message.senderId);
    const clubId = event.params.clubId;
    const messageId = event.params.messageId;
    if (!senderId || !clubId || !messageId) return;

    const clubSnap = await db.collection("clubs").doc(clubId).get();
    if (!clubSnap.exists) return;
    const club = clubSnap.data() ?? {};
    const memberRecipients = messageRecipients(club.memberIds, senderId, club.mutedBy, MAX_RECIPIENTS);
    // Club membership is not block-scoped. Unlike `chatRooms`, whose message rule requires
    // canAccessRoom (and canAccessRoom checks hasBlockRelationship), `clubs/{clubId}/messages` has
    // no block condition, so a blocked pair who share a club can still exchange club messages. The
    // filter must therefore happen here, or the push and its in-app notification reach a blocked
    // member. Muted and sender filtering already happened in messageRecipients.
    const recipients = excludeBlockedRecipients(
      memberRecipients,
      await findBlockedRecipientIds(senderId, memberRecipients),
    );
    if (recipients.length === 0) return;

    const senderSnap = await db.collection("users").doc(senderId).get();
    const senderName = stringValue(message.senderName) || stringValue(senderSnap.data()?.name) || "Someone";
    const clubName = stringValue(club.name) || "your club";
    const preview = messagePreview(message);
    const title = `${senderName} in ${clubName}`;
    const link = `/club/${clubId}`;

    const recipientDocs = await Promise.all(
      recipients.map(async (recipientId) => {
        const notificationId = `club_${messageId}_${recipientId}`;
        const ref = db.collection("notifications").doc(notificationId);
        await ref.create({
          userId: recipientId,
          type: "new_message",
          title,
          message: preview,
          link,
          read: false,
          createdAt: FieldValue.serverTimestamp(),
        }).catch((error: unknown) => {
          if (!isAlreadyExists(error)) throw error;
        });
        return { recipientId, notificationId };
      }),
    );

    const users = await Promise.all(
      recipientDocs.map(({ recipientId }) => db.collection("users").doc(recipientId).get()),
    );
    const tokenOwners: Array<{ uid: string; token: string }> = [];
    users.forEach((userSnap, index) => {
      stringList(userSnap.data()?.androidFcmTokens).forEach((token) => {
        tokenOwners.push({ uid: recipientDocs[index].recipientId, token });
      });
    });
    if (tokenOwners.length === 0) return;
    const deliveryTargets = tokenOwners.slice(0, 500);
    const payload: MulticastMessage = {
      tokens: deliveryTargets.map(({ token }) => token),
      notification: { title, body: preview },
      data: {
        title,
        body: preview,
        message: preview,
        type: "new_message",
        link,
        clubId,
        senderId,
        senderName,
        notificationId: `club_${clubId}`,
      },
      android: {
        priority: "high",
        notification: { channelId: "nextbench_messages" },
      },
    };
    const result = await messaging.sendEachForMulticast(payload);
    const invalidTokens = result.responses
      .map((response, index) => response.success ? null : ({
        uid: deliveryTargets[index].uid,
        token: deliveryTargets[index].token,
        code: response.error?.code ?? "",
      }))
      .filter((value): value is { uid: string; token: string; code: string } => value !== null)
      .filter(({ code }) => code.includes("registration-token-not-registered") || code.includes("invalid-registration-token"));

    await Promise.all(invalidTokens.map(async ({ uid, token }) => {
      const batch = db.batch();
      batch.set(
        db.collection("users").doc(uid),
        { androidFcmTokens: FieldValue.arrayRemove(token) },
        { merge: true },
      );
      batch.delete(db.collection("androidPushTokens").doc(tokenId(token)));
      await batch.commit();
    }));
  },
);

function requireAuth(uid: string | undefined): string {
  if (!uid) throw new HttpsError("unauthenticated", "Authentication is required.");
  return uid;
}

function blockDocRef(blockerId: string, blockedId: string) {
  return db.collection("blocks").doc(`${blockerId}_${blockedId}`);
}

async function hasBlockRelationship(firstId: string, secondId: string): Promise<boolean> {
  const [first, second] = await Promise.all([
    blockDocRef(firstId, secondId).get(),
    blockDocRef(secondId, firstId).get(),
  ]);
  return first.exists || second.exists;
}

/**
 * Recipients that are in a block relationship with the sender, in either direction. Read with the
 * admin credential, so the result is authoritative rather than client-reported, and fetched in a
 * single round trip so a full recipient batch costs one additional read.
 */
async function findBlockedRecipientIds(senderId: string, recipientIds: string[]): Promise<string[]> {
  if (recipientIds.length === 0) return [];
  const refs = blockDocIds(senderId, recipientIds).map((id) => db.collection("blocks").doc(id));
  const docs = await db.getAll(...refs);
  return blockedRecipientIds(
    recipientIds,
    docs.map((doc) => doc.exists),
  );
}

function uniqueIds(ids: string[]): string[] {
  return [...new Set(ids.filter((id) => id.length > 0))];
}

function publicProfileUser(doc: FirebaseFirestore.DocumentSnapshot): Record<string, unknown> {
  const data = doc.data() ?? {};
  return {
    id: doc.id,
    name: stringValue(data.name) || "Student",
    username: stringValue(data.username) || null,
    school: stringValue(data.school),
    city: stringValue(data.city),
    about: stringValue(data.about) || null,
    profilePicture: stringValue(data.profilePicture) || null,
    verified: data.verified === true,
    reputation: typeof data.reputation === "number" ? data.reputation : 0,
  };
}

function emptyProfileStats() {
  return {
    followersCount: 0,
    followingCount: 0,
    followers: [],
    following: [],
    mutuals: [],
    mutualCount: 0,
    isFollowing: false,
    isFollowedBy: false,
  };
}

function validateToken(value: unknown): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > MAX_TOKEN_LENGTH) {
    throw new HttpsError("invalid-argument", "A valid Android push token is required.");
  }
  return value.trim();
}

function isAlreadyExists(error: unknown): boolean {
  if (typeof error !== "object" || error === null || !("code" in error)) return false;
  const code = (error as { code?: unknown }).code;
  return code === 6 || code === "6" || code === "already-exists";
}

function tokenId(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

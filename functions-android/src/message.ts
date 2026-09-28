export function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

export function stringList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string" && item.trim().length > 0)
    : [];
}

export function messageRecipients(
  members: unknown,
  senderId: string,
  mutedMembers: unknown,
  limit: number,
): string[] {
  const muted = new Set(stringList(mutedMembers));
  return [...new Set(stringList(members))]
    .filter((uid) => uid !== senderId && !muted.has(uid))
    .slice(0, limit);
}

/** `${blockerId}_${blockedId}` — the `blocks` document identity the clients and rules both use. */
export function blockDocId(blockerId: string, blockedId: string): string {
  return `${blockerId}_${blockedId}`;
}

/**
 * The `blocks` document ids that would prove a relationship between the sender and each recipient,
 * in both directions. Index `2n` and `2n + 1` both belong to `recipientIds[n]`.
 */
export function blockDocIds(senderId: string, recipientIds: string[]): string[] {
  return recipientIds.flatMap((recipientId) => [
    blockDocId(senderId, recipientId),
    blockDocId(recipientId, senderId),
  ]);
}

/**
 * Recipients who are in a block relationship with the sender, read from the paired existence flags
 * produced for [blockDocIds]. Either direction blocks delivery.
 */
export function blockedRecipientIds(recipientIds: string[], blockDocExists: boolean[]): string[] {
  return recipientIds.filter(
    (_, index) => blockDocExists[index * 2] === true || blockDocExists[index * 2 + 1] === true,
  );
}

/** Removes recipients in a block relationship with the sender. Everyone else is untouched. */
export function excludeBlockedRecipients(recipientIds: string[], blockedIds: string[]): string[] {
  if (blockedIds.length === 0) return recipientIds;
  const blocked = new Set(blockedIds);
  return recipientIds.filter((recipientId) => !blocked.has(recipientId));
}

export function messagePreview(message: Record<string, unknown>): string {
  const text = stringValue(message.text);
  if (text) return text.slice(0, 160);
  if (message.image) return "Sent you a photo";
  if (message.video) return "Sent you a video";
  if (message.file) return "Sent you a document";
  if (message.audioUrl || message.type === "voice") return "Sent you a voice message";
  return "Sent you a message";
}

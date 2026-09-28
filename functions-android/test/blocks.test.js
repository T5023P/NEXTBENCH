const assert = require("node:assert/strict");
const test = require("node:test");
const {
  blockDocId,
  blockDocIds,
  blockedRecipientIds,
  excludeBlockedRecipients,
  messageRecipients,
} = require("../lib/message.js");

test("blockDocId matches the identity the rules and clients use", () => {
  assert.equal(blockDocId("alice", "bob"), "alice_bob");
  // Direction matters: the reverse block is a different document.
  assert.equal(blockDocId("bob", "alice"), "bob_alice");
});

test("blockDocIds pairs both directions per recipient in order", () => {
  assert.deepEqual(blockDocIds("sender", ["a", "b"]), ["sender_a", "a_sender", "sender_b", "b_sender"]);
  assert.deepEqual(blockDocIds("sender", []), []);
});

test("blockedRecipientIds flags a block in either direction", () => {
  const recipients = ["a", "b", "c"];

  // a: the sender blocked a -> sender_a exists
  // b: b blocked the sender -> b_sender exists
  // c: no relationship
  assert.deepEqual(blockedRecipientIds(recipients, [true, false, false, true, false, false]), ["a", "b"]);
  assert.deepEqual(blockedRecipientIds(recipients, [false, false, false, false, false, false]), []);
  assert.deepEqual(blockedRecipientIds(recipients, [true, true, true, true, true, true]), recipients);
});

test("blockedRecipientIds ignores missing or short flag lists", () => {
  assert.deepEqual(blockedRecipientIds(["a"], []), []);
  assert.deepEqual(blockedRecipientIds(["a", "b"], [true, false]), ["a"]);
});

test("excludeBlockedRecipients keeps order and leaves everyone else untouched", () => {
  assert.deepEqual(excludeBlockedRecipients(["a", "b", "c"], ["b"]), ["a", "c"]);
  assert.deepEqual(excludeBlockedRecipients(["a", "b", "c"], []), ["a", "b", "c"]);
  assert.deepEqual(excludeBlockedRecipients(["a", "b"], ["a", "b", "not-a-recipient"]), []);
});

test("club recipients combine sender, muted, and block filtering", () => {
  const members = ["sender", "member-1", "member-2", "muted"];
  const recipients = messageRecipients(members, "sender", ["muted"], 10);
  assert.deepEqual(recipients, ["member-1", "member-2"]);

  // member-2 has blocked the sender; member-1 is unaffected.
  const kept = excludeBlockedRecipients(
    recipients,
    blockedRecipientIds(recipients, [false, false, true, false]),
  );
  assert.deepEqual(kept, ["member-1"]);
});

test("a club where every recipient is blocked yields no delivery targets", () => {
  const recipients = messageRecipients(["sender", "member-1"], "sender", [], 10);
  const kept = excludeBlockedRecipients(
    recipients,
    blockedRecipientIds(recipients, [true, true]),
  );

  assert.deepEqual(kept, []);
});

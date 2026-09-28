const assert = require("node:assert/strict");
const test = require("node:test");
const { messagePreview, messageRecipients, stringList, stringValue } = require("../lib/message.js");

test("messagePreview prefers and limits text", () => {
  assert.equal(messagePreview({ text: "  hello there  ", image: "photo" }), "hello there");
  assert.equal(messagePreview({ text: "x".repeat(200) }).length, 160);
});

test("messagePreview describes supported attachments", () => {
  assert.equal(messagePreview({ image: {} }), "Sent you a photo");
  assert.equal(messagePreview({ video: {} }), "Sent you a video");
  assert.equal(messagePreview({ file: {} }), "Sent you a document");
  assert.equal(messagePreview({ type: "voice" }), "Sent you a voice message");
  assert.equal(messagePreview({}), "Sent you a message");
});

test("string helpers discard invalid values", () => {
  assert.equal(stringValue("  user-1 "), "user-1");
  assert.equal(stringValue(7), "");
  assert.deepEqual(stringList(["user-1", "", null, 7, "user-2"]), ["user-1", "user-2"]);
});

test("messageRecipients removes the sender, muted members, and duplicates", () => {
  assert.deepEqual(
    messageRecipients(["sender", "member-1", "member-1", "muted", "member-2"], "sender", ["muted"], 10),
    ["member-1", "member-2"],
  );
  assert.deepEqual(messageRecipients(["one", "two", "three"], "sender", [], 2), ["one", "two"]);
});

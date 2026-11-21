"use strict";
const { fork } = require("node:child_process");
const assert = require("node:assert/strict");
const { test } = require("node:test");
test("desktop child requests the original status and snapshot packets", async () => {
  const child = fork(require.resolve("./desktop-peer.cjs"), { silent: true });
  const operations = [];
  child.on("message", (packet) => {
    operations.push([packet.channel, packet.op]);
    child.send({ ...packet, ok: true, value: packet.channel === "lh:chatgpt" ? { status: { connected: false } } : { statuses: [], models: {} } });
  });
  assert.equal(await new Promise(resolve => child.once("exit", resolve)), 0);
  assert.deepEqual(operations.sort(), [["lh:chatgpt", "status"], ["lh:subscriptions", "snapshot"]]);
});

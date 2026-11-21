#!/usr/bin/env node
"use strict";

// Fork this fixture from Electron main (or the Vert.x team's Node launcher).
// A successful exit confirms both response envelopes. No credential is requested or printed.
const { connectDesktop } = require("../helpers/desktop-ipc.cjs");
async function main() {
  const bridge = connectDesktop();
  try {
    const [chatgpt, subscriptions] = await Promise.all([
      bridge.request("lh:chatgpt", "status"),
      bridge.request("lh:subscriptions", "snapshot"),
    ]);
    if (!chatgpt?.status || !Array.isArray(subscriptions?.statuses)) throw new Error("Invalid desktop snapshot");
  } finally { bridge.close(); if (process.connected) process.disconnect(); }
}
main().catch(() => { process.exitCode = 1; });

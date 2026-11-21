"use strict";

const { randomUUID } = require("node:crypto");

/** Runs in the inherited Node child of Electron main. Callers consume leases only in the child. */
function connectDesktop(child = process) {
  const pending = new Map();
  const listeners = new Set();
  const allowed = {
    "lh:chatgpt": new Set(["status", "models", "lease"]),
    "lh:subscriptions": new Set(["snapshot", "lease"]),
  };
  function receive(packet) {
    if (!packet || !Object.hasOwn(allowed, packet.channel)) return;
    const request = pending.get(packet.id);
    if (!request) {
      if (packet.op === "changed") for (const listener of listeners) listener(packet);
      return;
    }
    if (packet.channel !== request.channel || request.op !== packet.op) return;
    pending.delete(packet.id);
    clearTimeout(request.timer);
    if (packet.ok) request.resolve(packet.value);
    else request.reject(new Error("Desktop request refused"));
  }
  function close() {
    child.removeListener("message", receive);
    child.removeListener("disconnect", close);
    for (const request of pending.values()) {
      clearTimeout(request.timer);
      request.reject(new Error("Desktop disconnected"));
    }
    pending.clear();
    listeners.clear();
  }
  child.on("message", receive);
  child.once("disconnect", close);
  return {
    close,
    onChange(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    request(channel, op, fields = {}) {
      if (!child.connected || !allowed[channel]?.has(op)) return Promise.reject(new Error("Desktop unavailable"));
      const id = randomUUID();
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { pending.delete(id); reject(new Error("Desktop request timed out")); }, 45_000);
        pending.set(id, { channel, op, timer, resolve, reject });
        child.send({ ...fields, channel, id, op }, (error) => {
          if (error && pending.delete(id)) { clearTimeout(timer); reject(new Error("Desktop send failed")); }
        });
      });
    },
  };
}
module.exports = { connectDesktop };

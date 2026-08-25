/*
 * Node `process` shim for the browser bundle.
 *
 * oref0 is a Node library: determine-basal narrates its reasoning with
 * `process.stderr.write(...)`, unconditionally on the autosens path. esbuild's browser
 * platform does not polyfill Node globals, so in the browser the first such call throws
 * "process is not defined" and EVERY cycle fails — which is why in-browser counterfactuals
 * silently evaluated 0 of 400 requests.
 *
 * esbuild `--inject` rewrites free references to `process` to use this export. Discarding
 * the narration matches the server oracle, which only reads determine-basal's stdout and
 * lets stderr go to the void; the decision itself comes back in rT (rT.reason included).
 */
const write = () => true;

export const process = {
  env: {},
  browser: true,
  argv: [],
  version: '',
  versions: {},
  platform: 'browser',
  stderr: { write },
  stdout: { write },
  nextTick: (fn, ...args) => queueMicrotask(() => fn(...args)),
};

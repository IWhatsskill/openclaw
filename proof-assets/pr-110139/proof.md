# PR #110139 proof transcript

- PR head: `807ecb4c66a01e0639a5d0c10a246e4a89d524b8`
- Surface: actual Control UI channel setup wizard
- Origin class: plain HTTP on a non-trustworthy hostname
- Runtime: repository Control UI E2E server + repository mock Gateway + Playwright Chromium 149.0.7827.55
- Harness note: Playwright routed the non-secure `http://openclaw.test:<ephemeral-port>` origin to the loopback Vite server. Product source files were not modified.

## Browser assertions

- `window.isSecureContext === false`
- `typeof navigator.clipboard === "undefined"`
- Before clicking Copy: zero legacy copy events.
- Clicking the real wizard Copy button invoked `document.execCommand("copy")` exactly once.
- The selected text at that call was exactly `OPENCLAW-PLAIN-HTTP-PROOF-110139`.
- Focus returned to the Copy button after the scratch textarea was removed.
- Result: **PASS**

## Focused validation

```text
node node_modules/vitest/vitest.mjs run --root ui --config vitest.config.ts src/lib/clipboard.test.ts src/pages/model-setup/view.test.ts src/pages/channels/wizard-view.test.ts --reporter=verbose
Test Files  3 passed (3)
Tests       25 passed (25)
Duration    2.22s
```

The browser proof exercises the channel wizard on an actual plain-HTTP Control UI origin. The focused suites also cover the shared helper and model-setup wizard integration.

## Artifact integrity

- Browser screenshot PNG SHA-256: `02ff2cef67e894c847d092f439705b6f3bd2b5a775f61925bc70528ace6014cf`
- Browser video WebM SHA-256: `a513f15116865391ccd453888cacf8b5153c997ba3886c4fb77a5bd119915787`

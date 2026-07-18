# PR #110146 proof transcript

- PR head: `0ac768960f595e2d97aea560b4397a3aa4354f45`
- Surface: actual Control UI channel setup wizard connected to the repository mock Gateway
- Runtime: repository Control UI E2E server + Playwright Chromium 149.0.7827.55
- Time control: Playwright's virtual clock advanced the real UI timeout by exactly `120000 ms`; the production timeout constant was not modified.
- Harness note: product source files were not modified.

## Browser request timeline

1. First `wizard.start({ flow: "channels", channel: "slack" })` was held pending.
2. Advancing the browser clock by 120 seconds rendered `Error: wizard request timed out: wizard.start`.
3. The late response created live session `s-timeout`.
4. The Control UI emitted exactly one `wizard.cancel({ sessionId: "s-timeout" })`.
5. Closing the error modal and starting again emitted the second `wizard.start`.
6. The retry reached a live channel-selection step.
7. Final counts: `wizard.start = 2`, `wizard.cancel = 1`.
8. Result: **PASS**

## Focused controller validation

```text
node node_modules/vitest/vitest.mjs run --root ui --config vitest.config.ts src/pages/channels/wizard-controller.test.ts --reporter=verbose
Test Files  1 passed (1)
Tests       9 passed (9)
Duration    1.31s
```

This includes the timed-out start, late result, cancellation, and successful retry case.

## Real Gateway lifecycle validation

```text
node scripts/run-vitest.mjs src/gateway/gateway.test.ts -t "routes wizard.start flow channels to the channel wizard runner" --reporter=verbose
Test Files  1 passed (1)
Tests       1 passed | 10 skipped (11)
Duration    30.87s
```

## Artifact integrity

- Timeout screenshot PNG SHA-256: `c66daa84d301ef3e427b13d461edfb9ec5b75e75142de27e95f03c9fbf84339d`
- Retry screenshot PNG SHA-256: `eae24dc39f79cce6efb59b6e387d5fad9af13a70cd3d4ed51a00e1120f1d7003`
- Browser video WebM SHA-256: `d9d6e6bb42894b523636c80bc49f9b79c7e3fa33f7e6bf2fb5dc6148f6e204a7`

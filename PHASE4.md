# Phase 4 — Compose ink workspace

Phase 4 adds a native Compose workspace on top of the Phase 1–3 domain, recognition, and reactive-CAS layers.

## Included
- `MainActivity` launcher and dark Material 3 theme (charcoal background, gunmetal surfaces, amber accent, crimson errors).
- A shared pan/zoom `ViewportState` used by the Jetpack Ink wet overlay and dry vector canvas so finished strokes remain in workspace coordinates.
- Stylus-first `InProgressStrokesView` capture with MotionEvent prediction, per-pointer completion/cancellation, and finger pan/pinch gestures.
- Completed-stroke handoff to `InkWorkspaceReducer`, stale-ticket recognition gating, spatial block inspector, live DAG bindings/results, and symbolic/numeric controls.
- Native `JLatexMathDrawable` rendering for CAS results beside the detected result anchor.
- Long-press manual LaTeX dialog calling `HmerRecognitionCoordinator.injectManualLatex` with the current block revision.

## Offline / model behavior
The app remains offline-only; no `INTERNET` permission or WebView is introduced. The ONNX model and vocabulary are optional assets and are not bundled in this repository. Until compatible assets are added under `app/src/main/assets/hmer/`, the coordinator reports model-unavailable status and the inspector's manual LaTeX path remains available. Manual entries are explicitly labeled as test input, not handwriting-recognition output.

## Verification
`ViewportStateTest` covers coordinate round-tripping and zoom anchoring. Run the project's Android Gradle test task on a machine with the Android SDK installed to verify the full Compose/Ink dependency set and instrument the canvas on a device.

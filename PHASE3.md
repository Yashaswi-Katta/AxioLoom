# AxioLoom Phase 3 — Offline HMER inference

## Added

- `HmerVocabulary.kt`: strict line-indexed UTF-8 vocabulary loader, token-to-id/id-to-token lookup, special-token handling, and LaTeX decoding.
- `OnnxHmerEngine.kt`: ONNX Runtime Mobile session lifecycle, CPU/XNNPACK initialization fallback, single incremental graph and named split encoder/decoder contracts, greedy decoding capped at 128 tokens, tensor shape checks, and explicit unavailable/failure results.
- `HmerRecognitionCoordinator.kt`: per-block 500 ms debounce, revision-aware stale-result suppression, and a guarded manual LaTeX injection API for a future long-press test dialog.
- `HmerVocabularyTest.kt`: vocabulary and greedy-logit selection unit tests.

## Asset contract

Place these paired, model-specific assets under `app/src/main/assets/hmer/`:

- `hmer_vocab.txt`: one token per UTF-8 line; the line index is the model token ID; must include `<sos>` and `<eos>`; optional `<pad>` and `<unk>`.
- Either `hmer.onnx`, or both `hmer_encoder.onnx` and `hmer_decoder.onnx`.

The engine deliberately ships no model or vocabulary and does not pretend to recognize handwriting when those assets are absent. It returns `ModelUnavailable`, allowing the Phase 4 long-press UI to call `injectManualLatex(block.ticket, text)` for local test entry. Manual results are marked `MANUAL_TEST`, not model predictions.

## ONNX export requirements

The default single-graph contract takes float NCHW `input_image` (`[1,1,H,W]`), optional float `image_mask` of the same shape, and Int64 `tokens` containing the current prefix (`[1,T]`); `logits` must end in the vocabulary dimension and may include a sequence axis. The engine chooses logits from the final sequence position.

The split contract expects an encoder with `input_image`, optional `image_mask`, and NCHW `feature_map` output. The decoder consumes `feature_map`, Int64 `tokens`, and float `coverage_past` shaped `[1,1,Hfeature,Wfeature]`, then returns `logits` and a float `coverage` tensor with the same coverage shape. This is a deliberately explicit adapter contract—not a claim that arbitrary upstream CoMER/CAN ONNX exports share identical signatures. Configure names in `HmerModelConfig` or export a small model-specific adapter if the graph differs. Confirm the mask convention and preprocessing against the actual checkpoint before shipping.

Sessions are initialized off the UI thread. XNNPACK is attempted first, with CPU-only session creation as a fallback. All execution remains local; no Internet permission or remote inference service is added.

## Integration in Phase 4

1. Construct one `OnnxHmerEngine` and `HmerRecognitionCoordinator` in the ViewModel/application scope.
2. Call `submit(block)` for each newly dirty block; feed the callback through the ViewModel using the supplied `BlockRevision` ticket.
3. Retain the existing revision check before applying recognized LaTeX to a block.
4. On long-press, open a manual LaTeX entry dialog and pass the current `block.ticket` to `injectManualLatex`; a stale ticket returns `false`.
5. Call `forget(id)` when a block is removed, and close the coordinator/engine with the owning lifecycle.

## Verification boundary

The source includes deterministic unit tests for vocabulary mapping and token selection. They do not execute an ONNX graph. No model/vocabulary assets are currently bundled, and a full Android/Gradle build plus device inference still needs to be run against the actual exported model and its exact tensor contract.

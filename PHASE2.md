# AxioLoom Phase 2 — spatial ink foundation

This adds the geometry and rasterization layer to the Phase 1 core. No HMER model, UI, WebView, network permission, or recognition service is introduced in this phase.

## New implementation

- `TimedStroke.kt` snapshots a completed Jetpack Ink stroke, input samples, monotonic completion time, and native-mesh AABB in workspace coordinates. Stroke IDs identify immutable geometry.
- `EquationBlock.kt` represents a spatially grouped expression and carries its revision ticket, recognized math/CAS payload slots, status, and nullable terminal-equals result anchor.
- `SpatialBlockGrouper.kt` applies the requested dilated intersection, vertical stacking, and diacritic rules using sweep-pruned candidate pairs and union-find. It returns deterministic components, matches old IDs one-to-one by Jaccard overlap, preserves payload only for unchanged membership, increments revisions on edits, and recognizes a plausible two-bar equals anchor.
- `ScratchOutDetector.kt` first measures centerline path density and hysteretic horizontal reversals (minimum 0.25 of stroke width), then uses Jetpack Ink `PartitionedMesh.intersects` for actual target mesh intersection. AABB is only a broad-phase filter. The scratch itself is not returned as an erased target.
- `InkWorkspaceReducer.kt` makes stroke commit/erase/recluster one immutable operation and returns DAG block removals, invalidations, and IDs that need a new recognition/debounce cycle. Apply those updates transactionally in the Phase 3 coordinator.
- `StrokeBitmapRasterizer.kt` creates an anti-aliased ARGB_8888 black/white crop with 3.5 px round centerlines, 96 px height normalization, 512 px maximum content width, 16 px edge padding, and a native-order direct NCHW float tensor. Oversized inputs fail explicitly under dimension/pixel budgets.
- `Phase2GeometryTest.kt` covers grouping, vertical stacking, one-to-one identity matching, scratch oscillation, and raster sizing.

## Coordinate/time contract

Every `Stroke` passed to `TimedStroke.fromStroke` must already be in workspace coordinates, consistent across its input batch and native `shape`. Apply the viewport inverse before creating the Jetpack Ink stroke; never separately transform the mesh after capture. The completion time is a non-negative monotonic workspace timestamp. Convert sample timestamps are kept relative to the stroke start.

## Integration outline

1. On an Ink stroke-finished callback, construct `TimedStroke.fromStroke(stroke, monotonicFinishedAtMillis)`.
2. Call `InkWorkspaceReducer.commit(currentState, completedStroke)` and atomically publish the returned `state`.
3. Remove `removedBlockIds` from the reactive DAG; replace/invalidate `invalidatedBlockIds`; start or reset the Phase 3 500 ms recognizer debounce for `dirtyBlockIds`. Compare a recognition callback's `BlockRevision` to the current `EquationBlock.ticket` before accepting it.
4. Pass one block's `TimedStroke` list to `StrokeBitmapRasterizer.rasterize`. Its tensor shape is `[1, 1, H, W]`; use `shape()` and `asFloatBuffer()` when creating the ONNX input tensor.

## Limits and verification

Clustering uses workspace-pixel thresholds, so density-independent DPI calibration and per-device scaling belong at the canvas/input boundary. Rasterization intentionally uses centerlines and a fixed normalized width, not the original brush mesh. Pure geometry tests do not initialize Android's bitmap runtime or Ink native libraries; mesh-hit and pixel-output behavior require Android instrumentation/device testing. This source package keeps the Phase 1 minimum SDK and offline manifest unchanged.

# Phases 6, 7, and 8 — Complete Engineering Notebook Documentation

## Phase 6 — Syntax-Guided Beam Search HMER, Lasso & Ink-to-Typeset Morphing

1. **Constrained Beam Search Decoding (`ml/hmer/ConstrainedBeamDecoder.kt`)**
   - Width-3 beam search decoder (`beamWidth = 3`, length penalty $\alpha = 0.6$: $\text{score} = \frac{\sum \log P(y_t)}{((5 + T)/6)^\alpha}$).
   - Numerically stable log-softmax using max-trick: $\log P(i) = z_i - \max_k(z_k) - \log \sum_k \exp(z_k - \max_j z_j)$.
   - Syntax-guided prefix constraints:
     - Masks out closing brace `}` whenever `braceDepth == 0`.
     - Masks out `<eos>` while braces remain open or environment stack is non-empty.
     - Prevents empty groups `{}` immediately after `\frac`, `\sqrt`, `^`, and `_`.
     - Tracks LaTeX environments (`\begin{matrix}` / `\end{matrix}`).
   - Wired into `OnnxHmerEngine` with `DecodingStrategy.BEAM_3` enabled by default.

2. **Lasso Selection, Block Translation & Tool Modes (`domain/model/TimedStroke.kt` & `ui/canvas/WetInkOverlay.kt`)**
   - `TimedStroke.translate(dx, dy)`: shifts stroke coordinates, bounds, and centerline while keeping immutable stroke ID and timestamps intact.
   - `AxioLoomViewModel.moveBlock(blockId, deltaWorld)`: translates blocks in real time without triggering redundant HMER re-recognition.
   - Interactive `CanvasToolMode` toggle in top toolbar:
     - **`Draw`**: Low-latency Jetpack Ink stroke writing with stylus or single finger.
     - **`Select / Move`**: Real-time dragging of selected blocks across the canvas or canvas panning when dragging on empty space.
     - **`Eraser`**: Touch-based deletion of equation blocks.

3. **Ink-to-Typeset Morphing (`EquationBlock.kt` & `ui/canvas/DryInkCanvas.kt`)**
   - `BlockDisplayMode.HANDWRITTEN` vs `BlockDisplayMode.TYPESET`.
   - Double-tap or InspectorPanel chip toggles display mode.
   - In `TYPESET` mode, raw handwritten vector strokes are hidden and recognized LaTeX is rendered via `LatexCanvasRenderer` in Crisp White (`#F4F5F7`), scaled to fit block bounds alongside solved CAS results.

---

## Phase 7 — Engineering & Linear Algebra CAS Extensions

1. **Symbolic Transformation Modes (`domain/model/EquationBlock.kt` & `domain/cas/OfflineCasEngine.kt`)**
   - `SymbolicTransform`: `AUTO`, `SIMPLIFY`, `FACTOR`, `EXPAND`, `PARTIAL_FRACTIONS`, `TOGETHER`, `TRIG_EXPAND`, `TRIG_REDUCE`.
   - Passes transformation into Symja:
     - `PARTIAL_FRACTIONS` $\rightarrow$ `Apart(...)` for Laplace and signal analysis.
     - `SIMPLIFY` $\rightarrow$ `Simplify(...)`
     - `FACTOR` $\rightarrow$ `Factor(...)`
     - `EXPAND` $\rightarrow$ `Expand(...)`
     - `TOGETHER` $\rightarrow$ `Together(...)`
     - `TRIG_EXPAND` $\rightarrow$ `TrigExpand(...)`
     - `TRIG_REDUCE` $\rightarrow$ `TrigReduce(...)`
   - Horizontal scrollable `FilterChip`s in `InspectorPanel` allow instantaneous re-evaluation under any algebraic transformation.

2. **Linear Algebra Built-ins (`MathIntent.kt` & `LatexToSymjaTranspiler.kt`)**
   - `Eigenvalues`, `Eigenvectors`, `Tr` (trace), `MatrixRank`, `RowReduce` (rref).
   - Matrix exponentiation $A^{-1}$: compiles to `Inverse(A)` when operand is a matrix or matrix-bound symbol.
   - Matrix dot product `.`: compiles to `Dot(A, B)` when multiplying matrix operands.

3. **Complex Numbers & Phasor Notation (ECE Essentials)**
   - `Re`, `Im`, `Conjugate`, `Arg`.
   - Phasor angle operator `\angle` and Unicode `∠` (`\u2220`) with precedence 25:
     `r \angle \theta` compiles to `((r) * Exp(I * (theta) * Pi / 180))` (evaluating degrees in phasor analysis).

4. **Ordinary Differential Equations (`MathIntent.OdeSolve`)**
   - Prime notation (`y'`, `y''`) and differential notation (`\frac{dy}{dx}`).
   - Equations containing derivatives are classified as `MathIntent.OdeSolve` and compiled to `DSolve((lhs)==(rhs), y[x], x)`.

---

## Phase 8 — Multi-Notebook Vault, Frustum Culling & Export

1. **Viewport Frustum Culling (`ui/canvas/DryInkCanvas.kt` & `ViewportState.kt`)**
   - `ViewportState.visibleWorldRect(screenWidth, screenHeight, margin)`: computes the visible world rectangle.
   - `DryInkCanvas` culls offscreen blocks before drawing, keeping frame rate high on large workspaces.
   - Clamps background dot-grid rendering when zoomed out to `0.35x` to cap draw operations.

2. **Multi-Page Notebook Repository (`data/storage/NotebookRepository.kt`)**
   - Manages indexed notebook pages in `context.filesDir/notebooks/`.
   - `notebooks_index.json` stores page metadata (`id`, `title`, `updatedAtMillis`, `blockCount`).
   - Create, rename, delete, and switch between multiple pages seamlessly.
   - Migrates legacy `workspace_v1.json` automatically on first launch.

3. **100% Offline Obsidian Markdown Exporter (`domain/export/MarkdownExporter.kt`)**
   - Spatial reading-order sorting (top-to-bottom row bands, then left-to-right).
   - Obsidian YAML frontmatter (`title`, `created`, `mode`, `tags`).
   - Symbol table (`| Symbol | Definition | Evaluated Value |`).
   - `$$...$$` math blocks with evaluated results ($\implies$).
   - One-click copy to Android Clipboard.

4. **100% Offline Vector PDF Exporter (`domain/export/PdfExporter.kt`)**
   - Uses Android native `android.graphics.pdf.PdfDocument` with zero external dependencies.
   - Automatically computes bounding box and scales content to fit A4 layout with 36pt margins.
   - Supports `PdfTheme.DARK_ENGINEERING` and `PdfTheme.PRINT_LIGHT`.
   - Renders vector strokes (or typeset LaTeX in `TYPESET` mode) and solved CAS results directly onto PDF canvas.

---

## Unit Test Verification

- `ConstrainedBeamDecoderTest.kt`: verifies brace constraints, `<eos>` blocking on unclosed groups, empty group blocking, and beam search selection over greedy traps.
- `Phase7EngineeringCasTest.kt`: verifies matrix multiplication (`Dot`), matrix inverse ($A^{-1}$), eigenvalues, partial fraction decomposition (`Apart`), phasor degrees arithmetic, and ODE solving via `DSolve`.
- `MarkdownExporterTest.kt`: verifies spatial reading-order band sorting, YAML frontmatter generation, symbol table generation, and math blocks formatting.

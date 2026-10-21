# Phase 5 — Undo/Redo, Persistence, Multi-Variable Solves, Grammar, Plotting & ONNX Tooling

Phase 5 completes the feature set of InkMath, making workspaces persistent across process restarts, fully undoable/redoable, mathematically expanded, visually interactive with a 2D function plotter, and verifiable via offline ONNX export tooling.

## Deliverables

1. **Multi-Level Undo/Redo History (`domain/clustering/WorkspaceHistoryManager.kt`)**
   - Bounded deques (`maxDepth = 50`) tracking immutable `InkWorkspaceState` snapshots.
   - Computes `WorkspaceDiff` on undo and redo by comparing `(block.id, block.revision)` pairs.
   - Connected to `InkMathViewModel.canUndo`, `canRedo`, `undo()`, and `redo()`.
   - Restores cached block intents into `ReactiveSymbolGraph` immediately without re-triggering HMER recognition debounces.
   - Exposed via **Undo** and **Redo** top bar controls in `MathWorkspaceScreen`.

2. **100% Offline Workspace Storage (`data/storage/WorkspaceStorage.kt`)**
   - Zero-dependency JSON storage in `context.filesDir/workspace_v1.json` written atomically via a `.tmp` file.
   - Serializes `TimedStroke` geometry, timestamps, and brush parameters.
   - Reconstructs native Jetpack Ink `Stroke` instances via `MutableStrokeInputBatch` and `StrokeInput.create`.
   - Re-transpiles recognized LaTeX and repopulates `ReactiveSymbolGraph` on application startup.
   - Debounced auto-save (1000 ms) and atomic wipe on `clearWorkspace()`.

3. **Multi-Variable Solve Target Selector**
   - Extended `EquationBlock` with `solveTarget: String?` and `candidateSolveVars: List<String>`.
   - `AmbiguousSolveVariableException(candidates)` thrown by `LatexToSymjaTranspiler` when multiple unbound variables exist.
   - Automated fallback to the first candidate with interactive `FilterChip`s in `InspectorPanel` to switch targets on demand.

4. **Extended Mathematical Grammar**
   - Inverse trigonometric functions: `\arcsin`, `\arccos`, `\arctan`, `\sin^{-1}`, `\cos^{-1}`, `\tan^{-1}` (rewriting `\sin^{-1}(x)` to `ArcSin` while preserving `\sin^2(x)` as `(Sin(x))^2`).
   - Hyperbolic functions: `\sinh`, `\cosh`, `\tanh`.
   - Postfix factorial (`!`): parses expressions like `5!` to `Factorial(5)` (= 120) and `n!` to `Factorial(n)`.
   - Imaginary unit (`\imaginaryi`, `\imath`, and standalone `i` when unbound) mapping to `MathExpr.Constant.I`.
   - Higher-order derivatives: `\frac{d^n}{dx^n}(body)` mapping to `MathExpr.Derivative(body, var, order = n)` and Symja `D(body, {x, n})`.

5. **Native Compose 2D Function Plotter (`ui/canvas/FunctionPlotCard.kt` & `domain/evaluator/FastPlotEvaluator.kt`)**
   - Fast Kotlin AST numeric evaluator executing in `< 2ms` without invoking the CAS thread.
   - Real-time touch scrubbing with coordinate readout and root indication.
   - Charcoal/Gunmetal dark engineering theme with Warm Amber (`#FFB300`) curve and domain zoom/pan controls.
   - Substitutes DAG-bound variable values from upstream assignment blocks.

6. **Python HMER Export & Synthetic Verification Tooling (`tools/hmer_export/`)**
   - `export_hmer_to_onnx.py`: generates zero-weight synthetic verification models (`--mode synthetic`) or exports CoMER/CAN production checkpoints (`--mode comer` / `--mode can`) with INT8 quantization (`--quantize`).
   - `README.md`: detailed documentation for model contracts and export workflows.

7. **Comprehensive Unit Tests**
   - `Phase5GrammarTest.kt`: verifies trig identities, inverse trig, factorial, higher-order derivatives, and multi-variable equation resolution.
   - `WorkspaceHistoryTest.kt`: verifies state transitions, diff computation, and bounded stack capping.
   - `FastPlotEvaluatorTest.kt`: verifies polynomial, trig, exponential, and DAG-bound substitution parity between the fast Kotlin evaluator and Symja.

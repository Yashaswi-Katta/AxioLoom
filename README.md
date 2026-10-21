# InkMath v2.0 — Offline Reactive Ink Scratchpad & Embedded Engineering Workbench

InkMath is a 100% offline Android math workspace that couples Google Jetpack Ink vector stroke authoring with spatial expression clustering, on-device handwriting recognition (HMER via ONNX Runtime Mobile), a local Computer Algebra System (Symja), and a complete hardware/embedded systems engineering suite (schematic synthesis, C99/ESP32 code generation, Dormand-Prince RK45 non-linear ODE simulation, SI dimensional unit checking, and Root-Locus/Nyquist feedback control stability).

## Complete 31-Phase Application Architecture (v2.0)

The application comprises thirty-one integrated engineering layers:

1. **Phase 1 — Core Math & Reactive CAS Engine**
   - **AST & Intent Domain**: Closed mathematical expression tree (`MathExpr`) and execution intents (`MathIntent`) defending the CAS boundary against arbitrary input execution.
   - **LaTeX Transpiler**: Bounded `LatexToSymjaTranspiler` converting handwritten/typed math TeX syntax into structural Symja commands.
   - **Offline CAS**: Single-threaded `OfflineCasEngine` executing Symja on a dedicated daemon dispatcher with background warm-up.
   - **Reactive Dependency DAG**: `ReactiveSymbolGraph` evaluating dependent equations and propagating updates automatically.

2. **Phase 2 — Spatial Ink Foundation & Geometry**
   - **Stroke Representation**: `TimedStroke` capturing immutable vector strokes, monotonic timestamps, and workspace-coordinate AABB bounding boxes.
   - **Spatial Clustering**: `SpatialBlockGrouper` grouping strokes into discrete `EquationBlock` instances using dilated intersection, vertical stacking, diacritic clustering, and terminal-equals anchor detection.
   - **Scratch-Out Detection**: `ScratchOutDetector` identifying stroke deletions via centerline path density, horizontal reversals, and native `PartitionedMesh.intersects` testing.
   - **Workspace Reducer**: `InkWorkspaceReducer` handling atomic commits, deletions, and reclustering.
   - **Rasterizer**: `StrokeBitmapRasterizer` normalizing stroke groups to 96 px height and generating NCHW float tensors for HMER.

3. **Phase 3 — Offline HMER Inference**
   - **Vocabulary**: `HmerVocabulary` mapping model tokens to UTF-8 LaTeX strings with special-token handling.
   - **ONNX Engine**: `OnnxHmerEngine` running ONNX Runtime Mobile sessions with CPU and XNNPACK support for single and split encoder/decoder models.
   - **Coordinator**: `HmerRecognitionCoordinator` managing 500 ms per-block debounce, revision ticketing to discard stale results, and manual LaTeX fallback injection.

4. **Phase 4 — Compose Canvas & Workspace UI**
   - **Workspace Screen**: `MathWorkspaceScreen` hosting the interactive split workspace, inspector panel, and manual LaTeX input modal.
   - **Wet Ink Overlay**: `WetInkOverlay` wrapping `InProgressStrokesView` with motion prediction, 1-finger handwriting, 2-finger pan/pinch-zoom, and clean long-press gesture handling.
   - **Dry Ink Canvas**: `DryInkCanvas` rendering committed vector strokes and computing warm amber (`#FFB300`) CAS outputs alongside crimson error states using `LatexCanvasRenderer` (`JLatexMathDrawable`).
   - **Viewport Synchronization**: `ViewportState` coordinating scale and translation across wet and dry layers.
   - **ViewModel**: `InkMathViewModel` orchestrating workspace reduction, recognition pipelines, reactive DAG state, and lifecycle cleanup.

5. **Phase 5 — Undo/Redo, Persistence, Plotting & Extended Math**
   - **History Stack**: `WorkspaceHistoryManager` providing 50-step undo/redo with instant DAG cache restoration.
   - **Offline Persistence**: `WorkspaceStorage` persisting strokes, equations, and bindings into local JSON with startup re-transpilation.
   - **Multi-Variable Solves**: `AmbiguousSolveVariableException` and interactive `FilterChip` solve target selectors.
   - **Extended Grammar**: Postfix factorials (`5! = 120`), inverse trig (`\arcsin`, `\sin^{-1}`), hyperbolic functions, imaginary unit `i`, and higher-order derivatives (`\frac{d^2}{dx^2}`).
   - **Native 2D Plotter**: `FunctionPlotCard` & `FastPlotEvaluator` rendering interactive curves in `< 2ms` with touch scrubbing and root detection.
   - **ONNX Export Tooling**: `export_hmer_to_onnx.py` supporting zero-weight synthetic model generation and PyTorch checkpoint exports.

6. **Phase 6 — Syntax-Guided Beam Search, Lasso & Ink Morphing**
   - **Constrained Beam Decoder**: `ConstrainedBeamDecoder` (Width-3, $\alpha=0.6$) with log-softmax max-trick and syntax constraints preventing unclosed braces and invalid tokens.
   - **Interactive Canvas Tools**: `CanvasToolMode` (`Draw`, `Select / Move`, `Eraser`) allowing real-time translation of equation blocks across the workspace.
   - **Ink-to-Typeset Morphing**: Seamless toggling between raw handwritten vector strokes and clean typeset LaTeX math (`BlockDisplayMode.TYPESET`).

7. **Phase 7 — Engineering & Linear Algebra CAS Extensions**
   - **Symbolic Transformations**: Per-block `SymbolicTransform` modes (`Auto`, `Simplify`, `Factor`, `Expand`, `Apart / Partial Fractions`, `Together`, `TrigExpand`, `TrigReduce`).
   - **Linear Algebra**: Matrix multiplication (`Dot`), matrix exponentiation ($A^{-1}$ -> `Inverse`), eigenvalues, eigenvectors, trace, rank, and row reduction (`rref`).
   - **Complex & Phasor Math**: `Re`, `Im`, `Conjugate`, `Arg`, and ECE phasor angle operator `\angle` / `∠` in degrees.
   - **Differential Equations**: Automatic classification of prime (`y'`, `y''`) and differential (`\frac{dy}{dx}`) notation into `MathIntent.OdeSolve` with Symja `DSolve`.

8. **Phase 8 — Multi-Notebook Vault, Frustum Culling & Export**
   - **Frustum Culling**: `ViewportState.visibleWorldRect` skipping offscreen blocks in `DryInkCanvas` and throttling dot-grid overdraw at low zoom.
   - **Multi-Page Notebook Vault**: `NotebookRepository` managing indexed notebook pages with creation, switching, renaming, and atomic persistence.
   - **Obsidian Markdown Export**: `MarkdownExporter` producing reading-order-sorted notes with YAML frontmatter, symbol table, and `$$...$$` equations.
   - **Vector PDF Export**: `PdfExporter` rendering high-resolution A4 PDFs with Dark Engineering and Print Light themes via Android's native `PdfDocument`.

9. **Phase 9 — Signals Transforms, Vector Calculus & SI Prefixes**
   - **Transforms**: Laplace ($\mathcal{L}$), Inverse Laplace ($\mathcal{L}^{-1}$), Z-Transform ($\mathcal{Z}$), Heaviside Unit Step ($u(t)$ / $\theta(t)$), and Dirac Delta ($\delta(t)$).
   - **Vector Calculus**: Del operator ($\nabla f$, $\nabla \cdot \mathbf{F}$, $\nabla \times \mathbf{F}$) mapped to Symja `Grad`, `Div`, and `Curl`.
   - **SI Prefixes & Physics Library**: Automated expansion of engineering suffixes (k, M, G, m, u, n, p) and standard physical constants ($k_B$, $q_e$, $c_0$, $\epsilon_0$, $\mu_0$, $h_0$, $g_0$).

10. **Phase 10 — Step Derivation Breakdown & Top-K Disambiguation**
    - **Step-by-Step Derivation**: `StepDerivationEngine` generating pedagogical intermediate stages (antiderivative bounds, characteristic polynomials, partial fraction decompositions, factored forms, exact results, and decimal approximations).
    - **Top-3 Beam Disambiguation**: Interactive `FilterChip` suggestion chips for 1-tap switching between top-3 HMER beam hypotheses.

11. **Phase 11 — Release Hardening & Custom Model Importer**
    - **R8 / ProGuard Optimization**: Minification and resource shrinking configured in `app/build.gradle.kts` and `app/proguard-rules.pro` with reflection rules for Symja, ONNX, and JLatexMath.
    - **Offline Model Importer**: `ExternalModelManager` enabling on-device importing and validation of custom `.onnx` and vocabulary bundles without network access.

12. **Phase 12 — Live Parameter Scrubbing & Interactive Canvas Sliders**
    - **Fast Numeric DAG Propagator**: `FastNumericPropagator` evaluating downstream assignments and queries in `< 0.2ms` with 16-point Gauss-Legendre quadrature for definite integrals and 150ms debounced CAS commits.
    - **Interactive Parameter Slider Pill**: `ParameterSliderPill` featuring auto-bounds, 1-tap preset chips (`[-10, 10]`, `[0, 100]`, `[0, 2π]`), and a 60fps Play/Oscillate dynamic animation sweep.

13. **Phase 13 — Control Systems & Signal Analysis Workbench**
    - **Transfer Function Detection & Root Finding**: `TransferFunctionAnalyzer` extracting poles and zeros via closed-form and Weierstrass (Durand-Kerner) algorithms with BIBO stability classification.
    - **Dual-Tab Analysis Card**: `TransferFunctionAnalyzerCard` rendering interactive Bode magnitude & phase plots with $-3\text{ dB}$ cutoff and touch cursor, alongside a complex $s$-plane pole-zero map.
    - **Taylor Series & Piecewise Mathematics**: `MathExpr.TaylorSeries` with 1-tap `Series (O^5)` expansion and `MathExpr.Piecewise` with relational operators ($<, \le, >, \ge, \ne$).

14. **Phase 14 — Offline Personal Ink Calibrator & Instrumentation Tests**
    - **Multi-Stroke DTW Classifier**: `SymbolPrototypeClassifier` resampling stroke polylines to $N = 32$ normalized points in $[0, 1]^2$ with Elastic DTW and stroke permutation invariance.
    - **Personal Calibration**: Offline recording and JSON persistence to `user_symbol_prototypes.json` with seamless fallback in `HmerRecognitionCoordinator`.
    - **Comprehensive Test Suite**: Automated unit tests in `Phase12To14Test.kt` and instrumentation tests in `InkMathInstrumentationTest.kt`.

15. **Phase 15 — Symbolic MNA Circuit Solver & Impedance Primitives**
    - **Parallel Impedance Operator**: `\parallel` and `||` with precedence 22 compiled to `((A*B)/(A+B))` in `SymjaCompiler` and evaluated in `<0.2ms`.
    - **Modified Nodal Analysis (MNA)**: `SymbolicMnaSolver` generating symbolic $\mathbf{G}\mathbf{x} = \mathbf{i}$ equations for RLC networks, independent sources, and VCCS elements, with Thevenin/Norton port extraction.
    - **Circuit Card**: `CircuitSolverSheet` providing netlist editing and real-time node voltage, branch current, and Thevenin equivalent display.

16. **Phase 16 — Spatial 2D Matrix & System-of-Equations Synthesis**
    - **Tall Bracket Detection**: Geometric classification of single strokes into left/right brackets (`[`, `]`, `|`, `{`) based on height and concavity.
    - **2D Grid Matrix Synthesis**: `SpatialStructureSynthesizer` projecting enclosed cell clusters onto $Y$ and $X$ axes to construct composite `\begin{bmatrix}` and `\begin{vmatrix}` matrices.
    - **System-of-Equations Synthesis**: Grouping vertically aligned equation blocks sharing `=` into simultaneous systems (`\begin{cases}`).

17. **Phase 17 — Boolean Logic, Karnaugh Map Minimizer & Multi-Coordinate Plotter**
    - **Quine-McCluskey & Petrick's Method**: `KarnaughMinimizer` computing exact prime implicants, minimal SOP and POS, and truth tables for 2 to 4 variables with don't cares.
    - **Interactive Karnaugh Map**: `KarnaughMapCard` with Gray-code grids, one-tap cell toggling ($0 \to 1 \to X$), and color-coded prime implicant loops.
    - **Multi-Coordinate Function Plotter**: `FunctionPlotCard` updated with Cartesian, Polar ($r(\theta)$), and Parametric ($(x(t), y(t))$) plotting modes.

18. **Phase 18 — Symbolic Calculus Theorem Verifier & Matrix Diagonalization Workbench**
    - **Mean Value Theorem Engine**: `CalculusTheoremAnalyzer` verifying Rolle's, Lagrange's, and Cauchy's MVT on user intervals $[a, b]$ with singularity/domain continuity checks, critical point extraction, and tangent line synthesis.
    - **Matrix Diagonalization & Cayley-Hamilton**: `MatrixAnalyzer` computing Characteristic Polynomial $p(\lambda) = \det(A - \lambda I)$, eigenvalues, modal matrix $P$, diagonal matrix $D$, Cayley-Hamilton verification $p(A) = \mathbf{0}$, and symbolic inverse derivation.

19. **Phase 19 — Discrete-Time Digital Signal Processing (DSP) Engine**
    - **Discrete Signal AST**: `MathExpr.DiscreteSequence`, `DiscreteConvolution` (`\ast`, `\star`), `CircularConvolution`, and `DiscreteFourierTransform`.
    - **Signal Processing Engine**: `DiscreteSignalEngine` executing linear convolution with shift-and-multiply table, circular convolution, Cooley-Tukey Radix-2 FFT and arbitrary-$N$ DFT ($<10^{-6}$ round-trip), and spectral analysis (magnitude, phase, PSD).
    - **Discrete Stem Plot**: `DiscreteStemPlotCard` rendering native Canvas lollipop plots with interactive sample scrubbing and step breakdown.

20. **Phase 20 — RF Smith Chart & Pre-Built Engineering Notebook Templates**
    - **Vector RF Smith Chart**: `SmithChartCard` & `SmithChartMath` rendering constant-resistance circles, constant-reactance arcs, and constant-VSWR circles on Canvas with live load impedance $z_L$ and quarter-wave $z_{\text{in}}$ scrubbing.
    - **1-Tap Starter Templates**: `WorkspaceTemplates` providing 5 instant starter notebooks (Calculus MVT, Linear Algebra, AC Circuits, Control Systems, Digital Logic & DSP).

21. **Phase 21 — 3D Isometric Surface & 2D Vector Field Plotter**
    - **Isometric 3D Surface Plotter**: `Surface3DPlotCard` and `FastPlotEvaluator.sampleSurface` rendering $z = f(x, y)$ over a uniform grid with interactive touch yaw $\theta \in [-180^\circ, 180^\circ]$ and pitch $\phi \in [10^\circ, 80^\circ]$, Painter's algorithm depth-sorting, Dark Engineering gradient, and wireframe bounding cube.
    - **2D Vector Quiver Field & RK4 Streamlines**: `VectorFieldPlotCard` computing $\mathbf{F}(x, y) = \langle P, Q \rangle$ across a $15 \times 15$ grid with 4th-order Runge-Kutta streamline tracing from touch seeds.

22. **Phase 22 — Handwritten Data Tables & Statistical Curve Fitting**
    - **Spatial Table Synthesis**: `SpatialStructureSynthesizer.synthesizeDataTable` converting 2D cell grids into $(x_i, y_i)$ scatter data points.
    - **Regression Engine**: `DataRegressionEngine` providing closed-form Linear Least Squares ($y = mx + c$, Pearson $r$, $R^2$, RMSE), Polynomial Regression ($d \in 1..4$) via Gaussian elimination with partial pivoting on Vandermonde systems, Exponential fit ($y = A e^{Bx}$), and descriptive statistics (mean, variance, stdev, median, min, max).
    - **Transpiler Grammar**: Support for `\operatorname{linreg}`, `\operatorname{polyreg}`, `\operatorname{mean}`, and `\operatorname{stdev}`.

23. **Phase 23 — 100% Offline Camera / Whiteboard Math Importer**
    - **Adaptive Sauvola Thresholding**: `ImageMathPreprocessor` computing $O(W \times H)$ single-pass Integral Images $S(x, y)$ and $S_2(x, y)$ with dynamic windowing and polarity detection.
    - **Connected Component Denoiser**: 8-connected component filter discarding noise speckles $< 6\text{px}$.
    - **CROHME Tensor Normalization**: Tight bounding-box crop with aspect ratio padding into normalized $[1, 1, H, W]$ float tensors for onnx HMER.
    - **Asynchronous Photo Picker**: `rememberLauncherForActivityResult` loading camera/gallery images into `InkMathViewModel.importImageBitmap`.

24. **Phase 24 — Hardware Stylus Ergonomics & Adaptive Tablet Workspace**
    - **Hardware Stylus Ergonomics**: S-Pen / active stylus barrel button instant eraser (`MotionEvent.BUTTON_STYLUS_PRIMARY`), hover reticle preview (`ACTION_HOVER_MOVE`), and double-tap canvas detection.
    - **8-Sector Radial Symbol Menu**: `RadialSymbolMenu` popping at double-tap point for 1-touch insertion of $\int$, $\frac{d}{dx}$, $\sum$, Matrix, $\mathcal{L}$, $H(s)$, $\parallel$, and K-Map.
    - **Adaptive Split-Pane UX**: `BoxWithConstraints` automatically configuring a 65% canvas / 35% docked scrollable workbench sidebar for tablet/desktop screens ($\ge 720\text{dp}$) versus a collapsible bottom sheet on phones.

25. **Phase 25 — Subsystem Telemetry HUD, Comprehensive Tests & v1.0 Release**
    - **Real-Time Performance HUD**: `PerformanceOverlayBadge` monitoring clustering, rasterization, HMER inference, CAS evaluation, and JVM heap usage.
    - **Full Production Test Suite**: `Phase21To25FinalTest.kt` verifying 3D Painter's depth sorting, linear/polynomial regression, Sauvola binarization, and 50-node reactive DAG chain stress evaluation.

26. **Phase 26 — Hand-Drawn Circuit Schematic Synthesizer & Netlist Extractor**
    - **Stroke Classifier**: `SchematicStrokeRecognizer` classifying resistors (4–12 lateral peaks), capacitors (parallel plate pairs), ground (3 decreasing stacked bars), DC sources (circular loops), and wire polylines.
    - **Disjoint-Set Clustering**: 24px Union-Find terminal merging mapping co-located connections to circuit nodes with Ground automatically anchored to reference Node 0.
    - **Spatial Value Association**: Nearest-neighbor pairing of schematic components with handwritten value blocks (`10k`, `100u`, `5V`) with direct feed into `SymbolicMnaSolver`.

27. **Phase 27 — Embedded C99 / Arduino / ESP32 Firmware Code Synthesizer**
    - **Tustin Discretization**: `BilinearDiscretizer` expanding $(1-z^{-1})^k (1+z^{-1})^{N-k}$ via polynomial convolution for Direct-Form I/II difference equations with exact unity DC gain.
    - **Firmware Generation**: `EmbeddedCGenerator` emitting zero-allocation, ISR-safe C structs (`state_t`), `init` and `update` step functions for ARM Cortex, Arduino, and ESP32 with precision selection (`float`/`double`).
    - **Code Export Dialog**: `CodeExportDialog` providing header, source, and Arduino `.ino` tabs with one-tap clipboard copy.

28. **Phase 28 — Numerical Non-Linear ODE & State-Space Simulator (RK45)**
    - **Adaptive RK45 Integrator**: `OdeNumericalSimulator` implementing 7-stage Dormand-Prince 5(4) with FSAL optimization and adaptive step size control.
    - **Non-Linear Presets**: Harmonic oscillator ($<10^{-4}$ analytical error at $2\pi$), Van der Pol oscillator, non-linear pendulum, and Lorenz chaotic attractor.
    - **Interactive State-Space Card**: `OdeSimulationCard` rendering multi-trace time-domain waveforms and 2D phase-plane trajectories with live parameter sliders.

29. **Phase 29 — Dimensional Analysis & SI Physical Unit Consistency Engine**
    - **7-Tuple SI Base Dimensions**: `DimensionalAnalyzer` representing $[L, M, T, I, \Theta, N, J]$ and verifying derived units ($\text{V}, \Omega, \text{F}, \text{H}, \text{W}, \text{J}, \text{N}, \text{Hz}$) including $[\Omega] \times [\text{F}] = [\text{s}]$ and $[\text{H}] / [\Omega] = [\text{s}]$.
    - **Closed AST Validation**: Verifies dimensional homogeneity across additions, subtractions, powers, and transcendental function arguments, highlighting mismatches (e.g. $V + I$) in Crimson Red (`#DC143C`).

30. **Phase 30 — Root-Locus & Nyquist Stability Designer**
    - **Simultaneous Root Finding**: `RootLocusEngine` sweeping gain $K$ across 80 steps using Durand-Kerner (Aberth-Ehrlich) polynomial root finding in $<2\text{ms}$ for degree $\le 8$.
    - **Critical Stability Detection**: Identifies imaginary axis crossings ($K_{\text{crit}} = 6.0$, $\omega_{\text{crit}} = \sqrt{2}\text{ rad/s}$ for $G(s) = \frac{1}{s(s+1)(s+2)}$).
    - **Root-Locus & Nyquist Card**: `RootLocusNyquistCard` rendering S-plane root trajectories with interactive gain slider $K$, damping $\zeta$, natural frequency $\omega_n$, and polar Nyquist stability contours with $(-1+j0)$ reticle.

31. **Phase 31 — Unified Engineering Workbench Router & Bundled Offline HMER Assets**
    - **Exhaustive AST & Compiler Exhaustiveness**: 100% explicit `when` coverage across all 25 `MathExpr` subtypes in references, Symja compiler, fast numeric evaluators, dimensional analysis, and embedded C generator.
    - **Bundled HMER Assets**: Pre-packaged `app/src/main/assets/hmer/hmer_vocab.txt` with 184 line-indexed UTF-8 CROHME and engineering tokens, with out-of-the-box Elastic DTW prototype fallback.
    - **Unified 8-Tab Workbench Router**: `EngineeringWorkbenchPanel` hosting `Inspector`, `Steps`, `Plot 2D/3D`, `Control (Bode/RL)`, `RF Smith`, `Circuit MNA`, `Logic K-Map`, and `C99 Code` across tablet and mobile layouts with zero orphan cards.

## 5-Layer Master Architecture

```mermaid
graph TD
    subgraph Layer 1: Ink & Hardware Stylus Layer
        A1[Jetpack Ink InProgressStrokesView] --> A2[WetInkOverlay]
        A2 --> A3[Hardware Stylus & S-Pen Button Eraser]
        A2 --> A4[Hover Reticle Preview]
        A2 --> A5[Radial Quick-Symbol Menu]
        A2 --> A6[DryInkCanvas / LatexCanvasRenderer]
    end

    subgraph Layer 2: Spatial Clustering, Schematics & Vision Preprocessor
        B1[SpatialBlockGrouper] --> B2[ScratchOutDetector]
        B3[SpatialStructureSynthesizer Matrix & Table] --> B1
        B4[ImageMathPreprocessor Sauvola & 8-CC Denoiser] --> B5[StrokeBitmapRasterizer]
        B6[SchematicStrokeRecognizer] --> B7[Disjoint-Set Union-Find Netlist]
    end

    subgraph Layer 3: Machine Learning & HMER Pipeline
        C1[OnnxHmerEngine CPU / XNNPACK] --> C2[ConstrainedBeamDecoder Width-3, Alpha=0.6]
        C2 --> C3[HmerRecognitionCoordinator]
        C4[SymbolPrototypeClassifier Elastic DTW] --> C3
    end

    subgraph Layer 4: Mathematical Domain, Firmware & Control Systems
        D1[LatexToSymjaTranspiler Closed AST] --> D2[MathExpr / MathIntent Boundary]
        D2 --> D3[OfflineCasEngine Symja Worker Thread]
        D2 --> D4[ReactiveSymbolGraph DAG Propagation]
        D2 --> D5[FastPlotEvaluator 2D, 3D & Vector]
        D2 --> D6[DataRegressionEngine Linear, Poly & Exp]
        D2 --> D7[SymbolicMnaSolver & DiscreteSignalEngine]
        D2 --> D8[BilinearDiscretizer & EmbeddedCGenerator]
        D2 --> D9[OdeNumericalSimulator RK45]
        D2 --> D10[DimensionalAnalyzer SI Dimensions]
        D2 --> D11[RootLocusEngine Durand-Kerner]
    end

    subgraph Layer 5: UI & Presentation Layer
        E1[MathWorkspaceScreen Adaptive Split-Pane]
        E2[65% Interactive Vector Canvas] --> E1
        E3[35% Docked Engineering Workbench Sidebar] --> E1
        E4[Surface3DPlotCard & VectorFieldPlotCard] --> E3
        E5[PerformanceOverlayBadge Telemetry HUD] --> E1
        E6[OdeSimulationCard & RootLocusNyquistCard] --> E3
        E7[CodeExportDialog C99 & Arduino] --> E1
    end

    A6 --> B1
    A6 --> B6
    B5 --> C1
    B7 --> D7
    C3 --> D1
    D4 --> E2
    D5 --> E4
    D6 --> E3
    D8 --> E7
    D9 --> E6
    D11 --> E6
```

## Extended Mathematical Grammar Cheat-Sheet

| Construct | Handwritten LaTeX Syntax | Symja / Kotlin Engine |
| :--- | :--- | :--- |
| **Definite Integral** | `\int_{0}^{\pi} \sin(x) dx =` | `Integrate(Sin(x), {x, 0, Pi})` |
| **Derivative** | `\frac{d}{dx}(x^3 + 2x) =` | `D(x^3 + 2*x, x)` |
| **Summation** | `\sum_{n=1}^{10} n^2 =` | `Sum(n^2, {n, 1, 10})` |
| **Linear Regression** | `\operatorname{linreg}((1,2),(2,4),(3,5))` | Closed-form Least Squares $(y = mx + c)$ |
| **Polynomial Fit** | `\operatorname{polyreg}(2, (0,1),(1,2),(2,5))` | Vandermonde Gaussian Pivot $(y = ax^2 + bx + c)$ |
| **3D Surface** | `z = x^2 - y^2` | FastPlotEvaluator 3D Isometric Painter's Mesh |
| **Vector Field** | `\mathbf{F} = \langle y, -x \rangle` | Quiver sampling + RK4 Streamlines |
| **Parallel Impedance** | `R_1 \parallel R_2` or `R_1 || R_2` | `((R1*R2)/(R1+R2))` |
| **Circuit MNA** | `V_1 1 0 10; R_1 1 2 1k` | Modified Nodal Analysis $(\mathbf{G}\mathbf{x} = \mathbf{i})$ |
| **Karnaugh Map** | `\operatorname{kmap}(4, \sum m(0,1,2,3))` | Quine-McCluskey & Petrick Prime Implicants |
| **Laplace Transform** | `\mathcal{L}\{e^{-2t}\} =` | `LaplaceTransform(Exp(-2*t), t, s)` |
| **Bode / S-Plane** | `H(s) = \frac{10}{s^2 + 3s + 2}` | BIBO Durand-Kerner Poles/Zeros + Bode Card |
| **Matrix Operations** | `A \cdot B =`, `A^{-1} =`, `\operatorname{eig}(A)` | Symja `Dot`, `Inverse`, `Eigenvalues` |
| **Calculus MVT** | `\operatorname{mvt}(x^2, x, 0, 4)` | Rolle / Lagrange critical point tangent synthesis |
| **Discrete DSP** | `\{1, 2, 3\} \ast \{1, 1\} =` | Discrete convolution & Cooley-Tukey Radix-2 FFT |
| **Smith Chart** | `z_L = 50 + j 25` | RF Reflection coefficient $\Gamma = \frac{z - 1}{z + 1}$ |
| **Circuit Schematic** | Hand-drawn R, C, GND, Source | Union-Find topological netlist extraction & MNA solve |
| **Bilinear C99 Export** | $H(s) = \frac{1}{s + 1} \to H(z)$ | Tustin DF-I/II C99, ARM Cortex, Arduino & ESP32 driver |
| **RK45 Non-Linear ODE** | $\ddot{y} - \mu(1-y^2)\dot{y} + y = 0$ | Adaptive Dormand-Prince 5(4) state-space simulation |
| **Dimensional Units** | $[\Omega] \times [\text{F}] = [\text{s}]$, catches $V + I$ | 7-Tuple SI unit consistency validation |
| **Root-Locus & Nyquist** | $G(s) = \frac{1}{s(s+1)(s+2)}$ | Durand-Kerner $K_{\text{crit}} = 6.0$ & S-Plane / Nyquist plots |

## Offline and Safety Boundaries

- **Zero Permissions**: Zero permissions requested in `AndroidManifest.xml`—strictly no `android.permission.INTERNET`.
- **Zero WebViews**: No WebViews used anywhere. All mathematical formulas, 2D curves, 3D surfaces, vector fields, and Smith charts are rendered via native Android Jetpack Compose and Canvas primitives.
- **Closed AST Boundary**: All input parses into strongly typed `MathExpr` / `MathIntent` nodes before execution. Symja is never invoked with unvalidated arbitrary strings.
- **Isolated CAS**: Symja evaluates on a dedicated single-threaded worker daemon with sandboxed symbol scopes and background warm-up.

## Build and Testing

Open this project in Android Studio or build via Gradle with JDK 17:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Unit test suites verify all layers across Phases 1 through 31:
- `Phase1CoreTest.kt`: Transpiler, Unicode operators, DAG cycle detection.
- `Phase5GrammarTest.kt`: Factorials, hyperbolic trig, multi-variable solve targets.
- `Phase7EngineeringCasTest.kt`: Linear algebra, matrix inverse, phasors, ODEs.
- `Phase9To11Test.kt`: Laplace/Z-transforms, vector calculus, physical SI constants.
- `Phase12To14Test.kt`: Fast parameter scrubbing, Durand-Kerner BIBO stability, DTW prototype calibration.
- `Phase15To17Test.kt`: Modified Nodal Analysis circuit solver, 2D matrix spatial synthesis, Karnaugh map minimizer.
- `Phase18To20Test.kt`: Rolle/Lagrange MVT, Cayley-Hamilton, FFT/DFT convolution, Smith chart geometry.
- `Phase21To25FinalTest.kt`: 3D isometric Painter's depth-sorting, linear/polynomial regression, Sauvola camera binarization, and 50-node reactive DAG chain stress test under execution budget.
- `Phase26To30V2Test.kt`: Resistor/capacitor/ground schematic stroke recognition and Union-Find netlist extraction, Tustin bilinear discretization and C99/Arduino firmware synthesis, Dormand-Prince RK45 adaptive ODE integration of harmonic oscillator at $2\pi$ to $<10^{-4}$ and Van der Pol stability, 7-tuple SI dimensional algebra ($[\Omega] \times [\text{F}] = [\text{s}]$) and AST mismatch warnings, Durand-Kerner root-locus critical stability gain ($K_{\text{crit}} = 6.0$, $\omega = \sqrt{2}$) and closed-loop poles.

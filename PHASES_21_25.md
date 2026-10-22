# Phases 21–25 Implementation Guide — 3D Surface & Vector Field Plotter, Data Regression, Offline Camera/Image Math Importer, Hardware Stylus & Tablet UX, and Production Release Hardening

## Overview

Phases 21 through 25 mark the **v1.0 Production Completion** of **AxioLoom** (`com.axioloom.app`), bringing the application to a fully featured, offline mathematical workbench with hardware stylus ergonomics, spatial regression, 3D visualization, camera math binarization, and adaptive dual-pane tablet productivity.

All foundational architectural invariants remain strictly enforced:
- **100% Offline Execution**: Zero `android.permission.INTERNET` and zero `WebView` components.
- **Closed AST Boundary**: All mathematical transformations compile through typed `MathExpr` / `MathIntent` AST nodes into Symja or pure Kotlin analytic solvers.
- **Dark Engineering Theme**: Charcoal (`#121417`), Gunmetal (`#191D22` / `#252A31`), Crisp White (`#F4F5F7`), Warm Amber (`#FFB300`), Gold (`#FFD54F`), and Crimson Red (`#DC143C`).

---

## Phase 21: 3D Isometric Surface & 2D Vector Field Plotter

### 21.1 3D Surface Plotter (`ui/canvas/Surface3DPlotCard.kt` & `domain/evaluator/FastPlotEvaluator.kt`)
- **Fast 2D Grid Evaluation**:
  - `FastPlotEvaluator.sampleSurface(expr, var1, var2, range1, range2, steps, bindings)` samples a uniform $(N \times N)$ grid (default $36 \times 36 = 1,296$ vertices) over $[-5, 5]^2$.
- **Isometric Rotation & Perspective Projector (`Surface3DProjector`)**:
  - Compound Euler rotation matrix:
    $$R(\theta, \phi) = R_x(\phi) \cdot R_z(\theta)$$
  - Interactive touch yaw $\theta \in [-180^\circ, 180^\circ]$ and pitch $\phi \in [10^\circ, 80^\circ]$.
- **Painter's Algorithm Depth-Sorting**:
  - Quadrilaterals formed by $(i, j), (i+1, j), (i+1, j+1), (i, j+1)$ are depth-sorted by average projected $Z$-depth:
    $$Z_{\text{avg}} = \frac{z_0 + z_1 + z_2 + z_3}{4}$$
  - Rendered back-to-front to guarantee correct visual occlusion without OpenGL or WebGL dependencies.
- **Dark Engineering Gradient**:
  - Continuous height mapping from Gunmetal (`#1E242C`) $\to$ Crimson (`#DC143C`) $\to$ Warm Amber (`#FFB300`) $\to$ Gold (`#FFD54F`).
  - Wireframe bounding box cube with axis indicators and zoom controls.

### 21.2 2D Quiver Vector Field & RK4 Streamlines (`ui/canvas/VectorFieldPlotCard.kt`)
- **Quiver Field Sampling**:
  - Evaluates $\mathbf{F}(x, y) = \langle P(x, y), Q(x, y) \rangle$ across a $15 \times 15$ grid.
  - Draws directional arrows with magnitude-scaled shaft thickness and coloring (muted Gunmetal to Amber).
- **Runge-Kutta 4th-Order (RK4) Streamline Integration**:
  - `VectorFieldSimulator.rk4Streamline(pExpr, qExpr, x0, y0, steps, h, bindings)` integrates forward and backward from user-tapped seed points:
    $$k_1 = \mathbf{F}(\mathbf{x}_n)$$
    $$k_2 = \mathbf{F}\left(\mathbf{x}_n + \frac{h}{2} k_1\right)$$
    $$k_3 = \mathbf{F}\left(\mathbf{x}_n + \frac{h}{2} k_2\right)$$
    $$k_4 = \mathbf{F}(\mathbf{x}_n + h k_3)$$
    $$\mathbf{x}_{n+1} = \mathbf{x}_n + \frac{h}{6}(k_1 + 2k_2 + 2k_3 + k_4)$$
  - Renders smooth streamlines across user touches with instant "Clear" capability.

---

## Phase 22: Handwritten Data Tables & Statistical Curve Fitting

### 22.1 Spatial Table Cluster Synthesis (`domain/clustering/SpatialStructureSynthesizer.kt`)
- `synthesizeDataTable(cells: List<CellCluster>): List<DataPoint>?` scans 2D cell grids.
- Identifies 2-column tabular arrangements representing $(x_i, y_i)$ pairs and converts them into numeric point lists for automated regression.

### 22.2 Curve Fitting & Regression Engine (`domain/stats/DataRegressionEngine.kt`)
- **Linear Least Squares ($y = mx + c$)**:
  - Closed-form analytical slope, intercept, Pearson correlation coefficient $r$, coefficient of determination $R^2$, and Root-Mean-Square Error (RMSE).
- **Polynomial Regression (Degree $d \in 1..4$)**:
  - Formulates Vandermonde normal equations $(\mathbf{X}^T \mathbf{X})\mathbf{a} = \mathbf{X}^T \mathbf{y}$.
  - Solves the $(d+1) \times (d+1)$ linear system via Gaussian elimination with row partial pivoting for numeric stability without matrix singularities.
- **Exponential Regression ($y = A e^{Bx}$)**:
  - Log-transformed linear fit on $(x_i, \ln y_i)$ with recovered amplitude $A = e^{c}$ and growth rate $B = m$.
- **Descriptive Statistics**:
  - Computes count $N$, mean $\mu$, sample variance $s^2$, sample standard deviation $s$, median, minimum, and maximum.
- **Transpiler & Grammar Integration**:
  - Transpiler tokenizes and recognizes `\operatorname{linreg}`, `\operatorname{polyreg}`, `\operatorname{mean}`, and `\operatorname{stdev}`.

---

## Phase 23: 100% Offline Camera / Image Math Importer

### 23.1 Sauvola / Bradley-Roth Adaptive Binarization (`ml/hmer/ImageMathPreprocessor.kt`)
- **Downsampling**:
  - Constrains input camera bitmaps to $\le 1024\text{px}$ preserving aspect ratio.
- **$O(W \times H)$ Integral Image Acceleration**:
  - Computes single-pass Integral Image $S(x, y) = \sum I$ and Squared Integral Image $S_2(x, y) = \sum I^2$ using `DoubleArray` to prevent 32-bit integer overflow.
  - Queries local window mean $\mu$ and standard deviation $\sigma$ in $O(1)$ time:
    $$T(x, y) = \mu(x, y) \cdot \left[1 + k \cdot \left(\frac{\sigma(x, y)}{R} - 1\right)\right]$$
    with $k = 0.2$ and dynamic window size $W = \max(15, \min(w, h) / 16)$.
- **Automatic Polarity Correction**:
  - Detects dark-on-light vs light-on-dark contrast; inverts if foreground exceeds 50%.
- **8-Connected Component Speckle Filtering**:
  - Discards noise artifacts and camera grain with component area $< 6\text{px}$.
- **CROHME Tensor Normalization**:
  - Crops tightly to ink bounding box and normalizes with padding into standard HMER `RasterizedTensor` $[1, 1, H, W]$ with $H, W \le 512$.

### 23.2 Photo Picker Integration
- Uses `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` in `MathWorkspaceScreen.kt`.
- Asynchronously processes camera or gallery snapshots via `viewModel.importImageBitmap(bitmap)` without blocking the main UI thread.

---

## Phase 24: Hardware Stylus Ergonomics & Adaptive Tablet Workspace

### 24.1 Hardware Stylus & Hover Ergonomics (`ui/canvas/WetInkOverlay.kt`)
- **S-Pen / Hardware Stylus Barrel Button Eraser**:
  - Intercepts `MotionEvent.BUTTON_STYLUS_PRIMARY` to instantly switch to scratch/point eraser while depressed, returning to active pen on release.
- **Hover Reticle Cursor**:
  - Tracks stylus hover via `ACTION_HOVER_MOVE` and `ACTION_HOVER_EXIT` to draw a precision circular reticle with tool-mode color coding.
- **Double-Tap Callback**:
  - Detects double-tap on empty canvas to surface the Radial Quick-Symbol Menu at the exact tap coordinate.

### 24.2 8-Sector Radial Quick-Symbol Menu (`ui/canvas/RadialSymbolMenu.kt`)
- Instant radial pie menu centered at touch coordinate with 8 engineering templates:
  1. $\int_{a}^{b} x^2 dx$ (Definite Integral)
  2. $\frac{d}{dx}(x^3)$ (Derivative)
  3. $\sum_{n=1}^{10} n$ (Summation)
  4. $\begin{bmatrix} 1 & 0 \\ 0 & 1 \end{bmatrix}$ ($2 \times 2$ Matrix)
  5. $\mathcal{L}\{e^{-2t}\}$ (Laplace Transform)
  6. $H(s) = \frac{10}{s + 2}$ (Transfer Function)
  7. $R_1 \parallel R_2$ (Parallel Impedance)
  8. $\operatorname{kmap}(4, \dots)$ (Karnaugh Map)
- Sector hover highlighting, touch hit-testing, and center dismiss button.

### 24.3 Adaptive Split-Pane Tablet UX (`ui/canvas/MathWorkspaceScreen.kt`)
- Uses `BoxWithConstraints` to dynamically adapt layout:
  - **Tablet / Large Screens ($\ge 720\text{dp}$)**:
    - 65% width interactive canvas on the left.
    - 35% docked scrollable engineering workbench sidebar on the right hosting live 3D surface plots, vector fields, Bode plots, circuit solvers, K-maps, Smith charts, and block inspectors simultaneously.
  - **Mobile / Compact Screens ($< 720\text{dp}$)**:
    - Full-screen canvas with collapsible bottom inspector and overlay cards.

---

## Phase 25: Real-Time Performance HUD & Production Verification

### 25.1 Subsystem Telemetry HUD (`ui/canvas/PerformanceOverlayBadge.kt` & `AxioLoomViewModel.kt`)
- Live HUD tracking:
  - Spatial Clustering latency ($T_{\text{cluster}}$)
  - Stroke Rasterization latency ($T_{\text{raster}}$)
  - HMER Model Inference latency ($T_{\text{hmer}}$)
  - Symbolic CAS Evaluation latency ($T_{\text{cas}}$)
  - JVM Heap memory consumption ($M_{\text{heap}}$)
- Expandable telemetry card with individual timing meters.

### 25.2 Comprehensive Unit Test Suite (`Phase21To25FinalTest.kt`)
- `surface3DProjectorOrdersQuadsBackToFrontAndEvaluatesTwoVarFunction`: Verifies $36 \times 36$ surface sampling of saddle function $x^2 - y^2$, depth calculation, and Painter's algorithm monotonic depth ordering.
- `vectorFieldEvaluatorAndSimulatorGeneratesVortexAndRK4Streamline`: Verifies rotational vector field sampling and zero-point invariants.
- `dataRegressionEngineComputesExactLinearAndQuadraticLeastSquares`: Verifies exact linear fit ($R^2 = 1.0$), quadratic fit ($y = x^2 + 1$), exponential fit ($y = 2e^x$), and descriptive statistics ($N=5, \mu=3.0, s^2=2.5$).
- `sauvolaImagePreprocessorBinarizesSyntheticNoisyBitmapIntoNormalizedTensor`: Verifies local integral image thresholding on synthetic cross and 8-connected noise suppression.
- `fullEndToEndStressTestWith50DependentBlocksCompletesUnderBudget`: Verifies 50-node reactive DAG chain ($v_0 \dots v_{49}$) evaluation and topological propagation within execution budget.

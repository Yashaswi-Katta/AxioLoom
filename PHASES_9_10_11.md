# Phases 9, 10 & 11 Implementation Guide — Advanced Signals, Vector Calculus, Derivation Breakdown, Top-K Disambiguation & Release Hardening

## Overview of Added Capabilities

Phases 9, 10, and 11 complete the engineering capabilities of **InkMath**, delivering advanced transformation calculus, vector analysis, SI prefixes, step-by-step derivation explanations, beam alternatives disambiguation, and hardware-accelerated release packaging.

---

## 1. Phase 9 — Signals & Systems Transforms, Vector Calculus & SI Prefixes

### 1.1 Integral & Discrete Domain Transforms
- **Laplace Transform**:
  - AST: `MathExpr.LaplaceTransform(expr, timeVar = "t", freqVar = "s")`
  - LaTeX Syntax: `\mathcal{L}\{ EXPR \}` or `\operatorname{laplace}(EXPR)`
  - Symja Target: `LaplaceTransform(expr, t, s)`
- **Inverse Laplace Transform**:
  - AST: `MathExpr.InverseLaplaceTransform(expr, freqVar = "s", timeVar = "t")`
  - LaTeX Syntax: `\mathcal{L}^{-1}\{ EXPR \}` or `\operatorname{ilaplace}(EXPR)`
  - Symja Target: `InverseLaplaceTransform(expr, s, t)`
- **Z-Transform**:
  - AST: `MathExpr.ZTransform(expr, discreteVar = "n", zVar = "z")`
  - LaTeX Syntax: `\mathcal{Z}\{ EXPR \}` or `\operatorname{ztrans}(EXPR)`
  - Symja Target: `ZTransform(expr, n, z)`
- **Signal Step & Impulse Functions**:
  - Heaviside Unit Step: $u(t - a)$ / `\theta(t - a)` / `\operatorname{step}(t - a)` $\to$ `MathExpr.Builtin(UNIT_STEP)` $\to$ `UnitStep(t - a)`
  - Dirac Delta: $\delta(t - a)$ / `\operatorname{dirac}(t - a)` $\to$ `MathExpr.Builtin(DIRAC_DELTA)` $\to$ `DiracDelta(t - a)`
  - Integrated into `FastPlotEvaluator` for $< 2\text{ms}$ instant 2D function curve plotting.

### 1.2 Vector Calculus Operators
- **Del Operator $\nabla$ Parsing**:
  - Differentiates $\nabla \cdot \mathbf{F}$ (Divergence) vs $\nabla \times \mathbf{F}$ (Curl) vs $\nabla f$ (Gradient) via token differentiation (`Kind.CROSS` vs `Kind.STAR`).
  - Gradient: $\nabla f$ or `\operatorname{grad}(f)` $\to$ `MathExpr.Gradient(f, listOf("x", "y", "z"))` $\to$ `Grad(f, {x, y, z})`
  - Divergence: $\nabla \cdot \mathbf{F}$ or `\operatorname{div}(\mathbf{F})` $\to$ `MathExpr.Divergence(F, listOf("x", "y", "z"))` $\to$ `Div(F, {x, y, z})`
  - Curl: $\nabla \times \mathbf{F}$ or `\operatorname{curl}(\mathbf{F})` $\to$ `MathExpr.Curl(F, listOf("x", "y", "z"))` $\to$ `Curl(F, {x, y, z})`

### 1.3 Engineering SI Prefixes & Physical Constants Library
- **Automatic SI Suffix Preprocessing** (`EngineeringConstants.expandSiSuffixes`):
  - Automatically expands engineering multipliers attached to numbers without manual scientific notation:
    - `G` $\to 10^9$
    - `M` $\to 10^6$
    - `k` $\to 10^3$
    - `m` $\to 10^{-3}$
    - `u` / `\mu` $\to 10^{-6}$
    - `n` $\to 10^{-9}$
    - `p` $\to 10^{-12}$
  - Strips engineering units: $\text{Hz}, \text{F}, \text{H}, \text{s}, \text{V}, \text{A}, \text{W}, \Omega$
- **Physical Constants Library** (`EngineeringConstants.getPhysicalConstantsBindings()`):
  - Boltzmann constant $k_B = 1.380649 \times 10^{-23} \text{ J/K}$
  - Elementary charge $q_e = 1.602176634 \times 10^{-19} \text{ C}$
  - Speed of light $c_0 = 299792458 \text{ m/s}$
  - Vacuum permittivity $\epsilon_0 = 8.8541878128 \times 10^{-12} \text{ F/m}$
  - Vacuum permeability $\mu_0 = 1.25663706212 \times 10^{-6} \text{ H/m}$
  - Planck constant $h_0 = 6.62607015 \times 10^{-34} \text{ J}\cdot\text{s}$
  - Standard gravity $g_0 = 9.80665 \text{ m/s}^2$
  - One-tap toggle via `SI Constants` chip in top bar.

---

## 2. Phase 10 — Step-by-Step Derivation Breakdown & Top-K Beam Disambiguation

### 2.1 Pedagogical Derivation Breakdown Engine (`StepDerivationEngine`)
Deconstructs complex math problems into 5 intuitive stages:
1. **Initial Problem**: Recognized LaTeX input.
2. **Variable Substitution**: Shows values of bound symbols expanded in context.
3. **Calculus / Algebra Intermediates**:
   - Definite Integrals: Intermediate antiderivative evaluated at bounds $\left[ F(x) \right]_a^b$.
   - Matrix Operations: Characteristic polynomial $\det(A - \lambda I) = 0$ for eigenvalue solves.
   - Partial Fractions: Decomposed rational terms via `Apart(...)`.
   - Quadratics / Algebraic Solves: Factored form $(x - r_1)(x - r_2) = 0$.
   - Laplace Transforms: Integral kernel $\int_0^\infty f(t) e^{-st} \, dt$.
4. **Exact Symbolic Result**: The simplified analytical result.
5. **Decimal Approximation**: Floating-point evaluation shown with $\approx$.

### 2.2 Top-3 Beam Disambiguation UI
- `ConstrainedBeamDecoder.topHypotheses` exposes top-3 distinct valid beam hypotheses.
- `OnnxHmerEngine` propagates `alternatives: List<String>` in `HmerInferenceResult.Recognized`.
- Inspector Panel displays 1-tap `FilterChip` suggestion chips for instant candidate switching (`viewModel.selectBlockAlternative`).

---

## 3. Phase 11 — Release Hardening & Offline Model Importer

### 3.1 ProGuard / R8 Hardening (`app/proguard-rules.pro`)
- Strict keep rules for:
  - `ai.onnxruntime.**` (JNI native function bindings)
  - `org.matheclipse.core.**` & `org.matheclipse.parser.**` (dynamic function lookup and reflection)
  - `org.hipparchus.**` (quadrature and linear algebra routines)
  - `ru.noties.jlatexmath.**` & `org.scilab.forge.jlatexmath.**` (font XML asset loading via `getResourceAsStream`)
  - `androidx.ink.**`
- Enabled `isMinifyEnabled = true` and `isShrinkResources = true` in `build.gradle.kts`.

### 3.2 External Model Importer (`ExternalModelManager`)
- Imports custom `.onnx` and `_vocab.txt` files to `context.filesDir/custom_hmer/`.
- Verifies model byte integrity (> 1 KB non-empty) and vocabulary line count (> 10 tokens).
- "Model" status dialog in workspace top bar displays active model source (Bundled vs Custom) and provides one-click revert.

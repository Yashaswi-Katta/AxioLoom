# Phases 26–30 Implementation Guide — AxioLoom v2.0 Hardware & Embedded Systems Expansion

## Overview

Phases 26 through 30 bring **AxioLoom** (`com.axioloom.app`) to **v2.0**, introducing the **Hardware & Embedded Systems Expansion**. This turns AxioLoom from a mathematical scratchpad into an end-to-end engineering workbench capable of hand-drawn circuit schematic capture, automatic firmware synthesis for embedded microcontrollers (C99, Arduino, ESP32), numerical non-linear ODE state-space simulation, dimensional unit consistency verification, and classic feedback control system stability analysis (Root-Locus and Nyquist).

All foundational architectural invariants remain strictly enforced:
- **100% Offline Execution**: Zero `android.permission.INTERNET` and zero `WebView` components.
- **Closed AST Boundary**: All mathematical transformations, C code generation, ODE state equations, and dimensional constraints compile through typed `MathExpr` / `MathIntent` AST nodes into pure Kotlin analytic solvers or Symja.
- **Dark Engineering Theme**: Charcoal (`#121417`), Gunmetal (`#191D22` / `#252A31`), Crisp White (`#F4F5F7`), Warm Amber (`#FFB300`), Gold (`#FFD54F`), and Crimson Red (`#DC143C`).

---

## Phase 26: Hand-Drawn 2D Circuit Schematic Synthesizer & Topological Netlist Extractor

### 26.1 Geometric Stroke Classifier (`domain/circuit/SchematicStrokeRecognizer.kt`)
- **Resistor Recognition**:
  - Classifies strokes with 4 to 12 alternating lateral peaks perpendicular to the principal axis of the stroke bounding box.
  - Extracts 2 end terminals at stroke start and finish.
- **Capacitor Recognition**:
  - Detects pairs of nearly parallel line strokes with comparable lengths ($\Delta L < 30\%$) and narrow inter-plate gap spacing ($4\text{px} \le d \le 40\text{px}$).
  - Computes orthogonal lead terminals extending outward from the plate midpoints.
- **Ground Symbol Recognition**:
  - Classifies 3 stacked horizontal bars with decreasing lengths ($w_1 > w_2 > w_3$) spaced uniformly along the vertical axis.
  - Emits a single connection terminal at the top bar midpoint, automatically anchoring to reference Ground (Node 0).
- **Independent DC Voltage Source Recognition**:
  - Identifies closed circular loops (isClosed = true or high circularity $\frac{4\pi A}{P^2} > 0.65$).
  - Extracts opposing vertical terminals (positive and negative terminals).
- **Wire Interconnects**:
  - Single straight or orthogonal polyline strokes connecting components.

### 26.2 Disjoint-Set Union-Find Terminal Clustering
- Employs a Disjoint-Set Union-Find data structure with an adaptive 24px snap radius:
  - Gathers all component terminals and wire endpoints.
  - Merges terminal coordinates within the snap radius into unified electrical nodes.
  - Automatically maps any node connected to a Ground component terminal to Node 0.

### 26.3 Spatial Value Association & Netlist Synthesis
- Scans canvas `EquationBlock` instances to associate handwritten numerical values (e.g., `10k`, `100u`, `5V`, `12V`, `1M`) with the nearest recognized schematic component using Euclidean bounding-box center distance.
- Bridges recognized components directly into `SymbolicMnaSolver`'s `MnaNetlist`, enabling instant DC and AC node voltage and branch current calculation.

### 26.4 UI Integration
- Added `CanvasToolMode.SCHEMATIC` to `WetInkOverlay.kt`.
- Rendered circular Node Voltage Badges (`V1`, `V2`, `GND`) in `DryInkCanvas.kt` at node terminal positions.
- Added "Schematic" tool toggle and "Solve Schematic" execution action in `MathWorkspaceScreen.kt`.

---

## Phase 27: Symbolic-to-Embedded C99 / Arduino / ESP32 Firmware Code Synthesizer

### 27.1 Continuous-to-Discrete Bilinear Transform (`domain/codegen/BilinearDiscretizer.kt`)
- **Tustin Transform ($s \to z$)**:
  $$s = \frac{2}{T_s} \frac{1 - z^{-1}}{1 + z^{-1}}$$
- **Pure Discrete Polynomial Expansion**:
  - Convolves basis polynomials $(1 - z^{-1})$ and $(1 + z^{-1})$ to expand $(1 - z^{-1})^k (1 + z^{-1})^{N-k}$ without relying on symbolic engines.
  - Computes exact Direct-Form I / II difference equation coefficients:
    $$y[n] = \frac{1}{a_0} \left( \sum_{k=0}^{M} b_k x[n-k] - \sum_{k=1}^{N} a_k y[n-k] \right)$$
  - Normalizes coefficients such that $a_0 = 1.0$ and guarantees exact DC unity gain $H(z=1) = 1.0$ for low-pass filter transfer functions ($< 10^{-4}$ tolerance).

### 27.2 Embedded C99 & Arduino Firmware Generator (`domain/codegen/EmbeddedCGenerator.kt`)
- **Mode A: MathExpr to C Function**:
  - Traverses `MathExpr` AST trees to generate clean, portable C99 `<math.h>` functions with typed `float` or `double` precision.
  - Common Subexpression Elimination (CSE) identifies repeated subtrees and extracts them into temporary variables.
  - Automatically maps math functions to standard C intrinsics (`sinf`, `cosf`, `expf`, `powf`, `sqrtf`, `fabsf`).
- **Mode B: Real-Time Digital Filter Driver**:
  - **Zero-Allocation Structs**: Emits static state structures containing ring-buffer histories for past inputs $x[n-k]$ and past outputs $y[n-k]$.
  - **ISR-Safe Updates**: Generates re-entrant `_init` and `_update` step functions designed for interrupt service routines (timer ISRs) on ARM Cortex-M, Arduino, and ESP32.
  - **Complete Deliverables**:
    - `.h` Header file with `#ifndef` guards, struct definitions, and function prototypes.
    - `.c` Source file with static `const` coefficient arrays and unrolled Direct-Form I/II evaluation.
    - `.ino` Arduino / ESP-IDF sketch with `setup()`, ADC input reading, filter step execution, and DAC / PWM output.

### 27.3 Firmware Export UI (`ui/canvas/CodeExportDialog.kt`)
- Dark engineering dialog featuring:
  - Mode tabs (`Header (.h)`, `Source (.c)`, `Arduino (.ino)`).
  - Precision selector (`float` 32-bit vs `double` 64-bit).
  - Sampling rate chip selector ($100\text{ Hz}$, $1\text{ kHz}$, $10\text{ kHz}$, $48\text{ kHz}$).
  - Syntax-highlighted monospaced code preview.
  - One-tap "Copy to Clipboard" with Toast confirmation.

---

## Phase 28: Numerical Non-Linear ODE / State-Space Simulator (Dormand-Prince RK45)

### 28.1 Adaptive Dormand-Prince 5(4) Integrator (`domain/sim/OdeNumericalSimulator.kt`)
- **Dormand-Prince Butcher Tableau**:
  - 7-stage Runge-Kutta integrator with 5th-order solution $y_{n+1}$ and 4th-order embedded solution $\hat{y}_{n+1}$.
  - Utilizes First Same As Last (FSAL) property ($k_7 = k_1$ of next step) to reduce derivative evaluations to 6 per step.
- **Adaptive Step Size Control**:
  - Local truncation error estimation:
    $$E = \max_i \left| y_{n+1, i} - \hat{y}_{n+1, i} \right|$$
  - Optimal step size scaling with safety factors:
    $$h_{\text{new}} = h \cdot \min\left(2.0, \max\left(0.2, 0.9 \cdot \left(\frac{\text{tol}}{E}\right)^{1/5}\right)\right)$$
- **Non-Linear Presets**:
  - **Harmonic Oscillator**: $\ddot{y} + \omega^2 y = 0$ (matches analytical $\cos(\omega t)$ at $2\pi$ to $< 10^{-4}$).
  - **Van der Pol Oscillator**: $\ddot{y} - \mu(1 - y^2)\dot{y} + y = 0$ (demonstrates limit cycle attractor).
  - **Non-Linear Pendulum**: $\ddot{\theta} + \frac{g}{L}\sin\theta = 0$ (large-angle non-linear oscillation).
  - **Lorenz Chaotic Attractor**: 3D non-linear system ($\sigma=10, \rho=28, \beta=8/3$).

### 28.2 State-Space Simulation Card (`ui/canvas/OdeSimulationCard.kt`)
- Dual-mode visualization:
  - **Time Domain Waveforms**: Multi-trace plots displaying state variables over time (Position/Voltage in Warm Amber `#FFB300`, Velocity/Current in Gold `#FFD54F`).
  - **2D Phase-Plane Trajectory**: State $x_2$ vs $x_1$ phase orbit illustrating limit cycles, spirals, and equilibrium saddle points.
- Interactive parameter sliders for system properties ($\mu$, $\omega_0$) and integration horizon ($t_{\text{max}}$).

---

## Phase 29: Dimensional Analysis & SI Physical Unit Consistency Engine

### 29.1 7-Tuple SI Base Dimension Vector (`domain/units/DimensionalAnalyzer.kt`)
- Immutable 7-tuple representation:
  $$\mathbf{D} = [L, M, T, I, \Theta, N, J]$$
  representing Length ($\text{m}$), Mass ($\text{kg}$), Time ($\text{s}$), Electric Current ($\text{A}$), Thermodynamic Temperature ($\text{K}$), Amount of Substance ($\text{mol}$), and Luminous Intensity ($\text{cd}$).
- Derived Engineering Unit Dimensions:
  - $\text{Volt} = [2, 1, -3, -1, 0, 0, 0]$
  - $\text{Ohm} = [2, 1, -3, -2, 0, 0, 0]$
  - $\text{Farad} = [-2, -1, 4, 2, 0, 0, 0]$
  - $\text{Henry} = [2, 1, -2, -2, 0, 0, 0]$
  - $\text{Watt} = [2, 1, -3, 0, 0, 0, 0]$, $\text{Joule} = [2, 1, -2, 0, 0, 0, 0]$, $\text{Newton} = [1, 1, -2, 0, 0, 0, 0]$
- Exact Vector Verification:
  - $[\Omega] + [\text{F}] = [0, 0, 1, 0, 0, 0, 0] = [\text{s}]$ (RC time constant)
  - $[\text{H}] - [\Omega] = [0, 0, 1, 0, 0, 0, 0] = [\text{s}]$ (L/R inductive time constant)

### 29.2 Closed AST Dimensional Homogeneity Checker
- Recursively validates `MathExpr` trees:
  - **Addition / Subtraction**: Validates that $\mathbf{D}_{\text{left}} == \mathbf{D}_{\text{right}}$. Emits `DimensionalMismatchWarning` if units clash (e.g., attempting $V + I$).
  - **Multiplication / Division**: Algebraically adds/subtracts dimension exponents.
  - **Transcendental Functions** ($\sin, \cos, \exp, \ln$): Asserts that arguments are dimensionless ($\mathbf{D} == \mathbf{0}$).
  - **Powers**: Asserts that exponent is dimensionless.
- Surfaced in Crimson Red (`#DC143C`) as inline badges on `DryInkCanvas.kt` and detailed error notifications in the inspector panel.

---

## Phase 30: Root-Locus & Nyquist Stability Designer

### 30.1 Root-Locus Engine & Durand-Kerner Complex Solver (`domain/cas/RootLocusEngine.kt`)
- **Characteristic Equation Formulation**:
  $$1 + K G(s) = 0 \implies D(s) + K N(s) = 0$$
- **Simultaneous Durand-Kerner (Aberth-Ehrlich) Root Finding**:
  - Sweeps gain $K$ logarithmically over 80 steps from $K = 0$ to $K = K_{\text{max}}$.
  - Finds all roots of complex polynomial $P(s)$ simultaneously in $\le 40$ iterations ($< 2\text{ms}$ execution for degree $\le 8$), guaranteeing real-time 60fps slider responsiveness.
- **Critical Stability Detection**:
  - Identifies imaginary axis crossings ($\operatorname{Re}(s) = 0$) where closed-loop poles transition into the unstable right-half plane.
  - Matches the classic Routh-Hurwitz benchmark $G(s) = \frac{1}{s(s+1)(s+2)}$ with exact critical gain $K_{\text{crit}} = 6.0$ and crossing frequency $\omega_{\text{crit}} = \sqrt{2} \approx 1.4142\text{ rad/s}$.
- **Nyquist Contour & Stability Margins**:
  - Evaluates $G(j\omega)$ along the imaginary axis contour $\omega \in [10^{-3}, 10^3]$.
  - Calculates Gain Margin ($\text{GM}$) and Phase Margin ($\text{PM}$).
  - Evaluates encirclements of the critical point $(-1 + j0)$.

### 30.2 Root-Locus & Nyquist Card UI (`ui/canvas/RootLocusNyquistCard.kt`)
- **Interactive S-Plane Plot**:
  - Displays open-loop poles ($\times$ in Crimson Red) and zeros ($\circ$ in Gunmetal).
  - Real-time gain slider $K$ updating closed-loop pole positions ($\blacksquare$ in Warm Amber).
  - Displays natural frequency $\omega_n$ and damping ratio $\zeta = \cos\theta$.
- **Nyquist Polar Diagram**:
  - Displays polar trajectory of $G(j\omega)$, unit circle $|G|=1$, negative real axis intercept, and $(-1 + j0)$ stability reticle.

---

## Phase 26–30 Automated Test Verification (`Phase26To30V2Test.kt`)

The complete Phase 26–30 test suite is implemented in `app/src/test/java/com/axioloom/app/domain/Phase26To30V2Test.kt` with 5 targeted tests:

1. `schematicStrokeRecognizerClassifiesResistorCapacitorAndGround`: Verifies zig-zag lateral peak counting for resistors, parallel stroke gap detection for capacitors, 3-bar stacking for ground, wire connectivity, and Disjoint-Set Union-Find terminal clustering into Node 0.
2. `bilinearDiscretizerAndEmbeddedCGeneratorEmitValidC99Filter`: Verifies Tustin discretization polynomial expansion, DC gain $H(z=1) = 1.0$, C99 header/source code generation with ring-buffer state structs, Arduino `.ino` driver code, and `MathExpr` AST function generation with `sinf`/`expf`.
3. `rk45SimulatorAccuratelyIntegratesHarmonicOscillatorAndVanDerPol`: Verifies Dormand-Prince 5(4) adaptive integration of the harmonic oscillator matching analytical solution $\cos(2\pi) = 1.0, \dot{y}(2\pi) = 0.0$ to $< 10^{-4}$, and confirms finite non-divergent integration of the Van der Pol oscillator.
4. `dimensionalAnalyzerConfirmsRcTimeConstantIsSecondsAndCatchesUnitMismatch`: Verifies $[\Omega] \times [\text{F}] = [\text{s}]$, $[\text{H}] / [\Omega] = [\text{s}]$, and verifies that attempting $V + I$ triggers a `DimensionalMismatchWarning`.
5. `aberthEhrlichRootLocusFindsExactCriticalGainAndClosedLoopPoles`: Verifies open-loop pole extraction at $s = 0, -1, -2$, finds critical stability gain $K_{\text{crit}} = 6.0$, critical crossing frequency $\omega = \sqrt{2}\text{ rad/s}$, and Durand-Kerner imaginary roots $\pm j\sqrt{2}$.

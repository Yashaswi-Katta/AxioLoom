package com.axioloom.app.domain.model

/**
 * Engineering SI multipliers and standard physical constants library.
 * Allows electronics & signals calculations using standard engineering prefixes
 * (e.g., 4.7k, 10u, 2.4G) without manual scientific notation.
 */
object EngineeringConstants {

    /**
     * Replaces engineering SI prefixes attached to numbers with their mathematical power-of-10 multipliers.
     * Handles both raw text and LaTeX notation (e.g., 4.7\text{k}\Omega, 10\mu\text{F}, 2.4\text{G}\text{Hz}).
     */
    fun expandSiSuffixes(latex: String): String {
        var clean = latex
        // Strip common engineering unit symbols that don't affect scalar CAS math
        clean = clean.replace(Regex("""\\(?:text|mathrm)\s*\{\s*(?:Hz|hz|F|H|s|sec|V|v|A|a|W|w|Ohm|ohm|\\Omega)\s*\}"""), "")
            .replace(Regex("""(?<=[0-9])\s*(?:Hz|hz|\\Omega)\b"""), "")

        // Map SI multipliers:
        // G -> 10^9
        // M -> 10^6
        // k -> 10^3
        // m -> 10^-3
        // \mu or u -> 10^-6
        // n -> 10^-9
        // p -> 10^-12

        // First handle \text{...} or \mathrm{...} wrapped prefixes
        clean = clean.replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*G\s*\}""")) { "(${it.groupValues[1]} * 10^9)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*M\s*\}""")) { "(${it.groupValues[1]} * 10^6)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*k\s*\}""")) { "(${it.groupValues[1]} * 10^3)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*m\s*\}""")) { "(${it.groupValues[1]} * 10^-3)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*u\s*\}""")) { "(${it.groupValues[1]} * 10^-6)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*n\s*\}""")) { "(${it.groupValues[1]} * 10^-9)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\(?:text|mathrm)\s*\{\s*p\s*\}""")) { "(${it.groupValues[1]} * 10^-12)" }
            // Handle \mu command directly: 10\mu
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)\s*\\mu\b""")) { "(${it.groupValues[1]} * 10^-6)" }
            // Handle bare SI characters immediately adjacent to numbers: e.g. 4.7k, 100m, 10u, 2.4G, 100n, 22p
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)G(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^9)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)M(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^6)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)k(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^3)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)m(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^-3)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)u(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^-6)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)n(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^-9)" }
            .replace(Regex("""([0-9]+(?:\.[0-9]+)?)p(?![a-zA-Z0-9_\(])""")) { "(${it.groupValues[1]} * 10^-12)" }

        return clean
    }

    /**
     * Standard physics and electronics constants formatted as typed AST nodes.
     * Can be injected directly into ReactiveSymbolGraph and BindingSnapshot.
     */
    fun getPhysicalConstantsBindings(): Map<String, MathExpr> = mapOf(
        "k_B" to MathExpr.Number("1.380649e-23"),        // Boltzmann constant (J/K)
        "q_e" to MathExpr.Number("1.602176634e-19"),      // Elementary charge (C)
        "c_0" to MathExpr.Number("299792458"),            // Speed of light in vacuum (m/s)
        "eps_0" to MathExpr.Number("8.8541878128e-12"),   // Vacuum permittivity (F/m)
        "mu_0" to MathExpr.Number("1.25663706212e-6"),    // Vacuum permeability (H/m)
        "h_0" to MathExpr.Number("6.62607015e-34"),       // Planck constant (J*s)
        "hbar" to MathExpr.Number("1.054571817e-34"),     // Reduced Planck constant (J*s)
        "g_0" to MathExpr.Number("9.80665")              // Standard gravity (m/s^2)
    )
}

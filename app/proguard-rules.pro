# ProGuard / R8 Release Hardening for InkMath (100% Offline)

# 1. Microsoft ONNX Runtime Mobile
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 2. Symja / MathEclipse Computer Algebra System
-keep class org.matheclipse.core.** { *; }
-keep class org.matheclipse.parser.** { *; }
-dontwarn org.matheclipse.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.imageio.**

# 3. Hipparchus Math Library (used by Symja for ODEs, linear algebra, quadrature)
-keep class org.hipparchus.** { *; }
-dontwarn org.hipparchus.**

# 4. JLatexMath Android Typesetting Engine
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn ru.noties.jlatexmath.**
-dontwarn org.scilab.forge.jlatexmath.**

# 5. Preserve XML font and symbol resources loaded by JLatexMath via getResourceAsStream
-keepclassmembers class org.scilab.forge.jlatexmath.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepattributes InnerClasses, Signature, EnclosingMethod

# 6. AndroidX Ink & Motion Prediction
-keep class androidx.ink.** { *; }
-keep class androidx.input.motionprediction.** { *; }
-dontwarn androidx.ink.**

# 7. Log4j (required by Symja ExprEvaluator)
-dontwarn org.apache.logging.log4j.**
-keep class org.apache.logging.log4j.** { *; }

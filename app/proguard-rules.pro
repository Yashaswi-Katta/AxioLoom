# ProGuard / R8 Release Hardening for InkMath (100% Offline)

-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn javax.management.**
-dontwarn java.beans.**
-dontwarn org.apfloat.**
-dontwarn org.slf4j.**
-dontwarn org.jgrapht.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.matheclipse.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.imageio.**
-dontwarn org.hipparchus.**
-dontwarn ru.noties.jlatexmath.**
-dontwarn org.scilab.forge.jlatexmath.**
-dontwarn androidx.ink.**
-dontwarn ai.onnxruntime.**
-dontwarn com.google.common.**

# 1. Microsoft ONNX Runtime Mobile
-keep class ai.onnxruntime.** { *; }

# 2. Symja / MathEclipse Computer Algebra System
-keep class org.matheclipse.** { *; }

# 3. Hipparchus Math Library
-keep class org.hipparchus.** { *; }

# 4. JLatexMath Android Typesetting Engine
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }

# 5. AndroidX Ink & Motion Prediction
-keep class androidx.ink.** { *; }
-keep class androidx.input.motionprediction.** { *; }

# 6. Log4j
-keep class org.apache.logging.log4j.** { *; }

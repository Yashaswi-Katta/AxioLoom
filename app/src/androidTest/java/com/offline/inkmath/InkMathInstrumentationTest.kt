package com.offline.inkmath

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offline.inkmath.ml.hmer.SymbolPrototypeClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InkMathInstrumentationTest {

    @Test
    fun useAppContextAndVerifyOfflinePrototypeStorage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.offline.inkmath", appContext.packageName)

        val classifier = SymbolPrototypeClassifier(appContext)
        val dummyStrokes = listOf(
            listOf(
                androidx.compose.ui.geometry.Offset(10f, 10f),
                androidx.compose.ui.geometry.Offset(90f, 90f)
            )
        )
        classifier.addCustomPrototype("custom_slash", "/", dummyStrokes, saveToFile = true)

        val prototypesFile = File(appContext.filesDir, SymbolPrototypeClassifier.PROTOTYPES_FILE_NAME)
        assertTrue("User prototypes file should exist", prototypesFile.exists())
        assertTrue("Prototypes file should contain custom_slash", prototypesFile.readText().contains("custom_slash"))
    }
}

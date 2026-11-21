package com.enhikyy.girassol

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootSmokeTest {
    @Test fun instrumentationProcessStartsOnApi35() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.enhikyy.girassol", context.packageName)
    }

    @Test fun mainActivityCreatesItsInitialUi() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("com.enhikyy.girassol", activity.packageName)
            }
        }
    }
}

package bw.agriconnect

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that boots a [HiltTestApplication] instead of
 * [AgriConnectApp], so tests can install `@HiltAndroidTest` components and swap
 * bindings (`@UninstallModules`, `@BindValue`) without touching production code.
 *
 * Referenced from `defaultConfig.testInstrumentationRunner`; without it, Hilt
 * instrumentation tests fail with "The component was not created".
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}

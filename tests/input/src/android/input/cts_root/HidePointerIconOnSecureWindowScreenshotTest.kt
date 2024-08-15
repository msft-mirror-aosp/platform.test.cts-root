/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.input.cts_root

import android.cts.input.EventVerifier
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemProperties
import android.platform.test.annotations.EnableFlags
import android.view.MotionEvent
import android.view.WindowManager
import android.virtualdevice.cts.common.FakeAssociationRule
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.cts.input.DefaultPointerSpeedRule
import com.android.cts.input.TestPointerDevice
import com.android.cts.input.VirtualDisplayActivityScenarioRule
import com.android.cts.input.inputeventmatchers.withMotionAction
import com.android.input.flags.Flags
import com.android.xts.root.annotations.RequireAdbRoot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import platform.test.screenshot.GoldenPathManager
import platform.test.screenshot.PathConfig
import platform.test.screenshot.ScreenshotTestRule
import platform.test.screenshot.assertAgainstGolden
import platform.test.screenshot.matchers.AlmostPerfectMatcher
import platform.test.screenshot.matchers.BitmapMatcher
import kotlin.test.assertNotNull

/**
 * End-to-end tests for the hiding pointer icons of screenshots of secure displays
 *
 * We use a secure virtual display to launch the test activity, and use virtual Input devices to
 * move the pointer for it to show up. We then take a screenshot of the display to ensure the icon
 * does not shows up on screenshot. We use the virtual display to be able to precisely compare the
 * screenshots across devices of various form factors and sizes.
 *
 * Following tests must be run as root as they require CAPTURE_SECURE_VIDEO_OUTPUT permission
 * override which can only be done by root.
 */
@MediumTest
@RunWith(Parameterized::class)
@RequireAdbRoot
class HidePointerIconOnSecureWindowScreenshotTest {
    private lateinit var activity: CaptureEventActivity
    private lateinit var verifier: EventVerifier
    private lateinit var exactScreenshotMatcher: BitmapMatcher

    @get:Rule
    val testName = TestName()
    @get:Rule
    val virtualDisplayRule = VirtualDisplayActivityScenarioRule<CaptureEventActivity>(
        testName,
        /*useSecureDisplay=*/true
    )
    @get:Rule
    val fakeAssociationRule = FakeAssociationRule()
    @get:Rule
    val defaultPointerSpeedRule = DefaultPointerSpeedRule()
    @get:Rule
    val screenshotRule = ScreenshotTestRule(GoldenPathManager(
        InstrumentationRegistry.getInstrumentation().context,
        ASSETS_PATH,
        TEST_OUTPUT_PATH,
        PathConfig()
    ), disableIconPool = false)

    @Parameter(0)
    lateinit var device: TestPointerDevice

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        activity = virtualDisplayRule.activity
        activity.runOnUiThread {
            activity.actionBar?.hide()
            activity.window.decorView.rootView.setBackgroundColor(Color.WHITE)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        device.setUp(context, virtualDisplayRule.virtualDisplay.display, fakeAssociationRule)

        verifier = EventVerifier(activity::getInputEvent)

        exactScreenshotMatcher =
            AlmostPerfectMatcher(acceptableThresholdCount = MAX_PIXELS_DIFFERENT)
    }

    @After
    fun tearDown() {
        device.tearDown()
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_POINTER_INDICATORS_FOR_SECURE_WINDOWS)
    fun testHidePointerIconOnSecureWindowScreenshot() {
        device.hoverMove(1, 1)
        verifier.assertReceivedMotion(withMotionAction(MotionEvent.ACTION_HOVER_ENTER))
        waitForPointerIconUpdate()

        assertScreenshotsMatch()
    }

    private fun getActualScreenshot(): Bitmap {
        val actualBitmap: Bitmap? = virtualDisplayRule.getScreenshot()
        assertNotNull(actualBitmap, "Screenshot is null.")
        return actualBitmap
    }

    private fun assertScreenshotsMatch() {
        getActualScreenshot().assertAgainstGolden(
            screenshotRule,
            getParameterizedExpectedScreenshotName(),
            exactScreenshotMatcher
        )
    }

    private fun getParameterizedExpectedScreenshotName(): String {
        // Replace illegal characters '[' and ']' in expected screenshot name with underscores.
        return "${testName.methodName}expected".replace("""\[|\]""".toRegex(), "_")
    }

    // We don't have a way to synchronously know when the requested pointer icon has been drawn
    // to the display, so wait some time (at least one display frame) for the icon to propagate.
    private fun waitForPointerIconUpdate() = Thread.sleep(500L * HW_TIMEOUT_MULTIPLIER)

    companion object {
        const val MAX_PIXELS_DIFFERENT = 5
        const val ASSETS_PATH = "tests/input/assets"
        val TEST_OUTPUT_PATH =
            "/sdcard/Download/CtsInputTestCases/" +
            HidePointerIconOnSecureWindowScreenshotTest::class.java.simpleName
        val HW_TIMEOUT_MULTIPLIER = SystemProperties.getInt("ro.hw_timeout_multiplier", 1);

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Iterable<Any> =
            listOf(TestPointerDevice.MOUSE, TestPointerDevice.DRAWING_TABLET)
    }
}

/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bugreport.cts_root;

import static android.app.admin.flags.Flags.FLAG_ONBOARDING_BUGREPORT_STORAGE_BUG_FIX;
import static android.app.admin.flags.Flags.FLAG_ONBOARDING_CONSENTLESS_BUGREPORTS;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BugreportManager;
import android.os.BugreportManager.BugreportCallback;
import android.os.BugreportParams;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Device-side tests for Bugreport Manager API.
 *
 * <p>These tests require root to allowlist the test package to use the BugreportManager APIs.
 */
@RunWith(AndroidJUnit4.class)
public class BugreportManagerTest {

    private Context mContext;
    private BugreportManager mBugreportManager;

    @Rule
    public TestName name = new TestName();

    private static final long UIAUTOMATOR_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    private static final long BUGREPORT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(4);
    private static final int MAX_ALLOWED_BUGREPROTS = 8;
    private static final String INTENT_BUGREPORT_FINISHED =
            "com.android.internal.intent.action.BUGREPORT_FINISHED";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();


    @Before
    public void setup() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mBugreportManager = mContext.getSystemService(BugreportManager.class);
        ensureNoConsentDialogShown();


        // Unlock before finding/clicking an object.
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        device.executeShellCommand("wm dismiss-keyguard");
    }

    @BeforeClass
    public static void classSetup() {
        runShellCommand("settings put global auto_time 0");
        runShellCommand("svc power stayon true");
        // Kill current bugreport, so that it does not interfere with future bugreports.
        runShellCommand("setprop ctl.stop bugreportd");
    }

    @AfterClass
    public static void classTearDown() {
        // Restore auto time
        runShellCommand("settings put global auto_time 1");
        runShellCommand("svc power stayon false");
        // Kill current bugreport, so that it does not interfere with future bugreports.
        runShellCommand("setprop ctl.stop bugreportd");
    }

    @LargeTest
    @Test
    public void testRetrieveBugreportConsentGranted() throws Exception {
        try {
            ensureNotConsentlessReport();
            File startBugreportFile = createTempFile("startbugreport", ".zip");
            CountDownLatch latch = new CountDownLatch(1);
            BugreportCallbackImpl callback = new BugreportCallbackImpl(latch);
            mBugreportManager.startBugreport(parcelFd(startBugreportFile), null,
                    new BugreportParams(
                            BugreportParams.BUGREPORT_MODE_ONBOARDING,
                            BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT),
                    mContext.getMainExecutor(), callback);
            latch.await(4, TimeUnit.MINUTES);
            assertThat(callback.isSuccess()).isTrue();
            // No data should be passed to the FD used to call startBugreport.
            assertThat(startBugreportFile.length()).isEqualTo(0);
            String bugreportFileLocation = callback.getBugreportFile();
            waitForDumpstateServiceToStop();

            // Trying to retrieve an unknown bugreport should fail
            latch = new CountDownLatch(1);
            callback = new BugreportCallbackImpl(latch);
            File bugreportFile2 = createTempFile("bugreport2_" + name.getMethodName(), ".zip");
            mBugreportManager.retrieveBugreport(
                    "unknown/file.zip", parcelFd(bugreportFile2),
                    mContext.getMainExecutor(), callback);
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(callback.getErrorCode()).isEqualTo(
                    BugreportCallback.BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE);
            waitForDumpstateServiceToStop();

            File bugreportFile = createTempFile("bugreport_" + name.getMethodName(), ".zip");
            // A bugreport was previously generated for this caller. When the consent dialog is invoked
            // and accepted, the bugreport files should be passed to the calling package.
            ParcelFileDescriptor bugreportFd = parcelFd(bugreportFile);
            assertThat(bugreportFd).isNotNull();
            latch = new CountDownLatch(1);
            mBugreportManager.retrieveBugreport(bugreportFileLocation, bugreportFd,
                    mContext.getMainExecutor(), new BugreportCallbackImpl(latch));
            shareConsentDialog(ConsentReply.ALLOW);
            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
            assertThat(bugreportFile.length()).isGreaterThan(0);
        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }


    @LargeTest
    @Test
    public void testRetrieveBugreportConsentDenied() throws Exception {
        try {
            // User denies consent, therefore no data should be passed back to the bugreport file.
            ensureNotConsentlessReport();
            CountDownLatch latch = new CountDownLatch(1);
            BugreportCallbackImpl callback = new BugreportCallbackImpl(latch);
            mBugreportManager.startBugreport(parcelFd(new File("/dev/null")),
                    null, new BugreportParams(BugreportParams.BUGREPORT_MODE_ONBOARDING,
                            BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT),
                    mContext.getMainExecutor(), callback);
            latch.await(4, TimeUnit.MINUTES);
            assertThat(callback.isSuccess()).isTrue();
            String bugreportFileLocation = callback.getBugreportFile();
            waitForDumpstateServiceToStop();

            latch = new CountDownLatch(1);
            callback = new BugreportCallbackImpl(latch);
            File bugreportFile = createTempFile("bugreport_" + name.getMethodName(), ".zip");
            ParcelFileDescriptor bugreportFd = parcelFd(bugreportFile);
            assertThat(bugreportFd).isNotNull();
            mBugreportManager.retrieveBugreport(
                    bugreportFileLocation,
                    bugreportFd,
                    mContext.getMainExecutor(),
                    callback);
            shareConsentDialog(ConsentReply.DENY);
            latch.await(1, TimeUnit.MINUTES);
            assertThat(callback.getErrorCode()).isEqualTo(
                    BugreportCallback.BUGREPORT_ERROR_USER_DENIED_CONSENT);
            assertThat(bugreportFile.length()).isEqualTo(0);
            waitForDumpstateServiceToStop();

            // Since consent has already been denied, this call should fail because consent cannot
            // be requested twice for the same bugreport.
            latch = new CountDownLatch(1);
            callback = new BugreportCallbackImpl(latch);
            mBugreportManager.retrieveBugreport(bugreportFileLocation, parcelFd(bugreportFile),
                    mContext.getMainExecutor(), callback);
            latch.await(1, TimeUnit.MINUTES);
            assertThat(callback.getErrorCode()).isEqualTo(
                    BugreportCallback.BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE);
            waitForDumpstateServiceToStop();
        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    @LargeTest
    @Test
    @RequiresFlagsEnabled(FLAG_ONBOARDING_BUGREPORT_STORAGE_BUG_FIX)
    @Ignore
    public void testBugreportsLimitReached() throws Exception {
        try {
            List<File> bugreportFiles = new ArrayList<>();
            List<String> bugreportFileLocations = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            for (int i = 0; i < MAX_ALLOWED_BUGREPROTS + 1; i++) {
                waitForDumpstateServiceToStop();
                File bugreportFile = createTempFile(
                        "bugreport_" + name.getMethodName() + "_" + i, ".zip");
                bugreportFiles.add(bugreportFile);
                File startBugreportFile = createTempFile("startbugreport", ".zip");

                latch = new CountDownLatch(1);
                BugreportCallbackImpl callback = new BugreportCallbackImpl(latch);

                mBugreportManager.startBugreport(parcelFd(startBugreportFile), null,
                        new BugreportParams(
                                BugreportParams.BUGREPORT_MODE_ONBOARDING,
                                BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT),
                        mContext.getMainExecutor(), callback);

                latch.await(BUGREPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                assertThat(callback.isSuccess()).isTrue();
                bugreportFileLocations.add(callback.getBugreportFile());
                waitForDumpstateServiceToStop();
            }

            final long newTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10);
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mContext.getSystemService(AlarmManager.class).setTime(newTime));

            // Trigger a shell bugreport to trigger cleanup logic
            triggerShellBugreport(BugreportParams.BUGREPORT_MODE_ONBOARDING);

            // The retrieved first bugreport file should be empty.
            latch = new CountDownLatch(1);
            BugreportCallbackImpl callback = new BugreportCallbackImpl(latch);
            mBugreportManager.retrieveBugreport(
                    bugreportFileLocations.getFirst(), parcelFd(bugreportFiles.getFirst()),
                    mContext.getMainExecutor(), callback);
            ensureNotConsentlessReport();
            shareConsentDialog(ConsentReply.ALLOW);
            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
            assertThat(bugreportFiles.getFirst().length()).isEqualTo(0);
            waitForDumpstateServiceToStop();

            // The retrieved last bugreport file should not be empty.
            latch = new CountDownLatch(1);
            callback = new BugreportCallbackImpl(latch);
            mBugreportManager.retrieveBugreport(
                    bugreportFileLocations.getLast(), parcelFd(bugreportFiles.getLast()),
                    mContext.getMainExecutor(), callback);
            ensureNotConsentlessReport();
            shareConsentDialog(ConsentReply.ALLOW);
            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
            assertThat(bugreportFiles.getLast().length()).isGreaterThan(0);
            waitForDumpstateServiceToStop();
        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    @LargeTest
    @Test
    @RequiresFlagsEnabled(FLAG_ONBOARDING_CONSENTLESS_BUGREPORTS)
    @Ignore
    public void testBugreport_skipsConsentForDeferredReportAfterFullReport() throws Exception {
        try {
            ensureNotConsentlessReport();
            startFullReport(false);

            startDeferredReport(true);
            startDeferredReport(true);

        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    @LargeTest
    @Test
    @RequiresFlagsEnabled(FLAG_ONBOARDING_CONSENTLESS_BUGREPORTS)
    @Ignore
    public void testBugreport_skipConsentForDeferredReportAfterDeferredReport() throws Exception {
        try {
            ensureNotConsentlessReport();
            startDeferredReport(false);

            startDeferredReport(true);

        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    @LargeTest
    @Test
    @RequiresFlagsEnabled(FLAG_ONBOARDING_CONSENTLESS_BUGREPORTS)
    @Ignore("b/344704922")
    public void testBugreport_doesNotSkipConsentForFullReportAfterFullReport() throws Exception {
        try {
            ensureNotConsentlessReport();
            startFullReport(false);

            startFullReport(false);

        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    @LargeTest
    @Test
    @RequiresFlagsEnabled(FLAG_ONBOARDING_CONSENTLESS_BUGREPORTS)
    @Ignore
    public void testBugreport_skipConsentForFullReportAfterDeferredReport() throws Exception {
        try {
            ensureNotConsentlessReport();
            startDeferredReport(false);

            startFullReport(true);

        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    @LargeTest
    @Test
    @RequiresFlagsEnabled(FLAG_ONBOARDING_CONSENTLESS_BUGREPORTS)
    @Ignore
    public void testBugreport_doesNotSkipConsentAfterTimeLimit() throws Exception {
        try {
            ensureNotConsentlessReport();
            startFullReport(false);
            final long newTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
            SystemUtil.runWithShellPermissionIdentity(() ->
                    mContext.getSystemService(AlarmManager.class).setTime(newTime));

            startDeferredReport(false);

        } finally {
            waitForDumpstateServiceToStop();
            // Remove all bugreport files
            SystemUtil.runShellCommand("rm -f -rR -v /bugreports/");
        }
    }

    private void ensureNotConsentlessReport() {
        final long time = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(60);
        SystemUtil.runWithShellPermissionIdentity(() ->
                mContext.getSystemService(AlarmManager.class).setTime(time));
        assertThat(System.currentTimeMillis()).isGreaterThan(time);
    }

    private void startFullReport(boolean skipConsent) throws Exception {
        waitForDumpstateServiceToStop();
        File bugreportFile = createTempFile("startbugreport", ".zip");
        CountDownLatch latch = new CountDownLatch(1);
        BugreportCallbackImpl callback = new BugreportCallbackImpl(latch);
        mBugreportManager.startBugreport(parcelFd(bugreportFile), null,
                new BugreportParams(BugreportParams.BUGREPORT_MODE_ONBOARDING, 0),
                mContext.getMainExecutor(), callback);
        if (!skipConsent) {
            shareConsentDialog(ConsentReply.ALLOW);
        }

        latch.await(2, TimeUnit.MINUTES);
        assertThat(callback.isSuccess()).isTrue();
        // No data should be passed to the FD used to call startBugreport.
        assertThat(bugreportFile.length()).isGreaterThan(0);
        waitForDumpstateServiceToStop();
    }

    private void startDeferredReport(boolean skipConsent) throws Exception {
        waitForDumpstateServiceToStop();
        File bugreportFile = createTempFile("startbugreport", ".zip");
        CountDownLatch latch = new CountDownLatch(1);
        BugreportCallbackImpl callback = new BugreportCallbackImpl(latch);
        mBugreportManager.startBugreport(parcelFd(bugreportFile), null,
                new BugreportParams(
                        BugreportParams.BUGREPORT_MODE_ONBOARDING,
                        BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT),
                mContext.getMainExecutor(), callback);

        latch.await(1, TimeUnit.MINUTES);
        assertThat(callback.isSuccess()).isTrue();
        String location = callback.getBugreportFile();
        waitForDumpstateServiceToStop();


        // The retrieved bugreport file should not be empty.
        latch = new CountDownLatch(1);
        callback = new BugreportCallbackImpl(latch);
        mBugreportManager.retrieveBugreport(
                location, parcelFd(bugreportFile),
                mContext.getMainExecutor(), callback);
        if (!skipConsent) {
            shareConsentDialog(ConsentReply.ALLOW);
        }
        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(bugreportFile.length()).isGreaterThan(0);
        waitForDumpstateServiceToStop();
    }

    private void triggerShellBugreport(int type) throws Exception {
        BugreportBroadcastReceiver br = new BugreportBroadcastReceiver();
        final IntentFilter intentFilter = new IntentFilter(INTENT_BUGREPORT_FINISHED);
        mContext.registerReceiver(br, intentFilter, Context.RECEIVER_EXPORTED);
        final BugreportParams params = new BugreportParams(type);
        mBugreportManager.requestBugreport(params, "" /* shareTitle */, "" /* shareDescription */);

        try {
            br.waitForBugreportFinished();
        } finally {
            // The latch may fail for a number of reasons but we still need to unregister the
            // BroadcastReceiver.
            mContext.unregisterReceiver(br);
        }

        Intent response = br.getBugreportFinishedIntent();
        assertThat(response.getAction()).isEqualTo(intentFilter.getAction(0));
        waitForDumpstateServiceToStop();
    }

    private class BugreportBroadcastReceiver extends BroadcastReceiver {
        Intent bugreportFinishedIntent = null;
        final CountDownLatch latch;

        BugreportBroadcastReceiver() {
            latch = new CountDownLatch(1);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            setBugreportFinishedIntent(intent);
            latch.countDown();
        }

        private void setBugreportFinishedIntent(Intent intent) {
            bugreportFinishedIntent = intent;
        }

        public Intent getBugreportFinishedIntent() {
            return bugreportFinishedIntent;
        }

        public void waitForBugreportFinished() throws Exception {
            if (!latch.await(BUGREPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new Exception("Failed to receive BUGREPORT_FINISHED in "
                        + BUGREPORT_TIMEOUT_MS + " ms.");
            }
        }
    }

    private ParcelFileDescriptor parcelFd(File file) throws Exception {
        return ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_APPEND);
    }

    private static File createTempFile(String prefix, String extension) throws Exception {
        final File f = File.createTempFile(prefix, extension);
        f.setReadable(true, true);
        f.setWritable(true, true);

        f.deleteOnExit();
        return f;
    }

    private static final class BugreportCallbackImpl extends BugreportCallback {
        private int mErrorCode = -1;
        private boolean mSuccess = false;
        private String mBugreportFile;
        private final Object mLock = new Object();

        private final CountDownLatch mLatch;

        BugreportCallbackImpl(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void onError(int errorCode) {
            synchronized (mLock) {
                mErrorCode = errorCode;
                mLatch.countDown();
            }
        }

        @Override
        public void onFinished(String bugreportFile) {
            synchronized (mLock) {
                mBugreportFile = bugreportFile;
                mLatch.countDown();
                mSuccess =  true;
            }
        }

        @Override
        public void onFinished() {
            synchronized (mLock) {
                mLatch.countDown();
                mSuccess = true;
            }
        }

        public int getErrorCode() {
            synchronized (mLock) {
                return mErrorCode;
            }
        }

        public boolean isSuccess() {
            synchronized (mLock) {
                return mSuccess;
            }
        }

        public String getBugreportFile() {
            synchronized (mLock) {
                return mBugreportFile;
            }
        }
    }

    private enum ConsentReply {
        ALLOW,
        DENY,
        TIMEOUT
    }

    /*
     * Ensure the consent dialog is shown and take action according to <code>consentReply<code/>.
     * It will fail if the dialog is not shown when <code>ignoreNotFound<code/> is false.
     */
    private void shareConsentDialog(@NonNull ConsentReply consentReply) throws Exception {
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        final BySelector consentTitleObj = By.res("android", "alertTitle");
        if (!device.wait(Until.hasObject(consentTitleObj), UIAUTOMATOR_TIMEOUT_MS)) {
            fail("The consent dialog is not found");
        }
        if (consentReply.equals(ConsentReply.TIMEOUT)) {
            return;
        }
        final BySelector selector;
        if (consentReply.equals(ConsentReply.ALLOW)) {
            selector = By.res("android", "button1");
        } else { // ConsentReply.DENY
            selector = By.res("android", "button2");
        }
        final UiObject2 btnObj = device.findObject(selector);
        assertThat(btnObj).isNotNull();
        btnObj.click();

        assertThat(device.wait(Until.gone(consentTitleObj), UIAUTOMATOR_TIMEOUT_MS)).isTrue();
    }

    /*
     * Ensure the consent dialog is shown and take action according to <code>consentReply<code/>.
     * It will fail if the dialog is not shown when <code>ignoreNotFound<code/> is false.
     */
    private void ensureNoConsentDialogShown() throws Exception {
        final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        final BySelector consentTitleObj = By.res("android", "alertTitle");
        if (!device.wait(Until.hasObject(consentTitleObj), TimeUnit.SECONDS.toMillis(2))) {
            return;
        }
        final BySelector selector = By.res("android", "button2");
        final UiObject2 btnObj = device.findObject(selector);
        if (btnObj == null) {
            return;
        }
        btnObj.click();

        device.wait(Until.gone(consentTitleObj), UIAUTOMATOR_TIMEOUT_MS);
    }


    /** Waits for the dumpstate service to stop, for up to 5 seconds. */
    private void waitForDumpstateServiceToStop() throws Exception {
        int pollingIntervalMillis = 100;
        Method method = Class.forName("android.os.ServiceManager").getMethod(
                "getService", String.class);
        for (int i = 0; i < 10; i++) {
            int numPolls = 50;
            while (numPolls-- > 0) {
                // If getService() returns null, the service has stopped.
                if (method.invoke(null, "dumpstate") == null) {
                    break;
                }
                Thread.sleep(pollingIntervalMillis);
            }
        }
        if (method.invoke(null, "dumpstate") == null) {
            return;
        }
        fail("Dumpstate did not stop within 25 seconds");
    }
}

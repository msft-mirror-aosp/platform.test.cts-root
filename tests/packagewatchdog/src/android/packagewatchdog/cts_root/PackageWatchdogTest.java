/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.packagewatchdog.cts_root;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.VersionedPackage;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.PackageWatchdog;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PackageWatchdogTest {

    private PackageWatchdog mPackageWatchdog;

    private static final String PACKAGE_NAME = "test.package";
    private static final int FAILURE_COUNT_THRESHOLD = 5;

    @Before
    public void setUp() {
        Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mPackageWatchdog = PackageWatchdog.getInstance(mContext);
    }

    @Test
    public void testAppCrashIsMitigated() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        TestObserver mTestObserver = new TestObserver("test-observer", latch);
        mPackageWatchdog.registerHealthObserver(mTestObserver);
        mPackageWatchdog.startObservingHealth(
                mTestObserver, List.of(PACKAGE_NAME), TimeUnit.MINUTES.toMillis(5));
        for (int i = 0; i < FAILURE_COUNT_THRESHOLD; i++) {
            mPackageWatchdog.onPackageFailure(
                    List.of(new VersionedPackage(PACKAGE_NAME, 1)),
                    PackageWatchdog.FAILURE_REASON_APP_CRASH);
        }
        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(mTestObserver.mMitigatedPackages).isEqualTo(List.of(PACKAGE_NAME));
    }

    private static class TestObserver implements PackageWatchdog.PackageHealthObserver {
        private final String mName;
        private final int mImpact;
        final List<String> mMitigatedPackages = new ArrayList<>();
        private CountDownLatch mLatch;

        TestObserver(String name, CountDownLatch latch) {
            mName = name;
            mLatch = latch;
            mImpact = PackageWatchdog.PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
        }

        public int onHealthCheckFailed(VersionedPackage versionedPackage, int failureReason,
                int mitigationCount) {
            return mImpact;
        }

        public boolean execute(VersionedPackage versionedPackage, int failureReason,
                int mitigationCount) {
            mMitigatedPackages.add(versionedPackage.getPackageName());
            mLatch.countDown();
            return true;
        }

        public String getName() {
            return mName;
        }

        public int onBootLoop(int level) {
            return mImpact;
        }

        public boolean executeBootLoopMitigation(int level) {
            return true;
        }
    }
}

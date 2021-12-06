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

package com.android.cts_root.packageinstaller.host;

import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests sessions are cleaned up (session id and staging files) when installation fails.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class SessionCleanUpHostTest extends BaseHostJUnit4Test {
    /**
     * Checks staging directories are deleted when installation fails.
     */
    @Rule
    public TestRule mStagingDirectoryRule = (base, description) -> new Statement() {
        @Override
        public void evaluate() throws Throwable {
            List<String> stagedBefore = getStagingDirectoriesForStagedSessions();
            List<String> nonStagedBefore = getStagingDirectoriesForNonStagedSessions();
            base.evaluate();
            List<String> stagedAfter = getStagingDirectoriesForStagedSessions();
            List<String> nonStagedAfter = getStagingDirectoriesForNonStagedSessions();
            assertThat(stagedAfter).isEqualTo(stagedBefore);
            assertThat(nonStagedAfter).isEqualTo(nonStagedBefore);
        }
    };

    private void run(String method) throws Exception {
        assertThat(runDeviceTests("com.android.cts_root.packageinstaller",
                "com.android.cts_root.packageinstaller.SessionCleanUpTest",
                method)).isTrue();
    }

    @Before
    @After
    public void cleanUp() throws Exception {
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.B");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.C");
    }

    /**
     * Tests a successful single-package session is cleaned up.
     */
    @Test
    public void testSessionCleanUp_Single_Success() throws Exception {
        run("testSessionCleanUp_Single_Success");
    }

    /**
     * Tests a successful multi-package session is cleaned up.
     */
    @Test
    public void testSessionCleanUp_Multi_Success() throws Exception {
        run("testSessionCleanUp_Multi_Success");
    }

    /**
     * Tests a single-package session is cleaned up when verification failed.
     */
    @Test
    public void testSessionCleanUp_Single_VerificationFailed() throws Exception {
        run("testSessionCleanUp_Single_VerificationFailed");
    }

    /**
     * Tests a multi-package session is cleaned up when verification failed.
     */
    @Test
    public void testSessionCleanUp_Multi_VerificationFailed() throws Exception {
        run("testSessionCleanUp_Multi_VerificationFailed");
    }

    /**
     * Tests a single-package session is cleanup up when validation failed.
     */
    @Test
    public void testSessionCleanUp_Single_ValidationFailed() throws Exception {
        run("testSessionCleanUp_Single_ValidationFailed");
    }

    /**
     * Tests a multi-package session is cleaned up when validation failed.
     */
    @Test
    public void testSessionCleanUp_Multi_ValidationFailed() throws Exception {
        run("testSessionCleanUp_Multi_ValidationFailed");
    }

    /**
     * Tests a single-package session is cleaned up when user rejected the permission.
     */
    @Test
    public void testSessionCleanUp_Single_NoPermission() throws Exception {
        run("testSessionCleanUp_Single_NoPermission");
    }

    /**
     * Tests a multi-package session is cleaned up when user rejected the permission.
     */
    @Test
    public void testSessionCleanUp_Multi_NoPermission() throws Exception {
        run("testSessionCleanUp_Multi_NoPermission");
    }

    private List<String> getStagingDirectoriesForNonStagedSessions() throws Exception {
        return getStagingDirectories("/data/app", "vmdl\\d+.tmp");
    }

    private List<String> getStagingDirectoriesForStagedSessions() throws Exception {
        return getStagingDirectories("/data/app-staging", "session_\\d+");
    }

    private List<String> getStagingDirectories(String baseDir, String pattern) throws Exception {
        return getDevice().getFileEntry(baseDir).getChildren(false)
                .stream().filter(entry -> entry.getName().matches(pattern))
                .map(entry -> entry.getName())
                .collect(Collectors.toList());
    }
}

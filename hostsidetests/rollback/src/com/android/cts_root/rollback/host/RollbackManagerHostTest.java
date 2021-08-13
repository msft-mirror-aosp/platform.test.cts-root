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

package com.android.cts_root.rollback.host;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import com.android.ddmlib.Log;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IFileEntry;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CTS-root host tests for RollbackManager APIs.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class RollbackManagerHostTest extends BaseHostJUnit4Test {
    private static final String TAG = "RollbackManagerHostTest";

    private void run(String method) throws Exception {
        assertThat(runDeviceTests("com.android.cts_root.rollback.host.app",
                "com.android.cts_root.rollback.host.app.HostTestHelper",
                method)).isTrue();
    }

    @Before
    @After
    public void cleanUp() throws Exception {
        getDevice().executeShellCommand("for i in $(pm list staged-sessions --only-sessionid "
                + "--only-parent); do pm install-abandon $i; done");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.B");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.C");
        run("cleanUp");
    }

    /**
     * Tests user data is restored according to the preset rollback data policy.
     */
    @Test
    public void testRollbackDataPolicy() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_ce/0/rollback");

        run("testRollbackDataPolicy_Phase1_Install");
        getDevice().reboot();
        run("testRollbackDataPolicy_Phase2_Rollback");
        getDevice().reboot();
        run("testRollbackDataPolicy_Phase3_VerifyRollback");

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc_ce/0/rollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_ce/0/rollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    private List<String> getSnapshotDirectories(String baseDir) throws Exception {
        IFileEntry f = getDevice().getFileEntry(baseDir);
        if (f == null) {
            Log.d(TAG, "baseDir doesn't exist: " + baseDir);
            return Collections.EMPTY_LIST;
        }
        List<String> list = f.getChildren(false)
                .stream().filter(entry -> entry.getName().matches("\\d+(-prerestore)?"))
                .map(entry -> entry.getFullPath())
                .collect(Collectors.toList());
        Log.d(TAG, "getSnapshotDirectories=" + list);
        return list;
    }

    private void assertDirectoryIsEmpty(String path) {
        try {
            IFileEntry file = getDevice().getFileEntry(path);
            assertWithMessage("Not a directory: " + path).that(file.isDirectory()).isTrue();
            assertWithMessage("Directory not empty: " + path)
                    .that(file.getChildren(false)).isEmpty();
        } catch (DeviceNotAvailableException e) {
            fail("Can't access directory: " + path);
        }
    }
}

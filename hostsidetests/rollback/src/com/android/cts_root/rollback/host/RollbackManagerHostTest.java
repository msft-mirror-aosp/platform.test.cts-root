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

import static com.android.cts.shim.lib.ShimPackage.SHIM_APEX_PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.cts.install.lib.host.InstallUtilsHost;

import com.android.ddmlib.Log;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IFileEntry;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CTS-root host tests for RollbackManager APIs.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class RollbackManagerHostTest extends BaseHostJUnit4Test {
    private static final String TAG = "RollbackManagerHostTest";

    private static final String TESTAPP_A = "com.android.cts.install.lib.testapp.A";
    private static final String TEST_SUBDIR = "/subdir/";
    private static final String TEST_FILENAME_1 = "test_file.txt";
    private static final String TEST_STRING_1 = "hello this is a test";
    private static final String TEST_FILENAME_2 = "another_file.txt";
    private static final String TEST_STRING_2 = "this is a different file";
    private static final String TEST_FILENAME_3 = "also.xyz";
    private static final String TEST_STRING_3 = "also\n a\n test\n string";
    private static final String TEST_FILENAME_4 = "one_more.test";
    private static final String TEST_STRING_4 = "once more unto the test";

    private final InstallUtilsHost mHostUtils = new InstallUtilsHost(this);

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
        mHostUtils.uninstallShimApexIfNecessary();
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

    /**
     * Tests that data in DE apk data directory is restored when apk is rolled back.
     */
    @Test
    public void testRollbackApkDataDirectories_De() throws Exception {
        // Install version 1 of TESTAPP_A
        run("testRollbackApkDataDirectories_Phase1_InstallV1");

        // Push files to apk data directory
        String oldFilePath1 = apkDataDirDe(TESTAPP_A, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 = apkDataDirDe(TESTAPP_A, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        pushString(TEST_STRING_1, oldFilePath1);
        pushString(TEST_STRING_2, oldFilePath2);

        // Install version 2 of TESTAPP_A with rollback enabled
        run("testRollbackApkDataDirectories_Phase2_InstallV2");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apkDataDirDe(TESTAPP_A, 0) + "/" + TEST_FILENAME_3;
        String newFilePath4 = apkDataDirDe(TESTAPP_A, 0) + TEST_SUBDIR + TEST_FILENAME_4;
        getDevice().deleteFile(oldFilePath1);
        getDevice().deleteFile(oldFilePath2);
        pushString(TEST_STRING_3, newFilePath3);
        pushString(TEST_STRING_4, newFilePath4);

        // Roll back the APK
        run("testRollbackApkDataDirectories_Phase3_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        assertFileContents(TEST_STRING_1, oldFilePath1);
        assertFileContents(TEST_STRING_2, oldFilePath2);
        assertFileNotExists(newFilePath3);
        assertFileNotExists(newFilePath4);
    }

    /**
     * Tests that data in CE apex data directory is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexDataDirectories_Ce() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_ce/0/apexrollback");

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirCe(
                SHIM_APEX_PACKAGE_NAME, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 = apexDataDirCe(
                SHIM_APEX_PACKAGE_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        pushString(TEST_STRING_1, oldFilePath1);
        pushString(TEST_STRING_2, oldFilePath2);

        // Install new version of the APEX with rollback enabled
        run("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apexDataDirCe(
                SHIM_APEX_PACKAGE_NAME, 0) + "/" + TEST_FILENAME_3;
        String newFilePath4 = apexDataDirCe(
                SHIM_APEX_PACKAGE_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_4;
        getDevice().deleteFile(oldFilePath1);
        getDevice().deleteFile(oldFilePath2);
        pushString(TEST_STRING_3, newFilePath3);
        pushString(TEST_STRING_4, newFilePath4);

        // Roll back the APEX
        getDevice().executeShellCommand("pm rollback-app " + SHIM_APEX_PACKAGE_NAME);
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        assertFileContents(TEST_STRING_1, oldFilePath1);
        assertFileContents(TEST_STRING_2, oldFilePath2);
        assertFileNotExists(newFilePath3);
        assertFileNotExists(newFilePath4);

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc_ce/0/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_ce/0/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that data in DE (user) apex data directory is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexDataDirectories_DeUser() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_de/0/apexrollback");

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirDeUser(
                SHIM_APEX_PACKAGE_NAME, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 = apexDataDirDeUser(
                SHIM_APEX_PACKAGE_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        pushString(TEST_STRING_1, oldFilePath1);
        pushString(TEST_STRING_2, oldFilePath2);

        // Install new version of the APEX with rollback enabled
        run("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apexDataDirDeUser(
                SHIM_APEX_PACKAGE_NAME, 0) + "/" + TEST_FILENAME_3;
        String newFilePath4 = apexDataDirDeUser(
                SHIM_APEX_PACKAGE_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_4;
        getDevice().deleteFile(oldFilePath1);
        getDevice().deleteFile(oldFilePath2);
        pushString(TEST_STRING_3, newFilePath3);
        pushString(TEST_STRING_4, newFilePath4);

        // Roll back the APEX
        getDevice().executeShellCommand("pm rollback-app " + SHIM_APEX_PACKAGE_NAME);
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        assertFileContents(TEST_STRING_1, oldFilePath1);
        assertFileContents(TEST_STRING_2, oldFilePath2);
        assertFileNotExists(newFilePath3);
        assertFileNotExists(newFilePath4);

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc_de/0/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_de/0/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that data in DE_sys apex data directory is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexDataDirectories_DeSys() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc/apexrollback");

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirDeSys(
                SHIM_APEX_PACKAGE_NAME) + "/" + TEST_FILENAME_1;
        String oldFilePath2 = apexDataDirDeSys(
                SHIM_APEX_PACKAGE_NAME) + TEST_SUBDIR + TEST_FILENAME_2;
        pushString(TEST_STRING_1, oldFilePath1);
        pushString(TEST_STRING_2, oldFilePath2);

        // Install new version of the APEX with rollback enabled
        run("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apexDataDirDeSys(
                SHIM_APEX_PACKAGE_NAME) + "/" + TEST_FILENAME_3;
        String newFilePath4 = apexDataDirDeSys(
                SHIM_APEX_PACKAGE_NAME) + TEST_SUBDIR + TEST_FILENAME_4;
        getDevice().deleteFile(oldFilePath1);
        getDevice().deleteFile(oldFilePath2);
        pushString(TEST_STRING_3, newFilePath3);
        pushString(TEST_STRING_4, newFilePath4);

        // Roll back the APEX
        getDevice().executeShellCommand("pm rollback-app " + SHIM_APEX_PACKAGE_NAME);
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        assertFileContents(TEST_STRING_1, oldFilePath1);
        assertFileContents(TEST_STRING_2, oldFilePath2);
        assertFileNotExists(newFilePath3);
        assertFileNotExists(newFilePath4);

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that apex CE snapshots are deleted when its rollback is deleted.
     */
    @Test
    public void testExpireApexRollback() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_ce/0/apexrollback");

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirCe(
                SHIM_APEX_PACKAGE_NAME, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 = apexDataDirCe(
                SHIM_APEX_PACKAGE_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        pushString(TEST_STRING_1, oldFilePath1);
        pushString(TEST_STRING_2, oldFilePath2);

        // Install new version of the APEX with rollback enabled
        run("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        List<String> after = getSnapshotDirectories("/data/misc_ce/0/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_ce/0/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        // Expire all rollbacks and check CE snapshot directories are deleted
        run("cleanUp");
        assertFileNotExists(after.get(0));
    }

    /**
     * Tests an available rollback shouldn't be deleted when its session expires.
     */
    @Test
    public void testExpireSession() throws Exception {
        run("testExpireSession_Phase1_Install");
        getDevice().reboot();
        run("testExpireSession_Phase2_VerifyInstall");

        // Advance system clock by 7 days to expire the staged session
        Instant t1 = Instant.ofEpochMilli(getDevice().getDeviceDate());
        Instant t2 = t1.plusMillis(TimeUnit.DAYS.toMillis(7));

        try {
            getDevice().setDate(Date.from(t2));
            // Somehow we need to send the broadcast before reboot. Otherwise the change to the
            // system clock will be lost after reboot.
            getDevice().executeShellCommand("am broadcast -a android.intent.action.TIME_SET");
            getDevice().reboot();
            run("testExpireSession_Phase3_VerifyRollback");
        } finally {
            // Restore system clock
            getDevice().setDate(Date.from(t1));
            getDevice().executeShellCommand("am broadcast -a android.intent.action.TIME_SET");
        }
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

    private void assertFileContents(String expectedContents, String path) throws Exception {
        String actualContents = getDevice().pullFileContents(path);
        assertWithMessage("Failed to retrieve file=%s", path).that(actualContents).isNotNull();
        assertWithMessage("Mismatched file contents, path=%s", path)
                .that(actualContents).isEqualTo(expectedContents);
    }

    private void assertFileNotExists(String path) throws Exception {
        assertWithMessage("File shouldn't exist, path=%s", path)
                .that(getDevice().getFileEntry(path)).isNull();
    }

    private static String apkDataDirDe(String apkName, int userId) {
        return String.format("/data/user_de/%d/%s", userId, apkName);
    }

    private static String apexDataDirCe(String apexName, int userId) {
        return String.format("/data/misc_ce/%d/apexdata/%s", userId, apexName);
    }

    private static String apexDataDirDeUser(String apexName, int userId) {
        return String.format("/data/misc_de/%d/apexdata/%s", userId, apexName);
    }

    private static String apexDataDirDeSys(String apexName) {
        return String.format("/data/misc/apexdata/%s", apexName);
    }

    private void pushString(String contents, String path) throws Exception {
        assertWithMessage("Failed to push file to device, content=%s path=%s", contents, path)
                .that(getDevice().pushString(contents, path)).isTrue();
    }
}

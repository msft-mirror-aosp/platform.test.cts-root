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

package com.android.cts_root.rollback.host.app;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.rollback.lib.RollbackUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * On-device helper test methods used for host-driven rollback tests.
 */
@RunWith(JUnit4.class)
public class HostTestHelper {
    @Before
    public void setup() {
        InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
    }

    @After
    public void teardown() {
        InstallUtils.dropShellPermissionIdentity();
    }

    @Test
    public void cleanUp() {
        // Remove all pending rollbacks
        RollbackManager rm = RollbackUtils.getRollbackManager();
        rm.getAvailableRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
    }

    @Test
    public void testRollbackDataPolicy_Phase1_Install() throws Exception {
        Install.multi(TestApp.A1, TestApp.B1, TestApp.C1).commit();
        // Write user data version = 1
        InstallUtils.processUserData(TestApp.A);
        InstallUtils.processUserData(TestApp.B);
        InstallUtils.processUserData(TestApp.C);

        Install a2 = Install.single(TestApp.A2).setStaged()
                .setEnableRollback(PackageManager.ROLLBACK_DATA_POLICY_WIPE);
        Install b2 = Install.single(TestApp.B2).setStaged()
                .setEnableRollback(PackageManager.ROLLBACK_DATA_POLICY_RESTORE);
        Install c2 = Install.single(TestApp.C2).setStaged()
                .setEnableRollback(PackageManager.ROLLBACK_DATA_POLICY_RETAIN);
        Install.multi(a2, b2, c2).setEnableRollback().setStaged().commit();
    }

    @Test
    public void testRollbackDataPolicy_Phase2_Rollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);
        // Write user data version = 2
        InstallUtils.processUserData(TestApp.A);
        InstallUtils.processUserData(TestApp.B);
        InstallUtils.processUserData(TestApp.C);

        RollbackInfo info = RollbackUtils.getAvailableRollback(TestApp.A);
        RollbackUtils.rollback(info.getRollbackId());
    }

    @Test
    public void testRollbackDataPolicy_Phase3_VerifyRollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(1);
        assertThat(InstallUtils.getInstalledVersion(TestApp.C)).isEqualTo(1);
        // Read user data version from userdata.txt
        // A's user data version is -1 for user data is wiped.
        // B's user data version is 1 for user data is restored.
        // C's user data version is 2 for user data is retained.
        assertThat(InstallUtils.getUserDataVersion(TestApp.A)).isEqualTo(-1);
        assertThat(InstallUtils.getUserDataVersion(TestApp.B)).isEqualTo(1);
        assertThat(InstallUtils.getUserDataVersion(TestApp.C)).isEqualTo(2);
    }
}

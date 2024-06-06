/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.permission.cts_root;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.os.Build;
import android.permission.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;
import com.android.server.LocalManagerRegistry;
import com.android.server.permission.PermissionManagerLocal;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.pkg.PackageState;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class SignaturePermissionAllowlistTest {
    private static final String NORMAL_APP_APK_PATH = "/data/local/tmp/cts-root-permission/"
            + "CtsRootPermissionSignaturePermissionAllowlistNormalApp.apk";
    private static final String NORMAL_APP_PACKAGE_NAME =
            "android.permission.cts_root.apps.signaturepermissionallowlist.normal";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @NonNull
    private final PackageManagerLocal mPackageManagerLocal =
            LocalManagerRegistry.getManager(PackageManagerLocal.class);
    @NonNull
    private final PermissionManagerLocal mPermissionManagerLocal =
            LocalManagerRegistry.getManager(PermissionManagerLocal.class);
    @NonNull
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    @NonNull
    private final PackageManager mPackageManager = mContext.getPackageManager();

    @NonNull
    private SigningDetails mNormalAppSigningDetails;

    @BeforeClass
    public static void setUpClass() throws Exception {
        assumeTrue(Build.isDebuggable());
    }

    @Before
    public void setUp() throws Exception {
        mPermissionManagerLocal.setSignaturePermissionAllowlistForceEnforced(true);
        installPackage(NORMAL_APP_APK_PATH);
        SigningDetails platformSigningDetails;
        try (var snapshot = mPackageManagerLocal.withUnfilteredSnapshot()) {
            Map<String, PackageState> packageStates = snapshot.getPackageStates();
            mNormalAppSigningDetails = packageStates.get(NORMAL_APP_PACKAGE_NAME)
                    .getAndroidPackage().getSigningDetails();
            platformSigningDetails = packageStates.get("android").getAndroidPackage()
                    .getSigningDetails();
        }
        uninstallPackage(NORMAL_APP_PACKAGE_NAME);
        mPackageManagerLocal.addOverrideSigningDetails(mNormalAppSigningDetails,
                platformSigningDetails);
        installPackage(NORMAL_APP_APK_PATH);
    }

    @After
    public void tearDown() throws Exception {
        uninstallPackage(NORMAL_APP_PACKAGE_NAME);
        mPackageManagerLocal.removeOverrideSigningDetails(mNormalAppSigningDetails);
        mPermissionManagerLocal.setSignaturePermissionAllowlistForceEnforced(false);
    }

    @RequiresFlagsEnabled(Flags.FLAG_SIGNATURE_PERMISSION_ALLOWLIST_ENABLED)
    @Test
    public void normalAppCanNotGetSignaturePermissionWithoutAllowlist() throws Exception {
        assertThat(mPackageManager.checkPermission(android.Manifest.permission.BRICK,
                NORMAL_APP_PACKAGE_NAME)).isEqualTo(PackageManager.PERMISSION_DENIED);
    }

    @RequiresFlagsEnabled(Flags.FLAG_SIGNATURE_PERMISSION_ALLOWLIST_ENABLED)
    @Test
    public void normalAppCanGetSignaturePermissionWithAllowlist() throws Exception {
        assertThat(mPackageManager.checkPermission(
                android.Manifest.permission.RESERVED_FOR_TESTING_SIGNATURE,
                NORMAL_APP_PACKAGE_NAME)).isEqualTo(PackageManager.PERMISSION_GRANTED);
    }

    private void installPackage(@NonNull String apkPath) throws Exception {
        SystemUtil.runShellCommandOrThrow("pm install " + apkPath);
    }

    private void uninstallPackage(@NonNull String packageName) throws Exception {
        SystemUtil.runShellCommandOrThrow("pm uninstall " + packageName);
    }
}

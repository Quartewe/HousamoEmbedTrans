package com.quarty.housamoembedtrans.bridge;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

import java.util.Arrays;

public final class CallerVerifier {
    public static void enforceAllowedCaller(
        Context context, String... allowedPackages
    ) {
        int callerUid = Binder.getCallingUid();
        if (callerUid == Process.myUid()) return;

        if (allowedPackages == null || allowedPackages.length == 0) {
            throw new SecurityException(
                "No allowed packages specified for caller verification"
            );
        }

        String[] callerPackages = context.getPackageManager().getPackagesForUid(callerUid);

        if (callerPackages == null || callerPackages.length == 0) {
            throw new SecurityException(
                "No packages found for caller UID: " + callerUid
            );
        }

        for (String allowedPackage : allowedPackages) {
            if (allowedPackage == null || allowedPackage.isEmpty()) {
                continue;
            }

            for (String callerPackage : callerPackages) {
                if (allowedPackage.equals(callerPackage)) {
                    return;
                }
            }
        }

        throw new SecurityException(
            "Caller verification failed. Caller UID: "
                + callerUid
                + ", allowed packages: "
                + Arrays.toString(allowedPackages)
                + ", caller packages: "
                + Arrays.toString(callerPackages)
        );
    }

    private CallerVerifier() {
        throw new AssertionError("No instances");
    }
}

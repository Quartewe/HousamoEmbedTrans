package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

/** Connects the three top-level Android destinations. */
public final class PrimaryNavigation {
    public enum Destination {
        TASKS,
        MANAGEMENT,
        SETTINGS
    }

    private PrimaryNavigation() {
    }

    public static void attach(
        Activity activity,
        View navigation,
        Destination current
    ) {
        if (activity == null || navigation == null) {
            return;
        }
        View tasks = navigation.findViewById(R.id.nav_tasks);
        View management = navigation.findViewById(R.id.nav_management);
        View settings = navigation.findViewById(R.id.nav_settings);
        if (tasks == null || management == null || settings == null) {
            return;
        }
        tasks.setEnabled(true);
        management.setEnabled(true);
        settings.setEnabled(true);
        tasks.setSelected(current == Destination.TASKS);
        management.setSelected(current == Destination.MANAGEMENT);
        settings.setSelected(current == Destination.SETTINGS);
        tasks.setOnClickListener(view -> open(
            activity,
            Destination.TASKS,
            current
        ));
        management.setOnClickListener(view -> open(
            activity,
            Destination.MANAGEMENT,
            current
        ));
        settings.setOnClickListener(view -> open(
            activity,
            Destination.SETTINGS,
            current
        ));
    }

    private static void open(
        Activity activity,
        Destination destination,
        Destination current
    ) {
        if (destination == current) {
            return;
        }
        Class<?> target;
        switch (destination) {
            case TASKS:
                target = TranslationQueueActivity.class;
                break;
            case MANAGEMENT:
                target = ManagementHomeActivity.class;
                break;
            case SETTINGS:
            default:
                target = SettingsActivity.class;
                break;
        }
        activity.startActivity(
            new Intent(activity, target)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        );
    }
}

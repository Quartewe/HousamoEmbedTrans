package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
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
        boolean movingForward = navigationIndex(destination)
            > navigationIndex(current);
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
        Intent intent = new Intent(activity, target)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        // Reorder the existing shell activity so each top-level destination
        // keeps its own tab, search, and scroll state. Notifications use
        // their own explicit deep-link flags and are not affected here.
        Bundle animation = ActivityOptions.makeCustomAnimation(
            activity,
            movingForward
                ? R.anim.het_slide_in_right
                : R.anim.het_slide_in_left,
            movingForward
                ? R.anim.het_slide_out_left
                : R.anim.het_slide_out_right
        ).toBundle();
        activity.startActivity(intent, animation);
    }

    private static int navigationIndex(Destination destination) {
        switch (destination) {
            case TASKS:
                return 0;
            case MANAGEMENT:
                return 1;
            case SETTINGS:
            default:
                return 2;
        }
    }
}

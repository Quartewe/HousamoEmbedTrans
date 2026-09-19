package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/** Connects the four top-level Android destinations. */
public final class PrimaryNavigation {
    public enum Destination {
        HOME,
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
        View home = navigation.findViewById(R.id.nav_home);
        View tasks = navigation.findViewById(R.id.nav_tasks);
        View management = navigation.findViewById(R.id.nav_management);
        View settings = navigation.findViewById(R.id.nav_settings);
        if (home == null || tasks == null || management == null || settings == null) {
            return;
        }
        home.setEnabled(true);
        tasks.setEnabled(true);
        management.setEnabled(true);
        settings.setEnabled(true);
        home.setSelected(current == Destination.HOME);
        tasks.setSelected(current == Destination.TASKS);
        management.setSelected(current == Destination.MANAGEMENT);
        settings.setSelected(current == Destination.SETTINGS);
        home.setOnClickListener(view -> open(
            activity,
            Destination.HOME,
            current
        ));
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
            case HOME:
                target = HomeActivity.class;
                break;
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
            case HOME:
                return 0;
            case TASKS:
                return 1;
            case MANAGEMENT:
                return 2;
            case SETTINGS:
            default:
                return 3;
        }
    }
}

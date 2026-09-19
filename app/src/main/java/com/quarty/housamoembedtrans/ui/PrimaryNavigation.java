package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

/** Connects the four top-level Android destinations. */
public final class PrimaryNavigation {
    private static final String EXTRA_ENTER_DIRECTION =
        "com.quarty.housamoembedtrans.extra.PRIMARY_ENTER_DIRECTION";
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(EXTRA_ENTER_DIRECTION, movingForward ? 1 : -1);
        activity.startActivity(
            intent,
            ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
        );
        activity.overridePendingTransition(0, 0);
    }

    /** Animate only the page siblings; the navigation bar never moves. */
    public static void animateContentOnResume(Activity activity) {
        Intent intent = activity.getIntent();
        int direction = intent.getIntExtra(EXTRA_ENTER_DIRECTION, 0);
        intent.removeExtra(EXTRA_ENTER_DIRECTION);
        if (direction == 0) {
            return;
        }
        View navigation = activity.findViewById(R.id.primary_navigation);
        if (navigation == null || !(navigation.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup page = (ViewGroup) navigation.getParent();
        int animation = direction > 0
            ? R.anim.het_slide_in_right : R.anim.het_slide_in_left;
        for (int index = 0; index < page.getChildCount(); index++) {
            View child = page.getChildAt(index);
            if (child != navigation) {
                child.clearAnimation();
                child.startAnimation(AnimationUtils.loadAnimation(activity, animation));
            }
        }
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

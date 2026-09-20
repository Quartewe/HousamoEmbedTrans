package com.quarty.housamoembedtrans.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import com.quarty.housamoembedtrans.R;

/** Internal structural styling shared by confirmation dialogs. */
final class UiDialogPresentation {
    private static final float CONFIRM_WIDTH_DP = 280f;
    private static final float CONFIRM_SIDE_INSET_DP = 22f;
    private static final float BUTTON_HEIGHT_DP = 32f;
    private static final float BUTTON_GAP_DP = 6f;
    private static final float BUTTON_HORIZONTAL_PADDING_DP = 10f;
    private static final float BUTTON_RADIUS_DP = 999f;
    private static final Object DANGEROUS_POSITIVE_MARKER = new Object();

    private UiDialogPresentation() {
    }

    static void prepare(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        // Inflate AlertController content before WindowManager attaches the window.
        // Resizing from the first global layout can expose the original bounds for
        // one frame and then visibly recenter the confirmation dialog.
        dialog.create();
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.48f);
        styleContent(dialog, window.getDecorView());
    }

    /**
     * Marks the caller's positive action as destructive and applies its
     * explicit danger presentation.  This is intentionally caller-driven;
     * dialog copy and the platform's initial text color are not semantic
     * signals.
     */
    static void styleDangerousPositive(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setTag(DANGEROUS_POSITIVE_MARKER);
        }
        styleButton(
            dialog.getButton(AlertDialog.BUTTON_POSITIVE),
            dialog.getContext(),
            true,
            true
        );
    }

    private static void styleContent(
        AlertDialog dialog,
        View decor
    ) {
        TextView message = dialog.findViewById(android.R.id.message);
        Context context = dialog.getContext();
        View panel = findNamedView(decor, "parentPanel");
        if (panel == null) {
            panel = findNamedView(decor, "mtrl_alert_dialog_background_inset");
        }
        if (panel == null) {
            panel = dialog.findViewById(android.R.id.content);
        }
        if (panel != null) {
            GradientDrawable card = new GradientDrawable();
            card.setColor(ContextCompat.getColor(
                context,
                R.color.het_surface
            ));
            card.setCornerRadius(dp(context, 18));
            card.setStroke(
                dp(context, 1),
                ContextCompat.getColor(context, R.color.het_outline_soft)
            );
            panel.setBackground(card);
            // AlertDialogLayout's custom measure passes the original width to
            // each child and adds parent padding afterwards.  Keep the card
            // itself unpadded and put the insets on the real panels instead.
            panel.setPadding(0, 0, 0, 0);
            panel.setElevation(0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                panel.setClipToOutline(true);
            }
        }

        clearPanelBackground(decor, "title_template");
        clearPanelBackground(decor, "contentPanel");
        clearPanelBackground(decor, "buttonPanel");
        clearPanelInsets(decor, "title_template");
        clearPanelInsets(decor, "contentPanel");
        clearPanelInsets(decor, "customPanel");
        clearPanelInsets(decor, "buttonPanel");
        setPanelPadding(
            decor,
            "title_template",
            dp(context, 18),
            dp(context, 16),
            dp(context, 18),
            0
        );
        setPanelPadding(
            decor,
            "contentPanel",
            dp(context, 18),
            0,
            dp(context, 18),
            0
        );
        setPanelPadding(
            decor,
            "customPanel",
            dp(context, 18),
            0,
            dp(context, 18),
            0
        );
        setPanelPadding(
            decor,
            "buttonPanel",
            dp(context, 18),
            0,
            dp(context, 18),
            dp(context, 8)
        );

        View titleView = findNamedView(decor, "alertTitle");
        if (titleView instanceof TextView) {
            TextView title = (TextView) titleView;
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(
                context,
                R.color.het_on_surface
            ));
            title.setIncludeFontPadding(false);
        }
        if (message != null) {
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            message.setTextColor(ContextCompat.getColor(
                context,
                R.color.het_on_surface_muted
            ));
            message.setLineSpacing(0, 1.45f);
            message.setIncludeFontPadding(false);
            message.setPadding(
                0,
                dp(context, 6),
                0,
                message.getPaddingBottom()
            );
        }

        View buttonPanel = findNamedView(decor, "buttonPanel");
        if (buttonPanel instanceof LinearLayout) {
            LinearLayout actions = (LinearLayout) buttonPanel;
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setPadding(
                dp(context, 18),
                dp(context, 4),
                dp(context, 18),
                dp(context, 10)
            );
            actions.setBackgroundColor(Color.TRANSPARENT);
        } else if (buttonPanel instanceof ViewGroup) {
            ViewGroup panelGroup = (ViewGroup) buttonPanel;
            for (int index = 0; index < panelGroup.getChildCount(); index++) {
                View child = panelGroup.getChildAt(index);
                if (!(child instanceof LinearLayout)) {
                    continue;
                }
                LinearLayout actions = (LinearLayout) child;
                actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                actions.setPadding(
                    0,
                    actions.getPaddingTop(),
                    0,
                    actions.getPaddingBottom()
                );
                actions.setBackgroundColor(Color.TRANSPARENT);
                break;
            }
        }
        styleButton(
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
            context,
            false,
            false
        );
        styleButton(
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
            context,
            false,
            false
        );
        styleButton(
            dialog.getButton(AlertDialog.BUTTON_POSITIVE),
            context,
            true,
            decor.getTag() == DANGEROUS_POSITIVE_MARKER
        );
        setButtonGap(buttonPanel, context);

        Window currentWindow = dialog.getWindow();
        int targetWidth = message == null ? 0 : dialogWidth(context);
        if (targetWidth > 0 && currentWindow != null) {
            currentWindow.setLayout(
                targetWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private static void setPanelPadding(
        View root,
        String name,
        int left,
        int top,
        int right,
        int bottom
    ) {
        View panel = findNamedView(root, name);
        if (panel != null) {
            panel.setPadding(left, top, right, bottom);
        }
    }

    private static void clearPanelBackground(View root, String name) {
        View panel = findNamedView(root, name);
        if (panel != null) {
            panel.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static void clearPanelInsets(View root, String name) {
        View panel = findNamedView(root, name);
        if (panel != null) {
            panel.setPadding(0, 0, 0, 0);
        }
    }

    private static void styleButton(
        Button button,
        Context context,
        boolean primary,
        boolean danger
    ) {
        if (button == null) {
            return;
        }
        int currentTextColor = button.getCurrentTextColor();
        int errorColor = ContextCompat.getColor(context, R.color.het_error);
        int onErrorColor = ContextCompat.getColor(context, R.color.het_on_error);
        danger = danger
            || currentTextColor == errorColor
            || currentTextColor == onErrorColor;
        int fillColor = ContextCompat.getColor(
            context,
            danger
                ? R.color.het_error
                : primary
                    ? R.color.het_primary_container
                    : R.color.het_surface_container_high
        );
        int textColor = ContextCompat.getColor(
            context,
            danger
                ? R.color.het_on_error
                : primary
                    ? R.color.het_on_primary_container
                    : R.color.het_on_surface
        );
        int outlineColor = ContextCompat.getColor(
            context,
            danger ? R.color.het_error : R.color.het_outline_soft
        );
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTextColor(textColor);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        ViewGroup.LayoutParams buttonParams = button.getLayoutParams();
        if (buttonParams != null) {
            buttonParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            buttonParams.height = dp(context, BUTTON_HEIGHT_DP);
            button.setLayoutParams(buttonParams);
        }
        button.setPadding(
            dp(context, BUTTON_HORIZONTAL_PADDING_DP),
            0,
            dp(context, BUTTON_HORIZONTAL_PADDING_DP),
            0
        );
        button.setGravity(Gravity.CENTER);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        if (button instanceof MaterialButton) {
            MaterialButton materialButton = (MaterialButton) button;
            materialButton.setBackgroundTintList(
                ColorStateList.valueOf(fillColor)
            );
            materialButton.setCornerRadius(dp(context, BUTTON_RADIUS_DP));
            materialButton.setStrokeWidth(primary ? 0 : dp(context, 1));
            materialButton.setStrokeColor(ColorStateList.valueOf(outlineColor));
            materialButton.setInsetTop(0);
            materialButton.setInsetBottom(0);
        } else {
            GradientDrawable background = new GradientDrawable();
            background.setColor(fillColor);
            background.setCornerRadius(dp(context, BUTTON_RADIUS_DP));
            background.setStroke(danger ? 0 : dp(context, 1), outlineColor);
            ColorStateList rippleColor = new ColorStateList(
                new int[][] {
                    new int[] {android.R.attr.state_pressed},
                    new int[] {}
                },
                new int[] {
                    Color.argb(28, 0, 0, 0),
                    Color.TRANSPARENT
                }
            );
            button.setBackground(new RippleDrawable(
                rippleColor,
                background,
                null
            ));
        }
    }

    private static void setButtonGap(View root, Context context) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        int buttonIndex = 0;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof Button) {
                if (child.getVisibility() == View.GONE) {
                    continue;
                }
                ViewGroup.LayoutParams rawParams = child.getLayoutParams();
                if (rawParams instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) rawParams;
                    params.leftMargin = 0;
                    params.rightMargin = 0;
                    params.setMarginEnd(0);
                    params.setMarginStart(
                        buttonIndex == 0 ? 0 : dp(context, BUTTON_GAP_DP)
                    );
                    child.setLayoutParams(params);
                }
                buttonIndex++;
            } else {
                setButtonGap(child, context);
            }
        }
    }

    private static View findNamedView(View root, String name) {
        if (root == null) {
            return null;
        }
        int id = root.getId();
        if (id != View.NO_ID) {
            try {
                if (name.equals(root.getResources().getResourceEntryName(id))) {
                    return root;
                }
            } catch (RuntimeException ignored) {
                // Some framework-generated ids do not have a public name.
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findNamedView(group.getChildAt(index), name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int dialogWidth(Context context) {
        int desired = dp(context, CONFIRM_WIDTH_DP);
        int maxWidth = context.getResources().getDisplayMetrics().widthPixels
            - dp(context, CONFIRM_SIDE_INSET_DP * 2);
        return maxWidth > 0 ? Math.min(desired, maxWidth) : desired;
    }

}

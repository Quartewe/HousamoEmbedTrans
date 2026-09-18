package com.quarty.housamoembedtrans.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;

import com.quarty.housamoembedtrans.R;

/**
 * Small in-app menu surface shared by management, settings, and batch pages.
 *
 * The AndroidX PopupMenu remains the menu owner so item listeners, enabled
 * state, checked state, and submenu dispatch continue to use the platform
 * contract.  Only the visible surface is rendered in the task-page card
 * style.
 */
public final class StyledPopupMenu {
    private static final int MENU_WIDTH_DP = 224;
    private static final int MIN_CONTENT_WIDTH_DP = 128;
    private static final int ITEM_HEIGHT_DP = 40;
    private static final int MENU_CONTENT_PADDING_DP = 8;
    private static final int MENU_SCROLLBAR_WIDTH_DP = 14;
    private static final int ITEM_HORIZONTAL_PADDING_DP = 9;
    private static final int ITEM_ICON_WIDTH_DP = 20;
    private static final int ITEM_ICON_GAP_DP = 8;
    private static final int ITEM_TRAILING_SLOT_DP = 20;

    private final Context context;
    private final View anchor;
    private final int gravity;
    private final PopupMenu menuOwner;
    private CharSequence title;
    private boolean wrapContentWidth;
    private PopupWindow popupWindow;

    public StyledPopupMenu(Context context, View anchor) {
        this(context, anchor, Gravity.END);
    }

    public StyledPopupMenu(Context context, View anchor, int gravity) {
        this.context = context;
        this.anchor = anchor;
        this.gravity = gravity;
        this.menuOwner = new PopupMenu(context, anchor, gravity);
    }

    public Menu getMenu() {
        return menuOwner.getMenu();
    }

    public android.view.MenuInflater getMenuInflater() {
        return menuOwner.getMenuInflater();
    }

    public StyledPopupMenu setTitle(@Nullable CharSequence title) {
        this.title = title;
        return this;
    }

    /** Uses the widest visible item as the menu width instead of the default 224dp. */
    public StyledPopupMenu setWrapContentWidth() {
        wrapContentWidth = true;
        return this;
    }

    public StyledPopupMenu setOnMenuItemClickListener(
        PopupMenu.OnMenuItemClickListener listener
    ) {
        menuOwner.setOnMenuItemClickListener(listener);
        return this;
    }

    public void show() {
        showMenu(menuOwner.getMenu(), title, anchor, gravity);
    }

    public void dismiss() {
        if (popupWindow != null) {
            popupWindow.dismiss();
            popupWindow = null;
        }
    }

    private void showMenu(
        Menu menu,
        @Nullable CharSequence heading,
        View menuAnchor,
        int menuGravity
    ) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            dp(MENU_CONTENT_PADDING_DP),
            dp(MENU_CONTENT_PADDING_DP),
            dp(MENU_CONTENT_PADDING_DP),
            dp(MENU_CONTENT_PADDING_DP)
        );
        GradientDrawable surface = roundedSurface(
            ContextCompat.getColor(context, R.color.het_surface_container_high),
            dp(14),
            ContextCompat.getColor(context, R.color.het_outline_soft)
        );
        if (heading != null && heading.length() > 0) {
            TextView headingView = new TextView(context);
            headingView.setText(heading);
            headingView.setTextColor(ContextCompat.getColor(
                context,
                R.color.het_on_surface_muted
            ));
            headingView.setTextSize(12);
            headingView.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
            headingView.setIncludeFontPadding(false);
            headingView.setGravity(Gravity.CENTER_VERTICAL);
            headingView.setPadding(dp(9), dp(3), dp(9), dp(6));
            content.addView(headingView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24)
            ));
        }

        for (int index = 0; index < menu.size(); index++) {
            MenuItem item = menu.getItem(index);
            if (!item.isVisible()) {
                continue;
            }
            View row = createItemView(item, menu, menuAnchor, menuGravity);
            content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        DraggableScrollbarNestedScrollView scroll =
            new DraggableScrollbarNestedScrollView(context);
        scroll.setFillViewport(true);
        scroll.setPaddingRelative(
            0,
            0,
            wrapContentWidth ? 0 : dp(MENU_SCROLLBAR_WIDTH_DP),
            0
        );
        scroll.setBackground(surface);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            scroll.setClipToOutline(true);
        }
        scroll.addView(content, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        int width = wrapContentWidth
            ? contentWidth(menu, heading)
            : dp(MENU_WIDTH_DP);
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int maxHeight = context.getResources().getDisplayMetrics().heightPixels
            - dp(48);
        int popupHeight = Math.min(scroll.getMeasuredHeight(), maxHeight);
        PopupWindow window = new PopupWindow(
            scroll,
            width,
            popupHeight,
            true
        );
        if (popupWindow != null) {
            popupWindow.dismiss();
        }
        popupWindow = window;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
            Color.TRANSPARENT
        ));
        window.setOutsideTouchable(true);
        window.setElevation(dp(8));
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setOnDismissListener(() -> {
            if (popupWindow == window) {
                popupWindow = null;
            }
        });
        window.showAsDropDown(menuAnchor, 0, dp(4), menuGravity);
    }

    private View createItemView(
        MenuItem item,
        Menu owner,
        View menuAnchor,
        int menuGravity
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(4), dp(9), dp(4));
        row.setMinimumHeight(dp(ITEM_HEIGHT_DP));
        row.setFocusable(item.isEnabled());
        row.setClickable(item.isEnabled());
        row.setEnabled(item.isEnabled());
        row.setContentDescription(item.getTitle());

        Drawable icon = item.getIcon();
        if (icon != null) {
            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(icon);
            iconView.setAlpha(item.isEnabled() ? 1f : 0.45f);
            row.addView(iconView, new LinearLayout.LayoutParams(
                dp(20),
                dp(20)
            ));
            ViewGroup.LayoutParams iconParams = iconView.getLayoutParams();
            if (iconParams instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) iconParams).rightMargin = dp(8);
            }
        }

        TextView label = new TextView(context);
        label.setText(item.getTitle());
        label.setTextColor(ContextCompat.getColor(
            context,
            R.color.het_on_surface
        ));
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setAlpha(item.isEnabled() ? 1f : 0.45f);
        row.addView(label, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));

        if (item.hasSubMenu()) {
            TextView arrow = new TextView(context);
            arrow.setText("›");
            arrow.setTextColor(ContextCompat.getColor(
                context,
                R.color.het_on_surface_muted
            ));
            arrow.setTextSize(18);
            arrow.setGravity(Gravity.CENTER);
            arrow.setIncludeFontPadding(false);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(24)));
        } else if (item.isCheckable()) {
            TextView check = new TextView(context);
            check.setText("✓");
            check.setTextColor(ContextCompat.getColor(
                context,
                R.color.het_primary_strong
            ));
            check.setTextSize(13);
            check.setGravity(Gravity.CENTER);
            check.setIncludeFontPadding(false);
            check.setVisibility(item.isChecked() ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(dp(20), dp(24)));
        }

        row.setBackground(rowBackground(item.isChecked()));
        if (item.isEnabled()) {
            row.setOnClickListener(view -> {
                if (item.hasSubMenu()) {
                    showMenu(item.getSubMenu(), item.getTitle(), menuAnchor, menuGravity);
                    return;
                }
                if (popupWindow != null) {
                    popupWindow.dismiss();
                }
                owner.performIdentifierAction(
                    item.getItemId(),
                    0
                );
            });
        }
        return row;
    }

    private int contentWidth(Menu menu, @Nullable CharSequence heading) {
        int widestRow = 0;
        for (int index = 0; index < menu.size(); index++) {
            MenuItem item = menu.getItem(index);
            if (!item.isVisible()) {
                continue;
            }
            int rowWidth = dp(ITEM_HORIZONTAL_PADDING_DP * 2)
                + textWidth(item.getTitle(), 14);
            if (item.getIcon() != null) {
                rowWidth += dp(ITEM_ICON_WIDTH_DP + ITEM_ICON_GAP_DP);
            }
            if (item.hasSubMenu() || item.isCheckable()) {
                rowWidth += dp(ITEM_TRAILING_SLOT_DP);
            }
            widestRow = Math.max(widestRow, rowWidth);
        }
        if (heading != null && heading.length() > 0) {
            widestRow = Math.max(widestRow, dp(18) + textWidth(heading, 12));
        }
        int desiredWidth = widestRow
            + dp(MENU_CONTENT_PADDING_DP * 2);
        int maxWidth = visibleWindowWidth();
        return Math.min(
            maxWidth,
            Math.max(dp(MIN_CONTENT_WIDTH_DP), desiredWidth)
        );
    }

    private int textWidth(@Nullable CharSequence text, float textSizeSp) {
        TextView measureView = new TextView(context);
        measureView.setTextSize(textSizeSp);
        measureView.setText(text == null ? "" : text);
        return (int) Math.ceil(measureView.getPaint().measureText(
            text == null ? "" : text.toString()
        ));
    }

    private int visibleWindowWidth() {
        android.graphics.Rect visibleFrame = new android.graphics.Rect();
        anchor.getWindowVisibleDisplayFrame(visibleFrame);
        int width = visibleFrame.width();
        if (width <= 0) {
            width = context.getResources().getDisplayMetrics().widthPixels;
        }
        return Math.max(1, width);
    }

    private Drawable rowBackground(boolean selected) {
        return roundedSurface(
            selected
                ? ContextCompat.getColor(context, R.color.het_primary_container)
                : Color.TRANSPARENT,
            dp(8),
            Color.TRANSPARENT
        );
    }

    private GradientDrawable roundedSurface(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

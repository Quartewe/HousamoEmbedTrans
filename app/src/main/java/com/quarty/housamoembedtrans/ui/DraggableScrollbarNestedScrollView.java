package com.quarty.housamoembedtrans.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;

import com.quarty.housamoembedtrans.R;

/**
 * A small, theme-colored scrollbar that keeps NestedScrollView's normal input
 * behavior while allowing the visible thumb to be dragged directly.
 */
public class DraggableScrollbarNestedScrollView extends NestedScrollView {
    private static final int TRACK_ALPHA = 72;
    private static final int THUMB_ALPHA = 220;
    private static final int MIN_THUMB_DP = 24;
    private static final int TRACK_WIDTH_DP = 2;
    private static final int THUMB_WIDTH_DP = 4;
    private static final int EDGE_INSET_DP = 4;
    /** Matches the 14dp end gutter reserved by the shared scroll layouts. */
    private static final int HIT_WIDTH_DP = 14;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final float density;
    private final int trackWidth;
    private final int thumbWidth;
    private final int edgeInset;
    private final int hitWidth;
    private final int minThumbHeight;
    private boolean draggingScrollbar;
    private float dragOffsetY;

    public DraggableScrollbarNestedScrollView(Context context) {
        this(context, null);
    }

    public DraggableScrollbarNestedScrollView(
        Context context,
        AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public DraggableScrollbarNestedScrollView(
        Context context,
        AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        trackWidth = dp(TRACK_WIDTH_DP);
        thumbWidth = dp(THUMB_WIDTH_DP);
        edgeInset = dp(EDGE_INSET_DP);
        hitWidth = dp(HIT_WIDTH_DP);
        minThumbHeight = dp(MIN_THUMB_DP);
        trackPaint.setColor(resolveThemeColor(
            com.google.android.material.R.attr.colorOnSurface,
            R.color.het_outline_soft
        ));
        trackPaint.setAlpha(TRACK_ALPHA);
        thumbPaint.setColor(resolveThemeColor(
            com.google.android.material.R.attr.colorPrimary,
            R.color.het_primary_strong
        ));
        thumbPaint.setAlpha(THUMB_ALPHA);
        setVerticalScrollBarEnabled(false);
        setWillNotDraw(false);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int saveCount = canvas.save();
        canvas.translate(getScrollX(), getScrollY());
        drawScrollbar(canvas);
        canvas.restoreToCount(saveCount);
    }

    @Override
    protected void onScrollChanged(
        int scrollX,
        int scrollY,
        int oldScrollX,
        int oldScrollY
    ) {
        super.onScrollChanged(scrollX, scrollY, oldScrollX, oldScrollY);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        invalidate();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingScrollbar = isScrollable()
                    && isInScrollbarHitArea(event.getX(), event.getY());
                if (draggingScrollbar) {
                    stopOngoingScroll();
                    dragOffsetY = thumbOffsetForTouch(event.getY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingScrollbar) {
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null || !draggingScrollbar) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestParentDisallowIntercept(true);
                scrollToTouch(event.getY(), dragOffsetY);
                return true;
            case MotionEvent.ACTION_MOVE:
                scrollToTouch(event.getY(), dragOffsetY);
                return true;
            case MotionEvent.ACTION_UP:
                scrollToTouch(event.getY(), dragOffsetY);
                draggingScrollbar = false;
                requestParentDisallowIntercept(false);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingScrollbar = false;
                requestParentDisallowIntercept(false);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void drawScrollbar(Canvas canvas) {
        int range = getScrollRange();
        int viewportHeight = getViewportHeight();
        if (range <= 0 || viewportHeight <= 0) {
            return;
        }
        float trackTop = getPaddingTop();
        float trackBottom = getHeight() - getPaddingBottom();
        float trackHeight = trackBottom - trackTop;
        if (trackHeight <= 0f) {
            return;
        }
        float contentHeight = viewportHeight + range;
        float thumbHeight = Math.min(
            trackHeight,
            Math.max(minThumbHeight, trackHeight * viewportHeight / contentHeight)
        );
        float travel = trackHeight - thumbHeight;
        float fraction = range == 0
            ? 0f
            : clamp((float) getScrollY() / range, 0f, 1f);
        float thumbTop = trackTop + travel * fraction;
        float trackLeft = scrollbarLeft(trackWidth);
        float thumbLeft = scrollbarLeft(thumbWidth);
        trackRect.set(
            trackLeft,
            trackTop,
            trackLeft + trackWidth,
            trackBottom
        );
        thumbRect.set(
            thumbLeft,
            thumbTop,
            thumbLeft + thumbWidth,
            thumbTop + thumbHeight
        );
        float trackRadius = trackWidth / 2f;
        float thumbRadius = thumbWidth / 2f;
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint);
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint);
    }

    private void scrollToTouch(float y, float offsetY) {
        int range = getScrollRange();
        int viewportHeight = getViewportHeight();
        float trackTop = getPaddingTop();
        float trackBottom = getHeight() - getPaddingBottom();
        float trackHeight = trackBottom - trackTop;
        if (range <= 0 || viewportHeight <= 0 || trackHeight <= 0f) {
            return;
        }
        float contentHeight = viewportHeight + range;
        float thumbHeight = Math.min(
            trackHeight,
            Math.max(minThumbHeight, trackHeight * viewportHeight / contentHeight)
        );
        float travel = trackHeight - thumbHeight;
        float thumbTop = clamp(
            y - trackTop - offsetY,
            0f,
            travel
        );
        float fraction = travel <= 0f ? 0f : thumbTop / travel;
        scrollTo(getScrollX(), Math.round(fraction * range));
    }

    private boolean isInScrollbarHitArea(float x, float y) {
        float top = getPaddingTop();
        float bottom = getHeight() - getPaddingBottom();
        if (y < top || y > bottom) {
            return false;
        }
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            return x >= 0f && x <= hitWidth;
        }
        return x >= getWidth() - hitWidth && x <= getWidth();
    }

    private float scrollbarLeft(int width) {
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            return edgeInset - (width - trackWidth) / 2f;
        }
        return getWidth()
            - edgeInset
            - trackWidth
            - (width - trackWidth) / 2f;
    }

    private boolean isScrollable() {
        return getScrollRange() > 0;
    }

    private float thumbOffsetForTouch(float y) {
        int range = getScrollRange();
        int viewportHeight = getViewportHeight();
        float trackTop = getPaddingTop();
        float trackBottom = getHeight() - getPaddingBottom();
        float trackHeight = trackBottom - trackTop;
        if (range <= 0 || viewportHeight <= 0 || trackHeight <= 0f) {
            return 0f;
        }
        float contentHeight = viewportHeight + range;
        float thumbHeight = Math.min(
            trackHeight,
            Math.max(minThumbHeight, trackHeight * viewportHeight / contentHeight)
        );
        float travel = trackHeight - thumbHeight;
        float fraction = range == 0
            ? 0f
            : clamp((float) getScrollY() / range, 0f, 1f);
        float thumbTop = trackTop + travel * fraction;
        if (y >= thumbTop && y <= thumbTop + thumbHeight) {
            return y - thumbTop;
        }
        return thumbHeight / 2f;
    }

    private int getViewportHeight() {
        return Math.max(
            0,
            getHeight() - getPaddingTop() - getPaddingBottom()
        );
    }

    private int getScrollRange() {
        if (getChildCount() == 0) {
            return 0;
        }
        View child = getChildAt(0);
        int contentHeight = child.getHeight();
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins =
                (ViewGroup.MarginLayoutParams) params;
            contentHeight += margins.topMargin + margins.bottomMargin;
        }
        return Math.max(0, contentHeight - getViewportHeight());
    }

    private void stopOngoingScroll() {
        fling(0);
        stopNestedScroll(ViewCompat.TYPE_NON_TOUCH);
        stopNestedScroll(ViewCompat.TYPE_TOUCH);
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private int resolveThemeColor(int attr, int fallbackResId) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                return ContextCompat.getColor(getContext(), value.resourceId);
            }
            return value.data;
        }
        return ContextCompat.getColor(getContext(), fallbackResId);
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value * density));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

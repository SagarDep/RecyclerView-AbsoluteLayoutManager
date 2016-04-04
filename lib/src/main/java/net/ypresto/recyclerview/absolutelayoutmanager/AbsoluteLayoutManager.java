/*
 * Copyright (C) 2015 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.recyclerview.absolutelayoutmanager;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

// TODO: predictive item animations
public class AbsoluteLayoutManager extends RecyclerView.LayoutManager {
    private static final String TAG = "AbsoluteLayoutManager";
    private static final float MINIMUM_FILL_SCALE_FACTOR = 0.0f;
    private static final float MAXIMUM_FILL_SCALE_FACTOR = 0.33f; // MAX_SCROLL_FACTOR of LinearLayoutManager
    private static final int NO_POSITION = RecyclerView.NO_POSITION;
    // NOTE: Point as Parcelable is only on API >= 13.
    private static final String STATE_SCROLL_OFFSET_X = "scrollOffsetX";
    private static final String STATE_SCROLL_OFFSET_Y = "scrollOffsetY";
    private static boolean DEBUG = false;

    private final LayoutProvider mLayoutProvider;
    private boolean mIsLayoutProviderDirty;
    private final Point mCurrentScrollOffset = new Point(0, 0);
    // NOTE: Size class is only on API >= 22.
    private int mScrollContentWidth = 0;
    private int mScrollContentHeight = 0;
    private Rect mFilledRect = new Rect();
    private int mPendingScrollPosition = NO_POSITION;

    public AbsoluteLayoutManager(LayoutProvider layoutProvider) {
        mLayoutProvider = layoutProvider;
        mIsLayoutProviderDirty = true;
    }

    private static Rect createRect(int x, int y, int width, int height) {
        return new Rect(x, y, x + width, y + height);
    }

    private static boolean checkIfRectsIntersect(Rect rect1, Rect rect2) {
        // other intersect family methods are destructive...
        return rect1.intersects(rect2.left, rect2.top, rect2.right, rect2.bottom);
    }

    /**
     * Get visible rect of layout provider coordinate. Returned rect contains padding area.
     */
    private Rect getVisibleRect() {
        return createRect(mCurrentScrollOffset.x - getPaddingLeft(), mCurrentScrollOffset.y - getPaddingTop(), getWidth(), getHeight());
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int actualDx;
        if (dx > 0) {
            int remainingX = getScrollableWidth() - getWidth() - mCurrentScrollOffset.x;
            actualDx = Math.min(dx, remainingX);
        } else {
            actualDx = -Math.min(-dx, mCurrentScrollOffset.x);
        }
        mCurrentScrollOffset.offset(actualDx, 0);
        offsetChildrenHorizontal(-actualDx);
        fillRect(getVisibleRect(), dx < 0 ? Direction.LEFT : Direction.RIGHT, recycler);
        return actualDx;
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int actualDy;
        if (dy > 0) {
            int remainingY = getScrollableHeight() - getHeight() - mCurrentScrollOffset.y;
            actualDy = Math.min(dy, remainingY);
        } else {
            actualDy = -Math.min(-dy, mCurrentScrollOffset.y);
        }
        mCurrentScrollOffset.offset(0, actualDy);
        offsetChildrenVertical(-actualDy);
        fillRect(getVisibleRect(), dy < 0 ? Direction.TOP : Direction.BOTTOM, recycler);
        return actualDy;
    }

    /**
     * Fills child views around current scroll rect.
     *
     * @param visibleRect     Current visible rect by {@link #getVisibleRect()}.
     * @param scrollDirection Fills views by extending current filled rect to specified direction if possible.
     *                        Fills whole rect if {@code null} is passed or rect could not be extended.
     * @param recycler        Recycler for creating and recycling views.
     */
    private void fillRect(Rect visibleRect, Direction scrollDirection, RecyclerView.Recycler recycler) {
        Rect minimumRectToFill = getExtendedRectWithScaleFactor(visibleRect, MINIMUM_FILL_SCALE_FACTOR);
        if (mFilledRect.contains(minimumRectToFill)) {
            return;
        }

        Rect maximumRectToFill = getExtendedRectWithScaleFactor(visibleRect, MAXIMUM_FILL_SCALE_FACTOR);
        Rect rectToFill = maximumRectToFill;
        Rect newFilledRect = rectToFill;
        boolean isIncrementalFill = false;
        if (scrollDirection != null) {
            // shortcut by extending rect
            Rect incrementalFillRect = calculateExtendRectToDirection(mFilledRect, maximumRectToFill, scrollDirection);
            Rect incrementallyFilledRect = new Rect(mFilledRect);
            incrementallyFilledRect.union(incrementalFillRect);
            if (incrementallyFilledRect.contains(minimumRectToFill)) {
                rectToFill = incrementalFillRect;
                isIncrementalFill = true;
                // shrink rect to maximum
                boolean isIntersected = incrementallyFilledRect.intersect(maximumRectToFill);
                if (!isIntersected) {
                    throw new IllegalStateException("Unexpected non-intersect rect while calculating filled rect.");
                }
                newFilledRect = incrementallyFilledRect;
                if (DEBUG) {
                    Log.v(TAG, "Incrementally filling rect: " + newFilledRect);
                }
            }
        }
        if (isIncrementalFill) {
            removeChildViewsOutsideOfScrollRect(newFilledRect, recycler); // recycle first
            fillChildViewsInRect(rectToFill, mFilledRect, recycler); // fill views only not previously placed
        } else {
            detachAndScrapAttachedViews(recycler); // detach all views and fill entire rect
            fillChildViewsInRect(rectToFill, null, recycler);
        }
        mFilledRect = newFilledRect;
    }

    private Rect calculateExtendRectToDirection(Rect currentRect, Rect maximumRect, Direction direction) {
        boolean isIntersected = new Rect().setIntersect(currentRect, maximumRect);
        if (!isIntersected) return new Rect(0, 0, 0, 0);
        Rect extendedRect = new Rect(currentRect);
        switch (direction) {
            case LEFT:
                extendedRect.left = maximumRect.left;
                extendedRect.right = currentRect.left - 1;
                break;
            case TOP:
                extendedRect.top = maximumRect.top;
                extendedRect.bottom = currentRect.top - 1;
                break;
            case RIGHT:
                extendedRect.right = maximumRect.right;
                extendedRect.left = currentRect.right + 1;
                break;
            case BOTTOM:
                extendedRect.bottom = maximumRect.bottom;
                extendedRect.top = currentRect.bottom + 1;
                break;
            default:
                throw new AssertionError();
        }
        return extendedRect;
    }

    private Rect getExtendedRectWithScaleFactor(Rect rect, float scaleFactor) {
        int deltaWidth = Math.round(rect.width() * scaleFactor);
        int deltaHeight = Math.round(rect.height() * scaleFactor);
        int x = (int) Math.round(rect.left - deltaWidth / 2.0);
        int y = (int) Math.round(rect.top - deltaHeight / 2.0);
        return createRect(x, y, rect.width() + deltaWidth, rect.height() + deltaHeight);
    }

    private void fillChildViewsInRect(Rect rectToFill, Rect rectToExclude, RecyclerView.Recycler recycler) {
        if (DEBUG) {
            Log.v(TAG, "filling for rect: " + rectToFill);
        }
        List<LayoutProvider.LayoutAttribute> layoutAttributes = mLayoutProvider.getLayoutAttributesInRect(rectToFill);
        for (LayoutProvider.LayoutAttribute layoutAttribute : layoutAttributes) {
            if (rectToExclude != null && layoutAttribute.isIntersectWithRect(rectToExclude)) {
                continue;
            }
            View childView = recycler.getViewForPosition(layoutAttribute.mPosition);
            addView(childView);
            Rect rect = layoutAttribute.copyRect();
            offsetLayoutAttributeRectToChildViewRect(rect);
            // TODO: decoration margins
            childView.measure(View.MeasureSpec.makeMeasureSpec(rect.width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(rect.height(), View.MeasureSpec.EXACTLY));
            layoutDecorated(childView, rect.left, rect.top, rect.right, rect.bottom);
        }
    }

    private void updateRectWithView(Rect rect, View view) {
        rect.left = view.getLeft();
        rect.top = view.getTop();
        rect.right = view.getRight();
        rect.bottom = view.getBottom();
    }

    private void removeChildViewsOutsideOfScrollRect(Rect scrollRect, RecyclerView.Recycler recycler) {
        Rect retainChildViewRect = new Rect(scrollRect);
        offsetLayoutAttributeRectToChildViewRect(retainChildViewRect);

        int childCount = getChildCount();
        Rect viewRect = new Rect();
        int removed = 0;
        for (int i = 0; i < childCount; i++) {
            View childView = getChildAt(i - removed);
            updateRectWithView(viewRect, childView);
            if (!checkIfRectsIntersect(retainChildViewRect, viewRect)) {
                removeAndRecycleView(childView, recycler);
                removed++;
            }
        }
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        prepareLayoutProvider(state);
        if (mPendingScrollPosition != NO_POSITION) {
            Point scrollOffset = calculateScrollOffsetToShowPositionIfPossible(mPendingScrollPosition);
            if (scrollOffset != null) {
                mCurrentScrollOffset.set(scrollOffset.x, scrollOffset.y);
            }
            mPendingScrollPosition = NO_POSITION;
        }
        normalizeCurrentScrollOffset();
        detachAndScrapAttachedViews(recycler);
        mFilledRect.setEmpty();
        fillRect(getVisibleRect(), null, recycler);
    }

    /**
     * Limit scroll offset to possible value according to current layout.
     */
    private void normalizeCurrentScrollOffset() {
        int x = Math.max(0, Math.min(getScrollableWidth() - getWidth(), mCurrentScrollOffset.x));
        int y = Math.max(0, Math.min(getScrollableHeight() - getHeight(), mCurrentScrollOffset.y));
        mCurrentScrollOffset.set(x, y);
        if (DEBUG) {
            Log.v(TAG, "normalized scroll offset: " + mCurrentScrollOffset);
        }
    }

    private int getScrollableHeight() {
        return mScrollContentHeight + getPaddingTop() + getPaddingBottom();
    }

    private int getScrollableWidth() {
        return mScrollContentWidth + getPaddingLeft() + getPaddingRight();
    }

    /**
     * Convert coordinate of rect from layout provider to child view position.
     */
    private void offsetLayoutAttributeRectToChildViewRect(Rect rect) {
        rect.offset(-mCurrentScrollOffset.x + getPaddingLeft(), -mCurrentScrollOffset.y + getPaddingTop());
    }

    private Point calculateScrollOffsetToShowPositionIfPossible(int position) {
        if (position >= getItemCount()) return null;
        LayoutProvider.LayoutAttribute layoutAttribute = mLayoutProvider.getLayoutAttributeForItemAtPosition(position);
        Rect currentLayoutSpaceRect = createRect(mCurrentScrollOffset.x, mCurrentScrollOffset.y, getLayoutSpaceWidth(), getLayoutSpaceHeight());
        return calculateScrollOffsetToShowItem(layoutAttribute, currentLayoutSpaceRect);
    }

    private Point calculateScrollOffsetToShowItem(LayoutProvider.LayoutAttribute layoutAttribute, Rect fromRect) {
        Rect itemRect = layoutAttribute.copyRect();
        Point layoutAttributesScrollOffset = new Point(fromRect.left, fromRect.top); // defaults to current position
        if (itemRect.left < fromRect.left) {
            layoutAttributesScrollOffset.x = itemRect.left;
        } else if (itemRect.right > fromRect.right) {
            layoutAttributesScrollOffset.x = itemRect.right - fromRect.width();
        }
        if (itemRect.top < fromRect.top) {
            layoutAttributesScrollOffset.y = itemRect.top;
        } else if (itemRect.bottom > fromRect.bottom) {
            layoutAttributesScrollOffset.y = itemRect.bottom - fromRect.height();
        }

        return layoutAttributesScrollOffset;
    }

    private int getLayoutSpaceWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    private int getLayoutSpaceHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    public LayoutProvider getLayoutProvider() {
        return mLayoutProvider;
    }

    /**
     * Explicitly requests to call {@link LayoutProvider#prepareLayout()} on next layout cycle.
     * Note that any changes to adapter implicitly requests {@code prepareLayout()} call.
     */
    public void invalidateLayout() {
        mIsLayoutProviderDirty = true;
        requestLayout();
    }

    private void prepareLayoutProvider(RecyclerView.State state) {
        if (state.didStructureChange()) {
            mIsLayoutProviderDirty = true;
        }
        if (mLayoutProvider.mLayoutManagerState.mLayoutSpaceWidth != getLayoutSpaceWidth() ||
                mLayoutProvider.mLayoutManagerState.mLayoutSpaceHeight != getLayoutSpaceHeight()) {
            mIsLayoutProviderDirty = true;
        }

        if (!mIsLayoutProviderDirty) return;
        mFilledRect = new Rect(); // invalidate cache
        mLayoutProvider.mLayoutManagerState = new LayoutProvider.LayoutManagerState(
                getLayoutSpaceWidth(),
                getLayoutSpaceHeight(),
                getItemCount());
        mLayoutProvider.prepareLayout();
        mScrollContentWidth = mLayoutProvider.getScrollContentWidth();
        mScrollContentHeight = mLayoutProvider.getScrollContentHeight();
        mIsLayoutProviderDirty = false;
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        // It is happen when setLayoutManager() is called.
        mIsLayoutProviderDirty = true;
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        // It is not structure change, but we want to recalculate layout.
        mIsLayoutProviderDirty = true;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        Point scrollOffset = mCurrentScrollOffset;
        if (mPendingScrollPosition != NO_POSITION) {
            // TODO: Scroll offset for the position may be changed after restoration.
            Point pendingScrollOffset = calculateScrollOffsetToShowPositionIfPossible(mPendingScrollPosition);
            if (pendingScrollOffset != null) {
                scrollOffset = pendingScrollOffset;
            }
        }
        state.putInt(STATE_SCROLL_OFFSET_X, scrollOffset.x);
        state.putInt(STATE_SCROLL_OFFSET_Y, scrollOffset.y);
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof Bundle)) {
            Log.e(TAG, "Invalid state object is passed for onRestoreInstanceState: " + state.getClass().getName());
            return;
        }
        Bundle bundle = (Bundle) state;
        if (!(bundle.containsKey(STATE_SCROLL_OFFSET_X) && bundle.containsKey(STATE_SCROLL_OFFSET_Y))) {
            Log.e(TAG, "Invalid state object is passed, keys " + STATE_SCROLL_OFFSET_X + " and " + STATE_SCROLL_OFFSET_Y + " is required.");
            return;
        }
        int scrollOffsetX = bundle.getInt(STATE_SCROLL_OFFSET_X);
        int scrollOffsetY = bundle.getInt(STATE_SCROLL_OFFSET_Y);
        mCurrentScrollOffset.set(scrollOffsetX, scrollOffsetY);
        requestLayout();
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    public boolean canScrollHorizontally() {
        return mScrollContentWidth > getLayoutSpaceWidth();
    }

    @Override
    public boolean canScrollVertically() {
        return mScrollContentHeight > getLayoutSpaceHeight();
    }

    @Override
    public int computeHorizontalScrollOffset(RecyclerView.State state) {
        return mCurrentScrollOffset.x;
    }

    @Override
    public int computeVerticalScrollOffset(RecyclerView.State state) {
        return mCurrentScrollOffset.y;
    }

    @Override
    public int computeHorizontalScrollExtent(RecyclerView.State state) {
        return getWidth();
    }

    @Override
    public int computeVerticalScrollExtent(RecyclerView.State state) {
        return getHeight();
    }

    @Override
    public int computeHorizontalScrollRange(RecyclerView.State state) {
        return getScrollableWidth();
    }

    @Override
    public int computeVerticalScrollRange(RecyclerView.State state) {
        return getScrollableHeight();
    }

    @Override
    public void scrollToPosition(int position) {
        mPendingScrollPosition = position;
        requestLayout();
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state,
                                       int position) {
        prepareLayoutProvider(state);
        LinearSmoothScroller linearSmoothScroller =
                new LinearSmoothScroller(recyclerView.getContext()) {
                    @Override
                    public PointF computeScrollVectorForPosition(int targetPosition) {
                        Point targetScrollOffset = calculateScrollOffsetToShowPositionIfPossible(targetPosition);
                        if (targetScrollOffset == null) return null;
                        return calculateUnitVectorFromPoints(mCurrentScrollOffset, targetScrollOffset);
                    }
                };
        linearSmoothScroller.setTargetPosition(position);
        startSmoothScroll(linearSmoothScroller);
    }

    private PointF calculateUnitVectorFromPoints(Point currentScrollOffset, Point targetScrollOffset) {
        PointF vector = new PointF();
        vector.x = targetScrollOffset.x - currentScrollOffset.x;
        vector.y = targetScrollOffset.y - currentScrollOffset.y;

        //noinspection SuspiciousNameCombination
        double norm = Math.sqrt(Math.pow(vector.x, 2) + Math.pow(vector.y, 2));
        if (norm == 0.0) return vector;
        vector.x /= norm;
        vector.y /= norm;
        return vector;
    }

    private enum Direction {
        LEFT, TOP, RIGHT, BOTTOM
    }

    public abstract static class LayoutProvider {
        private LayoutManagerState mLayoutManagerState = new LayoutManagerState();

        /**
         * @deprecated Use {@link #getState()}.
         */
        @Deprecated
        public int getLayoutSpaceWidth() {
            return mLayoutManagerState.getLayoutSpaceWidth();
        }

        /**
         * @deprecated Use {@link #getState()}.
         */
        @Deprecated
        public int getLayoutSpaceHeight() {
            return mLayoutManagerState.getLayoutSpaceHeight();
        }

        /**
         * @deprecated Use {@link #getState()}.
         */
        @Deprecated
        public int getItemCount() {
            return mLayoutManagerState.getItemCount();
        }

        /**
         * Returns current state of layout manager, including size of layout space and item count.
         */
        public final LayoutManagerState getState() {
            return mLayoutManagerState;
        }

        public abstract void prepareLayout();

        public abstract int getScrollContentWidth();

        public abstract int getScrollContentHeight();

        public abstract List<LayoutAttribute> getLayoutAttributesInRect(Rect rect);

        public abstract LayoutAttribute getLayoutAttributeForItemAtPosition(int position);

        public static class LayoutManagerState {
            private final int mLayoutSpaceWidth;
            private final int mLayoutSpaceHeight;
            private final int mItemCount;

            private LayoutManagerState(int layoutSpaceWidth, int layoutSpaceHeight, int itemCount) {
                mLayoutSpaceWidth = layoutSpaceWidth;
                mLayoutSpaceHeight = layoutSpaceHeight;
                mItemCount = itemCount;
            }

            private LayoutManagerState() {
                mLayoutSpaceWidth = 0;
                mLayoutSpaceHeight = 0;
                mItemCount = 0;
            }

            public int getLayoutSpaceWidth() {
                return mLayoutSpaceWidth;
            }

            public int getLayoutSpaceHeight() {
                return mLayoutSpaceHeight;
            }

            public int getItemCount() {
                return mItemCount;
            }
        }

        public static class LayoutAttribute {
            private final int mPosition;
            private final Rect mRect;

            /**
             * Create layout attributes for item at position.
             *
             * @param position Adapter position of item.
             * @param rect     Absolute rect of item. Will be copied to keep from mutation.
             */
            public LayoutAttribute(int position, Rect rect) {
                mPosition = position;
                mRect = new Rect(rect);
            }

            /**
             * Get position of item.
             *
             * @return Adapter position.
             */
            public int getPosition() {
                return mPosition;
            }

            /**
             * Copy absolute rect of item.
             * There is no getter because {@link Rect} is mutable type.
             *
             * @return Absolute rect of item.
             */
            public Rect copyRect() {
                return new Rect(mRect);
            }

            public int getWidth() {
                return mRect.width();
            }

            public int getHeight() {
                return mRect.height();
            }

            public int getLeft() {
                return mRect.left;
            }

            public int getTop() {
                return mRect.top;
            }

            public int getRight() {
                return mRect.right;
            }

            public int getBottom() {
                return mRect.bottom;
            }

            public boolean isIntersectWithRect(Rect rect) {
                return AbsoluteLayoutManager.checkIfRectsIntersect(mRect, rect);
            }
        }
    }
}

/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.widget.picker;

import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_Y;
import static com.android.launcher3.testing.TestProtocol.NORMAL_STATE_ORDINAL;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.os.Process;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Insettable;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.R;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.views.RecyclerViewFastScroller;
import com.android.launcher3.views.TopRoundedCornerView;
import com.android.launcher3.widget.BaseWidgetSheet;
import com.android.launcher3.widget.LauncherAppWidgetHost.ProviderChangedListener;
import com.android.launcher3.widget.model.WidgetsListBaseEntry;
import com.android.launcher3.widget.picker.search.SearchModeListener;
import com.android.launcher3.widget.picker.search.WidgetsSearchBar;
import com.android.launcher3.workprofile.PersonalWorkPagedView;
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip.OnActivePageChangedListener;

import java.util.List;
import java.util.function.Predicate;

/**
 * Popup for showing the full list of available widgets
 */
public class WidgetsFullSheet extends BaseWidgetSheet
        implements Insettable, ProviderChangedListener, OnActivePageChangedListener,
        WidgetsRecyclerView.HeaderViewDimensionsProvider, SearchModeListener {

    private static final long DEFAULT_OPEN_DURATION = 267;
    private static final long FADE_IN_DURATION = 150;
    private static final float VERTICAL_START_POSITION = 0.3f;

    private final Rect mInsets = new Rect();
    private final boolean mHasWorkProfile;
    private final SparseArray<AdapterHolder> mAdapters = new SparseArray();
    private final UserHandle mCurrentUser = Process.myUserHandle();
    private final Predicate<WidgetsListBaseEntry> mPrimaryWidgetsFilter = entry ->
            mCurrentUser.equals(entry.mPkgItem.user);
    private final Predicate<WidgetsListBaseEntry> mWorkWidgetsFilter =
            mPrimaryWidgetsFilter.negate();

    @Nullable private PersonalWorkPagedView mViewPager;
    private int mInitialTabsHeight = 0;
    private boolean mIsInSearchMode;
    private View mTabsView;
    private TextView mNoWidgetsView;
    private SearchAndRecommendationViewHolder mSearchAndRecommendationViewHolder;
    private SearchAndRecommendationsScrollController mSearchAndRecommendationsScrollController;

    public WidgetsFullSheet(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mHasWorkProfile = context.getSystemService(LauncherApps.class).getProfiles().size() > 1;
        mAdapters.put(AdapterHolder.PRIMARY, new AdapterHolder(AdapterHolder.PRIMARY));
        mAdapters.put(AdapterHolder.WORK, new AdapterHolder(AdapterHolder.WORK));
        mAdapters.put(AdapterHolder.SEARCH, new AdapterHolder(AdapterHolder.SEARCH));
    }

    public WidgetsFullSheet(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findViewById(R.id.container);
        TopRoundedCornerView springLayout = (TopRoundedCornerView) mContent;

        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        int contentLayoutRes = mHasWorkProfile ? R.layout.widgets_full_sheet_paged_view
                : R.layout.widgets_full_sheet_recyclerview;
        layoutInflater.inflate(contentLayoutRes, springLayout, true);

        RecyclerViewFastScroller fastScroller = findViewById(R.id.fast_scroller);
        if (mHasWorkProfile) {
            mViewPager = findViewById(R.id.widgets_view_pager);
            mViewPager.initParentViews(this);
            mViewPager.getPageIndicator().setOnActivePageChangedListener(this);
            mViewPager.getPageIndicator().setActiveMarker(AdapterHolder.PRIMARY);
            mTabsView = findViewById(R.id.tabs);
            findViewById(R.id.tab_personal)
                    .setOnClickListener((View view) -> mViewPager.snapToPage(0));
            findViewById(R.id.tab_work)
                    .setOnClickListener((View view) -> mViewPager.snapToPage(1));
            fastScroller.setIsRecyclerViewFirstChildInParent(false);
            springLayout.addSpringView(R.id.primary_widgets_list_view);
            springLayout.addSpringView(R.id.work_widgets_list_view);
        } else {
            mViewPager = null;
            springLayout.addSpringView(R.id.primary_widgets_list_view);
        }

        layoutInflater.inflate(R.layout.widgets_full_sheet_search_and_recommendations, springLayout,
                true);
        springLayout.addSpringView(R.id.search_and_recommendations_container);

        mSearchAndRecommendationViewHolder = new SearchAndRecommendationViewHolder(
                findViewById(R.id.search_and_recommendations_container));
        mSearchAndRecommendationsScrollController = new SearchAndRecommendationsScrollController(
                mHasWorkProfile,
                mSearchAndRecommendationViewHolder,
                findViewById(R.id.primary_widgets_list_view),
                mHasWorkProfile ? findViewById(R.id.work_widgets_list_view) : null,
                findViewById(R.id.search_widgets_list_view),
                mTabsView,
                mViewPager);
        fastScroller.setOnFastScrollChangeListener(mSearchAndRecommendationsScrollController);

        mNoWidgetsView = findViewById(R.id.no_widgets_text);

        onWidgetsBound();

        mSearchAndRecommendationViewHolder.mSearchBar.initialize(
                mLauncher.getPopupDataProvider().getAllWidgets(), /* searchModeListener= */ this);
    }

    @Override
    public void onActivePageChanged(int currentActivePage) {
        AdapterHolder currentAdapterHolder = mAdapters.get(currentActivePage);
        WidgetsRecyclerView currentRecyclerView =
                mAdapters.get(currentActivePage).mWidgetsRecyclerView;

        updateNoWidgetsView(currentAdapterHolder);

        attachScrollbarToRecyclerView(currentRecyclerView);
    }

    private void attachScrollbarToRecyclerView(WidgetsRecyclerView recyclerView) {
        recyclerView.bindFastScrollbar();
        mSearchAndRecommendationsScrollController.setCurrentRecyclerView(recyclerView);
        reset();
    }

    private void updateNoWidgetsView(AdapterHolder adapterHolder) {
        boolean isWidgetAvailable = adapterHolder.mWidgetsListAdapter.getItemCount() > 0;
        adapterHolder.mWidgetsRecyclerView.setVisibility(isWidgetAvailable ? VISIBLE : GONE);

        // Always resets the text in case this is updated by search.
        mNoWidgetsView.setText(R.string.no_widgets_available);
        mNoWidgetsView.setVisibility(isWidgetAvailable ? GONE : VISIBLE);
    }

    private void reset() {
        mAdapters.get(AdapterHolder.PRIMARY).mWidgetsRecyclerView.scrollToTop();
        if (mHasWorkProfile) {
            mAdapters.get(AdapterHolder.WORK).mWidgetsRecyclerView.scrollToTop();
        }
        mAdapters.get(AdapterHolder.SEARCH).mWidgetsRecyclerView.scrollToTop();
        mSearchAndRecommendationsScrollController.reset();
    }

    @VisibleForTesting
    public WidgetsRecyclerView getRecyclerView() {
        if (mIsInSearchMode) {
            return mAdapters.get(AdapterHolder.SEARCH).mWidgetsRecyclerView;
        }
        if (!mHasWorkProfile || mViewPager.getCurrentPage() == AdapterHolder.PRIMARY) {
            return mAdapters.get(AdapterHolder.PRIMARY).mWidgetsRecyclerView;
        }
        return mAdapters.get(AdapterHolder.WORK).mWidgetsRecyclerView;
    }

    @Override
    protected Pair<View, String> getAccessibilityTarget() {
        return Pair.create(getRecyclerView(), getContext().getString(
                mIsOpen ? R.string.widgets_list : R.string.widgets_list_closed));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLauncher.getAppWidgetHost().addProviderChangeListener(this);
        notifyWidgetProvidersChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mLauncher.getAppWidgetHost().removeProviderChangeListener(this);
    }

    @Override
    public void setInsets(Rect insets) {
        mInsets.set(insets);

        setBottomPadding(mAdapters.get(AdapterHolder.PRIMARY).mWidgetsRecyclerView, insets.bottom);
        if (mHasWorkProfile) {
            setBottomPadding(mAdapters.get(AdapterHolder.WORK).mWidgetsRecyclerView, insets.bottom);
        }
        if (insets.bottom > 0) {
            setupNavBarColor();
        } else {
            clearNavBarColor();
        }

        ((TopRoundedCornerView) mContent).setNavBarScrimHeight(mInsets.bottom);
        requestLayout();
    }

    private void setBottomPadding(RecyclerView recyclerView, int bottomPadding) {
        recyclerView.setPadding(
                recyclerView.getPaddingLeft(),
                recyclerView.getPaddingTop(),
                recyclerView.getPaddingRight(),
                bottomPadding);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        DeviceProfile deviceProfile = mLauncher.getDeviceProfile();
        int widthUsed;
        if (mInsets.bottom > 0) {
            widthUsed = mInsets.left + mInsets.right;
        } else {
            Rect padding = deviceProfile.workspacePadding;
            widthUsed = Math.max(padding.left + padding.right,
                    2 * (mInsets.left + mInsets.right));
        }

        int heightUsed = mInsets.top + deviceProfile.edgeMarginPx;
        measureChildWithMargins(mContent, widthMeasureSpec,
                widthUsed, heightMeasureSpec, heightUsed);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));

        int paddingPx = 2 * getResources().getDimensionPixelOffset(
                R.dimen.widget_cell_horizontal_padding);
        int maxSpansPerRow = getMeasuredWidth() / (deviceProfile.cellWidthPx + paddingPx);
        mAdapters.get(AdapterHolder.PRIMARY).mWidgetsListAdapter.setMaxHorizontalSpansPerRow(
                maxSpansPerRow);
        if (mHasWorkProfile) {
            mAdapters.get(AdapterHolder.WORK).mWidgetsListAdapter.setMaxHorizontalSpansPerRow(
                    maxSpansPerRow);
        }

        if (mInitialTabsHeight == 0 && mTabsView != null) {
            mInitialTabsHeight = measureHeightWithVerticalMargins(mTabsView);
        }

        mSearchAndRecommendationsScrollController.updateMarginAndPadding();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;

        // Content is laid out as center bottom aligned
        int contentWidth = mContent.getMeasuredWidth();
        int contentLeft = (width - contentWidth - mInsets.left - mInsets.right) / 2 + mInsets.left;
        mContent.layout(contentLeft, height - mContent.getMeasuredHeight(),
                contentLeft + contentWidth, height);

        setTranslationShift(mTranslationShift);
    }

    @Override
    public void notifyWidgetProvidersChanged() {
        mLauncher.refreshAndBindWidgetsForPackageUser(null);
    }

    @Override
    public void onWidgetsBound() {
        List<WidgetsListBaseEntry> allWidgets = mLauncher.getPopupDataProvider().getAllWidgets();

        AdapterHolder primaryUserAdapterHolder = mAdapters.get(AdapterHolder.PRIMARY);
        primaryUserAdapterHolder.setup(findViewById(R.id.primary_widgets_list_view));
        AdapterHolder searchAdapterHolder = mAdapters.get(AdapterHolder.SEARCH);
        searchAdapterHolder.setup(findViewById(R.id.search_widgets_list_view));
        primaryUserAdapterHolder.mWidgetsListAdapter.setWidgets(allWidgets);
        updateNoWidgetsView(primaryUserAdapterHolder);

        if (mHasWorkProfile) {
            AdapterHolder workUserAdapterHolder = mAdapters.get(AdapterHolder.WORK);
            workUserAdapterHolder.setup(findViewById(R.id.work_widgets_list_view));
            workUserAdapterHolder.mWidgetsListAdapter.setWidgets(allWidgets);
            onActivePageChanged(mViewPager.getCurrentPage());
        }
    }

    @Override
    public void enterSearchMode() {
        if (mIsInSearchMode) return;
        setViewVisibilityBasedOnSearch(/*isInSearchMode= */ true);
        attachScrollbarToRecyclerView(mAdapters.get(AdapterHolder.SEARCH).mWidgetsRecyclerView);
    }

    @Override
    public void exitSearchMode() {
        setViewVisibilityBasedOnSearch(/*isInSearchMode=*/ false);
        if (mHasWorkProfile) {
            mViewPager.snapToPage(AdapterHolder.PRIMARY);
        }
        attachScrollbarToRecyclerView(mAdapters.get(AdapterHolder.PRIMARY).mWidgetsRecyclerView);
    }

    @Override
    public void onSearchResults(List<WidgetsListBaseEntry> entries) {
        mAdapters.get(AdapterHolder.SEARCH).mWidgetsListAdapter.setWidgetsOnSearch(entries);
    }

    private void setViewVisibilityBasedOnSearch(boolean isInSearchMode) {
        mIsInSearchMode = isInSearchMode;
        if (mHasWorkProfile) {
            mViewPager.setVisibility(isInSearchMode ? GONE : VISIBLE);
            mTabsView.setVisibility(isInSearchMode ? GONE : VISIBLE);
        } else {
            mAdapters.get(AdapterHolder.PRIMARY).mWidgetsRecyclerView
                    .setVisibility(isInSearchMode ? GONE : VISIBLE);
        }
        mAdapters.get(AdapterHolder.SEARCH).mWidgetsRecyclerView
                .setVisibility(mIsInSearchMode ? VISIBLE : GONE);
    }

    private void open(boolean animate) {
        if (animate) {
            if (getPopupContainer().getInsets().bottom > 0) {
                mContent.setAlpha(0);
                setTranslationShift(VERTICAL_START_POSITION);
            }
            mOpenCloseAnimator.setValues(
                    PropertyValuesHolder.ofFloat(TRANSLATION_SHIFT, TRANSLATION_SHIFT_OPENED));
            mOpenCloseAnimator
                    .setDuration(DEFAULT_OPEN_DURATION)
                    .setInterpolator(AnimationUtils.loadInterpolator(
                            getContext(), android.R.interpolator.linear_out_slow_in));
            mOpenCloseAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mOpenCloseAnimator.removeListener(this);
                }
            });
            post(() -> {
                mOpenCloseAnimator.start();
                mContent.animate().alpha(1).setDuration(FADE_IN_DURATION);
            });
        } else {
            setTranslationShift(TRANSLATION_SHIFT_OPENED);
            post(this::announceAccessibilityChanges);
        }
    }

    @Override
    protected void handleClose(boolean animate) {
        handleClose(animate, DEFAULT_OPEN_DURATION);
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_WIDGETS_FULL_SHEET) != 0;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        // Disable swipe down when recycler view is scrolling
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mNoIntercept = false;
            RecyclerViewFastScroller scroller = getRecyclerView().getScrollbar();
            if (scroller.getThumbOffsetY() >= 0
                    && getPopupContainer().isEventOverView(scroller, ev)) {
                mNoIntercept = true;
            } else if (getPopupContainer().isEventOverView(mContent, ev)) {
                mNoIntercept = !getRecyclerView().shouldContainerScroll(ev, getPopupContainer());
            }
        }
        return super.onControllerInterceptTouchEvent(ev);
    }

    /** Shows the {@link WidgetsFullSheet} on the launcher. */
    public static WidgetsFullSheet show(Launcher launcher, boolean animate) {
        WidgetsFullSheet sheet = (WidgetsFullSheet) launcher.getLayoutInflater()
                .inflate(R.layout.widgets_full_sheet, launcher.getDragLayer(), false);
        sheet.attachToContainer();
        sheet.mIsOpen = true;
        sheet.open(animate);
        return sheet;
    }

    /** Gets the {@link WidgetsRecyclerView} which shows all widgets in {@link WidgetsFullSheet}. */
    @VisibleForTesting
    public static WidgetsRecyclerView getWidgetsView(Launcher launcher) {
        return launcher.findViewById(R.id.primary_widgets_list_view);
    }

    @Override
    public void addHintCloseAnim(
            float distanceToMove, Interpolator interpolator, PendingAnimation target) {
        target.setFloat(getRecyclerView(), VIEW_TRANSLATE_Y, -distanceToMove, interpolator);
        target.setViewAlpha(getRecyclerView(), 0.5f, interpolator);
    }

    @Override
    protected void onCloseComplete() {
        super.onCloseComplete();
        AccessibilityManagerCompat.sendStateEventToTest(getContext(), NORMAL_STATE_ORDINAL);
    }

    @Override
    public int getHeaderViewHeight() {
        return measureHeightWithVerticalMargins(mSearchAndRecommendationViewHolder.mCollapseHandle)
                + measureHeightWithVerticalMargins(mSearchAndRecommendationViewHolder.mHeaderTitle)
                + measureHeightWithVerticalMargins(mSearchAndRecommendationViewHolder.mSearchBar);
    }

    /** private the height, in pixel, + the vertical margins of a given view. */
    private static int measureHeightWithVerticalMargins(View view) {
        if (view.getVisibility() != VISIBLE) {
            return 0;
        }
        MarginLayoutParams marginLayoutParams = (MarginLayoutParams) view.getLayoutParams();
        return view.getMeasuredHeight() + marginLayoutParams.bottomMargin
                + marginLayoutParams.topMargin;
    }

    /** A holder class for holding adapters & their corresponding recycler view. */
    private final class AdapterHolder {
        static final int PRIMARY = 0;
        static final int WORK = 1;
        static final int SEARCH = 2;

        private final int mAdapterType;
        private final WidgetsListAdapter mWidgetsListAdapter;

        private WidgetsRecyclerView mWidgetsRecyclerView;

        AdapterHolder(int adapterType) {
            mAdapterType = adapterType;

            Context context = getContext();
            LauncherAppState apps = LauncherAppState.getInstance(context);
            mWidgetsListAdapter = new WidgetsListAdapter(
                    context,
                    LayoutInflater.from(context),
                    apps.getWidgetCache(),
                    apps.getIconCache(),
                    /* iconClickListener= */ WidgetsFullSheet.this,
                    /* iconLongClickListener= */ WidgetsFullSheet.this);
            switch (mAdapterType) {
                case PRIMARY:
                    mWidgetsListAdapter.setFilter(mPrimaryWidgetsFilter);
                    break;
                case WORK:
                    mWidgetsListAdapter.setFilter(mWorkWidgetsFilter);
                    break;
                default:
                    break;
            }
        }

        void setup(WidgetsRecyclerView recyclerView) {
            mWidgetsRecyclerView = recyclerView;
            mWidgetsRecyclerView.setAdapter(mWidgetsListAdapter);
            mWidgetsRecyclerView.setHeaderViewDimensionsProvider(WidgetsFullSheet.this);
            mWidgetsRecyclerView.setEdgeEffectFactory(
                    ((TopRoundedCornerView) mContent).createEdgeEffectFactory());
            mWidgetsListAdapter.setApplyBitmapDeferred(false, mWidgetsRecyclerView);
        }
    }

    final class SearchAndRecommendationViewHolder {
        final View mContainer;
        final View mCollapseHandle;
        final WidgetsSearchBar mSearchBar;
        final TextView mHeaderTitle;

        SearchAndRecommendationViewHolder(View searchAndRecommendationContainer) {
            mContainer = searchAndRecommendationContainer;
            mCollapseHandle = mContainer.findViewById(R.id.collapse_handle);
            mSearchBar = mContainer.findViewById(R.id.widgets_search_bar);
            mHeaderTitle = mContainer.findViewById(R.id.title);
        }
    }
}
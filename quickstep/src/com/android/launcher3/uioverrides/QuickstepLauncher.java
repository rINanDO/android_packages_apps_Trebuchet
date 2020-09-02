/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.launcher3.uioverrides;

import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED;

import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.LauncherState.OVERVIEW_MODAL_TASK;
import static com.android.launcher3.compat.AccessibilityManagerCompat.sendCustomAccessibilityEvent;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_APP_LAUNCH_TAP;
import static com.android.launcher3.testing.TestProtocol.HINT_STATE_ORDINAL;
import static com.android.launcher3.testing.TestProtocol.OVERVIEW_STATE_ORDINAL;
import static com.android.launcher3.testing.TestProtocol.QUICK_SWITCH_STATE_ORDINAL;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.quickstep.SysUINavigationMode.Mode.NO_BUTTON;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_HOME_KEY;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.android.launcher3.BaseQuickstepLauncher;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.LauncherState;
import com.android.launcher3.Workspace;
import com.android.launcher3.allapps.DiscoveryBounce;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.appprediction.PredictionRowView;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.hybridhotseat.HotseatPredictionController;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.StatsLogManager.StatsLogger;
import com.android.launcher3.model.BgDataModel.FixedContainerItems;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.statemanager.StateManager.AtomicAnimationFactory;
import com.android.launcher3.testing.TestProtocol;
import com.android.launcher3.uioverrides.states.QuickstepAtomicAnimationFactory;
import com.android.launcher3.uioverrides.touchcontrollers.LandscapeEdgeSwipeController;
import com.android.launcher3.uioverrides.touchcontrollers.NavBarToHomeTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.NoButtonNavbarToOverviewTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.NoButtonQuickSwitchTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.OverviewToAllAppsTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.PortraitStatesTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.QuickSwitchTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.StatusBarTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.TaskViewTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.TransposedQuickSwitchTouchController;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.TouchController;
import com.android.launcher3.util.UiThreadHelper;
import com.android.launcher3.util.UiThreadHelper.AsyncCommand;
import com.android.quickstep.SysUINavigationMode;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class QuickstepLauncher extends BaseQuickstepLauncher {

    public static final boolean GO_LOW_RAM_RECENTS_ENABLED = false;
    /**
     * Reusable command for applying the shelf height on the background thread.
     */
    public static final AsyncCommand SET_SHELF_HEIGHT = (context, arg1, arg2) ->
            SystemUiProxy.INSTANCE.get(context).setShelfHeight(arg1 != 0, arg2);

    private FixedContainerItems mAllAppsPredictions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.createPredictor();
        }
    }

    @Override
    protected void setupViews() {
        super.setupViews();
        if (FeatureFlags.ENABLE_HYBRID_HOTSEAT.get()) {
            mHotseatPredictionController = new HotseatPredictionController(this);
        }
    }

    @Override
    protected void logAppLaunch(ItemInfo info, InstanceId instanceId) {
        StatsLogger logger = getStatsLogManager()
                .logger().withItemInfo(info).withInstanceId(instanceId);

        if (mAllAppsPredictions != null
                && (info.itemType == ITEM_TYPE_APPLICATION
                || info.itemType == ITEM_TYPE_SHORTCUT
                || info.itemType == ITEM_TYPE_DEEP_SHORTCUT)) {
            int count = mAllAppsPredictions.items.size();
            for (int i = 0; i < count; i++) {
                ItemInfo targetInfo = mAllAppsPredictions.items.get(i);
                if (targetInfo.itemType == info.itemType
                        && targetInfo.user.equals(info.user)
                        && Objects.equals(targetInfo.getIntent(), info.getIntent())) {
                    logger.withRank(i);
                    break;
                }

            }
        }
        logger.log(LAUNCHER_APP_LAUNCH_TAP);

        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.logLaunchedAppRankingInfo(info, instanceId);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        onStateOrResumeChanging(false /* inTransition */);
    }

    @Override
    public boolean startActivitySafely(View v, Intent intent, ItemInfo item) {
        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.setPauseUIUpdate(true);
        }
        return super.startActivitySafely(v, intent, item);
    }

    @Override
    protected void onActivityFlagsChanged(int changeBits) {
        super.onActivityFlagsChanged(changeBits);
        if ((changeBits & (ACTIVITY_STATE_DEFERRED_RESUMED | ACTIVITY_STATE_STARTED
                | ACTIVITY_STATE_USER_ACTIVE | ACTIVITY_STATE_TRANSITION_ACTIVE)) != 0) {
            onStateOrResumeChanging((getActivityFlags() & ACTIVITY_STATE_TRANSITION_ACTIVE) == 0);
        }

        if (mHotseatPredictionController != null && ((changeBits & ACTIVITY_STATE_STARTED) != 0
                || (changeBits & getActivityFlags() & ACTIVITY_STATE_DEFERRED_RESUMED) != 0)) {
            mHotseatPredictionController.setPauseUIUpdate(false);
        }
    }

    @Override
    protected void showAllAppsFromIntent(boolean alreadyOnHome) {
        ActivityManagerWrapper.getInstance().closeSystemWindows(
            CLOSE_SYSTEM_WINDOWS_REASON_HOME_KEY);
        super.showAllAppsFromIntent(alreadyOnHome);
    }

    @Override
    public void folderCreatedFromItem(Folder folder, WorkspaceItemInfo itemInfo) {
        super.folderCreatedFromItem(folder, itemInfo);
        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.folderCreatedFromWorkspaceItem(itemInfo, folder.getInfo());
        }
    }

    @Override
    public void folderConvertedToItem(Folder folder, WorkspaceItemInfo itemInfo) {
        super.folderConvertedToItem(folder, itemInfo);
        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.folderConvertedToWorkspaceItem(itemInfo, folder.getInfo());
        }
    }

    @Override
    public Stream<SystemShortcut.Factory> getSupportedShortcuts() {
        if (mHotseatPredictionController != null) {
            return Stream.concat(super.getSupportedShortcuts(),
                    Stream.of(mHotseatPredictionController));
        } else {
            return super.getSupportedShortcuts();
        }
    }

    /**
     * Recents logic that triggers when launcher state changes or launcher activity stops/resumes.
     */
    private void onStateOrResumeChanging(boolean inTransition) {
        LauncherState state = getStateManager().getState();
        DeviceProfile profile = getDeviceProfile();
        boolean willUserBeActive = (getActivityFlags() & ACTIVITY_STATE_USER_WILL_BE_ACTIVE) != 0;
        boolean visible = (state == NORMAL || state == OVERVIEW)
                && (willUserBeActive || isUserActive())
                && !profile.isVerticalBarLayout()
                && profile.isPhone && !profile.isLandscape;
        UiThreadHelper.runAsyncCommand(this, SET_SHELF_HEIGHT, visible ? 1 : 0,
                profile.hotseatBarSizePx);
        if (state == NORMAL && !inTransition) {
            ((RecentsView) getOverviewPanel()).setSwipeDownShouldLaunchApp(false);
        }
    }

    @Override
    public void bindPredictedItems(List<AppInfo> appInfos, IntArray ranks) {
        super.bindPredictedItems(appInfos, ranks);
        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.showCachedItems(appInfos, ranks);
        }
    }

    @Override
    public void bindExtraContainerItems(FixedContainerItems item) {
        if (item.containerId == Favorites.CONTAINER_PREDICTION) {
            mAllAppsPredictions = item;
            getAppsView().getFloatingHeaderView().findFixedRowByType(PredictionRowView.class)
                    .setPredictedApps(item.items);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHotseatPredictionController != null) {
            mHotseatPredictionController.destroy();
            mHotseatPredictionController = null;
        }
    }

    @Override
    public void onStateSetEnd(LauncherState state) {
        super.onStateSetEnd(state);

        switch (state.ordinal) {
            case HINT_STATE_ORDINAL: {
                Workspace workspace = getWorkspace();
                getStateManager().goToState(NORMAL);
                if (workspace.getNextPage() != Workspace.DEFAULT_PAGE) {
                    workspace.post(workspace::moveToDefaultScreen);
                }
                break;
            }
            case OVERVIEW_STATE_ORDINAL: {
                RecentsView recentsView = getOverviewPanel();
                DiscoveryBounce.showForOverviewIfNeeded(this,
                        recentsView.getPagedOrientationHandler());
                RecentsView rv = getOverviewPanel();
                sendCustomAccessibilityEvent(
                        rv.getPageAt(rv.getCurrentPage()), TYPE_VIEW_FOCUSED, null);
                break;
            }
            case QUICK_SWITCH_STATE_ORDINAL: {
                RecentsView rv = getOverviewPanel();
                TaskView tasktolaunch = rv.getTaskViewAt(0);
                if (tasktolaunch != null) {
                    tasktolaunch.launchTask(false, success -> {
                        if (!success) {
                            getStateManager().goToState(OVERVIEW);
                            tasktolaunch.notifyTaskLaunchFailed(TAG);
                        } else {
                            getStateManager().moveToRestState();
                        }
                    }, MAIN_EXECUTOR.getHandler());
                } else {
                    getStateManager().goToState(NORMAL);
                }
                break;
            }

        }
    }

    @Override
    public TouchController[] createTouchControllers() {
        if (TestProtocol.sDebugTracing) {
            Log.d(TestProtocol.PAUSE_NOT_DETECTED, "createTouchControllers.1");
        }
        Mode mode = SysUINavigationMode.getMode(this);

        ArrayList<TouchController> list = new ArrayList<>();
        list.add(getDragController());
        if (mode == NO_BUTTON) {
            list.add(new NoButtonQuickSwitchTouchController(this));
            list.add(new NavBarToHomeTouchController(this));
            if (TestProtocol.sDebugTracing) {
                Log.d(TestProtocol.PAUSE_NOT_DETECTED, "createTouchControllers.2");
            }
            list.add(new NoButtonNavbarToOverviewTouchController(this));
        } else {
            if (getDeviceProfile().isVerticalBarLayout()) {
                list.add(new OverviewToAllAppsTouchController(this));
                list.add(new LandscapeEdgeSwipeController(this));
                if (mode.hasGestures) {
                    list.add(new TransposedQuickSwitchTouchController(this));
                }
            } else {
                list.add(new PortraitStatesTouchController(this,
                        mode.hasGestures /* allowDragToOverview */));
                if (mode.hasGestures) {
                    list.add(new QuickSwitchTouchController(this));
                }
            }
        }

        if (!getDeviceProfile().isMultiWindowMode) {
            list.add(new StatusBarTouchController(this));
        }

        list.add(new LauncherTaskViewController(this));
        return list.toArray(new TouchController[list.size()]);
    }

    @Override
    public AtomicAnimationFactory createAtomicAnimationFactory() {
        return new QuickstepAtomicAnimationFactory(this);
    }

    private static final class LauncherTaskViewController extends
            TaskViewTouchController<Launcher> {

        LauncherTaskViewController(Launcher activity) {
            super(activity);
        }

        @Override
        protected boolean isRecentsInteractive() {
            return mActivity.isInState(OVERVIEW) || mActivity.isInState(OVERVIEW_MODAL_TASK);
        }

        @Override
        protected boolean isRecentsModal() {
            return mActivity.isInState(OVERVIEW_MODAL_TASK);
        }

        @Override
        protected void onUserControlledAnimationCreated(AnimatorPlaybackController animController) {
            mActivity.getStateManager().setCurrentUserControlledAnimation(animController);
        }
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        RecentsView recentsView = getOverviewPanel();
        writer.println("\nQuickstepLauncher:");
        writer.println(prefix + "\tmOrientationState: " + (recentsView == null ? "recentsNull" :
                recentsView.getPagedViewOrientedState()));
    }
}
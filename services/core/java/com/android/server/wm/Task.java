/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.RESIZE_MODE_FORCED;
import static android.app.ActivityTaskManager.RESIZE_MODE_SYSTEM;
import static android.app.ActivityTaskManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.EMPTY;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.SurfaceControl.METADATA_TASK_ID;

import static com.android.server.EventLogTags.WM_TASK_CREATED;
import static com.android.server.EventLogTags.WM_TASK_REMOVED;
import static com.android.server.am.TaskRecordProto.ACTIVITIES;
import static com.android.server.am.TaskRecordProto.ACTIVITY_TYPE;
import static com.android.server.am.TaskRecordProto.FULLSCREEN;
import static com.android.server.am.TaskRecordProto.LAST_NON_FULLSCREEN_BOUNDS;
import static com.android.server.am.TaskRecordProto.MIN_HEIGHT;
import static com.android.server.am.TaskRecordProto.MIN_WIDTH;
import static com.android.server.am.TaskRecordProto.ORIG_ACTIVITY;
import static com.android.server.am.TaskRecordProto.REAL_ACTIVITY;
import static com.android.server.am.TaskRecordProto.RESIZE_MODE;
import static com.android.server.am.TaskRecordProto.STACK_ID;
import static com.android.server.am.TaskRecordProto.TASK;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_REMOVED;
import static com.android.server.wm.ActivityRecord.STARTING_WINDOW_SHOWN;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.server.wm.TaskProto.APP_WINDOW_TOKENS;
import static com.android.server.wm.TaskProto.DISPLAYED_BOUNDS;
import static com.android.server.wm.TaskProto.FILLS_PARENT;
import static com.android.server.wm.TaskProto.SURFACE_HEIGHT;
import static com.android.server.wm.TaskProto.SURFACE_WIDTH;
import static com.android.server.wm.TaskProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.XmlUtils;
import com.android.server.protolog.common.ProtoLog;
import com.android.server.wm.ActivityStack.ActivityState;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;

class Task extends WindowContainer<ActivityRecord> implements ConfigurationContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_ATM;
    private static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    private static final String TAG_TASKS = TAG + POSTFIX_TASKS;

    private static final String ATTR_TASKID = "task_id";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_AFFINITYINTENT = "affinity_intent";
    private static final String ATTR_REALACTIVITY = "real_activity";
    private static final String ATTR_REALACTIVITY_SUSPENDED = "real_activity_suspended";
    private static final String ATTR_ORIGACTIVITY = "orig_activity";
    private static final String TAG_ACTIVITY = "activity";
    private static final String ATTR_AFFINITY = "affinity";
    private static final String ATTR_ROOT_AFFINITY = "root_affinity";
    private static final String ATTR_ROOTHASRESET = "root_has_reset";
    private static final String ATTR_AUTOREMOVERECENTS = "auto_remove_recents";
    private static final String ATTR_ASKEDCOMPATMODE = "asked_compat_mode";
    private static final String ATTR_USERID = "user_id";
    private static final String ATTR_USER_SETUP_COMPLETE = "user_setup_complete";
    private static final String ATTR_EFFECTIVE_UID = "effective_uid";
    @Deprecated
    private static final String ATTR_TASKTYPE = "task_type";
    private static final String ATTR_LASTDESCRIPTION = "last_description";
    private static final String ATTR_LASTTIMEMOVED = "last_time_moved";
    private static final String ATTR_NEVERRELINQUISH = "never_relinquish_identity";
    private static final String ATTR_TASK_AFFILIATION = "task_affiliation";
    private static final String ATTR_PREV_AFFILIATION = "prev_affiliation";
    private static final String ATTR_NEXT_AFFILIATION = "next_affiliation";
    private static final String ATTR_TASK_AFFILIATION_COLOR = "task_affiliation_color";
    private static final String ATTR_CALLING_UID = "calling_uid";
    private static final String ATTR_CALLING_PACKAGE = "calling_package";
    private static final String ATTR_SUPPORTS_PICTURE_IN_PICTURE = "supports_picture_in_picture";
    private static final String ATTR_RESIZE_MODE = "resize_mode";
    private static final String ATTR_NON_FULLSCREEN_BOUNDS = "non_fullscreen_bounds";
    private static final String ATTR_MIN_WIDTH = "min_width";
    private static final String ATTR_MIN_HEIGHT = "min_height";
    private static final String ATTR_PERSIST_TASK_VERSION = "persist_task_version";

    // Current version of the task record we persist. Used to check if we need to run any upgrade
    // code.
    private static final int PERSIST_TASK_VERSION = 1;

    private static final int INVALID_MIN_SIZE = -1;

    /**
     * The modes to control how the stack is moved to the front when calling {@link Task#reparent}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            REPARENT_MOVE_STACK_TO_FRONT,
            REPARENT_KEEP_STACK_AT_FRONT,
            REPARENT_LEAVE_STACK_IN_PLACE
    })
    @interface ReparentMoveStackMode {}
    // Moves the stack to the front if it was not at the front
    static final int REPARENT_MOVE_STACK_TO_FRONT = 0;
    // Only moves the stack to the front if it was focused or front most already
    static final int REPARENT_KEEP_STACK_AT_FRONT = 1;
    // Do not move the stack as a part of reparenting
    static final int REPARENT_LEAVE_STACK_IN_PLACE = 2;

    /**
     * The factory used to create {@link Task}. This allows OEM subclass {@link Task}.
     */
    private static TaskFactory sTaskFactory;

    String affinity;        // The affinity name for this task, or null; may change identity.
    String rootAffinity;    // Initial base affinity, or null; does not change from initial root.
    final IVoiceInteractionSession voiceSession;    // Voice interaction session driving task
    final IVoiceInteractor voiceInteractor;         // Associated interactor to provide to app
    Intent intent;          // The original intent that started the task. Note that this value can
                            // be null.
    Intent affinityIntent;  // Intent of affinity-moved activity that started this task.
    int effectiveUid;       // The current effective uid of the identity of this task.
    ComponentName origActivity; // The non-alias activity component of the intent.
    ComponentName realActivity; // The actual activity component that started the task.
    boolean realActivitySuspended; // True if the actual activity component that started the
                                   // task is suspended.
    boolean inRecents;      // Actually in the recents list?
    long lastActiveTime;    // Last time this task was active in the current device session,
                            // including sleep. This time is initialized to the elapsed time when
                            // restored from disk.
    boolean isAvailable;    // Is the activity available to be launched?
    boolean rootWasReset;   // True if the intent at the root of the task had
                            // the FLAG_ACTIVITY_RESET_TASK_IF_NEEDED flag.
    boolean autoRemoveRecents;  // If true, we should automatically remove the task from
                                // recents when activity finishes
    boolean askedCompatMode;// Have asked the user about compat mode for this task.
    boolean hasBeenVisible; // Set if any activities in the task have been visible to the user.

    String stringName;      // caching of toString() result.
    boolean mUserSetupComplete; // The user set-up is complete as of the last time the task activity
                                // was changed.

    int numFullscreen;      // Number of fullscreen activities.

    /** Can't be put in lockTask mode. */
    final static int LOCK_TASK_AUTH_DONT_LOCK = 0;
    /** Can enter app pinning with user approval. Can never start over existing lockTask task. */
    final static int LOCK_TASK_AUTH_PINNABLE = 1;
    /** Starts in LOCK_TASK_MODE_LOCKED automatically. Can start over existing lockTask task. */
    final static int LOCK_TASK_AUTH_LAUNCHABLE = 2;
    /** Can enter lockTask without user approval. Can start over existing lockTask task. */
    final static int LOCK_TASK_AUTH_WHITELISTED = 3;
    /** Priv-app that starts in LOCK_TASK_MODE_LOCKED automatically. Can start over existing
     * lockTask task. */
    final static int LOCK_TASK_AUTH_LAUNCHABLE_PRIV = 4;
    int mLockTaskAuth = LOCK_TASK_AUTH_PINNABLE;

    int mLockTaskUid = -1;  // The uid of the application that called startLockTask().

    /** Current stack. Setter must always be used to update the value. */
    private ActivityStack mStack;

    /** The process that had previously hosted the root activity of this task.
     * Used to know that we should try harder to keep this process around, in case the
     * user wants to return to it. */
    private WindowProcessController mRootProcess;

    /** Takes on same value as first root activity */
    boolean isPersistable = false;
    int maxRecents;

    /** Only used for persistable tasks, otherwise 0. The last time this task was moved. Used for
     * determining the order when restoring. Sign indicates whether last task movement was to front
     * (positive) or back (negative). Absolute value indicates time. */
    long mLastTimeMoved;

    /** If original intent did not allow relinquishing task identity, save that information */
    private boolean mNeverRelinquishIdentity = true;

    // Used in the unique case where we are clearing the task in order to reuse it. In that case we
    // do not want to delete the stack when the task goes empty.
    private boolean mReuseTask = false;

    CharSequence lastDescription; // Last description captured for this item.

    int mAffiliatedTaskId; // taskId of parent affiliation or self if no parent.
    int mAffiliatedTaskColor; // color of the parent task affiliation.
    Task mPrevAffiliate; // previous task in affiliated chain.
    int mPrevAffiliateTaskId = INVALID_TASK_ID; // previous id for persistence.
    Task mNextAffiliate; // next task in affiliated chain.
    int mNextAffiliateTaskId = INVALID_TASK_ID; // next id for persistence.

    // For relaunching the task from recents as though it was launched by the original launcher.
    int mCallingUid;
    String mCallingPackage;

    private final Rect mTmpStableBounds = new Rect();
    private final Rect mTmpNonDecorBounds = new Rect();
    private final Rect mTmpBounds = new Rect();
    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpFullBounds = new Rect();

    // Last non-fullscreen bounds the task was launched in or resized to.
    // The information is persisted and used to determine the appropriate stack to launch the
    // task into on restore.
    Rect mLastNonFullscreenBounds = null;
    // Minimal width and height of this task when it's resizeable. -1 means it should use the
    // default minimal width/height.
    int mMinWidth;
    int mMinHeight;

    // Ranking (from top) of this task among all visible tasks. (-1 means it's not visible)
    // This number will be assigned when we evaluate OOM scores for all visible tasks.
    int mLayerRank = -1;

    /** Helper object used for updating override configuration. */
    private Configuration mTmpConfig = new Configuration();

    /** Used by fillTaskInfo */
    final TaskActivitiesReport mReuseActivitiesReport = new TaskActivitiesReport();

    final ActivityTaskManagerService mAtmService;

    /* Unique identifier for this task. */
    final int mTaskId;
    /* User for which this task was created. */
    // TODO: Make final
    int mUserId;

    final Rect mPreparedFrozenBounds = new Rect();
    final Configuration mPreparedFrozenMergedConfig = new Configuration();

    // If non-empty, bounds used to display the task during animations/interactions.
    // TODO(b/119687367): This member is temporary.
    private final Rect mOverrideDisplayedBounds = new Rect();

    /** ID of the display which rotation {@link #mRotation} has. */
    private int mLastRotationDisplayId = Display.INVALID_DISPLAY;
    /**
     * Display rotation as of the last time {@link #setBounds(Rect)} was called or this task was
     * moved to a new display.
     */
    private int mRotation;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    // Based on the {@link ActivityInfo#resizeMode} of the root activity.
    int mResizeMode;

    // Whether or not this task and its activities support PiP. Based on the
    // {@link ActivityInfo#FLAG_SUPPORTS_PICTURE_IN_PICTURE} flag of the root activity.
    boolean mSupportsPictureInPicture;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;
    private int mDragResizeMode;

    // This represents the last resolved activity values for this task
    // NOTE: This value needs to be persisted with each task
    private TaskDescription mTaskDescription;

    // If set to true, the task will report that it is not in the floating
    // state regardless of it's stack affiliation. As the floating state drives
    // production of content insets this can be used to preserve them across
    // stack moves and we in fact do so when moving from full screen to pinned.
    private boolean mPreserveNonFloatingState = false;

    private Dimmer mDimmer = new Dimmer(this);
    private final Rect mTmpDimBoundsRect = new Rect();

    /** @see #setCanAffectSystemUiFlags */
    private boolean mCanAffectSystemUiFlags = true;

    /**
     * Don't use constructor directly. Use {@link #create(ActivityTaskManagerService, int,
     * ActivityInfo, Intent, TaskDescription)} instead.
     */
    Task(ActivityTaskManagerService atmService, int _taskId, ActivityInfo info, Intent _intent,
            IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            TaskDescription _taskDescription, ActivityStack stack) {
        this(atmService, _taskId, _intent,  null /*_affinityIntent*/, null /*_affinity*/,
                null /*_rootAffinity*/, null /*_realActivity*/, null /*_origActivity*/,
                false /*_rootWasReset*/, false /*_autoRemoveRecents*/, false /*_askedCompatMode*/,
                UserHandle.getUserId(info.applicationInfo.uid), 0 /*_effectiveUid*/,
                null /*_lastDescription*/, System.currentTimeMillis(),
                true /*neverRelinquishIdentity*/,
                _taskDescription != null ? _taskDescription : new TaskDescription(),
                _taskId, INVALID_TASK_ID, INVALID_TASK_ID, 0 /*taskAffiliationColor*/,
                info.applicationInfo.uid, info.packageName, info.resizeMode,
                info.supportsPictureInPicture(), false /*_realActivitySuspended*/,
                false /*userSetupComplete*/, INVALID_MIN_SIZE, INVALID_MIN_SIZE, info,
                _voiceSession, _voiceInteractor, stack);
    }

    /** Don't use constructor directly. This is only used by XML parser. */
    Task(ActivityTaskManagerService atmService, int _taskId, Intent _intent,
            Intent _affinityIntent, String _affinity, String _rootAffinity,
            ComponentName _realActivity, ComponentName _origActivity, boolean _rootWasReset,
            boolean _autoRemoveRecents, boolean _askedCompatMode, int _userId,
            int _effectiveUid, String _lastDescription,
            long lastTimeMoved, boolean neverRelinquishIdentity,
            TaskDescription _lastTaskDescription, int taskAffiliation, int prevTaskId,
            int nextTaskId, int taskAffiliationColor, int callingUid, String callingPackage,
            int resizeMode, boolean supportsPictureInPicture, boolean _realActivitySuspended,
            boolean userSetupComplete, int minWidth, int minHeight, ActivityInfo info,
            IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            ActivityStack stack) {
        super(atmService.mWindowManager);

        EventLog.writeEvent(WM_TASK_CREATED, _taskId,
                stack != null ? stack.mStackId : INVALID_STACK_ID);
        mAtmService = atmService;
        mTaskId = _taskId;
        mUserId = _userId;
        mResizeMode = resizeMode;
        mSupportsPictureInPicture = supportsPictureInPicture;
        mTaskDescription = _lastTaskDescription;
        // Tasks have no set orientation value (including SCREEN_ORIENTATION_UNSPECIFIED).
        setOrientation(SCREEN_ORIENTATION_UNSET);
        mRemoteToken = new RemoteToken(this);
        affinityIntent = _affinityIntent;
        affinity = _affinity;
        rootAffinity = _rootAffinity;
        voiceSession = _voiceSession;
        voiceInteractor = _voiceInteractor;
        realActivity = _realActivity;
        realActivitySuspended = _realActivitySuspended;
        origActivity = _origActivity;
        rootWasReset = _rootWasReset;
        isAvailable = true;
        autoRemoveRecents = _autoRemoveRecents;
        askedCompatMode = _askedCompatMode;
        mUserSetupComplete = userSetupComplete;
        effectiveUid = _effectiveUid;
        touchActiveTime();
        lastDescription = _lastDescription;
        mLastTimeMoved = lastTimeMoved;
        mNeverRelinquishIdentity = neverRelinquishIdentity;
        mAffiliatedTaskId = taskAffiliation;
        mAffiliatedTaskColor = taskAffiliationColor;
        mPrevAffiliateTaskId = prevTaskId;
        mNextAffiliateTaskId = nextTaskId;
        mCallingUid = callingUid;
        mCallingPackage = callingPackage;
        mResizeMode = resizeMode;
        if (info != null) {
            setIntent(_intent, info);
            setMinDimensions(info);
        } else {
            intent = _intent;
            mMinWidth = minWidth;
            mMinHeight = minHeight;
        }
        mAtmService.getTaskChangeNotificationController().notifyTaskCreated(_taskId, realActivity);
    }

    void cleanUpResourcesForDestroy() {
        if (hasChild()) {
            return;
        }

        // This task is going away, so save the last state if necessary.
        saveLaunchingStateIfNeeded();

        // TODO: VI what about activity?
        final boolean isVoiceSession = voiceSession != null;
        if (isVoiceSession) {
            try {
                voiceSession.taskFinished(intent, mTaskId);
            } catch (RemoteException e) {
            }
        }
        if (autoRemoveFromRecents() || isVoiceSession) {
            // Task creator asked to remove this when done, or this task was a voice
            // interaction, so it should not remain on the recent tasks list.
            mAtmService.mStackSupervisor.mRecentTasks.remove(this);
        }

        removeIfPossible();
    }

    @VisibleForTesting
    @Override
    void removeIfPossible() {
        mAtmService.getLockTaskController().clearLockedTask(this);
        if (shouldDeferRemoval()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            return;
        }
        removeImmediately();
        mAtmService.getTaskChangeNotificationController().notifyTaskRemoved(mTaskId);
    }

    void setResizeMode(int resizeMode) {
        if (mResizeMode == resizeMode) {
            return;
        }
        mResizeMode = resizeMode;
        mAtmService.mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        mAtmService.mRootActivityContainer.resumeFocusedStacksTopActivities();
        updateTaskDescription();
    }

    boolean resize(Rect bounds, int resizeMode, boolean preserveWindow, boolean deferResume) {
        mAtmService.deferWindowLayout();

        try {
            final boolean forced = (resizeMode & RESIZE_MODE_FORCED) != 0;

            if (getParent() == null) {
                // Task doesn't exist in window manager yet (e.g. was restored from recents).
                // All we can do for now is update the bounds so it can be used when the task is
                // added to window manager.
                setBounds(bounds);
                if (!inFreeformWindowingMode()) {
                    // re-restore the task so it can have the proper stack association.
                    mAtmService.mStackSupervisor.restoreRecentTaskLocked(this, null, !ON_TOP);
                }
                return true;
            }

            if (!canResizeToBounds(bounds)) {
                throw new IllegalArgumentException("resizeTask: Can not resize task=" + this
                        + " to bounds=" + bounds + " resizeMode=" + mResizeMode);
            }

            // Do not move the task to another stack here.
            // This method assumes that the task is already placed in the right stack.
            // we do not mess with that decision and we only do the resize!

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "resizeTask_" + mTaskId);

            boolean updatedConfig = false;
            mTmpConfig.setTo(getResolvedOverrideConfiguration());
            if (setBounds(bounds) != BOUNDS_CHANGE_NONE) {
                updatedConfig = !mTmpConfig.equals(getResolvedOverrideConfiguration());
            }
            // This variable holds information whether the configuration didn't change in a
            // significant way and the activity was kept the way it was. If it's false, it means
            // the activity had to be relaunched due to configuration change.
            boolean kept = true;
            if (updatedConfig) {
                final ActivityRecord r = topRunningActivityLocked();
                if (r != null && !deferResume) {
                    kept = r.ensureActivityConfiguration(0 /* globalChanges */,
                            preserveWindow);
                    // Preserve other windows for resizing because if resizing happens when there
                    // is a dialog activity in the front, the activity that still shows some
                    // content to the user will become black and cause flickers. Note in most cases
                    // this won't cause tons of irrelevant windows being preserved because only
                    // activities in this task may experience a bounds change. Configs for other
                    // activities stay the same.
                    mAtmService.mRootActivityContainer.ensureActivitiesVisible(r, 0,
                            preserveWindow);
                    if (!kept) {
                        mAtmService.mRootActivityContainer.resumeFocusedStacksTopActivities();
                    }
                }
            }
            resize(kept, forced);

            saveLaunchingStateIfNeeded();

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return kept;
        } finally {
            mAtmService.continueWindowLayout();
        }
    }

    /** Convenience method to reparent a task to the top or bottom position of the stack. */
    boolean reparent(ActivityStack preferredStack, boolean toTop,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            String reason) {
        return reparent(preferredStack, toTop ? MAX_VALUE : 0, moveStackMode, animate, deferResume,
                true /* schedulePictureInPictureModeChange */, reason);
    }

    /**
     * Convenience method to reparent a task to the top or bottom position of the stack, with
     * an option to skip scheduling the picture-in-picture mode change.
     */
    boolean reparent(ActivityStack preferredStack, boolean toTop,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            boolean schedulePictureInPictureModeChange, String reason) {
        return reparent(preferredStack, toTop ? MAX_VALUE : 0, moveStackMode, animate,
                deferResume, schedulePictureInPictureModeChange, reason);
    }

    /** Convenience method to reparent a task to a specific position of the stack. */
    boolean reparent(ActivityStack preferredStack, int position,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            String reason) {
        return reparent(preferredStack, position, moveStackMode, animate, deferResume,
                true /* schedulePictureInPictureModeChange */, reason);
    }

    /**
     * Reparents the task into a preferred stack, creating it if necessary.
     *
     * @param preferredStack the target stack to move this task
     * @param position the position to place this task in the new stack
     * @param animate whether or not we should wait for the new window created as a part of the
     *            reparenting to be drawn and animated in
     * @param moveStackMode whether or not to move the stack to the front always, only if it was
     *            previously focused & in front, or never
     * @param deferResume whether or not to update the visibility of other tasks and stacks that may
     *            have changed as a result of this reparenting
     * @param schedulePictureInPictureModeChange specifies whether or not to schedule the PiP mode
     *            change. Callers may set this to false if they are explicitly scheduling PiP mode
     *            changes themselves, like during the PiP animation
     * @param reason the caller of this reparenting
     * @return whether the task was reparented
     */
    // TODO: Inspect all call sites and change to just changing windowing mode of the stack vs.
    // re-parenting the task. Can only be done when we are no longer using static stack Ids.
    boolean reparent(ActivityStack preferredStack, int position,
            @ReparentMoveStackMode int moveStackMode, boolean animate, boolean deferResume,
            boolean schedulePictureInPictureModeChange, String reason) {
        final ActivityStackSupervisor supervisor = mAtmService.mStackSupervisor;
        final RootActivityContainer root = mAtmService.mRootActivityContainer;
        final WindowManagerService windowManager = mAtmService.mWindowManager;
        final ActivityStack sourceStack = getStack();
        final ActivityStack toStack = supervisor.getReparentTargetStack(this, preferredStack,
                position == MAX_VALUE);
        if (toStack == sourceStack) {
            return false;
        }
        if (!canBeLaunchedOnDisplay(toStack.mDisplayId)) {
            return false;
        }

        final boolean toTopOfStack = position == MAX_VALUE;
        if (toTopOfStack && toStack.getResumedActivity() != null
                && toStack.topRunningActivityLocked() != null) {
            // Pause the resumed activity on the target stack while re-parenting task on top of it.
            toStack.startPausingLocked(false /* userLeaving */, false /* uiSleeping */,
                    null /* resuming */);
        }

        final int toStackWindowingMode = toStack.getWindowingMode();
        final ActivityRecord topActivity = getTopActivity();

        final boolean mightReplaceWindow = topActivity != null
                && replaceWindowsOnTaskMove(getWindowingMode(), toStackWindowingMode);
        if (mightReplaceWindow) {
            // We are about to relaunch the activity because its configuration changed due to
            // being maximized, i.e. size change. The activity will first remove the old window
            // and then add a new one. This call will tell window manager about this, so it can
            // preserve the old window until the new one is drawn. This prevents having a gap
            // between the removal and addition, in which no window is visible. We also want the
            // entrance of the new window to be properly animated.
            // Note here we always set the replacing window first, as the flags might be needed
            // during the relaunch. If we end up not doing any relaunch, we clear the flags later.
            windowManager.setWillReplaceWindow(topActivity.appToken, animate);
        }

        mAtmService.deferWindowLayout();
        boolean kept = true;
        try {
            final ActivityRecord r = topRunningActivityLocked();
            final boolean wasFocused = r != null && root.isTopDisplayFocusedStack(sourceStack)
                    && (topRunningActivityLocked() == r);
            final boolean wasResumed = r != null && sourceStack.getResumedActivity() == r;
            final boolean wasPaused = r != null && sourceStack.mPausingActivity == r;

            // In some cases the focused stack isn't the front stack. E.g. pinned stack.
            // Whenever we are moving the top activity from the front stack we want to make sure to
            // move the stack to the front.
            final boolean wasFront = r != null && sourceStack.isTopStackOnDisplay()
                    && (sourceStack.topRunningActivityLocked() == r);

            final boolean moveStackToFront = moveStackMode == REPARENT_MOVE_STACK_TO_FRONT
                    || (moveStackMode == REPARENT_KEEP_STACK_AT_FRONT && (wasFocused || wasFront));

            reparent(toStack, position, moveStackToFront, reason);

            if (schedulePictureInPictureModeChange) {
                // Notify of picture-in-picture mode changes
                supervisor.scheduleUpdatePictureInPictureModeIfNeeded(this, sourceStack);
            }

            // If the task had focus before (or we're requested to move focus), move focus to the
            // new stack by moving the stack to the front.
            if (r != null) {
                toStack.moveToFrontAndResumeStateIfNeeded(r, moveStackToFront, wasResumed,
                        wasPaused, reason);
            }
            if (!animate) {
                mAtmService.mStackSupervisor.mNoAnimActivities.add(topActivity);
            }

            // We might trigger a configuration change. Save the current task bounds for freezing.
            // TODO: Should this call be moved inside the resize method in WM?
            toStack.prepareFreezingTaskBounds();

            // Make sure the task has the appropriate bounds/size for the stack it is in.
            final boolean toStackSplitScreenPrimary =
                    toStackWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
            final Rect configBounds = getRequestedOverrideBounds();
            if ((toStackWindowingMode == WINDOWING_MODE_FULLSCREEN
                    || toStackWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
                    && !Objects.equals(configBounds, toStack.getRequestedOverrideBounds())) {
                kept = resize(toStack.getRequestedOverrideBounds(), RESIZE_MODE_SYSTEM,
                        !mightReplaceWindow, deferResume);
            } else if (toStackWindowingMode == WINDOWING_MODE_FREEFORM) {
                Rect bounds = getLaunchBounds();
                if (bounds == null) {
                    mAtmService.mStackSupervisor.getLaunchParamsController().layoutTask(this, null);
                    bounds = configBounds;
                }
                kept = resize(bounds, RESIZE_MODE_FORCED, !mightReplaceWindow, deferResume);
            } else if (toStackSplitScreenPrimary || toStackWindowingMode == WINDOWING_MODE_PINNED) {
                if (toStackSplitScreenPrimary && moveStackMode == REPARENT_KEEP_STACK_AT_FRONT) {
                    // Move recents to front so it is not behind home stack when going into docked
                    // mode
                    mAtmService.mStackSupervisor.moveRecentsStackToFront(reason);
                }
                kept = resize(toStack.getRequestedOverrideBounds(), RESIZE_MODE_SYSTEM,
                        !mightReplaceWindow, deferResume);
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        if (mightReplaceWindow) {
            // If we didn't actual do a relaunch (indicated by kept==true meaning we kept the old
            // window), we need to clear the replace window settings. Otherwise, we schedule a
            // timeout to remove the old window if the replacing window is not coming in time.
            windowManager.scheduleClearWillReplaceWindows(topActivity.appToken, !kept);
        }

        if (!deferResume) {
            // The task might have already been running and its visibility needs to be synchronized
            // with the visibility of the stack / windows.
            root.ensureActivitiesVisible(null, 0, !mightReplaceWindow);
            root.resumeFocusedStacksTopActivities();
        }

        // TODO: Handle incorrect request to move before the actual move, not after.
        supervisor.handleNonResizableTaskIfNeeded(this, preferredStack.getWindowingMode(),
                DEFAULT_DISPLAY, toStack);

        return (preferredStack == toStack);
    }

    /**
     * @return {@code true} if the windows of tasks being moved to the target stack from the
     * source stack should be replaced, meaning that window manager will keep the old window
     * around until the new is ready.
     */
    private static boolean replaceWindowsOnTaskMove(
            int sourceWindowingMode, int targetWindowingMode) {
        return sourceWindowingMode == WINDOWING_MODE_FREEFORM
                || targetWindowingMode == WINDOWING_MODE_FREEFORM;
    }

    /**
     * DO NOT HOLD THE ACTIVITY MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    TaskSnapshot getSnapshot(boolean reducedResolution, boolean restoreFromDisk) {

        // TODO: Move this to {@link TaskWindowContainerController} once recent tasks are more
        // synchronized between AM and WM.
        return mAtmService.mWindowManager.getTaskSnapshot(mTaskId, mUserId, reducedResolution,
                restoreFromDisk);
    }

    void touchActiveTime() {
        lastActiveTime = SystemClock.elapsedRealtime();
    }

    long getInactiveDuration() {
        return SystemClock.elapsedRealtime() - lastActiveTime;
    }

    /** Sets the original intent, and the calling uid and package. */
    void setIntent(ActivityRecord r) {
        mCallingUid = r.launchedFromUid;
        mCallingPackage = r.launchedFromPackage;
        setIntent(r.intent, r.info);
        setLockTaskAuth(r);
    }

    /** Sets the original intent, _without_ updating the calling uid or package. */
    private void setIntent(Intent _intent, ActivityInfo info) {
        if (intent == null) {
            mNeverRelinquishIdentity =
                    (info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0;
        } else if (mNeverRelinquishIdentity) {
            return;
        }

        affinity = info.taskAffinity;
        if (intent == null) {
            // If this task already has an intent associated with it, don't set the root
            // affinity -- we don't want it changing after initially set, but the initially
            // set value may be null.
            rootAffinity = affinity;
        }
        effectiveUid = info.applicationInfo.uid;
        stringName = null;

        if (info.targetActivity == null) {
            if (_intent != null) {
                // If this Intent has a selector, we want to clear it for the
                // recent task since it is not relevant if the user later wants
                // to re-launch the app.
                if (_intent.getSelector() != null || _intent.getSourceBounds() != null) {
                    _intent = new Intent(_intent);
                    _intent.setSelector(null);
                    _intent.setSourceBounds(null);
                }
            }
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Setting Intent of " + this + " to " + _intent);
            intent = _intent;
            realActivity = _intent != null ? _intent.getComponent() : null;
            origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(
                    info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setSelector(null);
                targetIntent.setSourceBounds(null);
                if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                        "Setting Intent of " + this + " to target " + targetIntent);
                intent = targetIntent;
                realActivity = targetComponent;
                origActivity = _intent.getComponent();
            } else {
                intent = null;
                realActivity = targetComponent;
                origActivity = new ComponentName(info.packageName, info.name);
            }
        }

        final int intentFlags = intent == null ? 0 : intent.getFlags();
        if ((intentFlags & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            // Once we are set to an Intent with this flag, we count this
            // task as having a true root activity.
            rootWasReset = true;
        }
        mUserId = UserHandle.getUserId(info.applicationInfo.uid);
        mUserSetupComplete = Settings.Secure.getIntForUser(
                mAtmService.mContext.getContentResolver(), USER_SETUP_COMPLETE, 0, mUserId) != 0;
        if ((info.flags & ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS) != 0) {
            // If the activity itself has requested auto-remove, then just always do it.
            autoRemoveRecents = true;
        } else if ((intentFlags & (FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_RETAIN_IN_RECENTS))
                == FLAG_ACTIVITY_NEW_DOCUMENT) {
            // If the caller has not asked for the document to be retained, then we may
            // want to turn on auto-remove, depending on whether the target has set its
            // own document launch mode.
            if (info.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NONE) {
                autoRemoveRecents = false;
            } else {
                autoRemoveRecents = true;
            }
        } else {
            autoRemoveRecents = false;
        }
        if (mResizeMode != info.resizeMode) {
            mResizeMode = info.resizeMode;
            updateTaskDescription();
        }
        mSupportsPictureInPicture = info.supportsPictureInPicture();
    }

    /** Sets the original minimal width and height. */
    private void setMinDimensions(ActivityInfo info) {
        if (info != null && info.windowLayout != null) {
            mMinWidth = info.windowLayout.minWidth;
            mMinHeight = info.windowLayout.minHeight;
        } else {
            mMinWidth = INVALID_MIN_SIZE;
            mMinHeight = INVALID_MIN_SIZE;
        }
    }

    /**
     * Return true if the input activity has the same intent filter as the intent this task
     * record is based on (normally the root activity intent).
     */
    boolean isSameIntentFilter(ActivityRecord r) {
        final Intent intent = new Intent(r.intent);
        // Make sure the component are the same if the input activity has the same real activity
        // as the one in the task because either one of them could be the alias activity.
        if (Objects.equals(realActivity, r.mActivityComponent) && this.intent != null) {
            intent.setComponent(this.intent.getComponent());
        }
        return intent.filterEquals(this.intent);
    }

    boolean returnsToHomeStack() {
        final int returnHomeFlags = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME;
        return intent != null && (intent.getFlags() & returnHomeFlags) == returnHomeFlags;
    }

    void setPrevAffiliate(Task prevAffiliate) {
        mPrevAffiliate = prevAffiliate;
        mPrevAffiliateTaskId = prevAffiliate == null ? INVALID_TASK_ID : prevAffiliate.mTaskId;
    }

    void setNextAffiliate(Task nextAffiliate) {
        mNextAffiliate = nextAffiliate;
        mNextAffiliateTaskId = nextAffiliate == null ? INVALID_TASK_ID : nextAffiliate.mTaskId;
    }

    ActivityStack getStack() {
        return mStack;
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        final ActivityStack oldStack = ((ActivityStack) oldParent);
        final ActivityStack newStack = ((ActivityStack) newParent);

        mStack = newStack;

        super.onParentChanged(newParent, oldParent);

        if (oldStack != null) {
            for (int i = getChildCount() - 1; i >= 0; --i) {
                final ActivityRecord activity = getChildAt(i);
                oldStack.onActivityRemovedFromStack(activity);
            }

            if (oldStack.inPinnedWindowingMode()
                    && (newStack == null || !newStack.inPinnedWindowingMode())) {
                // Notify if a task from the pinned stack is being removed
                // (or moved depending on the mode).
                mAtmService.getTaskChangeNotificationController().notifyActivityUnpinned();
            }
        }

        if (newStack != null) {
            for (int i = getChildCount() - 1; i >= 0; --i) {
                final ActivityRecord activity = getChildAt(i);
                newStack.onActivityAddedToStack(activity);
            }

            // TODO: Ensure that this is actually necessary here
            // Notify the voice session if required
            if (voiceSession != null) {
                try {
                    voiceSession.taskStarted(intent, mTaskId);
                } catch (RemoteException e) {
                }
            }
        }

        // First time we are adding the task to the system.
        if (oldParent == null && newParent != null) {

            // TODO: Super random place to be doing this, but aligns with what used to be done
            // before we unified Task level. Look into if this can be done in a better place.
            updateOverrideConfigurationFromLaunchBounds();
        }

        // Task is being removed.
        if (oldParent != null && newParent == null) {
            cleanUpResourcesForDestroy();
        }


        // Update task bounds if needed.
        adjustBoundsForDisplayChangeIfNeeded(getDisplayContent());

        if (getWindowConfiguration().windowsAreScaleable()) {
            // We force windows out of SCALING_MODE_FREEZE so that we can continue to animate them
            // while a resize is pending.
            forceWindowsScaleable(true /* force */);
        } else {
            forceWindowsScaleable(false /* force */);
        }

        mAtmService.mRootActivityContainer.updateUIDsPresentOnDisplay();
    }

    void updateTaskMovement(boolean toFront) {
        if (isPersistable) {
            mLastTimeMoved = System.currentTimeMillis();
            // Sign is used to keep tasks sorted when persisted. Tasks sent to the bottom most
            // recently will be most negative, tasks sent to the bottom before that will be less
            // negative. Similarly for recent tasks moved to the top which will be most positive.
            if (!toFront) {
                mLastTimeMoved *= -1;
            }
        }
        mAtmService.mRootActivityContainer.invalidateTaskLayers();
    }

    /**
     * @return Id of current stack, {@link ActivityTaskManager#INVALID_STACK_ID} if no stack is set.
     */
    int getStackId() {
        return mStack != null ? mStack.mStackId : INVALID_STACK_ID;
    }

    // Close up recents linked list.
    private void closeRecentsChain() {
        if (mPrevAffiliate != null) {
            mPrevAffiliate.setNextAffiliate(mNextAffiliate);
        }
        if (mNextAffiliate != null) {
            mNextAffiliate.setPrevAffiliate(mPrevAffiliate);
        }
        setPrevAffiliate(null);
        setNextAffiliate(null);
    }

    void removedFromRecents() {
        closeRecentsChain();
        if (inRecents) {
            inRecents = false;
            mAtmService.notifyTaskPersisterLocked(this, false);
        }

        clearRootProcess();

        mAtmService.mWindowManager.mTaskSnapshotController.notifyTaskRemovedFromRecents(
                mTaskId, mUserId);
    }

    void setTaskToAffiliateWith(Task taskToAffiliateWith) {
        closeRecentsChain();
        mAffiliatedTaskId = taskToAffiliateWith.mAffiliatedTaskId;
        mAffiliatedTaskColor = taskToAffiliateWith.mAffiliatedTaskColor;
        // Find the end
        while (taskToAffiliateWith.mNextAffiliate != null) {
            final Task nextRecents = taskToAffiliateWith.mNextAffiliate;
            if (nextRecents.mAffiliatedTaskId != mAffiliatedTaskId) {
                Slog.e(TAG, "setTaskToAffiliateWith: nextRecents=" + nextRecents + " affilTaskId="
                        + nextRecents.mAffiliatedTaskId + " should be " + mAffiliatedTaskId);
                if (nextRecents.mPrevAffiliate == taskToAffiliateWith) {
                    nextRecents.setPrevAffiliate(null);
                }
                taskToAffiliateWith.setNextAffiliate(null);
                break;
            }
            taskToAffiliateWith = nextRecents;
        }
        taskToAffiliateWith.setNextAffiliate(this);
        setPrevAffiliate(taskToAffiliateWith);
        setNextAffiliate(null);
    }

    /** Returns the intent for the root activity for this task */
    Intent getBaseIntent() {
        return intent != null ? intent : affinityIntent;
    }

    /** Returns the first non-finishing activity from the bottom. */
    ActivityRecord getRootActivity() {
        final int rootActivityIndex = findRootIndex(false /* effectiveRoot */);
        if (rootActivityIndex == -1) {
            // There are no non-finishing activities in the task.
            return null;
        }
        return getChildAt(rootActivityIndex);
    }

    ActivityRecord getTopActivity() {
        return getTopActivity(true /* includeOverlays */);
    }

    ActivityRecord getTopActivity(boolean includeOverlays) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityRecord r = getChildAt(i);
            if (r.finishing || (!includeOverlays && r.mTaskOverlay)) {
                continue;
            }
            return r;
        }
        return null;
    }

    ActivityRecord topRunningActivityLocked() {
        if (mStack != null) {
            for (int activityNdx = getChildCount() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = getChildAt(activityNdx);
                if (!r.finishing && r.okToShowLocked()) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * Return true if any activities in this task belongs to input uid.
     */
    boolean containsAppUid(int uid) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityRecord r = getChildAt(i);
            if (r.getUid() == uid) {
                return true;
            }
        }
        return false;
    }

    void getAllRunningVisibleActivitiesLocked(ArrayList<ActivityRecord> outActivities) {
        if (mStack != null) {
            for (int activityNdx = getChildCount() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = getChildAt(activityNdx);
                if (!r.finishing && r.okToShowLocked() && r.visibleIgnoringKeyguard) {
                    outActivities.add(r);
                }
            }
        }
    }

    ActivityRecord topRunningActivityWithStartingWindowLocked() {
        if (mStack != null) {
            for (int activityNdx = getChildCount() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = getChildAt(activityNdx);
                if (r.mStartingWindowState != STARTING_WINDOW_SHOWN
                        || r.finishing || !r.okToShowLocked()) {
                    continue;
                }
                return r;
            }
        }
        return null;
    }

    /**
     * Return the number of running activities, and the number of non-finishing/initializing
     * activities in the provided {@param reportOut} respectively.
     */
    void getNumRunningActivities(TaskActivitiesReport reportOut) {
        reportOut.reset();
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityRecord r = getChildAt(i);
            if (r.finishing) {
                continue;
            }

            reportOut.base = r;

            // Increment the total number of non-finishing activities
            reportOut.numActivities++;

            if (reportOut.top == null || (reportOut.top.isState(ActivityState.INITIALIZING))) {
                reportOut.top = r;
                // Reset the number of running activities until we hit the first non-initializing
                // activity
                reportOut.numRunning = 0;
            }
            if (r.attachedToProcess()) {
                // Increment the number of actually running activities
                reportOut.numRunning++;
            }
        }
    }

    boolean okToShowLocked() {
        // NOTE: If {@link Task#topRunningActivity} return is not null then it is
        // okay to show the activity when locked.
        return mAtmService.mStackSupervisor.isCurrentProfileLocked(mUserId)
                || topRunningActivityLocked() != null;
    }

    /**
     * Reorder the history stack so that the passed activity is brought to the front.
     */
    final void moveActivityToFrontLocked(ActivityRecord newTop) {
        if (DEBUG_ADD_REMOVE) Slog.i(TAG_ADD_REMOVE, "Removing and adding activity "
                + newTop + " to stack at top callers=" + Debug.getCallers(4));

        positionChildAtTop(newTop);
        updateEffectiveIntent();
    }

    @Override
    public int getActivityType() {
        final int applicationType = super.getActivityType();
        if (applicationType != ACTIVITY_TYPE_UNDEFINED || !hasChild()) {
            return applicationType;
        }
        return getChildAt(0).getActivityType();
    }

    @Override
    void addChild(ActivityRecord r, int index) {
        // If this task had any child before we added this one.
        boolean hadChild = hasChild();

        index = getAdjustedAddPosition(r, index);
        super.addChild(r, index);

        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "addChild: %s at top.", this);
        r.inHistory = true;

        if (r.occludesParent()) {
            numFullscreen++;
        }
        // Only set this based on the first activity
        if (!hadChild) {
            if (r.getActivityType() == ACTIVITY_TYPE_UNDEFINED) {
                // Normally non-standard activity type for the activity record will be set when the
                // object is created, however we delay setting the standard application type until
                // this point so that the task can set the type for additional activities added in
                // the else condition below.
                r.setActivityType(ACTIVITY_TYPE_STANDARD);
            }
            setActivityType(r.getActivityType());
            isPersistable = r.isPersistable();
            mCallingUid = r.launchedFromUid;
            mCallingPackage = r.launchedFromPackage;
            // Clamp to [1, max].
            maxRecents = Math.min(Math.max(r.info.maxRecents, 1),
                    ActivityTaskManager.getMaxAppRecentsLimitStatic());
        } else {
            // Otherwise make all added activities match this one.
            r.setActivityType(getActivityType());
        }

        updateEffectiveIntent();
        if (r.isPersistable()) {
            mAtmService.notifyTaskPersisterLocked(this, false);
        }

        // Make sure the list of display UID whitelists is updated
        // now that this record is in a new task.
        mAtmService.mRootActivityContainer.updateUIDsPresentOnDisplay();
    }

    void addChild(ActivityRecord r) {
        addChild(r, Integer.MAX_VALUE /* add on top */);
    }

    @Override
    void removeChild(ActivityRecord r) {
        if (!mChildren.contains(r)) {
            Slog.e(TAG, "removeChild: r=" + r + " not found in t=" + this);
            return;
        }

        super.removeChild(r);
        if (r.occludesParent()) {
            numFullscreen--;
        }
        if (r.isPersistable()) {
            mAtmService.notifyTaskPersisterLocked(this, false);
        }

        if (inPinnedWindowingMode()) {
            // We normally notify listeners of task stack changes on pause, however pinned stack
            // activities are normally in the paused state so no notification will be sent there
            // before the activity is removed. We send it here so instead.
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
        }

        final String reason = "removeChild";
        if (hasChild()) {
            updateEffectiveIntent();

            // The following block can be executed multiple times if there is more than one overlay.
            // {@link ActivityStackSupervisor#removeTaskByIdLocked} handles this by reverse lookup
            // of the task by id and exiting early if not found.
            if (onlyHasTaskOverlayActivities(false /* excludingFinishing */)) {
                // When destroying a task, tell the supervisor to remove it so that any activity it
                // has can be cleaned up correctly. This is currently the only place where we remove
                // a task with the DESTROYING mode, so instead of passing the onlyHasTaskOverlays
                // state into removeChild(), we just clear the task here before the other residual
                // work.
                // TODO: If the callers to removeChild() changes such that we have multiple places
                //       where we are destroying the task, move this back into removeChild()
                mAtmService.mStackSupervisor.removeTaskByIdLocked(mTaskId, false /* killProcess */,
                        !REMOVE_FROM_RECENTS, reason);
            }
        } else if (!mReuseTask) {
            // Remove entire task if it doesn't have any activity left and it isn't marked for reuse
            mStack.removeChild(this, reason);
            EventLog.writeEvent(WM_TASK_REMOVED, mTaskId,
                    "removeChild: last r=" + r + " in t=" + this);
            removeIfPossible();
        }
    }

    /**
     * @return whether or not there are ONLY task overlay activities in the stack.
     *         If {@param excludeFinishing} is set, then ignore finishing activities in the check.
     *         If there are no task overlay activities, this call returns false.
     */
    boolean onlyHasTaskOverlayActivities(boolean excludeFinishing) {
        int count = 0;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final ActivityRecord r = getChildAt(i);
            if (excludeFinishing && r.finishing) {
                continue;
            }
            if (!r.mTaskOverlay) {
                return false;
            }
            count++;
        }
        return count > 0;
    }

    boolean autoRemoveFromRecents() {
        // We will automatically remove the task either if it has explicitly asked for
        // this, or it is empty and has never contained an activity that got shown to
        // the user.
        return autoRemoveRecents || (!hasChild() && !hasBeenVisible);
    }

    /**
     * Completely remove all activities associated with an existing
     * task starting at a specified index.
     */
    private void performClearTaskAtIndexLocked(int activityNdx, String reason) {
        int numActivities = getChildCount();
        for ( ; activityNdx < numActivities; ++activityNdx) {
            final ActivityRecord r = getChildAt(activityNdx);
            if (r.finishing) {
                continue;
            }
            if (mStack == null) {
                // Task was restored from persistent storage.
                r.takeFromHistory();
                removeChild(r);
                --activityNdx;
                --numActivities;
            } else if (r.finishIfPossible(Activity.RESULT_CANCELED, null /* resultData */, reason,
                    false /* oomAdj */)
                    == FINISH_RESULT_REMOVED) {
                --activityNdx;
                --numActivities;
            }
        }
    }

    /**
     * Completely remove all activities associated with an existing task.
     */
    void performClearTaskLocked() {
        mReuseTask = true;
        performClearTaskAtIndexLocked(0, "clear-task-all");
        mReuseTask = false;
    }

    ActivityRecord performClearTaskForReuseLocked(ActivityRecord newR, int launchFlags) {
        mReuseTask = true;
        final ActivityRecord result = performClearTaskLocked(newR, launchFlags);
        mReuseTask = false;
        return result;
    }

    /**
     * Perform clear operation as requested by
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP}: search from the top of the
     * stack to the given task, then look for
     * an instance of that activity in the stack and, if found, finish all
     * activities on top of it and return the instance.
     *
     * @param newR Description of the new activity being started.
     * @return Returns the old activity that should be continued to be used,
     * or {@code null} if none was found.
     */
    final ActivityRecord performClearTaskLocked(ActivityRecord newR, int launchFlags) {
        int numActivities = getChildCount();
        for (int activityNdx = numActivities - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord r = getChildAt(activityNdx);
            if (r.finishing) {
                continue;
            }
            if (r.mActivityComponent.equals(newR.mActivityComponent)) {
                // Here it is!  Now finish everything in front...
                final ActivityRecord ret = r;

                for (++activityNdx; activityNdx < numActivities; ++activityNdx) {
                    r = getChildAt(activityNdx);
                    if (r.finishing) {
                        continue;
                    }
                    ActivityOptions opts = r.takeOptionsLocked(false /* fromClient */);
                    if (opts != null) {
                        ret.updateOptionsLocked(opts);
                    }
                    if (r.finishIfPossible("clear-task-stack", false /* oomAdj */)
                            == FINISH_RESULT_REMOVED) {
                        --activityNdx;
                        --numActivities;
                    }
                }

                // Finally, if this is a normal launch mode (that is, not
                // expecting onNewIntent()), then we will finish the current
                // instance of the activity so a new fresh one can be started.
                if (ret.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                        && (launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) == 0
                        && !ActivityStarter.isDocumentLaunchesIntoExisting(launchFlags)) {
                    if (!ret.finishing) {
                        ret.finishIfPossible("clear-task-top", false /* oomAdj */);
                        return null;
                    }
                }

                return ret;
            }
        }

        return null;
    }

    void removeTaskActivitiesLocked(String reason) {
        // Just remove the entire task.
        performClearTaskAtIndexLocked(0, reason);
    }

    String lockTaskAuthToString() {
        switch (mLockTaskAuth) {
            case LOCK_TASK_AUTH_DONT_LOCK: return "LOCK_TASK_AUTH_DONT_LOCK";
            case LOCK_TASK_AUTH_PINNABLE: return "LOCK_TASK_AUTH_PINNABLE";
            case LOCK_TASK_AUTH_LAUNCHABLE: return "LOCK_TASK_AUTH_LAUNCHABLE";
            case LOCK_TASK_AUTH_WHITELISTED: return "LOCK_TASK_AUTH_WHITELISTED";
            case LOCK_TASK_AUTH_LAUNCHABLE_PRIV: return "LOCK_TASK_AUTH_LAUNCHABLE_PRIV";
            default: return "unknown=" + mLockTaskAuth;
        }
    }

    void setLockTaskAuth() {
        setLockTaskAuth(getRootActivity());
    }

    private void setLockTaskAuth(@Nullable ActivityRecord r) {
        if (r == null) {
            mLockTaskAuth = LOCK_TASK_AUTH_PINNABLE;
            return;
        }

        final String pkg = (realActivity != null) ? realActivity.getPackageName() : null;
        final LockTaskController lockTaskController = mAtmService.getLockTaskController();
        switch (r.lockTaskLaunchMode) {
            case LOCK_TASK_LAUNCH_MODE_DEFAULT:
                mLockTaskAuth = lockTaskController.isPackageWhitelisted(mUserId, pkg)
                        ? LOCK_TASK_AUTH_WHITELISTED : LOCK_TASK_AUTH_PINNABLE;
                break;

            case LOCK_TASK_LAUNCH_MODE_NEVER:
                mLockTaskAuth = LOCK_TASK_AUTH_DONT_LOCK;
                break;

            case LOCK_TASK_LAUNCH_MODE_ALWAYS:
                mLockTaskAuth = LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
                break;

            case LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED:
                mLockTaskAuth = lockTaskController.isPackageWhitelisted(mUserId, pkg)
                        ? LOCK_TASK_AUTH_LAUNCHABLE : LOCK_TASK_AUTH_PINNABLE;
                break;
        }
        if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "setLockTaskAuth: task=" + this
                + " mLockTaskAuth=" + lockTaskAuthToString());
    }

    @Override
    public boolean supportsSplitScreenWindowingMode() {
        // A task can not be docked even if it is considered resizeable because it only supports
        // picture-in-picture mode but has a non-resizeable resizeMode
        return super.supportsSplitScreenWindowingMode()
                // TODO(task-group): Probably makes sense to move this and associated code into
                // WindowContainer so it affects every node.
                && mAtmService.mSupportsSplitScreenMultiWindow
                && (mAtmService.mForceResizableActivities
                        || (isResizeable(false /* checkSupportsPip */)
                                && !ActivityInfo.isPreserveOrientationMode(mResizeMode)));
    }

    /**
     * Check whether this task can be launched on the specified display.
     *
     * @param displayId Target display id.
     * @return {@code true} if either it is the default display or this activity can be put on a
     *         secondary display.
     */
    boolean canBeLaunchedOnDisplay(int displayId) {
        return mAtmService.mStackSupervisor.canPlaceEntityOnDisplay(displayId,
                -1 /* don't check PID */, -1 /* don't check UID */, null /* activityInfo */);
    }

    /**
     * Check that a given bounds matches the application requested orientation.
     *
     * @param bounds The bounds to be tested.
     * @return True if the requested bounds are okay for a resizing request.
     */
    private boolean canResizeToBounds(Rect bounds) {
        if (bounds == null || !inFreeformWindowingMode()) {
            // Note: If not on the freeform workspace, we ignore the bounds.
            return true;
        }
        final boolean landscape = bounds.width() > bounds.height();
        final Rect configBounds = getRequestedOverrideBounds();
        if (mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION) {
            return configBounds.isEmpty()
                    || landscape == (configBounds.width() > configBounds.height());
        }
        return (mResizeMode != RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY || !landscape)
                && (mResizeMode != RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY || landscape);
    }

    /**
     * @return {@code true} if the task is being cleared for the purposes of being reused.
     */
    boolean isClearingToReuseTask() {
        return mReuseTask;
    }

    /**
     * Find the activity in the history stack within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    final ActivityRecord findActivityInHistoryLocked(ActivityRecord r) {
        final ComponentName realActivity = r.mActivityComponent;
        for (int activityNdx = getChildCount() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord candidate = getChildAt(activityNdx);
            if (candidate.finishing) {
                continue;
            }
            if (candidate.mActivityComponent.equals(realActivity)) {
                return candidate;
            }
        }
        return null;
    }

    /** Updates the last task description values. */
    void updateTaskDescription() {
        // TODO(AM refactor): Cleanup to use findRootIndex()
        // Traverse upwards looking for any break between main task activities and
        // utility activities.
        int activityNdx;
        final int numActivities = getChildCount();
        final boolean relinquish = numActivities != 0
                && (getChildAt(0).info.flags & FLAG_RELINQUISH_TASK_IDENTITY) != 0;
        for (activityNdx = Math.min(numActivities, 1); activityNdx < numActivities; ++activityNdx) {
            final ActivityRecord r = getChildAt(activityNdx);
            if (relinquish && (r.info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0) {
                // This will be the top activity for determining taskDescription. Pre-inc to
                // overcome initial decrement below.
                ++activityNdx;
                break;
            }
            if (r.intent != null
                    && (r.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                break;
            }
        }
        if (activityNdx > 0) {
            // Traverse downwards starting below break looking for set label, icon.
            // Note that if there are activities in the task but none of them set the
            // recent activity values, then we do not fall back to the last set
            // values in the Task.
            String label = null;
            String iconFilename = null;
            int iconResource = -1;
            int colorPrimary = 0;
            int colorBackground = 0;
            int statusBarColor = 0;
            int navigationBarColor = 0;
            boolean statusBarContrastWhenTransparent = false;
            boolean navigationBarContrastWhenTransparent = false;
            boolean topActivity = true;
            for (--activityNdx; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = getChildAt(activityNdx);
                if (r.mTaskOverlay) {
                    continue;
                }
                if (r.taskDescription != null) {
                    if (label == null) {
                        label = r.taskDescription.getLabel();
                    }
                    if (iconResource == -1) {
                        iconResource = r.taskDescription.getIconResource();
                    }
                    if (iconFilename == null) {
                        iconFilename = r.taskDescription.getIconFilename();
                    }
                    if (colorPrimary == 0) {
                        colorPrimary = r.taskDescription.getPrimaryColor();
                    }
                    if (topActivity) {
                        colorBackground = r.taskDescription.getBackgroundColor();
                        statusBarColor = r.taskDescription.getStatusBarColor();
                        navigationBarColor = r.taskDescription.getNavigationBarColor();
                        statusBarContrastWhenTransparent =
                                r.taskDescription.getEnsureStatusBarContrastWhenTransparent();
                        navigationBarContrastWhenTransparent =
                                r.taskDescription.getEnsureNavigationBarContrastWhenTransparent();
                    }
                }
                topActivity = false;
            }
            final TaskDescription taskDescription = new TaskDescription(label, null, iconResource,
                    iconFilename, colorPrimary, colorBackground, statusBarColor, navigationBarColor,
                    statusBarContrastWhenTransparent, navigationBarContrastWhenTransparent,
                    mResizeMode, mMinWidth, mMinHeight);
            setTaskDescription(taskDescription);
            // Update the task affiliation color if we are the parent of the group
            if (mTaskId == mAffiliatedTaskId) {
                mAffiliatedTaskColor = taskDescription.getPrimaryColor();
            }
            mAtmService.getTaskChangeNotificationController().notifyTaskDescriptionChanged(
                    getTaskInfo());
        }
    }

    /**
     * Find the index of the root activity in the task. It will be the first activity from the
     * bottom that is not finishing.
     *
     * @param effectiveRoot Flag indicating whether 'effective root' should be returned - an
     *                      activity that defines the task identity (its base intent). It's the
     *                      first one that does not have
     *                      {@link ActivityInfo#FLAG_RELINQUISH_TASK_IDENTITY}.
     * @return index of the 'root' or 'effective' root in the list of activities, -1 if no eligible
     *         activity was found.
     */
    int findRootIndex(boolean effectiveRoot) {
        int effectiveNdx = -1;
        final int topActivityNdx = getChildCount() - 1;
        for (int activityNdx = 0; activityNdx <= topActivityNdx; ++activityNdx) {
            final ActivityRecord r = getChildAt(activityNdx);
            if (r.finishing) {
                continue;
            }
            effectiveNdx = activityNdx;
            if (!effectiveRoot || (r.info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0) {
                break;
            }
        }
        return effectiveNdx;
    }

    // TODO (AM refactor): Invoke automatically when there is a change in children
    @VisibleForTesting
    void updateEffectiveIntent() {
        int effectiveRootIndex = findRootIndex(true /* effectiveRoot */);
        if (effectiveRootIndex == -1) {
            // All activities in the task are either finishing or relinquish task identity.
            // But we still want to update the intent, so let's use the bottom activity.
            effectiveRootIndex = 0;
        }
        final ActivityRecord r = getChildAt(effectiveRootIndex);
        setIntent(r);

        // Update the task description when the activities change
        updateTaskDescription();
    }

    void adjustForMinimalTaskDimensions(Rect bounds, Rect previousBounds) {
        final Rect parentBounds = getParent() != null ? getParent().getBounds() : null;
        if (bounds == null
                || (bounds.isEmpty() && (parentBounds == null || parentBounds.isEmpty()))) {
            return;
        }
        int minWidth = mMinWidth;
        int minHeight = mMinHeight;
        // If the task has no requested minimal size, we'd like to enforce a minimal size
        // so that the user can not render the task too small to manipulate. We don't need
        // to do this for the pinned stack as the bounds are controlled by the system.
        if (!inPinnedWindowingMode() && mStack != null) {
            final int defaultMinSizeDp =
                    mAtmService.mRootActivityContainer.mDefaultMinSizeOfResizeableTaskDp;
            final ActivityDisplay display =
                    mAtmService.mRootActivityContainer.getActivityDisplay(mStack.mDisplayId);
            final float density =
                    (float) display.getConfiguration().densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            final int defaultMinSize = (int) (defaultMinSizeDp * density);

            if (minWidth == INVALID_MIN_SIZE) {
                minWidth = defaultMinSize;
            }
            if (minHeight == INVALID_MIN_SIZE) {
                minHeight = defaultMinSize;
            }
        }
        if (bounds.isEmpty()) {
            // If inheriting parent bounds, check if parent bounds adhere to minimum size. If they
            // do, we can just skip.
            if (parentBounds.width() >= minWidth && parentBounds.height() >= minHeight) {
                return;
            }
            bounds.set(parentBounds);
        }
        final boolean adjustWidth = minWidth > bounds.width();
        final boolean adjustHeight = minHeight > bounds.height();
        if (!(adjustWidth || adjustHeight)) {
            return;
        }

        if (adjustWidth) {
            if (!previousBounds.isEmpty() && bounds.right == previousBounds.right) {
                bounds.left = bounds.right - minWidth;
            } else {
                // Either left bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping left.
                bounds.right = bounds.left + minWidth;
            }
        }
        if (adjustHeight) {
            if (!previousBounds.isEmpty() && bounds.bottom == previousBounds.bottom) {
                bounds.top = bounds.bottom - minHeight;
            } else {
                // Either top bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping top.
                bounds.bottom = bounds.top + minHeight;
            }
        }
    }

    void setLastNonFullscreenBounds(Rect bounds) {
        if (mLastNonFullscreenBounds == null) {
            mLastNonFullscreenBounds = new Rect(bounds);
        } else {
            mLastNonFullscreenBounds.set(bounds);
        }
    }

    /**
     * This should be called when an child activity changes state. This should only
     * be called from
     * {@link ActivityRecord#setState(ActivityState, String)} .
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, ActivityState state, String reason) {
        final ActivityStack parent = getStack();

        if (parent != null) {
            parent.onActivityStateChanged(record, state, reason);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        // Check if the new configuration supports persistent bounds (eg. is Freeform) and if so
        // restore the last recorded non-fullscreen bounds.
        final boolean prevPersistTaskBounds = getWindowConfiguration().persistTaskBounds();
        final boolean nextPersistTaskBounds =
                getRequestedOverrideConfiguration().windowConfiguration.persistTaskBounds()
                || newParentConfig.windowConfiguration.persistTaskBounds();
        if (!prevPersistTaskBounds && nextPersistTaskBounds
                && mLastNonFullscreenBounds != null && !mLastNonFullscreenBounds.isEmpty()) {
            // Bypass onRequestedOverrideConfigurationChanged here to avoid infinite loop.
            getRequestedOverrideConfiguration().windowConfiguration
                    .setBounds(mLastNonFullscreenBounds);
        }

        final boolean wasInMultiWindowMode = inMultiWindowMode();
        super.onConfigurationChanged(newParentConfig);
        if (wasInMultiWindowMode != inMultiWindowMode()) {
            mAtmService.mStackSupervisor.scheduleUpdateMultiWindowMode(this);
        }

        // If the configuration supports persistent bounds (eg. Freeform), keep track of the
        // current (non-fullscreen) bounds for persistence.
        if (getWindowConfiguration().persistTaskBounds()) {
            final Rect currentBounds = getRequestedOverrideBounds();
            if (!currentBounds.isEmpty()) {
                setLastNonFullscreenBounds(currentBounds);
            }
        }
        // TODO: Should also take care of Pip mode changes here.

        saveLaunchingStateIfNeeded();
    }

    /**
     * Saves launching state if necessary so that we can launch the activity to its latest state.
     * It only saves state if this task has been shown to user and it's in fullscreen or freeform
     * mode on freeform displays.
     */
    void saveLaunchingStateIfNeeded() {
        if (!hasBeenVisible) {
            // Not ever visible to user.
            return;
        }

        final int windowingMode = getWindowingMode();
        if (windowingMode != WINDOWING_MODE_FULLSCREEN
                && windowingMode != WINDOWING_MODE_FREEFORM) {
            return;
        }

        // Don't persist state if display isn't in freeform mode. Then the task will be launched
        // back to its last state in a freeform display when it's launched in a freeform display
        // next time.
        if (getWindowConfiguration().getDisplayWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return;
        }

        // Saves the new state so that we can launch the activity at the same location.
        mAtmService.mStackSupervisor.mLaunchParamsPersister.saveTask(this);
    }

    /**
     * Adjust bounds to stay within stack bounds.
     *
     * Since bounds might be outside of stack bounds, this method tries to move the bounds in a way
     * that keep them unchanged, but be contained within the stack bounds.
     *
     * @param bounds Bounds to be adjusted.
     * @param stackBounds Bounds within which the other bounds should remain.
     * @param overlapPxX The amount of px required to be visible in the X dimension.
     * @param overlapPxY The amount of px required to be visible in the Y dimension.
     */
    private static void fitWithinBounds(Rect bounds, Rect stackBounds, int overlapPxX,
            int overlapPxY) {
        if (stackBounds == null || stackBounds.isEmpty() || stackBounds.contains(bounds)) {
            return;
        }

        // For each side of the parent (eg. left), check if the opposing side of the window (eg.
        // right) is at least overlap pixels away. If less, offset the window by that difference.
        int horizontalDiff = 0;
        // If window is smaller than overlap, use it's smallest dimension instead
        int overlapLR = Math.min(overlapPxX, bounds.width());
        if (bounds.right < (stackBounds.left + overlapLR)) {
            horizontalDiff = overlapLR - (bounds.right - stackBounds.left);
        } else if (bounds.left > (stackBounds.right - overlapLR)) {
            horizontalDiff = -(overlapLR - (stackBounds.right - bounds.left));
        }
        int verticalDiff = 0;
        int overlapTB = Math.min(overlapPxY, bounds.width());
        if (bounds.bottom < (stackBounds.top + overlapTB)) {
            verticalDiff = overlapTB - (bounds.bottom - stackBounds.top);
        } else if (bounds.top > (stackBounds.bottom - overlapTB)) {
            verticalDiff = -(overlapTB - (stackBounds.bottom - bounds.top));
        }
        bounds.offset(horizontalDiff, verticalDiff);
    }

    /**
     * Intersects inOutBounds with intersectBounds-intersectInsets. If inOutBounds is larger than
     * intersectBounds on a side, then the respective side will not be intersected.
     *
     * The assumption is that if inOutBounds is initially larger than intersectBounds, then the
     * inset on that side is no-longer applicable. This scenario happens when a task's minimal
     * bounds are larger than the provided parent/display bounds.
     *
     * @param inOutBounds the bounds to intersect.
     * @param intersectBounds the bounds to intersect with.
     * @param intersectInsets insets to apply to intersectBounds before intersecting.
     */
    static void intersectWithInsetsIfFits(
            Rect inOutBounds, Rect intersectBounds, Rect intersectInsets) {
        if (inOutBounds.right <= intersectBounds.right) {
            inOutBounds.right =
                    Math.min(intersectBounds.right - intersectInsets.right, inOutBounds.right);
        }
        if (inOutBounds.bottom <= intersectBounds.bottom) {
            inOutBounds.bottom =
                    Math.min(intersectBounds.bottom - intersectInsets.bottom, inOutBounds.bottom);
        }
        if (inOutBounds.left >= intersectBounds.left) {
            inOutBounds.left =
                    Math.max(intersectBounds.left + intersectInsets.left, inOutBounds.left);
        }
        if (inOutBounds.top >= intersectBounds.top) {
            inOutBounds.top =
                    Math.max(intersectBounds.top + intersectInsets.top, inOutBounds.top);
        }
    }

    /**
     * Gets bounds with non-decor and stable insets applied respectively.
     *
     * If bounds overhangs the display, those edges will not get insets. See
     * {@link #intersectWithInsetsIfFits}
     *
     * @param outNonDecorBounds where to place bounds with non-decor insets applied.
     * @param outStableBounds where to place bounds with stable insets applied.
     * @param bounds the bounds to inset.
     */
    private void calculateInsetFrames(Rect outNonDecorBounds, Rect outStableBounds, Rect bounds,
            DisplayInfo displayInfo) {
        outNonDecorBounds.set(bounds);
        outStableBounds.set(bounds);
        if (getStack() == null || getStack().getDisplay() == null) {
            return;
        }
        DisplayPolicy policy = getStack().getDisplay().mDisplayContent.getDisplayPolicy();
        if (policy == null) {
            return;
        }
        mTmpBounds.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);

        policy.getNonDecorInsetsLw(displayInfo.rotation, displayInfo.logicalWidth,
                displayInfo.logicalHeight, displayInfo.displayCutout, mTmpInsets);
        intersectWithInsetsIfFits(outNonDecorBounds, mTmpBounds, mTmpInsets);

        policy.convertNonDecorInsetsToStableInsets(mTmpInsets, displayInfo.rotation);
        intersectWithInsetsIfFits(outStableBounds, mTmpBounds, mTmpInsets);
    }

    /**
     * Asks docked-divider controller for the smallestwidthdp given bounds.
     * @param bounds bounds to calculate smallestwidthdp for.
     */
    private int getSmallestScreenWidthDpForDockedBounds(Rect bounds) {
        DisplayContent dc = mStack.getDisplay().mDisplayContent;
        if (dc != null) {
            return dc.getDockedDividerController().getSmallestWidthDpForBounds(bounds);
        }
        return Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig) {
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* compatInsets */);
    }

    /**
     * Calculates configuration values used by the client to get resources. This should be run
     * using app-facing bounds (bounds unmodified by animations or transient interactions).
     *
     * This assumes bounds are non-empty/null. For the null-bounds case, the caller is likely
     * configuring an "inherit-bounds" window which means that all configuration settings would
     * just be inherited from the parent configuration.
     **/
    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        int windowingMode = inOutConfig.windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = parentConfig.windowConfiguration.getWindowingMode();
        }

        float density = inOutConfig.densityDpi;
        if (density == Configuration.DENSITY_DPI_UNDEFINED) {
            density = parentConfig.densityDpi;
        }
        density *= DisplayMetrics.DENSITY_DEFAULT_SCALE;

        final Rect resolvedBounds = inOutConfig.windowConfiguration.getBounds();
        if (resolvedBounds == null) {
            mTmpFullBounds.setEmpty();
        } else {
            mTmpFullBounds.set(resolvedBounds);
        }
        if (mTmpFullBounds.isEmpty()) {
            mTmpFullBounds.set(parentConfig.windowConfiguration.getBounds());
        }

        Rect outAppBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (outAppBounds == null || outAppBounds.isEmpty()) {
            inOutConfig.windowConfiguration.setAppBounds(mTmpFullBounds);
            outAppBounds = inOutConfig.windowConfiguration.getAppBounds();
        }
        // Non-null compatibility insets means the activity prefers to keep its original size, so
        // the out bounds doesn't need to be restricted by the parent.
        final boolean insideParentBounds = compatInsets == null;
        if (insideParentBounds && windowingMode != WINDOWING_MODE_FREEFORM) {
            final Rect parentAppBounds = parentConfig.windowConfiguration.getAppBounds();
            if (parentAppBounds != null && !parentAppBounds.isEmpty()) {
                outAppBounds.intersect(parentAppBounds);
            }
        }

        if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED
                || inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            if (insideParentBounds && mStack != null) {
                final DisplayInfo di = new DisplayInfo();
                mStack.getDisplay().mDisplay.getDisplayInfo(di);

                // For calculating screenWidthDp, screenWidthDp, we use the stable inset screen
                // area, i.e. the screen area without the system bars.
                // The non decor inset are areas that could never be removed in Honeycomb. See
                // {@link WindowManagerPolicy#getNonDecorInsetsLw}.
                calculateInsetFrames(mTmpNonDecorBounds, mTmpStableBounds, mTmpFullBounds, di);
            } else {
                // Apply the given non-decor and stable insets to calculate the corresponding bounds
                // for screen size of configuration.
                int rotation = inOutConfig.windowConfiguration.getRotation();
                if (rotation == ROTATION_UNDEFINED) {
                    rotation = parentConfig.windowConfiguration.getRotation();
                }
                if (rotation != ROTATION_UNDEFINED && compatInsets != null) {
                    mTmpNonDecorBounds.set(mTmpFullBounds);
                    mTmpStableBounds.set(mTmpFullBounds);
                    compatInsets.getDisplayBoundsByRotation(mTmpBounds, rotation);
                    intersectWithInsetsIfFits(mTmpNonDecorBounds, mTmpBounds,
                            compatInsets.mNonDecorInsets[rotation]);
                    intersectWithInsetsIfFits(mTmpStableBounds, mTmpBounds,
                            compatInsets.mStableInsets[rotation]);
                    outAppBounds.set(mTmpNonDecorBounds);
                } else {
                    // Set to app bounds because it excludes decor insets.
                    mTmpNonDecorBounds.set(outAppBounds);
                    mTmpStableBounds.set(outAppBounds);
                }
            }

            if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                final int overrideScreenWidthDp = (int) (mTmpStableBounds.width() / density);
                inOutConfig.screenWidthDp = insideParentBounds
                        ? Math.min(overrideScreenWidthDp, parentConfig.screenWidthDp)
                        : overrideScreenWidthDp;
            }
            if (inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                final int overrideScreenHeightDp = (int) (mTmpStableBounds.height() / density);
                inOutConfig.screenHeightDp = insideParentBounds
                        ? Math.min(overrideScreenHeightDp, parentConfig.screenHeightDp)
                        : overrideScreenHeightDp;
            }

            if (inOutConfig.smallestScreenWidthDp
                    == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                if (WindowConfiguration.isFloating(windowingMode)) {
                    // For floating tasks, calculate the smallest width from the bounds of the task
                    inOutConfig.smallestScreenWidthDp = (int) (
                            Math.min(mTmpFullBounds.width(), mTmpFullBounds.height()) / density);
                } else if (WindowConfiguration.isSplitScreenWindowingMode(windowingMode)) {
                    // Iterating across all screen orientations, and return the minimum of the task
                    // width taking into account that the bounds might change because the snap
                    // algorithm snaps to a different value
                    inOutConfig.smallestScreenWidthDp =
                            getSmallestScreenWidthDpForDockedBounds(mTmpFullBounds);
                }
                // otherwise, it will just inherit
            }
        }

        if (inOutConfig.orientation == ORIENTATION_UNDEFINED) {
            inOutConfig.orientation = (inOutConfig.screenWidthDp <= inOutConfig.screenHeightDp)
                    ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        }
        if (inOutConfig.screenLayout == Configuration.SCREENLAYOUT_UNDEFINED) {
            // For calculating screen layout, we need to use the non-decor inset screen area for the
            // calculation for compatibility reasons, i.e. screen area without system bars that
            // could never go away in Honeycomb.
            final int compatScreenWidthDp = (int) (mTmpNonDecorBounds.width() / density);
            final int compatScreenHeightDp = (int) (mTmpNonDecorBounds.height() / density);
            // We're only overriding LONG, SIZE and COMPAT parts of screenLayout, so we start
            // override calculation with partial default.
            // Reducing the screen layout starting from its parent config.
            final int sl = parentConfig.screenLayout
                    & (Configuration.SCREENLAYOUT_LONG_MASK | Configuration.SCREENLAYOUT_SIZE_MASK);
            final int longSize = Math.max(compatScreenHeightDp, compatScreenWidthDp);
            final int shortSize = Math.min(compatScreenHeightDp, compatScreenWidthDp);
            inOutConfig.screenLayout = Configuration.reduceScreenLayout(sl, longSize, shortSize);
        }
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        mTmpBounds.set(getResolvedOverrideConfiguration().windowConfiguration.getBounds());
        super.resolveOverrideConfiguration(newParentConfig);
        int windowingMode =
                getRequestedOverrideConfiguration().windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = newParentConfig.windowConfiguration.getWindowingMode();
        }
        Rect outOverrideBounds =
                getResolvedOverrideConfiguration().windowConfiguration.getBounds();

        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            computeFullscreenBounds(outOverrideBounds, null /* refActivity */,
                    newParentConfig.windowConfiguration.getBounds(),
                    newParentConfig.orientation);
        }

        adjustForMinimalTaskDimensions(outOverrideBounds, mTmpBounds);
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            // by policy, make sure the window remains within parent somewhere
            final float density =
                    ((float) newParentConfig.densityDpi) / DisplayMetrics.DENSITY_DEFAULT;
            final Rect parentBounds =
                    new Rect(newParentConfig.windowConfiguration.getBounds());
            final ActivityDisplay display = mStack.getDisplay();
            if (display != null && display.mDisplayContent != null) {
                // If a freeform window moves below system bar, there is no way to move it again
                // by touch. Because its caption is covered by system bar. So we exclude them
                // from stack bounds. and then caption will be shown inside stable area.
                final Rect stableBounds = new Rect();
                display.mDisplayContent.getStableRect(stableBounds);
                parentBounds.intersect(stableBounds);
            }

            fitWithinBounds(outOverrideBounds, parentBounds,
                    (int) (density * WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP),
                    (int) (density * WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP));

            // Prevent to overlap caption with stable insets.
            final int offsetTop = parentBounds.top - outOverrideBounds.top;
            if (offsetTop > 0) {
                outOverrideBounds.offset(0, offsetTop);
            }
        }
        computeConfigResourceOverrides(getResolvedOverrideConfiguration(), newParentConfig);
    }

    /**
     * Compute bounds (letterbox or pillarbox) for
     * {@link WindowConfiguration#WINDOWING_MODE_FULLSCREEN} when the parent doesn't handle the
     * orientation change and the requested orientation is different from the parent.
     */
    void computeFullscreenBounds(@NonNull Rect outBounds, @Nullable ActivityRecord refActivity,
            @NonNull Rect parentBounds, int parentOrientation) {
        // In FULLSCREEN mode, always start with empty bounds to indicate "fill parent".
        outBounds.setEmpty();
        if (handlesOrientationChangeFromDescendant()) {
            return;
        }
        if (refActivity == null) {
            // Use the top activity as the reference of orientation. Don't include overlays because
            // it is usually not the actual content or just temporarily shown.
            // E.g. ForcedResizableInfoActivity.
            refActivity = getTopActivity(false /* includeOverlays */);
        }

        // If the task or the reference activity requires a different orientation (either by
        // override or activityInfo), make it fit the available bounds by scaling down its bounds.
        final int overrideOrientation = getRequestedOverrideConfiguration().orientation;
        final int forcedOrientation =
                (overrideOrientation != ORIENTATION_UNDEFINED || refActivity == null)
                        ? overrideOrientation : refActivity.getRequestedConfigurationOrientation();
        if (forcedOrientation == ORIENTATION_UNDEFINED || forcedOrientation == parentOrientation) {
            return;
        }

        final int parentWidth = parentBounds.width();
        final int parentHeight = parentBounds.height();
        final float aspect = ((float) parentHeight) / parentWidth;
        if (forcedOrientation == ORIENTATION_LANDSCAPE) {
            final int height = (int) (parentWidth / aspect);
            final int top = parentBounds.centerY() - height / 2;
            outBounds.set(parentBounds.left, top, parentBounds.right, top + height);
        } else {
            final int width = (int) (parentHeight * aspect);
            final int left = parentBounds.centerX() - width / 2;
            outBounds.set(left, parentBounds.top, left + width, parentBounds.bottom);
        }
    }

    Rect updateOverrideConfigurationFromLaunchBounds() {
        final Rect bounds = getLaunchBounds();
        setBounds(bounds);
        if (bounds != null && !bounds.isEmpty()) {
            // TODO: Review if we actually want to do this - we are setting the launch bounds
            // directly here.
            bounds.set(getRequestedOverrideBounds());
        }
        return bounds;
    }

    /** Updates the task's bounds and override configuration to match what is expected for the
     * input stack. */
    void updateOverrideConfigurationForStack(ActivityStack inStack) {
        if (mStack != null && mStack == inStack) {
            return;
        }

        if (inStack.inFreeformWindowingMode()) {
            if (!isResizeable()) {
                throw new IllegalArgumentException("Can not position non-resizeable task="
                        + this + " in stack=" + inStack);
            }
            if (!matchParentBounds()) {
                return;
            }
            if (mLastNonFullscreenBounds != null) {
                setBounds(mLastNonFullscreenBounds);
            } else {
                mAtmService.mStackSupervisor.getLaunchParamsController().layoutTask(this, null);
            }
        } else {
            setBounds(inStack.getRequestedOverrideBounds());
        }
    }

    /** Returns the bounds that should be used to launch this task. */
    Rect getLaunchBounds() {
        if (mStack == null) {
            return null;
        }

        final int windowingMode = getWindowingMode();
        if (!isActivityTypeStandardOrUndefined()
                || windowingMode == WINDOWING_MODE_FULLSCREEN
                || (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && !isResizeable())) {
            return isResizeable() ? mStack.getRequestedOverrideBounds() : null;
        } else if (!getWindowConfiguration().persistTaskBounds()) {
            return mStack.getRequestedOverrideBounds();
        }
        return mLastNonFullscreenBounds;
    }

    void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int activityNdx = getChildCount() - 1; activityNdx >= 0; --activityNdx) {
            final ActivityRecord r = getChildAt(activityNdx);
            if (r.visible) {
                r.showStartingWindow(null /* prev */, false /* newTask */, taskSwitch);
            }
        }
    }

    void setRootProcess(WindowProcessController proc) {
        clearRootProcess();
        if (intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0) {
            mRootProcess = proc;
            mRootProcess.addRecentTask(this);
        }
    }

    void clearRootProcess() {
        if (mRootProcess != null) {
            mRootProcess.removeRecentTask(this);
            mRootProcess = null;
        }
    }

    void clearAllPendingOptions() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            getChildAt(i).clearOptionsLocked(false /* withAbort */);
        }
    }

    @Override
    DisplayContent getDisplayContent() {
        return getTaskStack() != null ? getTaskStack().getDisplayContent() : null;
    }

    ActivityStack getTaskStack() {
        return (ActivityStack) getParent();
    }

    int getAdjustedAddPosition(ActivityRecord r, int suggestedPosition) {
        int maxPosition = mChildren.size();
        if (!r.mTaskOverlay) {
            // We want to place all non-overlay activities below overlays.
            while (maxPosition > 0) {
                final ActivityRecord current = mChildren.get(maxPosition - 1);
                if (current.mTaskOverlay && !current.removed) {
                    --maxPosition;
                    continue;
                }
                break;
            }
            if (maxPosition < 0) {
                maxPosition = 0;
            }
        }

        if (suggestedPosition >= maxPosition) {
            return Math.min(maxPosition, suggestedPosition);
        }

        for (int pos = 0; pos < maxPosition && pos < suggestedPosition; ++pos) {
            // TODO: Confirm that this is the behavior we want long term.
            if (mChildren.get(pos).removed) {
                // suggestedPosition assumes removed tokens are actually gone.
                ++suggestedPosition;
            }
        }
        return Math.min(maxPosition, suggestedPosition);
    }

    @Override
    void positionChildAt(int position, ActivityRecord child, boolean includingParents) {
        position = getAdjustedAddPosition(child, position);
        super.positionChildAt(position, child, includingParents);
    }

    private boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (mChildren.get(i).hasWindowsAlive()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    boolean shouldDeferRemoval() {
        if (mChildren.isEmpty()) {
            // No reason to defer removal of a Task that doesn't have any child.
            return false;
        }
        return hasWindowsAlive() && getTaskStack().isAnimating(TRANSITION | CHILDREN);
    }

    @Override
    void removeImmediately() {
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "removeTask");
        super.removeImmediately();
    }

    // TODO: Consolidate this with Task.reparent()
    void reparent(ActivityStack stack, int position, boolean moveParents, String reason) {
        if (DEBUG_STACK) Slog.i(TAG, "reParentTask: removing taskId=" + mTaskId
                + " from stack=" + getTaskStack());
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "reParentTask");

        final ActivityStack prevStack = getTaskStack();
        final boolean wasTopFocusedStack =
                mAtmService.mRootActivityContainer.isTopDisplayFocusedStack(prevStack);
        final ActivityDisplay prevStackDisplay = prevStack.getDisplay();

        position = stack.findPositionForTask(this, position, showForAllUsers());

        reparent(stack, position);

        if (!moveParents) {
            // Only move home stack forward if we are not going to move the new parent forward.
            prevStack.moveHomeStackToFrontIfNeeded(wasTopFocusedStack, prevStackDisplay, reason);
        }

        stack.positionChildAt(position, this, moveParents);

        // If we are moving from the fullscreen stack to the pinned stack then we want to preserve
        // our insets so that there will not be a jump in the area covered by system decorations.
        // We rely on the pinned animation to later unset this value.
        mPreserveNonFloatingState = stack.inPinnedWindowingMode();
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mChildren.size(); appTokenNdx++) {
            mChildren.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    public int setBounds(Rect bounds, boolean forceResize) {
        final int boundsChanged = setBounds(bounds);

        if (forceResize && (boundsChanged & BOUNDS_CHANGE_SIZE) != BOUNDS_CHANGE_SIZE) {
            onResize();
            return BOUNDS_CHANGE_SIZE | boundsChanged;
        }

        return boundsChanged;
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    @Override
    public int setBounds(Rect bounds) {
        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = getTaskStack() != null
                ? getTaskStack().getDisplayContent() : null;
        if (displayContent != null) {
            rotation = displayContent.getDisplayInfo().rotation;
        } else if (bounds == null) {
            return super.setBounds(bounds);
        }

        final int boundsChange = super.setBounds(bounds);

        mRotation = rotation;

        updateSurfacePosition();
        return boundsChange;
    }

    @Override
    public boolean onDescendantOrientationChanged(IBinder freezeDisplayToken,
            ConfigurationContainer requestingContainer) {
        if (super.onDescendantOrientationChanged(freezeDisplayToken, requestingContainer)) {
            return true;
        }

        // No one in higher hierarchy handles this request, let's adjust our bounds to fulfill
        // it if possible.
        if (getParent() != null) {
            onConfigurationChanged(getParent().getConfiguration());
            return true;
        }
        return false;
    }

    void resize(boolean relayout, boolean forced) {
        if (setBounds(getRequestedOverrideBounds(), forced) != BOUNDS_CHANGE_NONE && relayout) {
            getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        adjustBoundsForDisplayChangeIfNeeded(dc);
        super.onDisplayChanged(dc);
        final int displayId = (dc != null) ? dc.getDisplayId() : Display.INVALID_DISPLAY;
        mWmService.mAtmService.getTaskChangeNotificationController().notifyTaskDisplayChanged(
                mTaskId, displayId);
    }

    /**
     * Displayed bounds are used to set where the task is drawn at any given time. This is
     * separate from its actual bounds so that the app doesn't see any meaningful configuration
     * changes during transitionary periods.
     */
    void setOverrideDisplayedBounds(Rect overrideDisplayedBounds) {
        if (overrideDisplayedBounds != null) {
            mOverrideDisplayedBounds.set(overrideDisplayedBounds);
        } else {
            mOverrideDisplayedBounds.setEmpty();
        }
        updateSurfacePosition();
    }

    /**
     * Gets the bounds that override where the task is displayed. See
     * {@link android.app.IActivityTaskManager#resizeDockedStack} why this is needed.
     */
    Rect getOverrideDisplayedBounds() {
        return mOverrideDisplayedBounds;
    }

    boolean isResizeable(boolean checkSupportsPip) {
        return (mAtmService.mForceResizableActivities || ActivityInfo.isResizeableMode(mResizeMode)
                || (checkSupportsPip && mSupportsPictureInPicture));
    }

    boolean isResizeable() {
        return isResizeable(true /* checkSupportsPip */);
    }

    /**
     * Tests if the orientation should be preserved upon user interactive resizig operations.

     * @return true if orientation should not get changed upon resizing operation.
     */
    boolean preserveOrientationOnResize() {
        return mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    boolean cropWindowsToStackBounds() {
        return isResizeable();
    }

    /**
     * Prepares the task bounds to be frozen with the current size. See
     * {@link ActivityRecord#freezeBounds}.
     */
    void prepareFreezingBounds() {
        mPreparedFrozenBounds.set(getBounds());
        mPreparedFrozenMergedConfig.setTo(getConfiguration());
    }

    /**
     * Align the task to the adjusted bounds.
     *
     * @param adjustedBounds Adjusted bounds to which the task should be aligned.
     * @param tempInsetBounds Insets bounds for the task.
     * @param alignBottom True if the task's bottom should be aligned to the adjusted
     *                    bounds's bottom; false if the task's top should be aligned
     *                    the adjusted bounds's top.
     */
    void alignToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds, boolean alignBottom) {
        if (!isResizeable() || EMPTY.equals(getRequestedOverrideConfiguration())) {
            return;
        }

        getBounds(mTmpRect2);
        if (alignBottom) {
            int offsetY = adjustedBounds.bottom - mTmpRect2.bottom;
            mTmpRect2.offset(0, offsetY);
        } else {
            mTmpRect2.offsetTo(adjustedBounds.left, adjustedBounds.top);
        }
        if (tempInsetBounds == null || tempInsetBounds.isEmpty()) {
            setOverrideDisplayedBounds(null);
            setBounds(mTmpRect2);
        } else {
            setOverrideDisplayedBounds(mTmpRect2);
            setBounds(tempInsetBounds);
        }
    }

    /**
     * Gets the current overridden displayed bounds. These will be empty if the task is not
     * currently overriding where it is displayed.
     */
    @Override
    public Rect getDisplayedBounds() {
        if (mOverrideDisplayedBounds.isEmpty()) {
            return super.getDisplayedBounds();
        } else {
            return mOverrideDisplayedBounds;
        }
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final WindowState windowState = getTopVisibleAppMainWindow();
        if (windowState != null) {
            windowState.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        } else {
            super.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        }
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out Rect containing the max visible bounds.
     * @return true if the task has some visible app windows; false otherwise.
     */
    private boolean getMaxVisibleBounds(Rect out) {
        boolean foundTop = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (token.mIsExiting || token.isClientHidden() || token.hiddenRequested) {
                continue;
            }
            final WindowState win = token.findMainWindow();
            if (win == null) {
                continue;
            }
            if (!foundTop) {
                foundTop = true;
                out.setEmpty();
            }

            win.getMaxVisibleBounds(out);
        }
        return foundTop;
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    public void getDimBounds(Rect out) {
        final DisplayContent displayContent = getTaskStack().getDisplayContent();
        // It doesn't matter if we in particular are part of the resize, since we couldn't have
        // a DimLayer anyway if we weren't visible.
        final boolean dockedResizing = displayContent != null
                && displayContent.mDividerControllerLocked.isResizing();
        if (inFreeformWindowingMode() && getMaxVisibleBounds(out)) {
            return;
        }

        if (!matchParentBounds()) {
            // When minimizing the docked stack when going home, we don't adjust the task bounds
            // so we need to intersect the task bounds with the stack bounds here.
            //
            // If we are Docked Resizing with snap points, the task bounds could be smaller than the
            // stack bounds and so we don't even want to use them. Even if the app should not be
            // resized the Dim should keep up with the divider.
            if (dockedResizing) {
                getTaskStack().getBounds(out);
            } else {
                getTaskStack().getBounds(mTmpRect);
                mTmpRect.intersect(getBounds());
                out.set(mTmpRect);
            }
        } else {
            out.set(getBounds());
        }
        return;
    }

    void setDragResizing(boolean dragResizing, int dragResizeMode) {
        if (mDragResizing != dragResizing) {
            // No need to check if the mode is allowed if it's leaving dragResize
            if (dragResizing && !DragResizeMode.isModeAllowedForStack(getTaskStack(), dragResizeMode)) {
                throw new IllegalArgumentException("Drag resize mode not allow for stack stackId="
                        + getTaskStack().mStackId + " dragResizeMode=" + dragResizeMode);
            }
            mDragResizing = dragResizing;
            mDragResizeMode = dragResizeMode;
            resetDragResizingChangeReported();
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    int getDragResizeMode() {
        return mDragResizeMode;
    }

    /**
     * Puts this task into docked drag resizing mode. See {@link DragResizeMode}.
     *
     * @param resizing Whether to put the task into drag resize mode.
     */
    public void setTaskDockedResizing(boolean resizing) {
        setDragResizing(resizing, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
    }

    void adjustBoundsForDisplayChangeIfNeeded(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (matchParentBounds()) {
            // TODO: Yeah...not sure if this works with WindowConfiguration, but shouldn't be a
            // problem once we move mBounds into WindowConfiguration.
            setBounds(null);
            return;
        }
        final int displayId = displayContent.getDisplayId();
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (displayId != mLastRotationDisplayId) {
            // This task is on a display that it wasn't on. There is no point to keep the relative
            // position if display rotations for old and new displays are different. Just keep these
            // values.
            mLastRotationDisplayId = displayId;
            mRotation = newRotation;
            return;
        }

        if (mRotation == newRotation) {
            // Rotation didn't change. We don't need to adjust the bounds to keep the relative
            // position.
            return;
        }

        // Device rotation changed.
        // - We don't want the task to move around on the screen when this happens, so update the
        //   task bounds so it stays in the same place.
        // - Rotate the bounds and notify activity manager if the task can be resized independently
        //   from its stack. The stack will take care of task rotation for the other case.
        mTmpRect2.set(getBounds());

        if (!getWindowConfiguration().canResizeTask()) {
            setBounds(mTmpRect2);
            return;
        }

        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (setBounds(mTmpRect2) != BOUNDS_CHANGE_NONE) {
            mAtmService.resizeTask(mTaskId, getBounds(), RESIZE_MODE_SYSTEM_SCREEN_ROTATION);
        }
    }

    /** Cancels any running app transitions associated with the task. */
    void cancelTaskWindowTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).cancelAnimation();
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mChildren.size();
        return (tokensCount != 0) && mChildren.get(tokensCount - 1).mShowForAllUsers;
    }

    /**
     * When we are in a floating stack (Freeform, Pinned, ...) we calculate
     * insets differently. However if we are animating to the fullscreen stack
     * we need to begin calculating insets as if we were fullscreen, otherwise
     * we will have a jump at the end.
     */
    boolean isFloating() {
        return getWindowConfiguration().tasksAreFloating()
                && !getTaskStack().isAnimatingBoundsToFullscreen() && !mPreserveNonFloatingState;
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        if (WindowManagerService.sHierarchicalAnimations) {
            return super.getAnimationLeashParent();
        }
        // Currently, only the recents animation will create animation leashes for tasks. In this
        // case, reparent the task to the home animation layer while it is being animated to allow
        // the home activity to reorder the app windows relative to its own.
        return getAppAnimationLayer(ANIMATION_LAYER_HOME);
    }

    boolean shouldAnimate() {
        // Don't animate while the task runs recents animation but only if we are in the mode
        // where we cancel with deferred screenshot, which means that the controller has
        // transformed the task.
        final RecentsAnimationController controller = mWmService.getRecentsAnimationController();
        if (controller != null && controller.isAnimatingTask(this)
                && controller.shouldDeferCancelUntilNextTransition()) {
            return false;
        }
        return true;
    }

    @Override
    SurfaceControl.Builder makeSurface() {
        return super.makeSurface().setMetadata(METADATA_TASK_ID, mTaskId);
    }

    boolean isTaskAnimating() {
        final RecentsAnimationController recentsAnim = mWmService.getRecentsAnimationController();
        if (recentsAnim != null) {
            if (recentsAnim.isAnimatingTask(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if changing app transition is running.
     */
    @Override
    boolean isChangingAppTransition() {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null && getDisplayContent().mChangingApps.contains(activity);
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null ? activity.createRemoteAnimationTarget(record) : null;
    }

    WindowState getTopVisibleAppMainWindow() {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null ? activity.findMainWindow() : null;
    }

    ActivityRecord getTopFullscreenActivity() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mChildren.get(i);
            final WindowState win = activity.findMainWindow();
            if (win != null && win.mAttrs.isFullscreen()) {
                return activity;
            }
        }
        return null;
    }

    ActivityRecord getTopVisibleActivity() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (!token.mIsExiting && !token.isClientHidden() && !token.hiddenRequested) {
                return token;
            }
        }
        return null;
    }

    void positionChildAtTop(ActivityRecord child) {
        positionChildAt(child, POSITION_TOP);
    }

    void positionChildAt(ActivityRecord child, int position) {
        if (child == null) {
            Slog.w(TAG_WM,
                    "Attempted to position of non-existing app");
            return;
        }

        positionChildAt(position, child, false /* includeParents */);
    }

    void forceWindowsScaleable(boolean force) {
        mWmService.openSurfaceTransaction();
        try {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                mChildren.get(i).forceWindowsScaleableInTransaction(force);
            }
        } finally {
            mWmService.closeSurfaceTransaction("forceWindowsScaleable");
        }
    }

    void setTaskDescription(TaskDescription taskDescription) {
        mTaskDescription = taskDescription;
    }

    void onSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {
        mAtmService.getTaskChangeNotificationController().notifyTaskSnapshotChanged(
                mTaskId, snapshot);
    }

    TaskDescription getTaskDescription() {
        return mTaskDescription;
    }

    @Override
    boolean fillsParent() {
        return matchParentBounds() || !getWindowConfiguration().canResizeTask();
    }

    @Override
    void forAllTasks(Consumer<Task> callback) {
        callback.accept(this);
    }

    @Override
    boolean forAllTasks(ToBooleanFunction<Task> callback) {
        return callback.apply(this);
    }

    /**
     * @param canAffectSystemUiFlags If false, all windows in this task can not affect SystemUI
     *                               flags. See {@link WindowState#canAffectSystemUiFlags()}.
     */
    void setCanAffectSystemUiFlags(boolean canAffectSystemUiFlags) {
        mCanAffectSystemUiFlags = canAffectSystemUiFlags;
    }

    /**
     * @see #setCanAffectSystemUiFlags
     */
    boolean canAffectSystemUiFlags() {
        return mCanAffectSystemUiFlags;
    }

    void dontAnimateDimExit() {
        mDimmer.dontAnimateExit();
    }

    String getName() {
        return "Task=" + mTaskId;
    }

    void clearPreserveNonFloatingState() {
        mPreserveNonFloatingState = false;
    }

    @Override
    Dimmer getDimmer() {
        return mDimmer;
    }

    @Override
    void prepareSurfaces() {
        mDimmer.resetDimStates();
        super.prepareSurfaces();
        getDimBounds(mTmpDimBoundsRect);

        // Bounds need to be relative, as the dim layer is a child.
        mTmpDimBoundsRect.offsetTo(0, 0);
        if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
            scheduleAnimation();
        }
    }

    // TODO(proto-merge): Remove once protos for TaskRecord and Task are merged.
    void writeToProtoInnerTaskOnly(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, logLevel);
        proto.write(TaskProto.ID, mTaskId);
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mChildren.get(i);
            activity.writeToProto(proto, APP_WINDOW_TOKENS, logLevel);
        }
        proto.write(FILLS_PARENT, matchParentBounds());
        getBounds().writeToProto(proto, TaskProto.BOUNDS);
        mOverrideDisplayedBounds.writeToProto(proto, DISPLAYED_BOUNDS);
        if (mSurfaceControl != null) {
            proto.write(SURFACE_WIDTH, mSurfaceControl.getWidth());
            proto.write(SURFACE_HEIGHT, mSurfaceControl.getHeight());
        }
        proto.end(token);
    }

    @Override
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        final String doublePrefix = prefix + "  ";

        pw.println(prefix + "taskId=" + mTaskId);
        pw.println(doublePrefix + "mBounds=" + getBounds().toShortString());
        pw.println(doublePrefix + "appTokens=" + mChildren);
        pw.println(doublePrefix + "mDisplayedBounds=" + mOverrideDisplayedBounds.toShortString());

        final String triplePrefix = doublePrefix + "  ";
        final String quadruplePrefix = triplePrefix + "  ";

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mChildren.get(i);
            pw.println(triplePrefix + "Activity #" + i + " " + activity);
            activity.dump(pw, quadruplePrefix, dumpAll);
        }
    }

    /**
     * Fills in a {@link TaskInfo} with information from this task.
     * @param info the {@link TaskInfo} to fill in
     */
    void fillTaskInfo(TaskInfo info) {
        getNumRunningActivities(mReuseActivitiesReport);
        info.userId = mUserId;
        info.stackId = getStackId();
        info.taskId = mTaskId;
        info.displayId = mStack == null ? Display.INVALID_DISPLAY : mStack.mDisplayId;
        info.isRunning = getTopActivity() != null;
        info.baseIntent = new Intent(getBaseIntent());
        info.baseActivity = mReuseActivitiesReport.base != null
                ? mReuseActivitiesReport.base.intent.getComponent()
                : null;
        info.topActivity = mReuseActivitiesReport.top != null
                ? mReuseActivitiesReport.top.mActivityComponent
                : null;
        info.origActivity = origActivity;
        info.realActivity = realActivity;
        info.numActivities = mReuseActivitiesReport.numActivities;
        info.lastActiveTime = lastActiveTime;
        info.taskDescription = new ActivityManager.TaskDescription(getTaskDescription());
        info.supportsSplitScreenMultiWindow = supportsSplitScreenWindowingMode();
        info.resizeMode = mResizeMode;
        info.configuration.setTo(getConfiguration());
    }

    /**
     * Returns a {@link TaskInfo} with information from this task.
     */
    ActivityManager.RunningTaskInfo getTaskInfo() {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        fillTaskInfo(info);
        return info;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("userId="); pw.print(mUserId);
        pw.print(" effectiveUid="); UserHandle.formatUid(pw, effectiveUid);
        pw.print(" mCallingUid="); UserHandle.formatUid(pw, mCallingUid);
        pw.print(" mUserSetupComplete="); pw.print(mUserSetupComplete);
        pw.print(" mCallingPackage="); pw.println(mCallingPackage);
        if (affinity != null || rootAffinity != null) {
            pw.print(prefix); pw.print("affinity="); pw.print(affinity);
            if (affinity == null || !affinity.equals(rootAffinity)) {
                pw.print(" root="); pw.println(rootAffinity);
            } else {
                pw.println();
            }
        }
        if (voiceSession != null || voiceInteractor != null) {
            pw.print(prefix); pw.print("VOICE: session=0x");
            pw.print(Integer.toHexString(System.identityHashCode(voiceSession)));
            pw.print(" interactor=0x");
            pw.println(Integer.toHexString(System.identityHashCode(voiceInteractor)));
        }
        if (intent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("intent={");
            intent.toShortString(sb, false, true, false, false);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (affinityIntent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("affinityIntent={");
            affinityIntent.toShortString(sb, false, true, false, false);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (origActivity != null) {
            pw.print(prefix); pw.print("origActivity=");
            pw.println(origActivity.flattenToShortString());
        }
        if (realActivity != null) {
            pw.print(prefix); pw.print("mActivityComponent=");
            pw.println(realActivity.flattenToShortString());
        }
        if (autoRemoveRecents || isPersistable || !isActivityTypeStandard() || numFullscreen != 0) {
            pw.print(prefix); pw.print("autoRemoveRecents="); pw.print(autoRemoveRecents);
            pw.print(" isPersistable="); pw.print(isPersistable);
            pw.print(" numFullscreen="); pw.print(numFullscreen);
            pw.print(" activityType="); pw.println(getActivityType());
        }
        if (rootWasReset || mNeverRelinquishIdentity || mReuseTask
                || mLockTaskAuth != LOCK_TASK_AUTH_PINNABLE) {
            pw.print(prefix); pw.print("rootWasReset="); pw.print(rootWasReset);
            pw.print(" mNeverRelinquishIdentity="); pw.print(mNeverRelinquishIdentity);
            pw.print(" mReuseTask="); pw.print(mReuseTask);
            pw.print(" mLockTaskAuth="); pw.println(lockTaskAuthToString());
        }
        if (mAffiliatedTaskId != mTaskId || mPrevAffiliateTaskId != INVALID_TASK_ID
                || mPrevAffiliate != null || mNextAffiliateTaskId != INVALID_TASK_ID
                || mNextAffiliate != null) {
            pw.print(prefix); pw.print("affiliation="); pw.print(mAffiliatedTaskId);
            pw.print(" prevAffiliation="); pw.print(mPrevAffiliateTaskId);
            pw.print(" (");
            if (mPrevAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(mPrevAffiliate)));
            }
            pw.print(") nextAffiliation="); pw.print(mNextAffiliateTaskId);
            pw.print(" (");
            if (mNextAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(mNextAffiliate)));
            }
            pw.println(")");
        }
        pw.print(prefix); pw.print("Activities="); pw.println(mChildren);
        if (!askedCompatMode || !inRecents || !isAvailable) {
            pw.print(prefix); pw.print("askedCompatMode="); pw.print(askedCompatMode);
            pw.print(" inRecents="); pw.print(inRecents);
            pw.print(" isAvailable="); pw.println(isAvailable);
        }
        if (lastDescription != null) {
            pw.print(prefix); pw.print("lastDescription="); pw.println(lastDescription);
        }
        if (mRootProcess != null) {
            pw.print(prefix); pw.print("mRootProcess="); pw.println(mRootProcess);
        }
        pw.print(prefix); pw.print("stackId="); pw.println(getStackId());
        pw.print(prefix + "hasBeenVisible=" + hasBeenVisible);
        pw.print(" mResizeMode=" + ActivityInfo.resizeModeToString(mResizeMode));
        pw.print(" mSupportsPictureInPicture=" + mSupportsPictureInPicture);
        pw.print(" isResizeable=" + isResizeable());
        pw.print(" lastActiveTime=" + lastActiveTime);
        pw.println(" (inactive for " + (getInactiveDuration() / 1000) + "s)");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        if (stringName != null) {
            sb.append(stringName);
            sb.append(" U=");
            sb.append(mUserId);
            sb.append(" StackId=");
            sb.append(getStackId());
            sb.append(" sz=");
            sb.append(getChildCount());
            sb.append('}');
            return sb.toString();
        }
        sb.append("Task{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        sb.append(mTaskId);
        if (affinity != null) {
            sb.append(" A=");
            sb.append(affinity);
        } else if (intent != null) {
            sb.append(" I=");
            sb.append(intent.getComponent().flattenToShortString());
        } else if (affinityIntent != null && affinityIntent.getComponent() != null) {
            sb.append(" aI=");
            sb.append(affinityIntent.getComponent().flattenToShortString());
        } else {
            sb.append(" ??");
        }
        stringName = sb.toString();
        return toString();
    }

    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        writeToProtoInnerTaskOnly(proto, TASK, logLevel);
        proto.write(com.android.server.am.TaskRecordProto.ID, mTaskId);
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final ActivityRecord activity = getChildAt(i);
            activity.writeToProto(proto, ACTIVITIES);
        }
        proto.write(STACK_ID, getStackId());
        if (mLastNonFullscreenBounds != null) {
            mLastNonFullscreenBounds.writeToProto(proto, LAST_NON_FULLSCREEN_BOUNDS);
        }
        if (realActivity != null) {
            proto.write(REAL_ACTIVITY, realActivity.flattenToShortString());
        }
        if (origActivity != null) {
            proto.write(ORIG_ACTIVITY, origActivity.flattenToShortString());
        }
        proto.write(ACTIVITY_TYPE, getActivityType());
        proto.write(RESIZE_MODE, mResizeMode);
        // TODO: Remove, no longer needed with windowingMode.
        proto.write(FULLSCREEN, matchParentBounds());

        if (!matchParentBounds()) {
            final Rect bounds = getRequestedOverrideBounds();
            bounds.writeToProto(proto, com.android.server.am.TaskRecordProto.BOUNDS);
        }
        proto.write(MIN_WIDTH, mMinWidth);
        proto.write(MIN_HEIGHT, mMinHeight);
        proto.end(token);
    }

    /** @see #getNumRunningActivities(TaskActivitiesReport) */
    static class TaskActivitiesReport {
        int numRunning;
        int numActivities;
        ActivityRecord top;
        ActivityRecord base;

        void reset() {
            numRunning = numActivities = 0;
            top = base = null;
        }
    }

    /**
     * Saves this {@link Task} to XML using given serializer.
     */
    void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        if (DEBUG_RECENTS) Slog.i(TAG_RECENTS, "Saving task=" + this);

        out.attribute(null, ATTR_TASKID, String.valueOf(mTaskId));
        if (realActivity != null) {
            out.attribute(null, ATTR_REALACTIVITY, realActivity.flattenToShortString());
        }
        out.attribute(null, ATTR_REALACTIVITY_SUSPENDED, String.valueOf(realActivitySuspended));
        if (origActivity != null) {
            out.attribute(null, ATTR_ORIGACTIVITY, origActivity.flattenToShortString());
        }
        // Write affinity, and root affinity if it is different from affinity.
        // We use the special string "@" for a null root affinity, so we can identify
        // later whether we were given a root affinity or should just make it the
        // same as the affinity.
        if (affinity != null) {
            out.attribute(null, ATTR_AFFINITY, affinity);
            if (!affinity.equals(rootAffinity)) {
                out.attribute(null, ATTR_ROOT_AFFINITY, rootAffinity != null ? rootAffinity : "@");
            }
        } else if (rootAffinity != null) {
            out.attribute(null, ATTR_ROOT_AFFINITY, rootAffinity != null ? rootAffinity : "@");
        }
        out.attribute(null, ATTR_ROOTHASRESET, String.valueOf(rootWasReset));
        out.attribute(null, ATTR_AUTOREMOVERECENTS, String.valueOf(autoRemoveRecents));
        out.attribute(null, ATTR_ASKEDCOMPATMODE, String.valueOf(askedCompatMode));
        out.attribute(null, ATTR_USERID, String.valueOf(mUserId));
        out.attribute(null, ATTR_USER_SETUP_COMPLETE, String.valueOf(mUserSetupComplete));
        out.attribute(null, ATTR_EFFECTIVE_UID, String.valueOf(effectiveUid));
        out.attribute(null, ATTR_LASTTIMEMOVED, String.valueOf(mLastTimeMoved));
        out.attribute(null, ATTR_NEVERRELINQUISH, String.valueOf(mNeverRelinquishIdentity));
        if (lastDescription != null) {
            out.attribute(null, ATTR_LASTDESCRIPTION, lastDescription.toString());
        }
        if (getTaskDescription() != null) {
            getTaskDescription().saveToXml(out);
        }
        out.attribute(null, ATTR_TASK_AFFILIATION_COLOR, String.valueOf(mAffiliatedTaskColor));
        out.attribute(null, ATTR_TASK_AFFILIATION, String.valueOf(mAffiliatedTaskId));
        out.attribute(null, ATTR_PREV_AFFILIATION, String.valueOf(mPrevAffiliateTaskId));
        out.attribute(null, ATTR_NEXT_AFFILIATION, String.valueOf(mNextAffiliateTaskId));
        out.attribute(null, ATTR_CALLING_UID, String.valueOf(mCallingUid));
        out.attribute(null, ATTR_CALLING_PACKAGE, mCallingPackage == null ? "" : mCallingPackage);
        out.attribute(null, ATTR_RESIZE_MODE, String.valueOf(mResizeMode));
        out.attribute(null, ATTR_SUPPORTS_PICTURE_IN_PICTURE,
                String.valueOf(mSupportsPictureInPicture));
        if (mLastNonFullscreenBounds != null) {
            out.attribute(
                    null, ATTR_NON_FULLSCREEN_BOUNDS, mLastNonFullscreenBounds.flattenToString());
        }
        out.attribute(null, ATTR_MIN_WIDTH, String.valueOf(mMinWidth));
        out.attribute(null, ATTR_MIN_HEIGHT, String.valueOf(mMinHeight));
        out.attribute(null, ATTR_PERSIST_TASK_VERSION, String.valueOf(PERSIST_TASK_VERSION));

        if (affinityIntent != null) {
            out.startTag(null, TAG_AFFINITYINTENT);
            affinityIntent.saveToXml(out);
            out.endTag(null, TAG_AFFINITYINTENT);
        }

        if (intent != null) {
            out.startTag(null, TAG_INTENT);
            intent.saveToXml(out);
            out.endTag(null, TAG_INTENT);
        }

        final int numActivities = getChildCount();
        for (int activityNdx = 0; activityNdx < numActivities; ++activityNdx) {
            final ActivityRecord r = getChildAt(activityNdx);
            if (r.info.persistableMode == ActivityInfo.PERSIST_ROOT_ONLY || !r.isPersistable()
                    || ((r.intent.getFlags() & FLAG_ACTIVITY_NEW_DOCUMENT
                            | FLAG_ACTIVITY_RETAIN_IN_RECENTS) == FLAG_ACTIVITY_NEW_DOCUMENT)
                    && activityNdx > 0) {
                // Stop at first non-persistable or first break in task (CLEAR_WHEN_TASK_RESET).
                break;
            }
            out.startTag(null, TAG_ACTIVITY);
            r.saveToXml(out);
            out.endTag(null, TAG_ACTIVITY);
        }
    }

    @VisibleForTesting
    static TaskFactory getTaskFactory() {
        if (sTaskFactory == null) {
            setTaskFactory(new TaskFactory());
        }
        return sTaskFactory;
    }

    static void setTaskFactory(TaskFactory factory) {
        sTaskFactory = factory;
    }

    static Task create(ActivityTaskManagerService service, int taskId, ActivityInfo info,
            Intent intent, IVoiceInteractionSession voiceSession,
            IVoiceInteractor voiceInteractor, ActivityStack stack) {
        return getTaskFactory().create(
                service, taskId, info, intent, voiceSession, voiceInteractor, stack);
    }

    static Task create(ActivityTaskManagerService service, int taskId, ActivityInfo info,
            Intent intent, TaskDescription taskDescription, ActivityStack stack) {
        return getTaskFactory().create(service, taskId, info, intent, taskDescription, stack);
    }

    static Task restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor)
            throws IOException, XmlPullParserException {
        return getTaskFactory().restoreFromXml(in, stackSupervisor);
    }

    /**
     * A factory class used to create {@link Task} or its subclass if any. This can be
     * specified when system boots by setting it with
     * {@link #setTaskFactory(TaskFactory)}.
     */
    static class TaskFactory {

        Task create(ActivityTaskManagerService service, int taskId, ActivityInfo info,
                Intent intent, IVoiceInteractionSession voiceSession,
                IVoiceInteractor voiceInteractor, ActivityStack stack) {
            return new Task(service, taskId, info, intent, voiceSession, voiceInteractor,
                    null /*taskDescription*/, stack);
        }

        Task create(ActivityTaskManagerService service, int taskId, ActivityInfo info,
                Intent intent, TaskDescription taskDescription, ActivityStack stack) {
            return new Task(service, taskId, info, intent, null /*voiceSession*/,
                    null /*voiceInteractor*/, taskDescription, stack);
        }

        /**
         * Should only be used when we're restoring {@link Task} from storage.
         */
        Task create(ActivityTaskManagerService service, int taskId, Intent intent,
                Intent affinityIntent, String affinity, String rootAffinity,
                ComponentName realActivity, ComponentName origActivity, boolean rootWasReset,
                boolean autoRemoveRecents, boolean askedCompatMode, int userId,
                int effectiveUid, String lastDescription,
                long lastTimeMoved, boolean neverRelinquishIdentity,
                TaskDescription lastTaskDescription, int taskAffiliation, int prevTaskId,
                int nextTaskId, int taskAffiliationColor, int callingUid, String callingPackage,
                int resizeMode, boolean supportsPictureInPicture, boolean realActivitySuspended,
                boolean userSetupComplete, int minWidth, int minHeight, ActivityStack stack) {
            return new Task(service, taskId, intent, affinityIntent, affinity,
                    rootAffinity, realActivity, origActivity, rootWasReset, autoRemoveRecents,
                    askedCompatMode, userId, effectiveUid, lastDescription,
                    lastTimeMoved, neverRelinquishIdentity, lastTaskDescription, taskAffiliation,
                    prevTaskId, nextTaskId, taskAffiliationColor, callingUid, callingPackage,
                    resizeMode, supportsPictureInPicture, realActivitySuspended, userSetupComplete,
                    minWidth, minHeight, null /*ActivityInfo*/, null /*_voiceSession*/,
                    null /*_voiceInteractor*/, stack);
        }

        Task restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor)
                throws IOException, XmlPullParserException {
            Intent intent = null;
            Intent affinityIntent = null;
            ArrayList<ActivityRecord> activities = new ArrayList<>();
            ComponentName realActivity = null;
            boolean realActivitySuspended = false;
            ComponentName origActivity = null;
            String affinity = null;
            String rootAffinity = null;
            boolean hasRootAffinity = false;
            boolean rootHasReset = false;
            boolean autoRemoveRecents = false;
            boolean askedCompatMode = false;
            int taskType = 0;
            int userId = 0;
            boolean userSetupComplete = true;
            int effectiveUid = -1;
            String lastDescription = null;
            long lastTimeOnTop = 0;
            boolean neverRelinquishIdentity = true;
            int taskId = INVALID_TASK_ID;
            final int outerDepth = in.getDepth();
            TaskDescription taskDescription = new TaskDescription();
            int taskAffiliation = INVALID_TASK_ID;
            int taskAffiliationColor = 0;
            int prevTaskId = INVALID_TASK_ID;
            int nextTaskId = INVALID_TASK_ID;
            int callingUid = -1;
            String callingPackage = "";
            int resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
            boolean supportsPictureInPicture = false;
            Rect lastNonFullscreenBounds = null;
            int minWidth = INVALID_MIN_SIZE;
            int minHeight = INVALID_MIN_SIZE;
            int persistTaskVersion = 0;

            for (int attrNdx = in.getAttributeCount() - 1; attrNdx >= 0; --attrNdx) {
                final String attrName = in.getAttributeName(attrNdx);
                final String attrValue = in.getAttributeValue(attrNdx);
                if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "Task: attribute name="
                        + attrName + " value=" + attrValue);
                switch (attrName) {
                    case ATTR_TASKID:
                        if (taskId == INVALID_TASK_ID) taskId = Integer.parseInt(attrValue);
                        break;
                    case ATTR_REALACTIVITY:
                        realActivity = ComponentName.unflattenFromString(attrValue);
                        break;
                    case ATTR_REALACTIVITY_SUSPENDED:
                        realActivitySuspended = Boolean.valueOf(attrValue);
                        break;
                    case ATTR_ORIGACTIVITY:
                        origActivity = ComponentName.unflattenFromString(attrValue);
                        break;
                    case ATTR_AFFINITY:
                        affinity = attrValue;
                        break;
                    case ATTR_ROOT_AFFINITY:
                        rootAffinity = attrValue;
                        hasRootAffinity = true;
                        break;
                    case ATTR_ROOTHASRESET:
                        rootHasReset = Boolean.parseBoolean(attrValue);
                        break;
                    case ATTR_AUTOREMOVERECENTS:
                        autoRemoveRecents = Boolean.parseBoolean(attrValue);
                        break;
                    case ATTR_ASKEDCOMPATMODE:
                        askedCompatMode = Boolean.parseBoolean(attrValue);
                        break;
                    case ATTR_USERID:
                        userId = Integer.parseInt(attrValue);
                        break;
                    case ATTR_USER_SETUP_COMPLETE:
                        userSetupComplete = Boolean.parseBoolean(attrValue);
                        break;
                    case ATTR_EFFECTIVE_UID:
                        effectiveUid = Integer.parseInt(attrValue);
                        break;
                    case ATTR_TASKTYPE:
                        taskType = Integer.parseInt(attrValue);
                        break;
                    case ATTR_LASTDESCRIPTION:
                        lastDescription = attrValue;
                        break;
                    case ATTR_LASTTIMEMOVED:
                        lastTimeOnTop = Long.parseLong(attrValue);
                        break;
                    case ATTR_NEVERRELINQUISH:
                        neverRelinquishIdentity = Boolean.parseBoolean(attrValue);
                        break;
                    case ATTR_TASK_AFFILIATION:
                        taskAffiliation = Integer.parseInt(attrValue);
                        break;
                    case ATTR_PREV_AFFILIATION:
                        prevTaskId = Integer.parseInt(attrValue);
                        break;
                    case ATTR_NEXT_AFFILIATION:
                        nextTaskId = Integer.parseInt(attrValue);
                        break;
                    case ATTR_TASK_AFFILIATION_COLOR:
                        taskAffiliationColor = Integer.parseInt(attrValue);
                        break;
                    case ATTR_CALLING_UID:
                        callingUid = Integer.parseInt(attrValue);
                        break;
                    case ATTR_CALLING_PACKAGE:
                        callingPackage = attrValue;
                        break;
                    case ATTR_RESIZE_MODE:
                        resizeMode = Integer.parseInt(attrValue);
                        break;
                    case ATTR_SUPPORTS_PICTURE_IN_PICTURE:
                        supportsPictureInPicture = Boolean.parseBoolean(attrValue);
                        break;
                    case ATTR_NON_FULLSCREEN_BOUNDS:
                        lastNonFullscreenBounds = Rect.unflattenFromString(attrValue);
                        break;
                    case ATTR_MIN_WIDTH:
                        minWidth = Integer.parseInt(attrValue);
                        break;
                    case ATTR_MIN_HEIGHT:
                        minHeight = Integer.parseInt(attrValue);
                        break;
                    case ATTR_PERSIST_TASK_VERSION:
                        persistTaskVersion = Integer.parseInt(attrValue);
                        break;
                    default:
                        if (attrName.startsWith(TaskDescription.ATTR_TASKDESCRIPTION_PREFIX)) {
                            taskDescription.restoreFromXml(attrName, attrValue);
                        } else {
                            Slog.w(TAG, "Task: Unknown attribute=" + attrName);
                        }
                }
            }

            int event;
            while (((event = in.next()) != XmlPullParser.END_DOCUMENT)
                    && (event != XmlPullParser.END_TAG || in.getDepth() >= outerDepth)) {
                if (event == XmlPullParser.START_TAG) {
                    final String name = in.getName();
                    if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG,
                            "Task: START_TAG name=" + name);
                    if (TAG_AFFINITYINTENT.equals(name)) {
                        affinityIntent = Intent.restoreFromXml(in);
                    } else if (TAG_INTENT.equals(name)) {
                        intent = Intent.restoreFromXml(in);
                    } else if (TAG_ACTIVITY.equals(name)) {
                        ActivityRecord activity =
                                ActivityRecord.restoreFromXml(in, stackSupervisor);
                        if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "Task: activity="
                                + activity);
                        if (activity != null) {
                            activities.add(activity);
                        }
                    } else {
                        handleUnknownTag(name, in);
                    }
                }
            }
            if (!hasRootAffinity) {
                rootAffinity = affinity;
            } else if ("@".equals(rootAffinity)) {
                rootAffinity = null;
            }
            if (effectiveUid <= 0) {
                Intent checkIntent = intent != null ? intent : affinityIntent;
                effectiveUid = 0;
                if (checkIntent != null) {
                    IPackageManager pm = AppGlobals.getPackageManager();
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(
                                checkIntent.getComponent().getPackageName(),
                                PackageManager.MATCH_UNINSTALLED_PACKAGES
                                        | PackageManager.MATCH_DISABLED_COMPONENTS, userId);
                        if (ai != null) {
                            effectiveUid = ai.uid;
                        }
                    } catch (RemoteException e) {
                    }
                }
                Slog.w(TAG, "Updating task #" + taskId + " for " + checkIntent
                        + ": effectiveUid=" + effectiveUid);
            }

            if (persistTaskVersion < 1) {
                // We need to convert the resize mode of home activities saved before version one if
                // they are marked as RESIZE_MODE_RESIZEABLE to
                // RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION since we didn't have that differentiation
                // before version 1 and the system didn't resize home activities before then.
                if (taskType == 1 /* old home type */ && resizeMode == RESIZE_MODE_RESIZEABLE) {
                    resizeMode = RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
                }
            } else {
                // This activity has previously marked itself explicitly as both resizeable and
                // supporting picture-in-picture.  Since there is no longer a requirement for
                // picture-in-picture activities to be resizeable, we can mark this simply as
                // resizeable and supporting picture-in-picture separately.
                if (resizeMode == RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED) {
                    resizeMode = RESIZE_MODE_RESIZEABLE;
                    supportsPictureInPicture = true;
                }
            }

            final Task task = create(stackSupervisor.mService,
                    taskId, intent, affinityIntent,
                    affinity, rootAffinity, realActivity, origActivity, rootHasReset,
                    autoRemoveRecents, askedCompatMode, userId, effectiveUid, lastDescription,
                    lastTimeOnTop, neverRelinquishIdentity, taskDescription,
                    taskAffiliation, prevTaskId, nextTaskId, taskAffiliationColor, callingUid,
                    callingPackage, resizeMode, supportsPictureInPicture, realActivitySuspended,
                    userSetupComplete, minWidth, minHeight, null /*stack*/);
            task.mLastNonFullscreenBounds = lastNonFullscreenBounds;
            task.setBounds(lastNonFullscreenBounds);

            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                task.addChild(activities.get(activityNdx));
            }

            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "Restored task=" + task);
            return task;
        }

        void handleUnknownTag(String name, XmlPullParser in)
                throws IOException, XmlPullParserException {
            Slog.e(TAG, "restoreTask: Unexpected name=" + name);
            XmlUtils.skipCurrentTag(in);
        }
    }
}

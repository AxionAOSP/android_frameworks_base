package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_PIP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.animation.MinimizeAnimator;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;

public class FreeformTaskTransitionHandler
        implements Transitions.TransitionHandler, FreeformTaskTransitionStarter {
    private static final String TAG = "FreeformTaskTransitionHandler";
    private static final int CLOSE_ANIM_DURATION = 300;
    private final Transitions mTransitions;
    private final DisplayController mDisplayController;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final Handler mAnimHandler;

    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();
    private final ArrayMap<IBinder, ArrayList<Animator>> mAnimations = new ArrayMap<>();

    public FreeformTaskTransitionHandler(
            Transitions transitions,
            DisplayController displayController,
            ShellExecutor mainExecutor,
            ShellExecutor animExecutor,
            Handler animHandler) {
        mTransitions = transitions;
        mDisplayController = displayController;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mAnimHandler = animHandler;
    }

    @Override
    public void startWindowingModeTransition(
            int targetWindowingMode, WindowContainerTransaction wct) {
        final int type;
        switch (targetWindowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                type = Transitions.TRANSIT_MAXIMIZE;
                break;
            case WINDOWING_MODE_FREEFORM:
                type = Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE;
                break;
            default:
                throw new IllegalArgumentException("Unexpected target windowing mode "
                        + WindowConfiguration.windowingModeToString(targetWindowingMode));
        }
        final IBinder token = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(token);
    }

    @Override
    public IBinder startMinimizedModeTransition(
            WindowContainerTransaction wct, int taskId, boolean isLastTask) {
        final int type = Transitions.TRANSIT_MINIMIZE;
        final IBinder token = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(token);
        return token;
    }

    @Override
    public IBinder startPipTransition(WindowContainerTransaction wct) {
        final IBinder token = mTransitions.startTransition(TRANSIT_PIP, wct, null);
        mPendingTransitionTokens.add(token);
        return token;
    }

    @Override
    public IBinder startRemoveTransition(WindowContainerTransaction wct) {
        final int type = WindowManager.TRANSIT_CLOSE;
        final IBinder transition = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(transition);
        return transition;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                                  @NonNull SurfaceControl.Transaction startT,
                                  @NonNull SurfaceControl.Transaction finishT,
                                  @NonNull Transitions.TransitionFinishCallback finishCallback) {
        boolean transitionHandled = false;
        final ArrayList<Animator> animations = new ArrayList<>();
        final Runnable onAnimFinish = () -> mMainExecutor.execute(() -> {
            if (!animations.isEmpty()) return;
            mAnimations.remove(transition);
            finishCallback.onTransitionFinished(null /* wct */);
        });

        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0) continue;
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId == -1) continue;

            switch (change.getMode()) {
                case WindowManager.TRANSIT_CHANGE:
                    transitionHandled |= startChangeTransition(
                        transition, info.getType(), change, finishT, animations, onAnimFinish);
                    break;
                case WindowManager.TRANSIT_TO_BACK:
                    transitionHandled |= startMinimizeTransition(
                            transition, info.getType(), change, finishT, animations, onAnimFinish);
                    break;
                case WindowManager.TRANSIT_CLOSE:
                    if (change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                        transitionHandled |= startCloseTransition(transition, change,
                                finishT, animations, onAnimFinish);
                    }
                    break;
            }
        }

        if (!transitionHandled) return false;

        mAnimations.put(transition, animations);
        startT.apply();
        mAnimExecutor.execute(() -> {
            for (Animator anim : animations) anim.start();
        });
        onAnimFinish.run();
        mPendingTransitionTokens.remove(transition);
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                               @NonNull SurfaceControl.Transaction startT,
                               @NonNull SurfaceControl.Transaction finishT,
                               @NonNull IBinder mergeTarget,
                               @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ArrayList<Animator> animations = mAnimations.get(mergeTarget);
        if (animations == null) return;
        mAnimExecutor.execute(() -> {
            for (Animator anim : animations) anim.end();
        });
    }

    private boolean startChangeTransition(
            IBinder transition,
            int type,
            TransitionInfo.Change change,
            SurfaceControl.Transaction finishT,
            ArrayList<Animator> animations,
            Runnable onAnimFinish) {

        if (!mPendingTransitionTokens.contains(transition)) return false;

        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (taskInfo == null) return false;

        final SurfaceControl leash = change.getLeash();
        final Rect startBounds = change.getStartAbsBounds();
        final Rect endBounds = change.getEndAbsBounds();
        if (startBounds.equals(endBounds)) return false;

        final int transitionType = type;
        final int changeMode = change.getMode();

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        final ValueAnimator[] animatorHolder = new ValueAnimator[1];

        if (transitionType == Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE &&
            changeMode == WindowManager.TRANSIT_CHANGE &&
            taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {

            float scaleStart = 0.8f;
            float scaleEnd = 1.0f;
            float alphaStart = 0f;
            float alphaEnd = 1f;

            final float finalX = endBounds.left;
            final float finalY = endBounds.top;

            t.setPosition(leash, finalX, finalY);
            t.setScale(leash, scaleStart, scaleStart);
            t.setAlpha(leash, alphaStart);
            t.apply();

            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(350);
            animator.setInterpolator(new OvershootInterpolator());

            animator.addUpdateListener(animation -> {
                float fraction = animation.getAnimatedFraction();
                float scale = scaleStart + (scaleEnd - scaleStart) * fraction;
                float alpha = alphaStart + (alphaEnd - alphaStart) * fraction;

                t.setScale(leash, scale, scale);
                t.setAlpha(leash, alpha);
                t.apply();
            });

            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mMainExecutor.execute(() -> {
                        animations.remove(animator);
                        onAnimFinish.run();
                    });
                }
            });

            animations.add(animator);
            return true;
        }

        boolean fade = false;
        long duration = 300;
        TimeInterpolator interpolator = new AccelerateDecelerateInterpolator();

        if (transitionType == Transitions.TRANSIT_MAXIMIZE &&
            taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            interpolator = new AccelerateInterpolator();
        } else {
            return false;
        }

        final boolean fadeAnim = fade;
        final float startW = startBounds.width();
        final float startH = startBounds.height();
        final float endW = endBounds.width();
        final float endH = endBounds.height();

        final float startAlpha = fadeAnim ? 0f : 1f;
        final float endAlpha = 1f;

        animatorHolder[0] = ValueAnimator.ofFloat(0f, 1f);
        animatorHolder[0].setDuration(duration);
        animatorHolder[0].setInterpolator(interpolator);

        animatorHolder[0].addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();

            float x = startBounds.left + fraction * (endBounds.left - startBounds.left);
            float y = startBounds.top + fraction * (endBounds.top - startBounds.top);

            float scaleX = (startW + fraction * (endW - startW)) / startW;
            float scaleY = (startH + fraction * (endH - startH)) / startH;

            float alpha = fadeAnim
                    ? startAlpha + fraction * (endAlpha - startAlpha)
                    : 1f;

            t.setPosition(leash, x, y);
            t.setScale(leash, scaleX, scaleY);
            t.setAlpha(leash, alpha);
            t.apply();
        });

        animatorHolder[0].addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mMainExecutor.execute(() -> {
                    animations.remove(animatorHolder[0]);
                    onAnimFinish.run();
                });
            }
        });

        animations.add(animatorHolder[0]);
        return true;
    }

    private boolean startMinimizeTransition(
            IBinder transition,
            int type,
            TransitionInfo.Change change,
            SurfaceControl.Transaction finishT,
            ArrayList<Animator> animations,
            Runnable onAnimFinish) {

        if (!mPendingTransitionTokens.contains(transition)) return false;

        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type != Transitions.TRANSIT_MINIMIZE) return false;

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        SurfaceControl sc = change.getLeash();
        finishT.hide(sc);
        final Context displayContext = mDisplayController.getDisplayContext(taskInfo.displayId);
        if (displayContext == null) {
            Log.w(TAG, "No displayContext for displayId=" + taskInfo.displayId);
            return false;
        }

        final Animator animator = MinimizeAnimator.create(
                displayContext, change, t,
                (anim) -> {
                    mMainExecutor.execute(() -> {
                        animations.remove(anim);
                        onAnimFinish.run();
                    });
                    return null;
                },
                InteractionJankMonitor.getInstance(),
                mAnimHandler);
        animations.add(animator);
        return true;
    }

    private boolean startCloseTransition(IBinder transition, TransitionInfo.Change change,
                                         SurfaceControl.Transaction finishT, ArrayList<Animator> animations,
                                         Runnable onAnimFinish) {
        if (!mPendingTransitionTokens.contains(transition)) return false;

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        SurfaceControl sc = change.getLeash();
        finishT.hide(sc);

        final Rect startBounds = new Rect(change.getStartAbsBounds());
        final float startX = startBounds.left;
        final float startY = startBounds.top;
        final float startWidth = startBounds.width();
        final float startHeight = startBounds.height();

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CLOSE_ANIM_DURATION);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());

        animator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            float scale = 1f - 0.2f * fraction;
            float alpha = 1f - fraction;

            t.setPosition(sc,
                startX + (startWidth * (1f - scale) / 2),
                startY + (startHeight * (1f - scale) / 2));
            t.setScale(sc, scale, scale);
            t.setAlpha(sc, alpha);
            t.apply();
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mMainExecutor.execute(() -> {
                    animations.remove(animator);
                    onAnimFinish.run();
                });
            }
        });

        animations.add(animator);
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                                                    @NonNull TransitionRequestInfo request) {
        return null;
    }
}

package com.lolicon.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * @author weishu
 * @date 2023/11/2.
 */
public final class Entry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMiuiHome(lpparam.packageName, lpparam.classLoader);
        hookSystemUi(lpparam.packageName, lpparam.classLoader);
    }

    private void hookSystemUi(String pkg, ClassLoader classLoader) {
        if (!"com.android.systemui".equals(pkg)) {
            return;
        }

        XC_MethodHook skipDefaultHomeChange = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(null);
            }
        };

        boolean hooked = tryHookMethod("com.android.systemui.assist.PhoneStateMonitorController",
                classLoader, "onDefaultHomeChanged", skipDefaultHomeChange, ComponentName.class);
        if (!hooked) {
            tryHookMethod("com.android.systemui.assist.PhoneStateMonitor",
                    classLoader, "onDefaultHomeChanged", skipDefaultHomeChange);
        }
    }

    private void hookMiuiHome(String pkg, ClassLoader classLoader) {
        if (!"com.miui.home".equals(pkg)) {
            return;
        }

        XC_MethodHook forceUsePocoAsDefaultHome = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(true);
            }
        };
        boolean defaultHomeHooked = tryHookMethod("com.miui.home.common.utils.BuildConfigUtils",
                classLoader, "isUsePocoHomeAsDefaultHome", forceUsePocoAsDefaultHome, Context.class);
        if (!defaultHomeHooked) {
            tryHookMethod("com.miui.home.launcher.common.Utilities",
                    classLoader, "isUsePocoHomeAsDefaultHome", forceUsePocoAsDefaultHome, Context.class);
        }

        tryHookMethod("com.miui.home.recents.BaseRecentsImpl", classLoader,
                "setIsUseMiuiHomeAsDefaultHome", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        log("setIsUseMiuiHomeAsDefaultHome: " + param.args[0]);
                        param.setResult(null);
                    }
                }, boolean.class);

        tryHookMethod("com.miui.home.recents.BaseRecentsImpl", classLoader,
                "updateFsgWindowState", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        boolean mIsUseMiuiHomeAsDefaultHome =
                                XposedHelpers.getBooleanField(param.thisObject, "mIsUseMiuiHomeAsDefaultHome");
                        log("mIsUseMiuiHomeAsDefaultHome: " + mIsUseMiuiHomeAsDefaultHome);
                        XposedHelpers.setBooleanField(param.thisObject, "mIsUseMiuiHomeAsDefaultHome", true);
                    }
                });

        tryHookMethod("com.miui.home.recents.BaseRecentsImpl", classLoader,
                "updateUseLauncherRecentsAndFsGesture", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        XposedHelpers.setBooleanField(param.thisObject, "mIsUseMiuiHomeAsDefaultHome", true);
                    }
                });

        AtomicBoolean isRecent = new AtomicBoolean(false);
        tryHookMethod("com.miui.home.recents.NavStubView", classLoader, "performAppToHome", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                log("performAppToHome");
                View view = (View) param.thisObject;
                Context context = view.getContext();

                log("context: " + context);

                if (isRecent.getAndSet(false)) {
                    // Prefer MIUI's own recents entrypoint so overview is shown as a gesture
                    // transition instead of a plain activity switch animation.
                    view.post(() -> startRecentsActivityCompat(param.thisObject, context));
                    return;
                }

                view.postDelayed(() -> startHomeActivity(context), 100);
                startAppToHomeAnimCompat(param.thisObject);
            }
        });

        tryHookMethod("com.miui.home.recents.NavStubView", classLoader,
                "performAppToRecents", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        log("performAppToRecents");
                        isRecent.set(true);
                    }
                }, boolean.class);
    }

    private static boolean tryHookMethod(String className, ClassLoader classLoader, String methodName,
                                         XC_MethodHook callback, Object... parameterTypes) {
        Object[] args = Arrays.copyOf(parameterTypes, parameterTypes.length + 1);
        args[parameterTypes.length] = callback;
        try {
            XposedHelpers.findAndHookMethod(className, classLoader, methodName, args);
            log("hooked " + className + "#" + methodName);
            return true;
        } catch (Throwable e) {
            log("skip hook " + className + "#" + methodName, e);
            return false;
        }
    }

    private static void startAppToHomeAnimCompat(Object navStubView) {
        if (tryCallMethod(navStubView, "startAppToHomeAnim")) {
            return;
        }
        if (tryCallMethod(navStubView, "startAppToHomeAnim", new Object[]{null})) {
            return;
        }
        log("skip call startAppToHomeAnim");
    }

    private static void startRecentsActivityCompat(Object navStubView, Context context) {
        if (tryCallMethod(navStubView, "startRecentsActivityHyper")) {
            return;
        }
        if (tryCallMethod(navStubView, "startRecentsActivityAtLeastW")) {
            return;
        }
        if (tryCallMethod(navStubView, "startRecentsActivityAtLeastU")) {
            return;
        }
        if (tryCallMethod(navStubView, "startRecentsActivityAtLeastS")) {
            return;
        }
        if (tryCallMethod(navStubView, "startRecentsActivity")) {
            return;
        }
        log("fallback to explicit RecentsActivity launch");
        startRecentsActivityFallback(context);
    }

    private static void startRecentsActivityFallback(Context context) {
        Intent intent = new Intent();
        ComponentName componentName = ComponentName.unflattenFromString("com.miui.home/.recents.RecentsActivity");
        intent.setComponent(componentName);
        intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.startActivity(intent);
    }

    private static void startHomeActivity(Context context) {
        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startMain.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(startMain);
    }

    private static boolean tryCallMethod(Object receiver, String methodName, Object... args) {
        try {
            XposedHelpers.callMethod(receiver, methodName, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final String TAG = "Launcher";

    public static void log(CharSequence msg) {
        if (msg == null) {
            return;
        }
        Log.i(TAG, msg.toString());
    }

    public static void log(CharSequence msg, Throwable th) {
        if (msg == null) {
            return;
        }
        Log.i(TAG, msg.toString(), th);
    }
}

package com.zaid.densityreset.density;

import android.content.Context;
import android.os.IBinder;
import android.view.Display;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Entry point executed by app_process through Shizuku's shell identity.
 * It talks directly to the WindowManager Binder and prints one machine-readable result line.
 */
public final class DensityBridge {

    private static final String RESULT_PREFIX = "ZAID_DENSITY_RESULT";

    private DensityBridge() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            printError("INVALID_ARGUMENTS", "Missing action or user id");
            return;
        }

        final String action = args[0];
        final int userId;
        try {
            userId = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            printError("INVALID_ARGUMENTS", "Invalid user id");
            return;
        }

        try {
            Object windowManager = getWindowManagerInterface();
            switch (action) {
                case "status":
                    printState(windowManager);
                    break;
                case "apply":
                    if (args.length < 3) {
                        printError("INVALID_ARGUMENTS", "Missing density");
                        return;
                    }
                    int density = Integer.parseInt(args[2]);
                    applyDensity(windowManager, density, userId);
                    break;
                case "reset":
                    resetDensity(windowManager, userId);
                    break;
                default:
                    printError("INVALID_ARGUMENTS", "Unknown action");
                    break;
            }
        } catch (Throwable throwable) {
            Throwable cause = unwrap(throwable);
            String className = cause.getClass().getName();
            String message = safeMessage(cause);

            if (cause instanceof SecurityException) {
                printError("SECURITY_EXCEPTION", message);
            } else if (className.contains("RemoteException") || className.contains("DeadObjectException")) {
                printError("REMOTE_EXCEPTION", message);
            } else if (cause instanceof ClassNotFoundException || cause instanceof NoSuchMethodException) {
                printError("HIDDEN_API_UNAVAILABLE", message);
            } else if (cause instanceof IllegalArgumentException) {
                printError("DENSITY_REJECTED", message);
            } else {
                printError("WINDOW_MANAGER_UNAVAILABLE", className + ": " + message);
            }
        }
    }

    private static Object getWindowManagerInterface() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, Context.WINDOW_SERVICE);
        if (binder == null) {
            throw new IllegalStateException("WindowManager Binder is null");
        }

        Class<?> stubClass = Class.forName("android.view.IWindowManager$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object windowManager = asInterface.invoke(null, binder);
        if (windowManager == null) {
            throw new IllegalStateException("IWindowManager is null");
        }
        return windowManager;
    }

    private static void applyDensity(Object windowManager, int density, int userId) throws Exception {
        if (density <= 0) {
            throw new IllegalArgumentException("Density must be positive");
        }

        invoke(
                windowManager,
                "setForcedDisplayDensityForUser",
                new Class<?>[]{int.class, int.class, int.class},
                Display.DEFAULT_DISPLAY,
                density,
                userId
        );
        waitForWindowManager();

        int initial = getInitialDensity(windowManager);
        int current = getCurrentDensity(windowManager);
        if (current != density) {
            printError(
                    "VERIFY_FAILED",
                    "Expected " + density + " but WindowManager returned " + current
            );
            return;
        }
        printSuccess(initial, current, current != initial);
    }

    private static void resetDensity(Object windowManager, int userId) throws Exception {
        invoke(
                windowManager,
                "clearForcedDisplayDensityForUser",
                new Class<?>[]{int.class, int.class},
                Display.DEFAULT_DISPLAY,
                userId
        );
        waitForWindowManager();

        int initial = getInitialDensity(windowManager);
        int current = getCurrentDensity(windowManager);
        if (current != initial) {
            printError(
                    "VERIFY_FAILED",
                    "WindowManager still reports override density " + current
            );
            return;
        }
        printSuccess(initial, current, false);
    }

    private static void printState(Object windowManager) throws Exception {
        int initial = getInitialDensity(windowManager);
        int current = getCurrentDensity(windowManager);
        printSuccess(initial, current, current != initial);
    }

    private static int getInitialDensity(Object windowManager) throws Exception {
        Object result = invoke(
                windowManager,
                "getInitialDisplayDensity",
                new Class<?>[]{int.class},
                Display.DEFAULT_DISPLAY
        );
        return ((Number) result).intValue();
    }

    private static int getCurrentDensity(Object windowManager) throws Exception {
        Object result = invoke(
                windowManager,
                "getBaseDisplayDensity",
                new Class<?>[]{int.class},
                Display.DEFAULT_DISPLAY
        );
        return ((Number) result).intValue();
    }

    private static Object invoke(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Class<?> interfaceClass = Class.forName("android.view.IWindowManager");
        Method method = interfaceClass.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getTargetException();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw exception;
        }
    }

    private static void waitForWindowManager() {
        try {
            Thread.sleep(350L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printSuccess(int initial, int current, boolean hasOverride) {
        System.out.println(
                RESULT_PREFIX
                        + "|ok=1"
                        + "|initial=" + initial
                        + "|current=" + current
                        + "|override=" + (hasOverride ? 1 : 0)
        );
    }

    private static void printError(String code, String message) {
        System.out.println(
                RESULT_PREFIX
                        + "|ok=0"
                        + "|code=" + sanitize(code)
                        + "|message=" + sanitize(message)
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value
                .replace('|', '/')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }
}

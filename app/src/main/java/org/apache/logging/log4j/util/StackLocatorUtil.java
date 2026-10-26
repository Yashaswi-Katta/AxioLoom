package org.apache.logging.log4j.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

public final class StackLocatorUtil {
    private StackLocatorUtil() {}

    public static Class<?> getCallerClass(final int depth) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            int target = depth + 3;
            if (target < stack.length) {
                return Class.forName(stack[target].getClassName());
            }
        } catch (Throwable ignored) {}
        return StackLocatorUtil.class;
    }

    public static Class<?> getCallerClass(final Class<?> anchor) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            boolean foundAnchor = false;
            for (StackTraceElement elem : stack) {
                if (foundAnchor) {
                    if (!elem.getClassName().equals(anchor.getName())) {
                        return Class.forName(elem.getClassName());
                    }
                } else if (elem.getClassName().equals(anchor.getName())) {
                    foundAnchor = true;
                }
            }
        } catch (Throwable ignored) {}
        return StackLocatorUtil.class;
    }

    public static Class<?> getCallerClass(final String fqcn) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            boolean found = false;
            for (StackTraceElement elem : stack) {
                if (found) {
                    if (!elem.getClassName().equals(fqcn)) {
                        return Class.forName(elem.getClassName());
                    }
                } else if (elem.getClassName().equals(fqcn)) {
                    found = true;
                }
            }
        } catch (Throwable ignored) {}
        return StackLocatorUtil.class;
    }

    public static Class<?> getCallerClass(final String fqcn, final String pkg) {
        return getCallerClass(fqcn);
    }

    public static Class<?> getCallerClass(final Class<?> sentinelClass, final Predicate<Class<?>> callerPredicate) {
        return StackLocatorUtil.class;
    }

    public static ClassLoader getCallerClassLoader(final int depth) {
        Class<?> clazz = getCallerClass(depth + 1);
        return clazz != null ? clazz.getClassLoader() : StackLocatorUtil.class.getClassLoader();
    }

    public static StackTraceElement calcLocation(final String fqcnOfLogger) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            boolean found = false;
            for (StackTraceElement elem : stack) {
                if (found) return elem;
                if (elem.getClassName().equals(fqcnOfLogger)) found = true;
            }
        } catch (Throwable ignored) {}
        return new StackTraceElement("AndroidLogger", "log", "AndroidLogger.java", 1);
    }

    public static StackTraceElement getStackTraceElement(final int depth) {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            int target = depth + 3;
            if (target < stack.length) return stack[target];
        } catch (Throwable ignored) {}
        return new StackTraceElement("AndroidLogger", "log", "AndroidLogger.java", 1);
    }

    public static Deque<Class<?>> getCurrentStackTrace() {
        return new ArrayDeque<>();
    }
}

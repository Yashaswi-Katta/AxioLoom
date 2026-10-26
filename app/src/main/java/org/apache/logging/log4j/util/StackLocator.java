package org.apache.logging.log4j.util;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

public final class StackLocator {
    public static Method GET_CALLER_CLASS_METHOD = null;
    public static int JDK_7U25_OFFSET = 0;

    private static final StackLocator INSTANCE = new StackLocator();

    public static StackLocator getInstance() {
        return INSTANCE;
    }

    public Class<?> getCallerClass(final int depth) {
        return StackLocatorUtil.getCallerClass(depth);
    }

    public Class<?> getCallerClass(final String fqcn) {
        return StackLocatorUtil.getCallerClass(fqcn);
    }

    public Class<?> getCallerClass(final Class<?> anchor) {
        return StackLocatorUtil.getCallerClass(anchor);
    }

    public Class<?> getCallerClass(final Class<?> sentinelClass, final Predicate<Class<?>> callerPredicate) {
        return StackLocatorUtil.getCallerClass(sentinelClass, callerPredicate);
    }

    public StackTraceElement calcLocation(final String fqcnOfLogger) {
        return StackLocatorUtil.calcLocation(fqcnOfLogger);
    }

    public StackTraceElement getStackTraceElement(final int depth) {
        return StackLocatorUtil.getStackTraceElement(depth);
    }

    public Deque<Class<?>> getCurrentStackTrace() {
        return new ArrayDeque<>();
    }
}

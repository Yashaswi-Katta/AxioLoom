package org.apache.logging.log4j;

import android.util.Log;
import org.apache.logging.log4j.util.Supplier;

class AndroidLog4jLogger implements Logger {
    private final String name;
    private Level level = Level.DEBUG;

    AndroidLog4jLogger(String name) {
        this.name = name != null && !name.isEmpty() ? name : "Symja";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Level getLevel() {
        return level;
    }

    @Override
    public boolean isTraceEnabled() { return false; }
    @Override
    public boolean isDebugEnabled() { return true; }
    @Override
    public boolean isInfoEnabled() { return true; }
    @Override
    public boolean isWarnEnabled() { return true; }
    @Override
    public boolean isErrorEnabled() { return true; }
    @Override
    public boolean isFatalEnabled() { return true; }

    private String format(String message, Object... params) {
        if (params == null || params.length == 0 || message == null) return String.valueOf(message);
        StringBuilder sb = new StringBuilder();
        int paramIdx = 0;
        int prevIdx = 0;
        int nextPlaceholder;
        while ((nextPlaceholder = message.indexOf("{}", prevIdx)) != -1 && paramIdx < params.length) {
            sb.append(message, prevIdx, nextPlaceholder);
            sb.append(params[paramIdx++]);
            prevIdx = nextPlaceholder + 2;
        }
        sb.append(message.substring(prevIdx));
        return sb.toString();
    }

    private void logInternal(int priority, String msg, Throwable t) {
        if (priority == Log.VERBOSE && !isTraceEnabled()) return;
        try {
            if (t != null) {
                Log.println(priority, name, (msg != null ? msg : "") + '\n' + Log.getStackTraceString(t));
            } else {
                Log.println(priority, name, msg != null ? msg : "null");
            }
        } catch (Throwable ignored) {
            if (priority >= Log.WARN) {
                System.err.println("[" + name + "] " + msg);
                if (t != null) t.printStackTrace(System.err);
            }
        }
    }

    // trace
    @Override public void trace(String message) { logInternal(Log.VERBOSE, message, null); }
    @Override public void trace(String message, Object p0) { logInternal(Log.VERBOSE, format(message, p0), null); }
    @Override public void trace(String message, Object p0, Object p1) { logInternal(Log.VERBOSE, format(message, p0, p1), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2) { logInternal(Log.VERBOSE, format(message, p0, p1, p2), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3, p4), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3, p4, p5), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3, p4, p5, p6), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3, p4, p5, p6, p7), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8), null); }
    @Override public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { logInternal(Log.VERBOSE, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9), null); }
    @Override public void trace(String message, Object... params) { logInternal(Log.VERBOSE, format(message, params), null); }
    @Override public void trace(String message, Throwable t) { logInternal(Log.VERBOSE, message, t); }
    @Override public void trace(Object message) { trace(String.valueOf(message)); }
    @Override public void trace(Object message, Throwable t) { logInternal(Log.VERBOSE, String.valueOf(message), t); }

    // debug
    @Override public void debug(String message) { logInternal(Log.DEBUG, message, null); }
    @Override public void debug(String message, Object p0) { logInternal(Log.DEBUG, format(message, p0), null); }
    @Override public void debug(String message, Object p0, Object p1) { logInternal(Log.DEBUG, format(message, p0, p1), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2) { logInternal(Log.DEBUG, format(message, p0, p1, p2), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3, p4), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3, p4, p5), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3, p4, p5, p6), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3, p4, p5, p6, p7), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8), null); }
    @Override public void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { logInternal(Log.DEBUG, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9), null); }
    @Override public void debug(String message, Object... params) { logInternal(Log.DEBUG, format(message, params), null); }
    @Override public void debug(String message, Throwable t) { logInternal(Log.DEBUG, message, t); }
    @Override public void debug(Object message) { debug(String.valueOf(message)); }
    @Override public void debug(Object message, Throwable t) { logInternal(Log.DEBUG, String.valueOf(message), t); }

    // info
    @Override public void info(String message) { logInternal(Log.INFO, message, null); }
    @Override public void info(String message, Object p0) { logInternal(Log.INFO, format(message, p0), null); }
    @Override public void info(String message, Object p0, Object p1) { logInternal(Log.INFO, format(message, p0, p1), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2) { logInternal(Log.INFO, format(message, p0, p1, p2), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3) { logInternal(Log.INFO, format(message, p0, p1, p2, p3), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4) { logInternal(Log.INFO, format(message, p0, p1, p2, p3, p4), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { logInternal(Log.INFO, format(message, p0, p1, p2, p3, p4, p5), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { logInternal(Log.INFO, format(message, p0, p1, p2, p3, p4, p5, p6), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { logInternal(Log.INFO, format(message, p0, p1, p2, p3, p4, p5, p6, p7), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { logInternal(Log.INFO, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8), null); }
    @Override public void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { logInternal(Log.INFO, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9), null); }
    @Override public void info(String message, Object... params) { logInternal(Log.INFO, format(message, params), null); }
    @Override public void info(String message, Throwable t) { logInternal(Log.INFO, message, t); }
    @Override public void info(Object message) { info(String.valueOf(message)); }
    @Override public void info(Object message, Throwable t) { logInternal(Log.INFO, String.valueOf(message), t); }

    // warn
    @Override public void warn(String message) { logInternal(Log.WARN, message, null); }
    @Override public void warn(String message, Object p0) { logInternal(Log.WARN, format(message, p0), null); }
    @Override public void warn(String message, Object p0, Object p1) { logInternal(Log.WARN, format(message, p0, p1), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2) { logInternal(Log.WARN, format(message, p0, p1, p2), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3) { logInternal(Log.WARN, format(message, p0, p1, p2, p3), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4) { logInternal(Log.WARN, format(message, p0, p1, p2, p3, p4), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { logInternal(Log.WARN, format(message, p0, p1, p2, p3, p4, p5), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { logInternal(Log.WARN, format(message, p0, p1, p2, p3, p4, p5, p6), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { logInternal(Log.WARN, format(message, p0, p1, p2, p3, p4, p5, p6, p7), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { logInternal(Log.WARN, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8), null); }
    @Override public void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { logInternal(Log.WARN, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9), null); }
    @Override public void warn(String message, Object... params) { logInternal(Log.WARN, format(message, params), null); }
    @Override public void warn(String message, Throwable t) { logInternal(Log.WARN, message, t); }
    @Override public void warn(Object message) { warn(String.valueOf(message)); }
    @Override public void warn(Object message, Throwable t) { logInternal(Log.WARN, String.valueOf(message), t); }

    // error
    @Override public void error(String message) { logInternal(Log.ERROR, message, null); }
    @Override public void error(String message, Object p0) { logInternal(Log.ERROR, format(message, p0), null); }
    @Override public void error(String message, Object p0, Object p1) { logInternal(Log.ERROR, format(message, p0, p1), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2) { logInternal(Log.ERROR, format(message, p0, p1, p2), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3, p4), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3, p4, p5), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3, p4, p5, p6), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3, p4, p5, p6, p7), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8), null); }
    @Override public void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { logInternal(Log.ERROR, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9), null); }
    @Override public void error(String message, Object... params) { logInternal(Log.ERROR, format(message, params), null); }
    @Override public void error(String message, Throwable t) { logInternal(Log.ERROR, message, t); }
    @Override public void error(Object message) { error(String.valueOf(message)); }
    @Override public void error(Object message, Throwable t) { logInternal(Log.ERROR, String.valueOf(message), t); }

    // fatal
    @Override public void fatal(String message) { logInternal(Log.ASSERT, message, null); }
    @Override public void fatal(String message, Object p0) { logInternal(Log.ASSERT, format(message, p0), null); }
    @Override public void fatal(String message, Object p0, Object p1) { logInternal(Log.ASSERT, format(message, p0, p1), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2) { logInternal(Log.ASSERT, format(message, p0, p1, p2), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3, p4), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3, p4, p5), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3, p4, p5, p6), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3, p4, p5, p6, p7), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8), null); }
    @Override public void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { logInternal(Log.ASSERT, format(message, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9), null); }
    @Override public void fatal(String message, Object... params) { logInternal(Log.ASSERT, format(message, params), null); }
    @Override public void fatal(String message, Throwable t) { logInternal(Log.ASSERT, message, t); }
    @Override public void fatal(Object message) { fatal(String.valueOf(message)); }
    @Override public void fatal(Object message, Throwable t) { logInternal(Log.ASSERT, String.valueOf(message), t); }

    // log
    @Override
    public void log(Level level, String message) {
        int prio = level != null && level.isMoreSpecificThan(Level.ERROR) ? Log.ERROR : (level == Level.WARN ? Log.WARN : Log.INFO);
        logInternal(prio, message, null);
    }
    @Override public void log(Level level, String message, Object p0) { log(level, format(message, p0)); }
    @Override public void log(Level level, String message, Object p0, Object p1) { log(level, format(message, p0, p1)); }
    @Override public void log(Level level, String message, Object p0, Object p1, Object p2) { log(level, format(message, p0, p1, p2)); }
    @Override public void log(Level level, String message, Object p0, Object p1, Object p2, Object p3) { log(level, format(message, p0, p1, p2, p3)); }
    @Override public void log(Level level, String message, Object... params) { log(level, format(message, params)); }
    @Override
    public void log(Level level, String message, Throwable t) {
        int prio = level != null && level.isMoreSpecificThan(Level.ERROR) ? Log.ERROR : (level == Level.WARN ? Log.WARN : Log.INFO);
        logInternal(prio, message, t);
    }
    @Override
    public void log(Level level, Object message) {
        log(level, String.valueOf(message));
    }
    @Override
    public void log(Level level, Object message, Throwable t) {
        int prio = level != null && level.isMoreSpecificThan(Level.ERROR) ? Log.ERROR : (level == Level.WARN ? Log.WARN : Log.INFO);
        logInternal(prio, String.valueOf(message), t);
    }
    @Override
    public void log(Level level, Supplier<?> messageSupplier) {
        if (messageSupplier != null) {
            Object obj = messageSupplier.get();
            log(level, String.valueOf(obj));
        }
    }
}

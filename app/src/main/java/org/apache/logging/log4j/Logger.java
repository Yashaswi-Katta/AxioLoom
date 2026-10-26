package org.apache.logging.log4j;

import org.apache.logging.log4j.util.Supplier;

public interface Logger {
    String getName();

    Level getLevel();

    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();
    boolean isFatalEnabled();

    // trace
    void trace(String message);
    void trace(String message, Object p0);
    void trace(String message, Object p0, Object p1);
    void trace(String message, Object p0, Object p1, Object p2);
    void trace(String message, Object p0, Object p1, Object p2, Object p3);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9);
    void trace(String message, Object... params);
    void trace(String message, Throwable t);
    void trace(Object message);
    void trace(Object message, Throwable t);

    // debug
    void debug(String message);
    void debug(String message, Object p0);
    void debug(String message, Object p0, Object p1);
    void debug(String message, Object p0, Object p1, Object p2);
    void debug(String message, Object p0, Object p1, Object p2, Object p3);
    void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4);
    void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5);
    void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6);
    void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
    void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8);
    void debug(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9);
    void debug(String message, Object... params);
    void debug(String message, Throwable t);
    void debug(Object message);
    void debug(Object message, Throwable t);

    // info
    void info(String message);
    void info(String message, Object p0);
    void info(String message, Object p0, Object p1);
    void info(String message, Object p0, Object p1, Object p2);
    void info(String message, Object p0, Object p1, Object p2, Object p3);
    void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4);
    void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5);
    void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6);
    void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
    void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8);
    void info(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9);
    void info(String message, Object... params);
    void info(String message, Throwable t);
    void info(Object message);
    void info(Object message, Throwable t);

    // warn
    void warn(String message);
    void warn(String message, Object p0);
    void warn(String message, Object p0, Object p1);
    void warn(String message, Object p0, Object p1, Object p2);
    void warn(String message, Object p0, Object p1, Object p2, Object p3);
    void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4);
    void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5);
    void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6);
    void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
    void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8);
    void warn(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9);
    void warn(String message, Object... params);
    void warn(String message, Throwable t);
    void warn(Object message);
    void warn(Object message, Throwable t);

    // error
    void error(String message);
    void error(String message, Object p0);
    void error(String message, Object p0, Object p1);
    void error(String message, Object p0, Object p1, Object p2);
    void error(String message, Object p0, Object p1, Object p2, Object p3);
    void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4);
    void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5);
    void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6);
    void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
    void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8);
    void error(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9);
    void error(String message, Object... params);
    void error(String message, Throwable t);
    void error(Object message);
    void error(Object message, Throwable t);

    // fatal
    void fatal(String message);
    void fatal(String message, Object p0);
    void fatal(String message, Object p0, Object p1);
    void fatal(String message, Object p0, Object p1, Object p2);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8);
    void fatal(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9);
    void fatal(String message, Object... params);
    void fatal(String message, Throwable t);
    void fatal(Object message);
    void fatal(Object message, Throwable t);

    // log
    void log(Level level, String message);
    void log(Level level, String message, Object p0);
    void log(Level level, String message, Object p0, Object p1);
    void log(Level level, String message, Object p0, Object p1, Object p2);
    void log(Level level, String message, Object p0, Object p1, Object p2, Object p3);
    void log(Level level, String message, Object... params);
    void log(Level level, String message, Throwable t);
    void log(Level level, Object message);
    void log(Level level, Object message, Throwable t);
    void log(Level level, Supplier<?> messageSupplier);
}

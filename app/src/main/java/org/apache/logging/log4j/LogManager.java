package org.apache.logging.log4j;

import org.apache.logging.log4j.message.MessageFactory;

public class LogManager {
    public static final String ROOT_LOGGER_NAME = "";

    public static Logger getLogger() {
        return getLogger("AxioLoom-Symja");
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz != null ? clazz.getSimpleName() : "AxioLoom-Symja");
    }

    public static Logger getLogger(String name) {
        return new AndroidLog4jLogger(name != null ? name : "AxioLoom-Symja");
    }

    public static Logger getLogger(Object value) {
        return getLogger(value != null ? value.getClass() : null);
    }

    public static Logger getLogger(Class<?> clazz, MessageFactory messageFactory) {
        return getLogger(clazz);
    }

    public static Logger getLogger(String name, MessageFactory messageFactory) {
        return getLogger(name);
    }

    public static Logger getRootLogger() {
        return getLogger(ROOT_LOGGER_NAME);
    }
}

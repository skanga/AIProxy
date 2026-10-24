package com.aiproxy.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/** Capture event fields rather than depending on a console stream or layout. */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    public LogCapture(String name) {
        logger = (Logger) LoggerFactory.getLogger(name);
        appender.start();
        logger.addAppender(appender);
    }

    public String text() {
        return appender.list.stream().map(event -> event.getFormattedMessage() + " "
                + event.getMDCPropertyMap() + " " + event.getKeyValuePairs())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Override public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}

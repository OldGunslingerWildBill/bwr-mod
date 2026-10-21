package dev.bwr.mod.devtest;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects what our code logs while a test runs: warnings and errors from any {@code dev.bwr} logger, and a count of
 * everything it logs at all, so a test can fail on an exception stack in the log (a ticker that throws and is caught
 * and logged keeps the server up and shows nowhere else) or on a message repeated every tick.
 */
final class LogWatch extends AbstractAppender {
    private static final String OURS = "dev.bwr";
    final List<String> problems = new CopyOnWriteArrayList<>();
    private int ours;

    private LogWatch() {
        super("bwr-test-log-watch", null, null, true, Property.EMPTY_ARRAY);
    }

    static LogWatch begin() {
        LogWatch watch = new LogWatch();
        watch.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(watch);
        return watch;
    }

    void stop_() {
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        String name = event.getLoggerName();
        if (name == null || !name.startsWith(OURS)) {
            return;
        }
        ours++;
        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
            String text = name + " " + event.getLevel() + ": " + event.getMessage().getFormattedMessage();
            if (event.getThrown() != null) {
                text += " [" + event.getThrown() + "]";
            }
            problems.add(text);
        }
    }

    /** Messages logged by our code since this watch started, of any level. */
    int ourMessages() {
        return ours;
    }
}

package com.kaboom;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

public class ModLogger {
    public static final Logger LOGGER =
            LogManager.getLogger(Kaboom.MODID);

    private static final String APPENDER_NAME = "LTS_KABOOM_FILE";

    private ModLogger() {}

    public static synchronized void initialize(File gameDirectory) {
        LoggerContext context =
                (LoggerContext) LogManager.getContext(false);

        Configuration config = context.getConfiguration();

        if (config.getAppender(APPENDER_NAME) != null) {
            return;
        }

        File logsDir = new File(gameDirectory, "logs");

        File logFile = new File(logsDir, "LTS_KABOOM.log");
        File archive = new File(logsDir, "LTS_KABOOM-%i.log.gz");

        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(config)
                .withCharset(StandardCharsets.UTF_8)
                .withPattern(
                        "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t/%level] %msg%n%throwable"
                )
                .build();

        RollingFileAppender appender = RollingFileAppender.newBuilder()
                .setConfiguration(config)
                .withName(APPENDER_NAME)
                .withFileName(logFile.getAbsolutePath())
                .withFilePattern(archive.getAbsolutePath())
                .withPolicy(SizeBasedTriggeringPolicy.createPolicy("10 MB"))
                .withLayout(layout)
                .withAppend(true)
                .withImmediateFlush(true)
                .build();

        if (appender == null) {
            throw new IllegalStateException("Failed to create mod logger");
        }

        appender.start();

        config.addAppender(appender);
        config.addLoggerAppender(
                context.getLogger(LOGGER.getName()),
                appender
        );

        config.getLoggerConfig(LOGGER.getName()).setLevel(Level.ALL);

        context.updateLoggers();

        LOGGER.info("Mod logging initialized: {}", logFile.getAbsolutePath());
    }

}

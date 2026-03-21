package com.satya.oms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration manager for OMS UI settings.
 */
public final class OMSUIConfig {
    private static final Logger logger = LoggerFactory.getLogger(OMSUIConfig.class);

    private static final String CONFIG_FILE = "oms-ui.properties";
    private static final Properties properties = new Properties();

    private static final String DEFAULT_AERON_CHANNEL = "aeron:ipc?term-length=64k";
    private static final int DEFAULT_AERON_IN_STREAM_ID = 1001;
    private static final int DEFAULT_AERON_OUT_STREAM_ID = 1002;
    private static final int DEFAULT_BLOTTER_MAX_ORDERS = 500;

    static {
        loadProperties();
    }

    private OMSUIConfig() {
    }

    private static void loadProperties() {
        try (InputStream input = OMSUIConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                properties.load(input);
                logger.info("Loaded configuration from {}", CONFIG_FILE);
            } else {
                logger.warn("Configuration file {} not found, using defaults", CONFIG_FILE);
            }
        } catch (IOException e) {
            logger.error("Error loading configuration file {}", CONFIG_FILE, e);
        }
    }

    public static String getAeronChannel() {
        return properties.getProperty("aeron.channel", DEFAULT_AERON_CHANNEL);
    }

    public static int getAeronInStreamId() {
        return Integer.parseInt(properties.getProperty("aeron.in.stream.id", String.valueOf(DEFAULT_AERON_IN_STREAM_ID)));
    }

    public static int getAeronOutStreamId() {
        return Integer.parseInt(properties.getProperty("aeron.out.stream.id", String.valueOf(DEFAULT_AERON_OUT_STREAM_ID)));
    }

    public static int getBlotterMaxOrders() {
        return Integer.parseInt(properties.getProperty("oms.ui.blotter.maxOrders", String.valueOf(DEFAULT_BLOTTER_MAX_ORDERS)));
    }
}

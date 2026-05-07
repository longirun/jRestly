package ru.jrestly;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AppProperties {
    private final Logger logger = LogManager.getLogger(getClass());

    private final static AppProperties instance = new AppProperties();
    private Configuration config;

    public AppProperties() {
        load();
    }

    public static Configuration all() {
        return instance.config;
    }

    public static String get(String key) {
        return instance.config != null ? instance.config.getString(key) : null;
    }

    private void load() {
        try {
            Parameters params = new Parameters();
            FileBasedConfigurationBuilder<PropertiesConfiguration> builder =
                    new FileBasedConfigurationBuilder<>(PropertiesConfiguration.class)
                            .configure(params.properties().setFileName("application.properties").setEncoding("UTF-8"));

            this.config = builder.getConfiguration();
        } catch(Exception e) {
            logger.error("Properties init error", e);
        }
    }
}

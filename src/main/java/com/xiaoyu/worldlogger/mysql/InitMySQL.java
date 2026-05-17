package com.xiaoyu.worldlogger.mysql;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

public class InitMySQL {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static HikariDataSource DataSource;
    private static boolean available = false;

    public static void InitHikari() {
        try {
            HikariConfig hikariConfig = new HikariConfig();

            String URL = Config.DATABASE_URL.get();
            if (!(URL.endsWith("/"))) {
                URL += "/";
            }

            hikariConfig.setJdbcUrl(URL + Config.DATABASE_NAME.get());
            hikariConfig.setUsername(Config.DATABASE_USERNAME.get());
            hikariConfig.setPassword(Config.DATABASE_PASSWORD.get());

            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setIdleTimeout(30000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setConnectionTimeout(10000);

            DataSource = new HikariDataSource(hikariConfig);

            available = true;
            LOGGER.info("Hikari DataSource initialized successfully.");
        } catch (Exception e) {
            LOGGER.error("Hikari DataSource initialization failed.", e);
        }
    }

    public static Connection getMySQLConnection() throws SQLException {
        if (DataSource == null || !(available)) {
            throw new IllegalStateException("HikariCP not initialized");
        }
        return DataSource.getConnection();
    }

    public static void closeHikari() {
        if (DataSource != null && !(DataSource.isClosed())) {
            DataSource.close();
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }
}

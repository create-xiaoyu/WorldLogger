package com.xiaoyu.worldlogger.mysql;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * MySQL 连接池管理类。
 *
 * <p>HikariCP 是数据库连接池。连接池会提前准备并复用数据库连接，
 * 比每次写表都重新连接 MySQL 更快，也更稳定。</p>
 *
 * <p>使用顺序：服务器启动时先调用 InitHikari；需要数据库连接时调用 getMySQLConnection；
 * 服务器关闭时调用 closeHikari。</p>
 */
public class InitMySQL {
    /** 日志对象，用于输出连接成功或失败的信息。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 全局 Hikari 数据源。为 null 表示连接池未初始化或已经关闭。 */
    private static HikariDataSource DataSource;

    /**
     * 根据配置文件初始化连接池。
     *
     * @return true 表示连接池创建成功；false 表示失败，调用者应停止数据库功能。
     */
    public static boolean InitHikari() {
        try {
            // 如果之前已经初始化过，先关闭旧连接池，避免重复创建连接池造成资源泄漏。
            closeHikari();

            HikariConfig hikariConfig = new HikariConfig();

            // 配置里的 DATABASE_URL 不包含数据库名，这里统一补斜杠后再拼接 DATABASE_NAME。
            String URL = Config.DATABASE_URL.get();
            if (!(URL.endsWith("/"))) {
                URL += "/";
            }

            // JDBC URL、用户名、密码是连接数据库的三个基本要素。
            hikariConfig.setJdbcUrl(URL + Config.DATABASE_NAME.get());
            hikariConfig.setUsername(Config.DATABASE_USERNAME.get());
            hikariConfig.setPassword(Config.DATABASE_PASSWORD.get());

            // 下面这些是连接池参数：最大连接数、最小空闲连接、空闲超时、最大寿命、获取连接超时。
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setIdleTimeout(30000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setConnectionTimeout(10000);

            DataSource = new HikariDataSource(hikariConfig);

            LOGGER.info("Hikari DataSource initialized successfully.");
            return true;

        } catch (Exception e) {
            DataSource = null;
            LOGGER.error("Hikari DataSource initialization failed.", e);
            return false;
        }
    }

    /**
     * 从连接池获取一个 MySQL 连接。
     *
     * @return 可用于执行 SQL 的 Connection。调用者应使用 try-with-resources 自动关闭。
     * @throws SQLException 当连接池未初始化或无法获取连接时抛出。
     */
    public static Connection getMySQLConnection() throws SQLException {
        if (DataSource == null) {
            throw new SQLException("HikariCP not initialized");
        }
        return DataSource.getConnection();
    }

    /**
     * 关闭连接池。
     * <p>
     * 注意：关闭后 DataSource 会被设为 null，后续必须重新 InitHikari 才能继续查询或写入。
     */
    public static void closeHikari() {
        if (DataSource != null && !(DataSource.isClosed())) {
            DataSource.close();
        }
        DataSource = null;
    }
}

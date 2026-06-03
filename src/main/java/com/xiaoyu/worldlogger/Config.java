package com.xiaoyu.worldlogger;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * WorldLogger 的公共配置类。
 *
 * <p>NeoForge 会根据这里定义的字段生成配置文件，玩家或服主可以在配置文件中修改数据库连接信息。
 * 这些配置属于 COMMON 类型，因此客户端和服务端都能读取，但真正使用数据库的是服务端。</p>
 */
public class Config {
    /** 配置构建器：每次 define 都是在向同一个配置文件里添加一个配置项。 */
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** MySQL JDBC 地址，例如 jdbc:mysql://localhost:3306。注意这里不包含数据库名。 */
    public static final ModConfigSpec.ConfigValue<String> DATABASE_URL = BUILDER
            .comment("MySQL JDBC URL, for example: jdbc:mysql://localhost:3306")
            .define("database_url", "jdbc:mysql://localhost:3306");

    /** 要连接的数据库名。InitMySQL 会把 DATABASE_URL 和 DATABASE_NAME 拼成完整 JDBC 地址。 */
    public static final ModConfigSpec.ConfigValue<String> DATABASE_NAME = BUILDER
            .comment("MySQL database name used by WorldLogger.")
            .define("database_name", "world_logger");

    /** MySQL 用户名。生产环境建议创建专用账号，不要长期使用 root。 */
    public static final ModConfigSpec.ConfigValue<String> DATABASE_USERNAME = BUILDER
            .comment("MySQL username.")
            .define("database_username", "root");

    /** MySQL 密码。默认空字符串只适合本地测试。 */
    public static final ModConfigSpec.ConfigValue<String> DATABASE_PASSWORD = BUILDER
            .comment("MySQL password.")
            .define("database_password", "");

    /** 数据库线程池大小。数值越大并发越高，但数据库压力也越大。 */
    public static final ModConfigSpec.IntValue THREAD_NUMBER = BUILDER
            .comment("Worker thread count for asynchronous MySQL reads and writes.")
            .defineInRange("thread_number", 8, 1, Integer.MAX_VALUE);

    /** 构建完成后的配置规格对象，WorldLogger 会把它注册给 NeoForge。 */
    static final ModConfigSpec SPEC = BUILDER.build();
}

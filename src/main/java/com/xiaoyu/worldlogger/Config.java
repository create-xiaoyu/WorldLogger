package com.xiaoyu.worldlogger;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DATABASE_URL = BUILDER
            .comment("Enter the MySQL DataBase URL here.")
            .define("database_url", "jdbc:mysql://localhost:3306");

    public static final ModConfigSpec.ConfigValue<String> DATABASE_NAME = BUILDER
            .comment("Enter the MySQL DataBase name here.")
            .define("database_name", "world_logger");

    public static final ModConfigSpec.ConfigValue<String> DATABASE_USERNAME = BUILDER
            .comment("Enter the MySQL DataBase username here.")
            .define("database_username", "root");

    public static final ModConfigSpec.ConfigValue<String> DATABASE_PASSWORD = BUILDER
            .comment("Enter the MySQL DataBase password here.")
            .define("database_password", "");

    public static final ModConfigSpec.IntValue THREAD_NUMBER = BUILDER
            .comment("Set the maximum number of threads in the mod thread pool.")
            .defineInRange("thread_number", 8, 1, Integer.MAX_VALUE);

    static final ModConfigSpec SPEC = BUILDER.build();
}

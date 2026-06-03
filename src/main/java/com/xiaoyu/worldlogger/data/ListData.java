package com.xiaoyu.worldlogger.data;

public class ListData {
    private static final String[] TABLES = {
            "ENTITY_BREAK_INFO",
            "ENTITY_DEATH_INFO",
            "ENTITY_PLACE_BLOCK",
            "ENTITY_SPAWN_INFO",
            "EXECUTE_COMMAND_INFO",
            "EXPLOSION_BREAK_BLOCK",
            "PLAYER_BASE_INFO",
            "PLAYER_BREAK_INFO",
            "PLAYER_CONTAINER_INFO",
            "PLAYER_DEATH_INFO",
            "PLAYER_LOGIN_INFO",
            "PLAYER_LOGOUT_INFO",
            "PLAYER_LOST_ITEM",
            "PLAYER_XP_INFO",
            "SERVER_CHAT_INFO"
    };

    public  static String[] getTables() {
        return TABLES;
    }

    public static String findTable(String value) {
        for (String table : TABLES) {
            if (table.equalsIgnoreCase(value)) {
                return table;
            }
        }
        return null;
    }

    public static boolean hasTable(String value) {
        return findTable(value) != null;
    }
}

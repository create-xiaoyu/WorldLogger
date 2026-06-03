package com.xiaoyu.worldlogger.data;

/**
 * 保存 WorldLogger 允许被查询的表名列表。
 *
 * <p>这个类很重要：命令和网络请求都必须通过这里的白名单校验表名，
 * 这样玩家输入的字符串就不会被直接拼进 SQL 里，能降低 SQL 注入风险。</p>
 */
public class ListData {
    /** 所有可查询的数据表。表名需要和 DataBase 中创建的表名保持一致。 */
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

    /**
     * 返回表名数组，供命令补全和 GUI 左侧列表使用。
     *
     * @return 当前模组允许展示和查询的所有表名。
     */
    public  static String[] getTables() {
        return TABLES;
    }

    /**
     * 根据玩家输入查找真实表名。
     *
     * @param value 玩家输入的表名，可以大小写不同。
     * @return 如果匹配成功，返回标准大写表名；否则返回 null。
     */
    public static String findTable(String value) {
        // 遍历白名单，而不是相信玩家输入，这样 SQL 拼接时只会拼接已知安全的表名。
        for (String table : TABLES) {
            if (table.equalsIgnoreCase(value)) {
                return table;
            }
        }
        return null;
    }

    /**
     * 判断一个字符串是否是合法表名。
     *
     * @param value 待检测的表名。
     * @return true 表示存在于白名单；false 表示不能查询。
     */
    public static boolean hasTable(String value) {
        return findTable(value) != null;
    }
}

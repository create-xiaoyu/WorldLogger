package com.xiaoyu.worldlogger.query;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.xiaoyu.worldlogger.mysql.InitMySQL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /worldlogger search 的附近记录聚合查询服务。
 *
 * <p>这个服务会从多张表中查询数据，再根据玩家当前位置筛选半径 16 格内的记录，
 * 最后按时间倒序合并成统一结果。</p>
 *
 * <p>注意：当前数据库把坐标保存成字符串，例如 [X: 1, Y: 64, Z: -2]。
 * 因此这里必须先从字符串中解析出 x/y/z，再在 Java 中计算距离。
 * 如果以后把坐标拆成数字列，可以把这部分改成 SQL WHERE 条件，效率会更高。</p>
 */
public final class WorldSearchQueryService {
    /** 搜索半径，单位是方块。 */
    private static final int RADIUS = 16;

    /** 每页显示的搜索结果数量。 */
    private static final int PAGE_SIZE = 5;

    /** 合并后最多保留的记录数，防止繁忙区域一次查询出几千条导致聊天栏和网络压力过大。 */
    private static final int MAX_RESULTS = 128;

    /** 从坐标字符串中提取 X/Y/Z 的正则表达式。支持负数坐标。 */
    private static final Pattern POS_PATTERN = Pattern.compile("X:\\s*(-?\\d+).*Y:\\s*(-?\\d+).*Z:\\s*(-?\\d+)");

    /** 工具类不需要实例化。 */
    private WorldSearchQueryService() {}

    /**
     * 搜索指定世界中，指定中心点附近的 WorldLogger 记录。
     *
     * @param centerX 玩家所在 X。
     * @param centerY 玩家所在 Y。
     * @param centerZ 玩家所在 Z。
     * @param worldId 玩家所在维度 ID，例如 minecraft:overworld。
     * @param page 页码，从 1 开始。
     * @return 当前页搜索结果。
     */
    public static SearchResult searchNear(int centerX, int centerY, int centerZ, String worldId, int page) throws SQLException {
        // 所有表查到的记录先放进同一个列表，再统一排序和分页。
        List<SearchRecord> records = new ArrayList<>();

        // 一个连接内连续查询多张表，避免每张表都重新从连接池借连接。
        try (Connection connection = InitMySQL.getMySQLConnection()) {
            // 登录、退出：玩家名 + 时间 + 坐标。
            searchPlayerSession(connection, records, "PLAYER_LOGIN_INFO", "text.worldlogger.search.player_login", centerX, centerY, centerZ, worldId);
            searchPlayerSession(connection, records, "PLAYER_LOGOUT_INFO", "text.worldlogger.search.player_logout", centerX, centerY, centerZ, worldId);

            // 玩家死亡、丢物：结构相似，都有 player_name、pos、world。
            searchPlayerNamedPosition(connection, records, "PLAYER_DEATH_INFO", "pos", "world", "text.worldlogger.search.player_death", centerX, centerY, centerZ, worldId);
            searchPlayerNamedPosition(connection, records, "PLAYER_LOST_ITEM", "pos", "world", "text.worldlogger.search.player_lost_item", centerX, centerY, centerZ, worldId);

            // 经验变化和容器变化有自己的显示参数，所以使用专门方法。
            searchPlayerXp(connection, records, centerX, centerY, centerZ, worldId);
            searchPlayerContainer(connection, records, centerX, centerY, centerZ, worldId);

            // 方块放置/破坏/爆炸。
            searchEntityBlock(connection, records, "ENTITY_PLACE_BLOCK", "text.worldlogger.search.entity_place_block", centerX, centerY, centerZ, worldId);
            searchPlayerBlock(connection, records, centerX, centerY, centerZ, worldId);
            searchEntityBlock(connection, records, "ENTITY_BREAK_INFO", "text.worldlogger.search.entity_break_block", centerX, centerY, centerZ, worldId);
            searchExplosion(connection, records, centerX, centerY, centerZ, worldId);

            // 实体死亡和生成。
            searchEntityNamedPosition(connection, records, "ENTITY_DEATH_INFO", "entity_pos", "entity_world", "text.worldlogger.search.entity_death", centerX, centerY, centerZ, worldId);
            searchEntityNamedPosition(connection, records, "ENTITY_SPAWN_INFO", "entity_pos", "world", "text.worldlogger.search.entity_spawn", centerX, centerY, centerZ, worldId);
        }

        // 最新记录排前面。sortTime 是毫秒时间戳。
        records.sort(Comparator.comparingLong(SearchRecord::sortTime).reversed());
        // 限制合并结果数量，避免一个命令查询过多记录。
        boolean truncated = records.size() > MAX_RESULTS;
        if (truncated) {
            records = List.copyOf(records.subList(0, MAX_RESULTS));
        }

        // 根据页码计算当前页在列表中的开始和结束下标。
        int normalizedPage = Math.max(1, page);
        int fromIndex = Math.min((normalizedPage - 1) * PAGE_SIZE, records.size());
        int toIndex = Math.min(fromIndex + PAGE_SIZE, records.size());
        boolean hasNext = toIndex < records.size();
        return new SearchResult(records.subList(fromIndex, toIndex), normalizedPage, hasNext, truncated);
    }

    /**
     * 搜索玩家登录/退出表。
     *
     * @param table PLAYER_LOGIN_INFO 或 PLAYER_LOGOUT_INFO。
     * @param translationKey 客户端显示时使用的语言 key。
     */
    private static void searchPlayerSession(Connection connection, List<SearchRecord> records, String table, String translationKey, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        // 先用 world LIKE 粗筛维度，再在 Java 中按坐标半径精筛。
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, pos FROM " + table + " WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    // readInRange 返回 null 表示坐标不存在、格式不对或超出半径。
                    Pos pos = readInRange(resultSet.getString("pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    // TimeData 同时保存排序用时间戳和显示用文本。
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), translationKey, List.of(
                            resultSet.getString("player_name"),
                            timeData.displayTime(),
                            pos.display()
                    )));
                }
            }
        }
    }

    /**
     * 搜索带 player_name 和位置列的玩家表。
     *
     * @param posColumn 坐标列名，例如 pos。
     * @param worldColumn 世界列名，例如 world。
     */
    private static void searchPlayerNamedPosition(Connection connection, List<SearchRecord> records, String table, String posColumn, String worldColumn, String translationKey, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, " + posColumn + " FROM " + table + " WHERE " + worldColumn + " LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString(posColumn), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), translationKey, List.of(
                            resultSet.getString("player_name"),
                            timeData.displayTime(),
                            pos.display()
                    )));
                }
            }
        }
    }

    /** 搜索 PLAYER_XP_INFO，并把经验变化类型和数量作为显示参数。 */
    private static void searchPlayerXp(Connection connection, List<SearchRecord> records, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, pos, xp_change_type, xp_change_count FROM PLAYER_XP_INFO WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString("pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), "text.worldlogger.search.player_xp", List.of(
                            resultSet.getString("player_name"),
                            timeData.displayTime(),
                            resultSet.getString("xp_change_type"),
                            String.valueOf(resultSet.getLong("xp_change_count")),
                            pos.display()
                    )));
                }
            }
        }
    }

    /** 搜索 PLAYER_CONTAINER_INFO，并把物品变化压缩成物品 ID 显示。 */
    private static void searchPlayerContainer(Connection connection, List<SearchRecord> records, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, player_pos, container_pos, source_item, modify_item, modify_type FROM PLAYER_CONTAINER_INFO WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    // 容器位置优先；如果没有容器位置，则退回玩家位置。
                    Pos pos = readInRange(resultSet.getString("container_pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        pos = readInRange(resultSet.getString("player_pos"), centerX, centerY, centerZ);
                    }
                    if (pos == null) {
                        continue;
                    }

                    // modifyType 会拼到语言 key 后面，所以必须标准化。
                    String modifyType = normalizeModifyType(resultSet.getString("modify_type"));
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), "text.worldlogger.search.player_container." + modifyType, List.of(
                            resultSet.getString("player_name"),
                            timeData.displayTime(),
                            extractItemId(resultSet.getString("modify_item")),
                            extractItemId(resultSet.getString("source_item")),
                            pos.display()
                    )));
                }
            }
        }
    }

    /**
     * 搜索实体放置/破坏方块表。
     *
     * @param table ENTITY_PLACE_BLOCK 或 ENTITY_BREAK_INFO。
     */
    private static void searchEntityBlock(Connection connection, List<SearchRecord> records, String table, String translationKey, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, entity_name, block_id, block_pos FROM " + table + " WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString("block_pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), translationKey, List.of(
                            resultSet.getString("entity_name"),
                            timeData.displayTime(),
                            resultSet.getString("block_id"),
                            pos.display()
                    )));
                }
            }
        }
    }

    /** 搜索 PLAYER_BREAK_INFO。玩家破坏方块的显示文本和实体破坏略有不同，所以单独写。 */
    private static void searchPlayerBlock(Connection connection, List<SearchRecord> records, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, block_id, block_pos FROM PLAYER_BREAK_INFO WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString("block_pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), "text.worldlogger.search.player_break_block", List.of(
                            resultSet.getString("player_name"),
                            timeData.displayTime(),
                            resultSet.getString("block_id"),
                            pos.display()
                    )));
                }
            }
        }
    }

    /** 搜索 EXPLOSION_BREAK_BLOCK。爆炸表保存的是爆炸来源位置，而不是每个方块单独一行。 */
    private static void searchExplosion(Connection connection, List<SearchRecord> records, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, source_name, source_pos, trigger_source FROM EXPLOSION_BREAK_BLOCK WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString("source_pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), "text.worldlogger.search.explosion_break_block", List.of(
                            resultSet.getString("source_name"),
                            timeData.displayTime(),
                            resultSet.getString("trigger_source"),
                            pos.display()
                    )));
                }
            }
        }
    }

    /**
     * 搜索实体死亡/生成这类“实体名 + 实体位置”的表。
     *
     * @param posColumn 坐标列名。
     * @param worldColumn 世界列名。
     */
    private static void searchEntityNamedPosition(Connection connection, List<SearchRecord> records, String table, String posColumn, String worldColumn, String translationKey, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, entity_name, " + posColumn + " FROM " + table + " WHERE " + worldColumn + " LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString(posColumn), centerX, centerY, centerZ);
                    if (pos == null) {
                        continue;
                    }
                    TimeData timeData = timeData(resultSet);
                    records.add(new SearchRecord(timeData.sortTime(), translationKey, List.of(
                            resultSet.getString("entity_name"),
                            timeData.displayTime(),
                            pos.display()
                    )));
                }
            }
        }
    }

    /**
     * 从 ResultSet 中读取 time 字段。
     *
     * @return TimeData，包含排序用毫秒和显示用字符串。
     */
    private static TimeData timeData(ResultSet resultSet) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp("time");
        if (timestamp == null) {
            return new TimeData(0L, "");
        }
        return new TimeData(timestamp.getTime(), timestamp.toString());
    }

    /**
     * 解析坐标并判断是否在半径范围内。
     *
     * @return 在范围内则返回 Pos；否则返回 null。
     */
    private static Pos readInRange(String posText, int centerX, int centerY, int centerZ) {
        Pos pos = parsePos(posText);
        if (pos == null) {
            return null;
        }

        // 使用平方距离，避免开平方运算；比较 dx^2 + dy^2 + dz^2 <= radius^2。
        long dx = pos.x() - centerX;
        long dy = pos.y() - centerY;
        long dz = pos.z() - centerZ;
        long distanceSquared = dx * dx + dy * dy + dz * dz;
        return distanceSquared <= (long) RADIUS * RADIUS ? pos : null;
    }

    /**
     * 从字符串中解析坐标。
     *
     * @param posText 形如 [X: 1, Y: 64, Z: -2] 的文本。
     * @return 解析成功返回 Pos；失败返回 null。
     */
    private static Pos parsePos(String posText) {
        if (posText == null) {
            return null;
        }

        Matcher matcher = POS_PATTERN.matcher(posText);
        if (!matcher.find()) {
            return null;
        }

        try {
            // group(1)、group(2)、group(3) 分别对应正则里的 X、Y、Z。
            return new Pos(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从物品 JSON 中提取 item 字段。
     *
     * @param itemData 物品 JSON。
     * @return 物品 ID；解析失败时返回原文本；空值返回 null。
     */
    private static String extractItemId(String itemData) {
        if (itemData == null || itemData.isBlank()) {
            return "null";
        }

        try {
            JsonObject itemObject = JsonParser.parseString(itemData).getAsJsonObject();
            if (itemObject.has("item") && !itemObject.get("item").isJsonNull()) {
                return itemObject.get("item").getAsString();
            }
        } catch (IllegalStateException | JsonSyntaxException ignored) {
            return itemData;
        }

        return "unknown";
    }

    /**
     * 标准化容器变化类型。
     *
     * @param modifyType 原始变化类型。
     * @return 可拼入语言 key 的小写字符串。
     */
    private static String normalizeModifyType(String modifyType) {
        if (modifyType == null || modifyType.isBlank()) {
            return "unknown";
        }
        // modifyType 会变成语言 key 的一部分，必须使用 Locale.ROOT 得到稳定的小写结果。
        return modifyType.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** 一页附近搜索结果。 */
    public record SearchResult(List<SearchRecord> records, int page, boolean hasNext, boolean truncated) {
        /** 创建不可变结果列表。 */
        public SearchResult {
            records = List.copyOf(records);
        }
    }

    /** 单条附近搜索结果。 */
    public record SearchRecord(long sortTime, String translationKey, List<String> args) {
        /** 创建不可变参数列表。 */
        public SearchRecord {
            args = List.copyOf(args);
        }
    }

    /** 同时保存排序用时间戳和显示用时间文本。 */
    private record TimeData(long sortTime, String displayTime) {}

    /** 简单的三维整数坐标。 */
    private record Pos(int x, int y, int z) {
        /** @return 和数据库一致的坐标显示格式。 */
        private String display() {
            return "[X: " + x + ", Y: " + y + ", Z: " + z + "]";
        }
    }
}

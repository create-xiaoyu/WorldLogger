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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorldSearchQueryService {
    private static final int RADIUS = 16;
    private static final int PAGE_SIZE = 5;
    private static final int MAX_RESULTS = 128;
    private static final Pattern POS_PATTERN = Pattern.compile("X:\\s*(-?\\d+).*Y:\\s*(-?\\d+).*Z:\\s*(-?\\d+)");

    private WorldSearchQueryService() {}

    public static SearchResult searchNear(int centerX, int centerY, int centerZ, String worldId, int page) throws SQLException {
        List<SearchRecord> records = new ArrayList<>();

        try (Connection connection = InitMySQL.getMySQLConnection()) {
            searchPlayerSession(connection, records, "PLAYER_LOGIN_INFO", "text.worldlogger.search.player_login", centerX, centerY, centerZ, worldId);
            searchPlayerSession(connection, records, "PLAYER_LOGOUT_INFO", "text.worldlogger.search.player_logout", centerX, centerY, centerZ, worldId);
            searchPlayerNamedPosition(connection, records, "PLAYER_DEATH_INFO", "pos", "world", "text.worldlogger.search.player_death", centerX, centerY, centerZ, worldId);
            searchPlayerNamedPosition(connection, records, "PLAYER_LOST_ITEM", "pos", "world", "text.worldlogger.search.player_lost_item", centerX, centerY, centerZ, worldId);
            searchPlayerXp(connection, records, centerX, centerY, centerZ, worldId);
            searchPlayerContainer(connection, records, centerX, centerY, centerZ, worldId);
            searchEntityBlock(connection, records, "ENTITY_PLACE_BLOCK", "text.worldlogger.search.entity_place_block", centerX, centerY, centerZ, worldId);
            searchPlayerBlock(connection, records, centerX, centerY, centerZ, worldId);
            searchEntityBlock(connection, records, "ENTITY_BREAK_INFO", "text.worldlogger.search.entity_break_block", centerX, centerY, centerZ, worldId);
            searchExplosion(connection, records, centerX, centerY, centerZ, worldId);
            searchEntityNamedPosition(connection, records, "ENTITY_DEATH_INFO", "entity_pos", "entity_world", "text.worldlogger.search.entity_death", centerX, centerY, centerZ, worldId);
            searchEntityNamedPosition(connection, records, "ENTITY_SPAWN_INFO", "entity_pos", "world", "text.worldlogger.search.entity_spawn", centerX, centerY, centerZ, worldId);
        }

        records.sort(Comparator.comparingLong(SearchRecord::sortTime).reversed());
        boolean truncated = records.size() > MAX_RESULTS;
        if (truncated) {
            records = List.copyOf(records.subList(0, MAX_RESULTS));
        }

        int normalizedPage = Math.max(1, page);
        int fromIndex = Math.min((normalizedPage - 1) * PAGE_SIZE, records.size());
        int toIndex = Math.min(fromIndex + PAGE_SIZE, records.size());
        boolean hasNext = toIndex < records.size();
        return new SearchResult(records.subList(fromIndex, toIndex), normalizedPage, hasNext, truncated);
    }

    private static void searchPlayerSession(Connection connection, List<SearchRecord> records, String table, String translationKey, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, pos FROM " + table + " WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString("pos"), centerX, centerY, centerZ);
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

    private static void searchPlayerContainer(Connection connection, List<SearchRecord> records, int centerX, int centerY, int centerZ, String worldId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT time, player_name, player_pos, container_pos, source_item, modify_item, modify_type FROM PLAYER_CONTAINER_INFO WHERE world LIKE ? ORDER BY time ASC")) {
            statement.setString(1, "%" + worldId + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Pos pos = readInRange(resultSet.getString("container_pos"), centerX, centerY, centerZ);
                    if (pos == null) {
                        pos = readInRange(resultSet.getString("player_pos"), centerX, centerY, centerZ);
                    }
                    if (pos == null) {
                        continue;
                    }

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

    private static TimeData timeData(ResultSet resultSet) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp("time");
        if (timestamp == null) {
            return new TimeData(0L, "");
        }
        return new TimeData(timestamp.getTime(), timestamp.toString());
    }

    private static Pos readInRange(String posText, int centerX, int centerY, int centerZ) {
        Pos pos = parsePos(posText);
        if (pos == null) {
            return null;
        }

        long dx = pos.x() - centerX;
        long dy = pos.y() - centerY;
        long dz = pos.z() - centerZ;
        long distanceSquared = dx * dx + dy * dy + dz * dz;
        return distanceSquared <= (long) RADIUS * RADIUS ? pos : null;
    }

    private static Pos parsePos(String posText) {
        if (posText == null) {
            return null;
        }

        Matcher matcher = POS_PATTERN.matcher(posText);
        if (!matcher.find()) {
            return null;
        }

        try {
            return new Pos(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

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

    private static String normalizeModifyType(String modifyType) {
        if (modifyType == null || modifyType.isBlank()) {
            return "unknown";
        }
        return modifyType.trim().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }

    public record SearchResult(List<SearchRecord> records, int page, boolean hasNext, boolean truncated) {
        public SearchResult {
            records = List.copyOf(records);
        }
    }

    public record SearchRecord(long sortTime, String translationKey, List<String> args) {
        public SearchRecord {
            args = List.copyOf(args);
        }
    }

    private record TimeData(long sortTime, String displayTime) {}

    private record Pos(int x, int y, int z) {
        private String display() {
            return "[X: " + x + ", Y: " + y + ", Z: " + z + "]";
        }
    }
}

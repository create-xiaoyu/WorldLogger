package com.xiaoyu.worldlogger.ai.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.xiaoyu.worldlogger.ai.OpenAiResponsesClient;
import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.query.WorldSearchQueryService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 提供给 AI 的只读数据库工具。
 *
 * <p>所有工具都只读数据库，不修改任何表。
 * 表名必须经过 ListData 白名单，搜索值使用 PreparedStatement 参数绑定。</p>
 */
public final class AiDatabaseToolService {
    /** 单个字段返回给 AI 的最大字符数，避免把完整 NBT 或大 JSON 直接发给模型。 */
    private static final int MAX_CELL_LENGTH = 800;

    /** 附近搜索最大半径，防止 AI 把范围放得过大。 */
    private static final int MAX_SEARCH_RADIUS = 128;

    /** 工具类不需要实例化。 */
    private AiDatabaseToolService() {}

    /**
     * 返回 OpenAI Responses API 使用的工具定义。
     *
     * @return function tool 定义数组。
     */
    public static JsonArray toolDefinitions() {
        JsonArray tools = new JsonArray();
        tools.add(playerActivityAfterLatestLoginTool());
        tools.add(listTablesTool());
        tools.add(describeTableTool());
        tools.add(queryTableTool());
        tools.add(searchNearPlayerTool());
        return tools;
    }

    /**
     * 执行指定工具。
     *
     * @param context 玩家上下文。
     * @param name 工具名称。
     * @param arguments 工具参数。
     * @param maxDepth 当前允许的最大搜索深度。
     * @return 工具结果 JSON。
     */
    public static JsonObject execute(AiToolContext context, String name, JsonObject arguments, int maxDepth) throws SQLException {
        return switch (name) {
            case "worldlogger_list_tables" -> listTables();
            case "worldlogger_describe_table" -> describeTable(readString(arguments, "table"));
            case "worldlogger_query_table" -> queryTable(
                    readString(arguments, "table"),
                    OpenAiResponsesClient.requestedDepth(arguments),
                    readString(arguments, "filter"),
                    readString(arguments, "order"),
                    maxDepth
            );
            case "worldlogger_search_near_player" -> searchNearPlayer(
                    context,
                    OpenAiResponsesClient.requestedDepth(arguments),
                    readInt(arguments, "radius", 16),
                    maxDepth
            );
            case "worldlogger_player_activity_after_latest_login" -> playerActivityAfterLatestLogin(
                    readString(arguments, "player"),
                    OpenAiResponsesClient.requestedDepth(arguments),
                    readString(arguments, "order"),
                    readBoolean(arguments, "include_chat", true),
                    readBoolean(arguments, "include_commands", true),
                    maxDepth
            );
            default -> error("Unknown tool: " + name);
        };
    }

    /** AI 工具：列出可查询表。 */
    private static JsonObject listTables() {
        JsonObject result = success();
        JsonArray tables = new JsonArray();
        for (String table : ListData.getTables()) {
            tables.add(table);
        }
        result.add("tables", tables);
        return result;
    }

    /** AI 工具：描述一张表的字段和总行数。 */
    private static JsonObject describeTable(String table) throws SQLException {
        String normalizedTable = requireTable(table);
        JsonObject result = success();
        result.addProperty("table", normalizedTable);

        try (Connection connection = InitMySQL.getMySQLConnection()) {
            result.add("columns", readColumns(connection, normalizedTable));
            result.addProperty("row_count", countRows(connection, normalizedTable));
        }

        return result;
    }

    /** AI 工具：查询一张表的若干行。 */
    private static JsonObject queryTable(String table, int requestedDepth, String filter, String order, int maxDepth) throws SQLException {
        String normalizedTable = requireTable(table);
        int depth = clamp(requestedDepth, 1, maxDepth);
        String normalizedFilter = filter == null ? "" : filter.trim();

        try (Connection connection = InitMySQL.getMySQLConnection()) {
            List<String> columns = columnNames(connection, normalizedTable);
            List<String> parameters = new ArrayList<>();
            String where = buildWhereClause(columns, normalizedFilter, parameters);
            String sql = "SELECT * FROM " + normalizedTable
                    + (where.isBlank() ? "" : " WHERE " + where)
                    + " ORDER BY " + orderExpression(columns, order)
                    + " LIMIT ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                for (String parameter : parameters) {
                    statement.setString(parameterIndex++, parameter);
                }
                statement.setInt(parameterIndex, depth);

                try (ResultSet resultSet = statement.executeQuery()) {
                    JsonObject result = success();
                    result.addProperty("table", normalizedTable);
                    result.addProperty("requested_depth", requestedDepth);
                    result.addProperty("depth_used", depth);
                    result.addProperty("filter", normalizedFilter);
                    result.addProperty("note", "Cell values are truncated to " + MAX_CELL_LENGTH + " characters.");
                    result.add("rows", readRows(resultSet));
                    return result;
                }
            }
        }
    }

    /** AI 工具：搜索执行命令玩家附近的记录。 */
    private static JsonObject searchNearPlayer(AiToolContext context, int requestedDepth, int requestedRadius, int maxDepth) throws SQLException {
        // depth 受 AI 配置控制；如果玩家批准过，调用方传入的 maxDepth 会变成 approved 上限。
        int depth = clamp(requestedDepth, 1, maxDepth);
        // 当前附近搜索服务内部仍固定 16 格半径，这里先保留 AI 想要的半径，方便以后坐标改成数字列后接上。
        int clampedRadius = clamp(requestedRadius, 1, MAX_SEARCH_RADIUS);
        int effectiveRadius = 16;

        JsonArray records = new JsonArray();
        int page = 1;
        while (records.size() < depth) {
            WorldSearchQueryService.SearchResult result = WorldSearchQueryService.searchNear(
                    context.centerX(),
                    context.centerY(),
                    context.centerZ(),
                    context.worldId(),
                    page
            );

            for (WorldSearchQueryService.SearchRecord record : result.records()) {
                if (records.size() >= depth) {
                    break;
                }
                JsonObject item = new JsonObject();
                item.addProperty("translation_key", record.translationKey());
                JsonArray args = new JsonArray();
                for (String arg : record.args()) {
                    args.add(arg);
                }
                item.add("args", args);
                item.addProperty("sort_time", record.sortTime());
                records.add(item);
            }

            if (!(result.hasNext())) {
                break;
            }
            page++;
        }

        JsonObject output = success();
        output.addProperty("player", context.playerName());
        output.addProperty("world", context.worldId());
        output.addProperty("center", "[X: " + context.centerX() + ", Y: " + context.centerY() + ", Z: " + context.centerZ() + "]");
        output.addProperty("requested_depth", requestedDepth);
        output.addProperty("depth_used", depth);
        output.addProperty("requested_radius", requestedRadius);
        output.addProperty("requested_radius_clamped", clampedRadius);
        output.addProperty("radius_used", effectiveRadius);
        output.addProperty("max_radius_limit", MAX_SEARCH_RADIUS);
        output.addProperty("note", "WorldLogger currently searches a fixed 16-block radius internally; requested radius is recorded but not used until positions are stored as numeric columns.");
        output.add("records", records);
        return output;
    }

    /** AI 工具：查找某个玩家最近一次登录后的时间线。 */
    private static JsonObject playerActivityAfterLatestLogin(String player, int requestedDepth, String order, boolean includeChat, boolean includeCommands, int maxDepth) throws SQLException {
        String normalizedPlayer = player == null ? "" : player.trim();
        if (normalizedPlayer.isBlank()) {
            return error("Missing player name or UUID.");
        }

        int depth = clamp(requestedDepth, 1, maxDepth);
        boolean oldestFirst = "oldest".equalsIgnoreCase(order);

        try (Connection connection = InitMySQL.getMySQLConnection()) {
            JsonObject login = latestLogin(connection, normalizedPlayer);
            if (login == null) {
                JsonObject output = success();
                output.addProperty("player", normalizedPlayer);
                output.addProperty("found_login", false);
                output.addProperty("message", "No PLAYER_LOGIN_INFO record matched this player.");
                output.add("events", new JsonArray());
                return output;
            }

            String playerUuid = readString(login, "player_uuid");
            String playerName = readString(login, "player_name");
            String loginTime = readString(login, "time");

            List<JsonObject> events = new ArrayList<>();
            events.add(loginEvent(login));
            appendPlayerLoginTableEvents(connection, events, "PLAYER_LOGOUT_INFO", "logout", "退出游戏", playerUuid, playerName, loginTime, depth);
            appendPlayerLoginTableEvents(connection, events, "PLAYER_DEATH_INFO", "death", "死亡", playerUuid, playerName, loginTime, depth);
            appendPlayerLoginTableEvents(connection, events, "PLAYER_LOST_ITEM", "lost_item", "丢弃或掉落物品", playerUuid, playerName, loginTime, depth);
            appendPlayerLoginTableEvents(connection, events, "PLAYER_XP_INFO", "xp", "经验变化", playerUuid, playerName, loginTime, depth);
            appendPlayerLoginTableEvents(connection, events, "PLAYER_CONTAINER_INFO", "container", "容器交互", playerUuid, playerName, loginTime, depth);
            appendPlayerLoginTableEvents(connection, events, "PLAYER_BREAK_INFO", "break_block", "破坏方块", playerUuid, playerName, loginTime, depth);
            appendEntityPlaceEvents(connection, events, playerUuid, playerName, loginTime, depth);
            if (includeChat) {
                appendPlayerLoginTableEvents(connection, events, "SERVER_CHAT_INFO", "chat", "聊天", playerUuid, playerName, loginTime, depth);
            }
            if (includeCommands) {
                appendCommandEvents(connection, events, playerUuid, playerName, loginTime, depth);
            }

            events.sort(eventComparator(oldestFirst));

            JsonArray limitedEvents = new JsonArray();
            for (int i = 0; i < Math.min(depth, events.size()); i++) {
                limitedEvents.add(events.get(i));
            }

            JsonObject output = success();
            output.addProperty("player", normalizedPlayer);
            output.addProperty("found_login", true);
            output.addProperty("matched_player_uuid", playerUuid);
            output.addProperty("matched_player_name", playerName);
            output.addProperty("latest_login_time", loginTime);
            output.addProperty("latest_login_pos", readString(login, "pos"));
            output.addProperty("latest_login_world", readString(login, "world"));
            output.addProperty("requested_depth", requestedDepth);
            output.addProperty("depth_used", depth);
            output.addProperty("total_events_found_before_limit", events.size());
            output.addProperty("truncated", events.size() > depth);
            output.addProperty("order", oldestFirst ? "oldest" : "newest");
            output.addProperty("note", "This tool searched records after the latest PLAYER_LOGIN_INFO row for the player. Long text, item JSON and NBT values are simplified.");
            output.add("events", limitedEvents);
            return output;
        }
    }

    /** 查询匹配玩家的最新登录记录。 */
    private static JsonObject latestLogin(Connection connection, String player) throws SQLException {
        String sql = """
                SELECT data_id, time, player_uuid, player_name, pos, world
                FROM PLAYER_LOGIN_INFO
                WHERE player_uuid = ? OR LOWER(player_name) = LOWER(?)
                ORDER BY time DESC, data_id DESC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player);
            statement.setString(2, player);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!(resultSet.next())) {
                    return null;
                }
                return rowObject(resultSet, resultSet.getMetaData());
            }
        }
    }

    /** 把最新登录本身也放进时间线，方便 AI 说明分析起点。 */
    private static JsonObject loginEvent(JsonObject login) {
        JsonObject event = baseEvent("PLAYER_LOGIN_INFO", "login", "进入游戏", login);
        event.addProperty("pos", readString(login, "pos"));
        event.addProperty("world", readString(login, "world"));
        return event;
    }

    /** 查询拥有 player_uuid/player_name 字段的玩家表。 */
    private static void appendPlayerLoginTableEvents(Connection connection, List<JsonObject> events, String table, String action, String actionText, String playerUuid, String playerName, String sinceTime, int limit) throws SQLException {
        String sql = "SELECT * FROM " + table
                + " WHERE time >= ? AND (player_uuid = ? OR LOWER(player_name) = LOWER(?))"
                + " ORDER BY time DESC, data_id DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sinceTime);
            statement.setString(2, playerUuid);
            statement.setString(3, playerName);
            statement.setInt(4, limit);
            appendRows(events, statement, table, action, actionText);
        }
    }

    /** 查询玩家放置方块记录；玩家放置方块目前写在 ENTITY_PLACE_BLOCK。 */
    private static void appendEntityPlaceEvents(Connection connection, List<JsonObject> events, String playerUuid, String playerName, String sinceTime, int limit) throws SQLException {
        String sql = """
                SELECT *
                FROM ENTITY_PLACE_BLOCK
                WHERE time >= ? AND (entity_name LIKE ? OR entity_name LIKE ?)
                ORDER BY time DESC, data_id DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sinceTime);
            statement.setString(2, "%" + playerUuid + "%");
            statement.setString(3, "%NAME: " + playerName + "%");
            statement.setInt(4, limit);
            appendRows(events, statement, "ENTITY_PLACE_BLOCK", "place_block", "放置方块");
        }
    }

    /** 查询玩家执行命令记录；source 字段中包含玩家 UUID 和名称。 */
    private static void appendCommandEvents(Connection connection, List<JsonObject> events, String playerUuid, String playerName, String sinceTime, int limit) throws SQLException {
        String sql = """
                SELECT *
                FROM EXECUTE_COMMAND_INFO
                WHERE time >= ? AND (source LIKE ? OR source LIKE ?)
                ORDER BY time DESC, data_id DESC
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sinceTime);
            statement.setString(2, "%" + playerUuid + "%");
            statement.setString(3, "%NAME: " + playerName + "%");
            statement.setInt(4, limit);
            appendRows(events, statement, "EXECUTE_COMMAND_INFO", "command", "执行命令");
        }
    }

    /** 执行查询并把结果追加到事件列表。 */
    private static void appendRows(List<JsonObject> events, PreparedStatement statement, String table, String action, String actionText) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            while (resultSet.next()) {
                JsonObject row = rowObject(resultSet, metaData);
                events.add(eventFromRow(table, action, actionText, row));
            }
        }
    }

    /** 把 ResultSet 当前行转成 JSON 对象。 */
    private static JsonObject rowObject(ResultSet resultSet, ResultSetMetaData metaData) throws SQLException {
        JsonObject row = new JsonObject();
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            String value = resultSet.getString(column);
            row.addProperty(metaData.getColumnLabel(column), truncate(value));
        }
        return row;
    }

    /** 把数据库行转成面向 AI 总结的紧凑事件。 */
    private static JsonObject eventFromRow(String table, String action, String actionText, JsonObject row) {
        JsonObject event = baseEvent(table, action, actionText, row);
        switch (table) {
            case "PLAYER_LOGOUT_INFO" -> addCommonPosition(event, row, "pos");
            case "PLAYER_DEATH_INFO" -> {
                addCommonPosition(event, row, "pos");
                addIfPresent(event, "death_type", row, "death_type");
                addIfPresent(event, "death_message", row, "death_message");
                addIfPresent(event, "source_name", row, "source_name");
            }
            case "PLAYER_LOST_ITEM" -> {
                addCommonPosition(event, row, "pos");
                addIfPresent(event, "lost_type", row, "lost_type");
                event.addProperty("item", extractItemId(readString(row, "lost_item")));
            }
            case "PLAYER_XP_INFO" -> {
                addCommonPosition(event, row, "pos");
                addIfPresent(event, "xp_change_type", row, "xp_change_type");
                addIfPresent(event, "xp_change_source", row, "xp_change_source");
                addIfPresent(event, "xp_change_count", row, "xp_change_count");
                addIfPresent(event, "xp_count", row, "xp_count");
            }
            case "PLAYER_CONTAINER_INFO" -> {
                addCommonPosition(event, row, "player_pos");
                addIfPresent(event, "container_id", row, "container_id");
                addIfPresent(event, "container_pos", row, "container_pos");
                addIfPresent(event, "slot_index", row, "slot_index");
                addIfPresent(event, "modify_type", row, "modify_type");
                event.addProperty("source_item", extractItemId(readString(row, "source_item")));
                event.addProperty("modify_item", extractItemId(readString(row, "modify_item")));
            }
            case "PLAYER_BREAK_INFO" -> {
                addCommonPosition(event, row, "player_pos");
                addIfPresent(event, "block_id", row, "block_id");
                addIfPresent(event, "block_pos", row, "block_pos");
            }
            case "ENTITY_PLACE_BLOCK" -> {
                addCommonPosition(event, row, "entity_pos");
                addIfPresent(event, "block_id", row, "block_id");
                addIfPresent(event, "block_pos", row, "block_pos");
            }
            case "SERVER_CHAT_INFO" -> {
                addCommonPosition(event, row, "pos");
                addIfPresent(event, "message", row, "raw_message");
            }
            case "EXECUTE_COMMAND_INFO" -> {
                addCommonPosition(event, row, "pos");
                addIfPresent(event, "command", row, "command");
            }
            default -> event.add("row", row);
        }
        return event;
    }

    /** 创建事件公共字段。 */
    private static JsonObject baseEvent(String table, String action, String actionText, JsonObject row) {
        JsonObject event = new JsonObject();
        event.addProperty("table", table);
        event.addProperty("data_id", readString(row, "data_id"));
        event.addProperty("time", readString(row, "time"));
        event.addProperty("action", action);
        event.addProperty("action_text", actionText);
        addIfPresent(event, "world", row, "world");
        return event;
    }

    /** 添加常见坐标字段。 */
    private static void addCommonPosition(JsonObject event, JsonObject row, String positionColumn) {
        addIfPresent(event, "pos", row, positionColumn);
        addIfPresent(event, "world", row, "world");
    }

    /** 字段存在且非空时才加入事件，减少工具输出噪音。 */
    private static void addIfPresent(JsonObject target, String outputKey, JsonObject source, String sourceKey) {
        String value = readString(source, sourceKey);
        if (!(value.isBlank()) && !"null".equalsIgnoreCase(value)) {
            target.addProperty(outputKey, value);
        }
    }

    /** 时间线排序器。 */
    private static Comparator<JsonObject> eventComparator(boolean oldestFirst) {
        Comparator<JsonObject> comparator = Comparator
                .comparing((JsonObject event) -> readString(event, "time"))
                .thenComparing(event -> parseInt(readString(event, "data_id")));
        return oldestFirst ? comparator : comparator.reversed();
    }

    /** 安全解析整数。 */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 从物品 JSON 中只提取物品 ID，避免把 custom_data/NBT 全部交给 AI。 */
    private static String extractItemId(String itemData) {
        if (itemData == null || itemData.isBlank() || "null".equalsIgnoreCase(itemData)) {
            return "null";
        }
        try {
            JsonObject itemObject = JsonParser.parseString(itemData).getAsJsonObject();
            if (itemObject.has("item") && !(itemObject.get("item").isJsonNull())) {
                return itemObject.get("item").getAsString();
            }
        } catch (IllegalStateException | JsonSyntaxException ignored) {
            return truncate(itemData);
        }
        return "unknown";
    }

    /** 构造 list_tables 工具定义。 */
    private static JsonObject listTablesTool() {
        JsonObject tool = functionTool("worldlogger_list_tables", "List all WorldLogger database tables that can be queried. Use this only when the user asks what tables exist or when you need schema discovery; do not use it as the final answer for player activity/session questions.");
        tool.add("parameters", objectSchema(new JsonObject(), List.of()));
        return tool;
    }

    /** 构造 describe_table 工具定义。 */
    private static JsonObject describeTableTool() {
        JsonObject properties = new JsonObject();
        properties.add("table", stringSchema("WorldLogger table name."));

        JsonObject tool = functionTool("worldlogger_describe_table", "Read column names and row count for one WorldLogger table.");
        tool.add("parameters", objectSchema(properties, List.of("table")));
        return tool;
    }

    /** 构造 query_table 工具定义。 */
    private static JsonObject queryTableTool() {
        JsonObject properties = new JsonObject();
        properties.add("table", stringSchema("WorldLogger table name."));
        properties.add("depth", integerSchema("How many rows to read. Larger values may require player approval."));
        properties.add("filter", stringSchema("Optional LIKE filter applied across columns. Use empty string if not needed."));
        properties.add("order", enumSchema("Sort order. Use newest for recent records or oldest for chronological order.", "newest", "oldest"));

        JsonObject tool = functionTool("worldlogger_query_table", "Query rows from a WorldLogger table for analysis.");
        tool.add("parameters", objectSchema(properties, List.of("table", "depth", "filter", "order")));
        return tool;
    }

    /** 构造 search_near_player 工具定义。 */
    private static JsonObject searchNearPlayerTool() {
        JsonObject properties = new JsonObject();
        properties.add("depth", integerSchema("How many nearby records to read. Larger values may require player approval."));
        properties.add("radius", integerSchema("Desired search radius in blocks. Current schema internally supports 16 blocks."));

        JsonObject tool = functionTool("worldlogger_search_near_player", "Search WorldLogger records near the command executor.");
        tool.add("parameters", objectSchema(properties, List.of("depth", "radius")));
        return tool;
    }

    /** 构造玩家最近登录后活动时间线工具定义。 */
    private static JsonObject playerActivityAfterLatestLoginTool() {
        JsonObject properties = new JsonObject();
        properties.add("player", stringSchema("Player name or UUID, for example Dev. Use the exact player mentioned by the user."));
        properties.add("depth", integerSchema("Maximum number of total timeline events to return. Larger values may require player approval."));
        properties.add("order", enumSchema("Timeline order. Use oldest for 'what happened after login' and newest for recent-first summaries.", "oldest", "newest"));
        properties.add("include_chat", booleanSchema("Whether to include SERVER_CHAT_INFO rows from this player."));
        properties.add("include_commands", booleanSchema("Whether to include EXECUTE_COMMAND_INFO rows from this player."));

        JsonObject tool = functionTool(
                "worldlogger_player_activity_after_latest_login",
                "Find a player's latest PLAYER_LOGIN_INFO row, then return a compact timeline of that player's records after that login across relevant WorldLogger tables. Use this directly when the user asks what a player did after joining, since their last login, or during the current/latest session."
        );
        tool.add("parameters", objectSchema(properties, List.of("player", "depth", "order", "include_chat", "include_commands")));
        return tool;
    }

    /** 构造 function tool 基础对象。 */
    private static JsonObject functionTool(String name, String description) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        return tool;
    }

    /** 构造 object JSON schema。 */
    private static JsonObject objectSchema(JsonObject properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray requiredArray = new JsonArray();
        for (String key : required) {
            requiredArray.add(key);
        }
        schema.add("required", requiredArray);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** 字符串 schema。 */
    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    /** 整数 schema。 */
    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        schema.addProperty("minimum", 1);
        return schema;
    }

    /** 布尔值 schema。 */
    private static JsonObject booleanSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        schema.addProperty("description", description);
        return schema;
    }

    /** 枚举字符串 schema。 */
    private static JsonObject enumSchema(String description, String... values) {
        JsonObject schema = stringSchema(description);
        JsonArray enumValues = new JsonArray();
        for (String value : values) {
            enumValues.add(value);
        }
        schema.add("enum", enumValues);
        return schema;
    }

    /** 读取表列名数组。 */
    private static JsonArray readColumns(Connection connection, String table) throws SQLException {
        JsonArray columns = new JsonArray();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " LIMIT 0");
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                JsonObject item = new JsonObject();
                item.addProperty("name", metaData.getColumnLabel(column));
                item.addProperty("type", metaData.getColumnTypeName(column));
                columns.add(item);
            }
        }
        return columns;
    }

    /** 读取表列名列表。 */
    private static List<String> columnNames(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " LIMIT 0");
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                columns.add(metaData.getColumnLabel(column));
            }
        }
        return columns;
    }

    /** 统计表总行数。 */
    private static long countRows(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /** 构造 LIKE 过滤条件。 */
    private static String buildWhereClause(List<String> columns, String filter, List<String> parameters) {
        if (filter == null || filter.isBlank()) {
            return "";
        }

        List<String> conditions = new ArrayList<>();
        for (String column : columns) {
            conditions.add("CAST(" + quote(column) + " AS CHAR) LIKE ?");
            parameters.add("%" + filter + "%");
        }
        return String.join(" OR ", conditions);
    }

    /** 选择排序表达式。 */
    private static String orderExpression(List<String> columns, String order) {
        String direction = "oldest".equalsIgnoreCase(order) ? "ASC" : "DESC";
        if (hasColumn(columns, "data_id")) {
            return quote("data_id") + " " + direction;
        }
        if (hasColumn(columns, "time")) {
            return quote("time") + " " + direction;
        }
        if (hasColumn(columns, "player_uuid")) {
            return quote("player_uuid") + " ASC";
        }
        return quote(columns.getFirst()) + " ASC";
    }

    /** 判断是否存在某列。 */
    private static boolean hasColumn(List<String> columns, String expected) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    /** 读取 ResultSet 的所有行。 */
    private static JsonArray readRows(ResultSet resultSet) throws SQLException {
        JsonArray rows = new JsonArray();
        ResultSetMetaData metaData = resultSet.getMetaData();
        while (resultSet.next()) {
            JsonObject row = new JsonObject();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                String value = resultSet.getString(column);
                row.addProperty(metaData.getColumnLabel(column), truncate(value));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 表名白名单校验。 */
    private static String requireTable(String table) throws SQLException {
        String normalizedTable = ListData.findTable(table);
        if (normalizedTable == null) {
            throw new SQLException("Unknown WorldLogger table: " + table);
        }
        return normalizedTable;
    }

    /** 给 MySQL 标识符加反引号。 */
    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** 截断长字段。 */
    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        if (normalized.length() <= MAX_CELL_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CELL_LENGTH - 3) + "...";
    }

    /** 创建成功结果。 */
    private static JsonObject success() {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
    }

    /** 创建失败结果。 */
    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", message);
        return result;
    }

    /** 读取字符串参数。 */
    private static String readString(JsonObject object, String key) {
        if (object == null || !(object.has(key)) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 读取整数参数。 */
    private static int readInt(JsonObject object, String key, int fallback) {
        if (object == null || !(object.has(key)) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 读取布尔参数。 */
    private static boolean readBoolean(JsonObject object, String key, boolean fallback) {
        if (object == null || !(object.has(key)) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 把 value 限制在 min 到 max 范围内。 */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

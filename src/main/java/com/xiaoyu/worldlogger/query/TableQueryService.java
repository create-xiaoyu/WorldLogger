package com.xiaoyu.worldlogger.query;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * /worldlogger select 的聊天栏查询服务。
 *
 * <p>这个服务刻意“一页只返回一条数据库记录”，并且会缩短长字段。
 * 原因是聊天栏空间很小，如果直接输出完整 NBT 或 JSON，会把聊天栏刷爆。</p>
 *
 * <p>GUI 需要查看完整原始数据，所以 GUI 使用 GuiTableQueryService，不使用这个压缩版服务。</p>
 */
public final class TableQueryService {
    /** 聊天栏中单个字段最多显示的字符数，超出后用 ... 截断。 */
    private static final int MAX_CELL_LENGTH = 120;

    /** 容器表名。容器表的 source_item/modify_item/modify_type 会被合并成一行显示。 */
    private static final String CONTAINER_TABLE = "PLAYER_CONTAINER_INFO";

    /** modify 组合值的分隔符。使用 tab 是因为物品 ID 中通常不会出现 tab。 */
    private static final String MODIFY_VALUE_SEPARATOR = "\t";

    /** 工具类不需要实例化。 */
    private TableQueryService() {}

    /**
     * 查询指定表的一页数据。
     *
     * @param table 玩家请求的表名。
     * @param page 页码，从 1 开始。
     * @return 包含当前页字段、页码和是否有下一页的 QueryPage。
     * @throws SQLException 表名非法或 SQL 执行失败时抛出。
     */
    public static QueryPage selectPage(String table, int page) throws SQLException {
        // 表名必须来自 ListData 白名单，不能直接使用玩家输入。
        String normalizedTable = ListData.findTable(table);
        if (normalizedTable == null) {
            throw new SQLException("Unknown table: " + table);
        }

        // PLAYER_BASE_INFO 没有 data_id，所以需要特殊排序列。
        String orderColumn = orderColumn(normalizedTable);
        // 表名来自白名单；用户输入不会直接拼接进 SQL。
        // LIMIT 2 的含义是：取 1 条用来显示，再多取 1 条用来判断有没有下一页。
        String sql = "SELECT * FROM " + normalizedTable + " ORDER BY " + orderColumn + " ASC LIMIT 2 OFFSET ?";
        try (Connection connection = InitMySQL.getMySQLConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            // page 从 1 开始，OFFSET 从 0 开始，所以要 page - 1。
            statement.setLong(1, Math.max(0L, (long) page - 1L));

            try (ResultSet resultSet = statement.executeQuery()) {
                List<QueryColumn> columns = new ArrayList<>();
                // ResultSetMetaData 可以读取查询结果的列名和列数。
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                // 没有第一条记录，说明这一页为空。
                if (!resultSet.next()) {
                    return new QueryPage(page, false, columns);
                }

                // 读取第一条记录并格式化成聊天栏可显示的列数据。
                columns.addAll(formatRecord(normalizedTable, resultSet, metaData, columnCount));
                // LIMIT 2 多读出来的第二条记录只用于判断 hasNext，不会显示。
                boolean hasNext = resultSet.next();
                return new QueryPage(page, hasNext, columns);
            }
        }
    }

    /**
     * 格式化一条数据库记录。
     *
     * @param table 表名。
     * @param resultSet 当前结果集，游标已经停在要读取的那一行。
     * @param metaData 列元数据。
     * @param columnCount 列数量。
     * @return 适合聊天栏显示的列列表。
     */
    private static List<QueryColumn> formatRecord(String table, ResultSet resultSet, ResultSetMetaData metaData, int columnCount) throws SQLException {
        // 容器记录里的物品 JSON 很长，所以单独处理。
        if (CONTAINER_TABLE.equals(table)) {
            return formatContainerRecord(resultSet, metaData, columnCount);
        }

        List<QueryColumn> columns = new ArrayList<>(columnCount);

        // 普通表：逐列读取，字段名保持数据库列名，值做换行清理和长度截断。
        for (int column = 1; column <= columnCount; column++) {
            String columnName = metaData.getColumnLabel(column);
            String value = resultSet.getString(column);
            columns.add(new QueryColumn(columnName, formatCell(value)));
        }

        return columns;
    }

    /**
     * 格式化 PLAYER_CONTAINER_INFO 记录。
     *
     * @return 将 source_item、modify_item、modify_type 合并后的列列表。
     */
    private static List<QueryColumn> formatContainerRecord(ResultSet resultSet, ResultSetMetaData metaData, int columnCount) throws SQLException {
        List<QueryColumn> columns = new ArrayList<>(columnCount);
        String sourceItem = null;
        String modifyItem = null;
        String modifyType = null;

        // 先遍历全部列。三个物品变化字段暂存起来，其他字段正常加入显示列表。
        for (int column = 1; column <= columnCount; column++) {
            String columnName = metaData.getColumnLabel(column);
            String value = resultSet.getString(column);

            switch (columnName) {
                case "source_item" -> sourceItem = value;
                case "modify_item" -> modifyItem = value;
                case "modify_type" -> modifyType = value;
                default -> columns.add(new QueryColumn(columnName, formatCell(value)));
            }
        }

        // 最后追加一个虚拟列 modify，由客户端网络处理层本地化成完整句子。
        columns.add(new QueryColumn("modify", formatModifyValue(modifyType, modifyItem, sourceItem)));
        return columns;
    }

    /**
     * 决定排序列。
     *
     * @param table 表名。
     * @return 可安全拼进 SQL 的排序列名。
     */
    private static String orderColumn(String table) {
        if ("PLAYER_BASE_INFO".equals(table)) {
            return "player_uuid";
        }
        return "data_id";
    }

    /**
     * 组合容器变化显示值。
     *
     * @return modifyType、modifyItemId、sourceItemId 三段 tab 分隔文本。
     */
    private static String formatModifyValue(String modifyType, String modifyItem, String sourceItem) {
        return formatCell(modifyType) + MODIFY_VALUE_SEPARATOR + extractItemId(modifyItem) + MODIFY_VALUE_SEPARATOR + extractItemId(sourceItem);
    }

    /**
     * 从物品 JSON 中提取物品 ID。
     *
     * @param itemData 数据库中的物品 JSON 字符串。
     * @return 物品 ID；无法解析时返回压缩后的原文本；空值返回 null。
     */
    private static String extractItemId(String itemData) {
        if (itemData == null || itemData.isBlank()) {
            return "null";
        }

        try {
            // 只读取 item 字段，避免把 custom_data 等长数据刷到聊天栏。
            JsonObject itemObject = JsonParser.parseString(itemData).getAsJsonObject();
            if (itemObject.has("item") && !itemObject.get("item").isJsonNull()) {
                return itemObject.get("item").getAsString();
            }
        } catch (IllegalStateException | JsonSyntaxException ignored) {
            // 数据不是合法 JSON 时，不让查询失败，而是显示截断后的原始值。
            return formatCell(itemData);
        }

        return "unknown";
    }

    /**
     * 格式化普通字段。
     *
     * @param value 数据库原始值。
     * @return 适合聊天栏显示的一行短文本。
     */
    private static String formatCell(String value) {
        if (value == null) {
            return "null";
        }

        // 聊天栏中换行会导致版式混乱，所以统一替换为空格。
        String formatted = value.replace('\r', ' ').replace('\n', ' ');
        if (formatted.length() <= MAX_CELL_LENGTH) {
            return formatted;
        }
        return formatted.substring(0, MAX_CELL_LENGTH - 3) + "...";
    }

    /**
     * 一页 select 查询结果。
     *
     * @param page 当前页码。
     * @param hasNext 是否还有下一页。
     * @param columns 当前记录的列数据。
     */
    public record QueryPage(int page, boolean hasNext, List<QueryColumn> columns) {
        /** 创建不可变列列表，防止调用方修改结果。 */
        public QueryPage {
            columns = List.copyOf(columns);
        }
    }

    /**
     * 一列查询结果。
     *
     * @param columnName 数据库列名或虚拟列名。
     * @param value 显示值。
     */
    public record QueryColumn(String columnName, String value) {}
}

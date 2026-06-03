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

public final class TableQueryService {
    private static final int MAX_CELL_LENGTH = 120;
    private static final String CONTAINER_TABLE = "PLAYER_CONTAINER_INFO";
    private static final String MODIFY_VALUE_SEPARATOR = "\t";

    private TableQueryService() {}

    public static QueryPage selectPage(String table, int page) throws SQLException {
        String normalizedTable = ListData.findTable(table);
        if (normalizedTable == null) {
            throw new SQLException("Unknown table: " + table);
        }

        String orderColumn = orderColumn(normalizedTable);
        String sql = "SELECT * FROM " + normalizedTable + " ORDER BY " + orderColumn + " ASC LIMIT 2 OFFSET ?";
        try (Connection connection = InitMySQL.getMySQLConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Math.max(0L, (long) page - 1L));

            try (ResultSet resultSet = statement.executeQuery()) {
                List<QueryColumn> columns = new ArrayList<>();
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (!resultSet.next()) {
                    return new QueryPage(page, false, columns);
                }

                columns.addAll(formatRecord(normalizedTable, resultSet, metaData, columnCount));
                boolean hasNext = resultSet.next();
                return new QueryPage(page, hasNext, columns);
            }
        }
    }

    private static List<QueryColumn> formatRecord(String table, ResultSet resultSet, ResultSetMetaData metaData, int columnCount) throws SQLException {
        if (CONTAINER_TABLE.equals(table)) {
            return formatContainerRecord(resultSet, metaData, columnCount);
        }

        List<QueryColumn> columns = new ArrayList<>(columnCount);

        for (int column = 1; column <= columnCount; column++) {
            String columnName = metaData.getColumnLabel(column);
            String value = resultSet.getString(column);
            columns.add(new QueryColumn(columnName, formatCell(value)));
        }

        return columns;
    }

    private static List<QueryColumn> formatContainerRecord(ResultSet resultSet, ResultSetMetaData metaData, int columnCount) throws SQLException {
        List<QueryColumn> columns = new ArrayList<>(columnCount);
        String sourceItem = null;
        String modifyItem = null;
        String modifyType = null;

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

        columns.add(new QueryColumn("modify", formatModifyValue(modifyType, modifyItem, sourceItem)));
        return columns;
    }

    private static String orderColumn(String table) {
        if ("PLAYER_BASE_INFO".equals(table)) {
            return "player_uuid";
        }
        return "data_id";
    }

    private static String formatModifyValue(String modifyType, String modifyItem, String sourceItem) {
        return formatCell(modifyType) + MODIFY_VALUE_SEPARATOR + extractItemId(modifyItem) + MODIFY_VALUE_SEPARATOR + extractItemId(sourceItem);
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
            return formatCell(itemData);
        }

        return "unknown";
    }

    private static String formatCell(String value) {
        if (value == null) {
            return "null";
        }

        String formatted = value.replace('\r', ' ').replace('\n', ' ');
        if (formatted.length() <= MAX_CELL_LENGTH) {
            return formatted;
        }
        return formatted.substring(0, MAX_CELL_LENGTH - 3) + "...";
    }

    public record QueryPage(int page, boolean hasNext, List<QueryColumn> columns) {
        public QueryPage {
            columns = List.copyOf(columns);
        }
    }

    public record QueryColumn(String columnName, String value) {}
}

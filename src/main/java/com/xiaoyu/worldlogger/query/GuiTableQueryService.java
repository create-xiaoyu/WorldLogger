package com.xiaoyu.worldlogger.query;

import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GuiTableQueryService {
    private GuiTableQueryService() {}

    public static QueryPage selectPage(String table, int page, String filter) throws SQLException {
        String normalizedTable = ListData.findTable(table);
        if (normalizedTable == null) {
            throw new SQLException("Unknown table: " + table);
        }

        int normalizedPage = Math.max(1, page);
        String normalizedFilter = filter == null ? "" : filter.trim();

        try (Connection connection = InitMySQL.getMySQLConnection()) {
            List<String> columns = readColumns(connection, normalizedTable);
            List<String> parameters = new ArrayList<>();
            String whereClause = buildWhereClause(columns, normalizedFilter, parameters);
            if (!(normalizedFilter.isBlank()) && whereClause.isBlank()) {
                return new QueryPage(normalizedPage, false, List.of());
            }

            String sql = "SELECT * FROM " + normalizedTable
                    + (whereClause.isBlank() ? "" : " WHERE " + whereClause)
                    + " ORDER BY " + orderExpression(columns)
                    + " LIMIT 2 OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                for (String parameter : parameters) {
                    statement.setString(parameterIndex++, parameter);
                }
                statement.setLong(parameterIndex, Math.max(0L, (long) normalizedPage - 1L));

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return new QueryPage(normalizedPage, false, List.of());
                    }

                    List<QueryCell> cells = readRow(resultSet);
                    boolean hasNext = resultSet.next();
                    return new QueryPage(normalizedPage, hasNext, cells);
                }
            }
        }
    }

    private static List<String> readColumns(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " LIMIT 0");
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<String> columns = new ArrayList<>(metaData.getColumnCount());
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                columns.add(metaData.getColumnLabel(column));
            }
            return columns;
        }
    }

    private static List<QueryCell> readRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<QueryCell> cells = new ArrayList<>(metaData.getColumnCount());
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            String value = resultSet.getString(column);
            cells.add(new QueryCell(metaData.getColumnLabel(column), value == null ? "null" : value));
        }
        return cells;
    }

    private static String buildWhereClause(List<String> columns, String filter, List<String> parameters) {
        if (filter.isBlank()) {
            return "";
        }

        List<String> conditions = new ArrayList<>();
        List<String> coordinateTokens = coordinateTokens(filter);
        String rawLike = "%" + filter + "%";

        for (String column : columns) {
            if (!(isSearchableColumn(column))) {
                continue;
            }

            if (isPositionColumn(column) && coordinateTokens.size() > 1) {
                List<String> tokenConditions = new ArrayList<>();
                for (String token : coordinateTokens) {
                    tokenConditions.add(quote(column) + " LIKE ?");
                    parameters.add("%" + token + "%");
                }
                conditions.add("(" + String.join(" AND ", tokenConditions) + ")");
            }

            conditions.add(quote(column) + " LIKE ?");
            parameters.add(rawLike);
        }

        return String.join(" OR ", conditions);
    }

    private static List<String> coordinateTokens(String filter) {
        String normalized = filter.toLowerCase(Locale.ROOT)
                .replace("x:", " ")
                .replace("y:", " ")
                .replace("z:", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace(",", " ");
        String[] split = normalized.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String token : split) {
            if (!(token.isBlank())) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean isSearchableColumn(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return "player_uuid".equals(lower)
                || "player_name".equals(lower)
                || isPositionColumn(lower);
    }

    private static boolean isPositionColumn(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return "pos".equals(lower) || lower.endsWith("_pos");
    }

    private static String orderExpression(List<String> columns) {
        if (hasColumn(columns, "data_id")) {
            return quote("data_id") + " DESC";
        }
        if (hasColumn(columns, "time")) {
            return quote("time") + " DESC";
        }
        if (hasColumn(columns, "player_uuid")) {
            return quote("player_uuid") + " ASC";
        }
        return quote(columns.getFirst()) + " ASC";
    }

    private static boolean hasColumn(List<String> columns, String expectedColumn) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(expectedColumn)) {
                return true;
            }
        }
        return false;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public record QueryPage(int page, boolean hasNext, List<QueryCell> cells) {
        public QueryPage {
            cells = List.copyOf(cells);
        }
    }

    public record QueryCell(String columnName, String value) {}
}

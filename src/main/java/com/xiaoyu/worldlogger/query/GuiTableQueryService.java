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

/**
 * GUI 原始表数据查询服务。
 *
 * <p>GUI 的目标是让管理员查看完整数据，所以这里不会像聊天栏 select 那样截断 JSON/NBT。
 * 表名仍然必须来自 ListData 白名单；搜索值通过 PreparedStatement 参数绑定，避免 SQL 注入。</p>
 */
public final class GuiTableQueryService {
    /** 工具类不需要实例化。 */
    private GuiTableQueryService() {}

    /**
     * 查询 GUI 当前页的一条记录。
     *
     * @param table 表名。
     * @param page 页码，从 1 开始。
     * @param filter 搜索框内容，可为空。
     * @return 当前页字段列表、页码和是否有下一页。
     */
    public static QueryPage selectPage(String table, int page, String filter) throws SQLException {
        // 表名白名单校验，防止玩家输入进入 SQL 表名位置。
        String normalizedTable = ListData.findTable(table);
        if (normalizedTable == null) {
            throw new SQLException("Unknown table: " + table);
        }

        // 页码和搜索词先规范化，后续逻辑不用反复判断 null 或负数。
        int normalizedPage = Math.max(1, page);
        String normalizedFilter = filter == null ? "" : filter.trim();

        try (Connection connection = InitMySQL.getMySQLConnection()) {
            // 读取列名后，才能知道这张表里有哪些字段可以搜索。
            List<String> columns = readColumns(connection, normalizedTable);
            List<String> parameters = new ArrayList<>();
            String whereClause = buildWhereClause(columns, normalizedFilter, parameters);
            // 如果用户输入了过滤词，但该表没有可搜索列，则直接返回空结果。
            if (!(normalizedFilter.isBlank()) && whereClause.isBlank()) {
                return new QueryPage(normalizedPage, false, List.of());
            }

            // 表名来自白名单，列名来自数据库元数据，搜索值仍然使用 ? 参数绑定。
            // LIMIT 2 同样是“显示 1 条 + 预读 1 条判断下一页”。
            String sql = "SELECT * FROM " + normalizedTable
                    + (whereClause.isBlank() ? "" : " WHERE " + whereClause)
                    + " ORDER BY " + orderExpression(columns)
                    + " LIMIT 2 OFFSET ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameterIndex = 1;
                // 给 WHERE 中的每个 LIKE ? 绑定搜索值。
                for (String parameter : parameters) {
                    statement.setString(parameterIndex++, parameter);
                }
                // 最后一个参数是分页偏移量。
                statement.setLong(parameterIndex, Math.max(0L, (long) normalizedPage - 1L));

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return new QueryPage(normalizedPage, false, List.of());
                    }

                    // GUI 每页显示一条记录，所以只读取第一行。
                    List<QueryCell> cells = readRow(resultSet);
                    // 如果还能读到第二行，说明有下一页。
                    boolean hasNext = resultSet.next();
                    return new QueryPage(normalizedPage, hasNext, cells);
                }
            }
        }
    }

    /**
     * 读取某张表的列名。
     *
     * @param connection 数据库连接。
     * @param table 已经白名单校验过的表名。
     * @return 该表的所有列名。
     */
    private static List<String> readColumns(Connection connection, String table) throws SQLException {
        // LIMIT 0 不读取数据，只让 ResultSetMetaData 告诉我们列结构。
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

    /**
     * 读取当前 ResultSet 行中的所有列。
     *
     * @param resultSet 游标已经停在要读取的行。
     * @return GUI 单元格列表。
     */
    private static List<QueryCell> readRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<QueryCell> cells = new ArrayList<>(metaData.getColumnCount());
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            String value = resultSet.getString(column);
            cells.add(new QueryCell(metaData.getColumnLabel(column), value == null ? "null" : value));
        }
        return cells;
    }

    /**
     * 根据搜索框内容构造 WHERE 条件。
     *
     * @param columns 当前表所有列名。
     * @param filter 搜索框内容。
     * @param parameters 输出参数列表，调用方会按顺序绑定到 PreparedStatement。
     * @return WHERE 子句，不包含开头的 WHERE。
     */
    private static String buildWhereClause(List<String> columns, String filter, List<String> parameters) {
        if (filter.isBlank()) {
            return "";
        }

        List<String> conditions = new ArrayList<>();
        List<String> coordinateTokens = coordinateTokens(filter);
        String rawLike = "%" + filter + "%";

        // 只允许搜索玩家 UUID、玩家名和坐标列，避免所有 TEXT 字段 LIKE 导致数据库压力太大。
        for (String column : columns) {
            if (!(isSearchableColumn(column))) {
                continue;
            }

            if (isPositionColumn(column) && coordinateTokens.size() > 1) {
                // 坐标搜索如 "100 64 -30" 应匹配格式化后的 "[X: 100, Y: 64, Z: -30]"。
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

    /**
     * 把坐标搜索文本拆成多个 token。
     *
     * @param filter 原始搜索词。
     * @return 去掉 x/y/z、括号、逗号后的片段列表。
     */
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

    /** 判断列是否允许被搜索。 */
    private static boolean isSearchableColumn(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return "player_uuid".equals(lower)
                || "player_name".equals(lower)
                || isPositionColumn(lower);
    }

    /** 判断列是否表示坐标。 */
    private static boolean isPositionColumn(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return "pos".equals(lower) || lower.endsWith("_pos");
    }

    /**
     * 选择排序表达式。
     *
     * @param columns 表的列名列表。
     * @return ORDER BY 后面的安全表达式。
     */
    private static String orderExpression(List<String> columns) {
        // 优先按 data_id 倒序，让最新记录排在前面。
        if (hasColumn(columns, "data_id")) {
            return quote("data_id") + " DESC";
        }
        // 没有 data_id 时尝试按 time 倒序。
        if (hasColumn(columns, "time")) {
            return quote("time") + " DESC";
        }
        // PLAYER_BASE_INFO 这类基础表没有时间列，可以按 UUID 排序。
        if (hasColumn(columns, "player_uuid")) {
            return quote("player_uuid") + " ASC";
        }
        // 最后兜底按第一列排序。
        return quote(columns.getFirst()) + " ASC";
    }

    /** 判断列列表中是否存在某个列名，忽略大小写。 */
    private static boolean hasColumn(List<String> columns, String expectedColumn) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(expectedColumn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给 MySQL 标识符加反引号。
     *
     * @param identifier 表名或列名。
     * @return 安全转义后的标识符。
     */
    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** GUI 一页查询结果。 */
    public record QueryPage(int page, boolean hasNext, List<QueryCell> cells) {
        /** 创建不可变单元格列表。 */
        public QueryPage {
            cells = List.copyOf(cells);
        }
    }

    /** GUI 中的一列数据。 */
    public record QueryCell(String columnName, String value) {}
}

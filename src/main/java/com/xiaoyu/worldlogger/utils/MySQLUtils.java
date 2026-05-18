package com.xiaoyu.worldlogger.utils;

public class MySQLUtils {
    public static String selectSQL(String Table) {
        return String.format("SELECT * FROM %s", Table);
    }

    public static String selectSQL(String Table, String Value) {
        return String.format("SELECT %s FROM %s", Value, Table);
    }

    public static String selectWhereSQL(String Table, String Value, String Where) {
        return String.format("SELECT %s FROM %s WHERE %s", Value, Table, Where);
    }

    public static String selectWhereSQL(String Table, String Where) {
        return String.format("SELECT * FROM %s WHERE %s", Table, Where);
    }
}

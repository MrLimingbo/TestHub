package io.testhub.platform.desktop;

import java.util.regex.Pattern;

/** Narrow translations for the application's static SQL; user values remain bound parameters. */
final class DesktopSql {
    private static final Pattern JSON_PARAMETER = Pattern.compile(
            "(?i)CAST\\(\\s*(:[a-zA-Z][a-zA-Z0-9_]*|\\?)\\s+AS\\s+JSON\\s*\\)");
    private static final Pattern TIME_INTERVAL = Pattern.compile(
            "(?i)DATE_(ADD|SUB)\\(\\s*CURRENT_TIMESTAMP\\(([0-6])\\)\\s*,\\s*INTERVAL\\s+(\\d+)\\s+(SECOND|MINUTE)\\s*\\)");

    private DesktopSql() { }

    static String translate(String sql) {
        StringBuilder result = new StringBuilder();
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            char quote = sql.charAt(i);
            if (quote != '\'' && quote != '"' && quote != '`') continue;
            result.append(expressions(sql.substring(start, i)));
            int quotedStart = i++;
            for (; i < sql.length(); i++) {
                if (sql.charAt(i) == '\\') i++;
                else if (sql.charAt(i) == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
                    else break;
                }
            }
            start = Math.min(i + 1, sql.length());
            result.append(sql, quotedStart, start);
        }
        return result.append(expressions(sql.substring(start))).toString();
    }

    private static String expressions(String sql) {
        // H2 CAST(text AS JSON) creates a JSON string. FORMAT JSON parses the JSON document instead.
        String json = JSON_PARAMETER.matcher(sql).replaceAll("$1 FORMAT JSON");
        return TIME_INTERVAL.matcher(json).replaceAll(match -> "DATEADD('" + match.group(4) + "', "
                + ("SUB".equalsIgnoreCase(match.group(1)) ? "-" : "") + match.group(3)
                + ", CURRENT_TIMESTAMP(" + match.group(2) + "))");
    }
}

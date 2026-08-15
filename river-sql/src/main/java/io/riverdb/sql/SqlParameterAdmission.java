package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Lexical admission policy for the first direct-block parameter slice. */
final class SqlParameterAdmission {
  private SqlParameterAdmission() {
  }

  static boolean hasMarker(CharSequence sql) {
    boolean text = false;
    for (int index = 0; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (character == '\'') {
        if (text && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
          index++;
        } else {
          text = !text;
        }
      } else if (!text && character == '?') {
        return true;
      }
    }
    return false;
  }

  static boolean dataStatement(CharSequence sql) {
    int start = 0;
    while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
      start++;
    }
    return startsKeyword(sql, start, "INSERT")
        || startsKeyword(sql, start, "UPDATE")
        || startsKeyword(sql, start, "DELETE")
        || startsKeyword(sql, start, "SELECT")
        || startsKeyword(sql, start, "EXPLAIN");
  }

  static StatusCode beginData(
      CharSequence sql, SqlParameterSource source, SqlParserInput input) {
    input.beginParameters(source);
    return hasMarker(sql) && !dataStatement(sql)
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  static StatusCode beginQuery(
      CharSequence sql,
      SqlParameterSource source,
      SqlParserInput input,
      SqlQueryParser parser) {
    input.beginParameters(source);
    return hasMarker(sql) && parser.hasNestedTopology(sql)
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  static StatusCode finish(StatusCode status, SqlParserInput input) {
    return status.isOk() ? input.finishParameters() : status;
  }

  private static boolean startsKeyword(
      CharSequence sql, int start, String keyword) {
    if (sql.length() - start < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (SqlParserInput.upper(sql.charAt(start + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int end = start + keyword.length();
    return end == sql.length()
        || !SqlParserInput.identifierStart(sql.charAt(end))
            && !(sql.charAt(end) >= '0' && sql.charAt(end) <= '9');
  }
}

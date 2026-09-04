package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses bounded INSERT, UPDATE, and DELETE values and predicates. */
final class SqlDataChangeParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlUpdateValueParser updateValues;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();
  private final LongRow rowResult = new LongRow();

  SqlDataChangeParser(
      SqlParser parent, SqlParserInput parserInput, SqlUpdateValueParser valueParser) {
    parser = parent;
    input = parserInput;
    updateValues = valueParser;
  }

  StatusCode parseInsert(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "INTO");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk() && consumeCharacter(sql, '(')) {
      status = insertColumns(sql, result);
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "VALUES");
    }
    if (status.isOk()) {
      status = appendInsertRow(sql, result, false);
    }
    while (status.isOk() && consumeCharacter(sql, ',')) {
      status = appendInsertRow(sql, result, true);
    }
    return status;
  }



  private StatusCode insertColumns(CharSequence sql, SqlCommand result) {
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      status = columnIdentifier(sql, result);
      if (!status.isOk() || consumeCharacter(sql, ')')) {
        return status;
      }
      status = requireCharacter(sql, ',');
    }
    return status;
  }

  private StatusCode appendInsertRow(
      CharSequence sql, SqlCommand result, boolean subsequent) {
    StatusCode status = row(sql, result, rowResult);
    if (status.isOk()
        && subsequent
        && rowResult.count != result.insertColumnCount()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      if (!result.appendInsert(
          rowResult.highs,
          rowResult.values,
          rowResult.nulls,
          rowResult.defaults,
          rowResult.typeDescriptors,
          rowResult.count)) return StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  StatusCode parseUpdate(CharSequence sql, SqlCommand result) {
    StatusCode status = identifier(sql, result.writableTableName());
    if (status.isOk()) {
      status = requireKeyword(sql, "SET");
    }
    while (status.isOk()) {
      status = appendUpdate(sql, result);
      if (!status.isOk() || !consumeCharacter(sql, ',')) {
        break;
      }
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "WHERE");
    }
    return status.isOk() ? predicates(sql, result, false) : status;
  }

  private StatusCode appendUpdate(CharSequence sql, SqlCommand result) {
    StatusCode status = columnIdentifier(sql, result);
    if (status.isOk()) status = requireCharacter(sql, '=');
    return status.isOk() ? appendUpdateValue(sql, result) : status;
  }

  private StatusCode appendUpdateValue(CharSequence sql, SqlCommand result) {
    return updateValues.parse(sql, result);
  }

  StatusCode parseDelete(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "FROM");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "WHERE");
    }
    return status.isOk() ? predicates(sql, result, false) : status;
  }
  private StatusCode row(
      CharSequence sql, SqlCommand command, LongRow result) {
    resetRow(result);
    StatusCode status = requireCharacter(sql, '(');
    while (status.isOk()) {
      if (result.count >= SqlCommand.MAXIMUM_COLUMNS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = appendRowValue(sql, command, result);
      if (!status.isOk() || consumeCharacter(sql, ')')) {
        break;
      }
      status = requireCharacter(sql, ',');
    }
    if (!status.isOk()) {
      return status;
    }
    return result.count >= 1 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static void resetRow(LongRow result) {
    int previous = result.count;
    result.count = 0;
    for (int index = 0; index < previous; index++) {
      result.typeDescriptors[index] = 0;
      result.highs[index] = 0;
      result.nulls[index] = false;
      result.defaults[index] = false;
    }
  }

  private StatusCode appendRowValue(
      CharSequence sql, SqlCommand command, LongRow result) {
    boolean defaultValue = consumeKeyword(sql, "DEFAULT");
    if (!result.ensure(result.count + 1)) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = parseRowValue(sql, command, defaultValue);
    if (!status.isOk()) {
      return status;
    }
    boolean nullValue = !defaultValue && numberResult.nullValue;
    int index = result.count++;
    result.values[index] = nullValue ? 0 : numberResult.value;
    result.highs[index] = nullValue ? 0 : numberResult.high;
    if (nullValue) {
      result.nulls[index] = true;
      result.typeDescriptors[index] = numberResult.typeDescriptor;
    } else if (defaultValue) {
      result.defaults[index] = true;
    } else {
      result.typeDescriptors[index] = numberResult.typeDescriptor;
    }
    return StatusCode.OK;
  }

  private StatusCode parseRowValue(
      CharSequence sql,
      SqlCommand command,
      boolean defaultValue) {
    if (defaultValue) {
      return StatusCode.OK;
    }
    return updateValues.parseInsert(sql, command, numberResult);
  }


  private StatusCode predicates(
      CharSequence sql, SqlCommand result, boolean qualified) {
    return parser.predicates(sql, result, qualified);
  }

  private StatusCode columnIdentifier(CharSequence sql, SqlCommand result) {
    return parser.columnIdentifier(sql, result);
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode requireKeyword(CharSequence sql, String keyword) {
    return input.requireKeyword(sql, keyword);
  }

  private boolean consumeKeyword(CharSequence sql, String keyword) {
    return input.consumeKeyword(sql, keyword);
  }

  private StatusCode requireCharacter(CharSequence sql, char expected) {
    return input.requireCharacter(sql, expected);
  }

  private boolean consumeCharacter(CharSequence sql, char expected) {
    return input.consumeCharacter(sql, expected);
  }

  private static final class LongRow {
    private long[] values = new long[8];
    private long[] highs = new long[8];
    private int[] typeDescriptors = new int[8];
    private boolean[] nulls = new boolean[8];
    private boolean[] defaults = new boolean[8];
    private final SqlIdentifier identifier = new SqlIdentifier();
    private int count;

    private boolean ensure(int required) {
      if (required <= values.length) return true;
      int capacity = Math.min(SqlCommand.MAXIMUM_COLUMNS, values.length * 2);
      try {
        long[] nextValues = java.util.Arrays.copyOf(values, capacity);
        long[] nextHighs = java.util.Arrays.copyOf(highs, capacity);
        int[] nextTypes = java.util.Arrays.copyOf(typeDescriptors, capacity);
        boolean[] nextNulls = java.util.Arrays.copyOf(nulls, capacity);
        boolean[] nextDefaults = java.util.Arrays.copyOf(defaults, capacity);
        values = nextValues;
        highs = nextHighs;
        typeDescriptors = nextTypes;
        nulls = nextNulls;
        defaults = nextDefaults;
        return true;
      } catch (OutOfMemoryError error) {
        return false;
      }
    }
  }
}

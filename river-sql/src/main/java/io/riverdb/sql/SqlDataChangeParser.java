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
    if (subsequent
        && result.insertRowCount() >= SqlCommand.MAXIMUM_INSERT_ROWS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = row(sql, rowResult);
    if (status.isOk()
        && subsequent
        && rowResult.count != result.insertColumnCount()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      result.appendInsert(
          rowResult.values,
          rowResult.nullMask,
          rowResult.defaultMask,
          rowResult.typeDescriptors,
          rowResult.count);
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
  private StatusCode row(CharSequence sql, LongRow result) {
    resetRow(result);
    StatusCode status = requireCharacter(sql, '(');
    while (status.isOk()) {
      if (result.count >= SqlCommand.MAXIMUM_COLUMNS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = appendRowValue(sql, result);
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
    result.count = 0;
    result.nullMask = 0;
    result.defaultMask = 0;
    for (int index = 0; index < result.typeDescriptors.length; index++) {
      result.typeDescriptors[index] = 0;
    }
  }

  private StatusCode appendRowValue(CharSequence sql, LongRow result) {
    boolean nullValue = consumeKeyword(sql, "NULL");
    boolean defaultValue = !nullValue && consumeKeyword(sql, "DEFAULT");
    StatusCode status = parseRowLiteral(sql, nullValue, defaultValue);
    if (!status.isOk()) {
      return status;
    }
    int index = result.count++;
    result.values[index] = nullValue ? 0 : numberResult.value;
    if (nullValue) {
      result.nullMask |= 1L << index;
    } else if (defaultValue) {
      result.defaultMask |= 1L << index;
    } else {
      result.typeDescriptors[index] = numberResult.typeDescriptor;
    }
    return StatusCode.OK;
  }

  private StatusCode parseRowLiteral(
      CharSequence sql,
      boolean nullValue,
      boolean defaultValue) {
    if (nullValue || defaultValue) {
      return StatusCode.OK;
    }
    return literal(sql, numberResult);
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

  private StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    return input.literal(sql, result);
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
    private final long[] values = new long[SqlCommand.MAXIMUM_COLUMNS];
    private final int[] typeDescriptors = new int[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlIdentifier identifier = new SqlIdentifier();
    private int count;
    private long nullMask;
    private long defaultMask;
  }
}

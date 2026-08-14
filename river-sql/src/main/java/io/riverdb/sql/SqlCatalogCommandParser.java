package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses catalog commands that do not own table-column or predicate grammar. */
final class SqlCatalogCommandParser {
  private final SqlParserInput input;
  private final SqlParser.LongResult number = new SqlParser.LongResult();
  private long sequenceStart;
  private long sequenceIncrement;
  private int sequenceOptions;
  private boolean optionConsumed;

  SqlCatalogCommandParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode parseCreateView(CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.CREATE_VIEW, 0, 0);
    StatusCode status = input.identifier(sql, result.writableTableName());
    if (status.isOk()) {
      status = input.requireKeyword(sql, "AS");
    }
    input.skipSpaces(sql);
    int start = input.position();
    int end = viewDefinitionEnd(sql, start);
    if (status.isOk()) {
      status = result.setViewQuery(sql, start, end);
    }
    input.position(sql.length());
    return status;
  }

  StatusCode parseCreateRemainder(CharSequence sql, SqlCommand result) {
    if (input.consumeKeyword(sql, "SEQUENCE")) {
      result.set(SqlCommandType.CREATE_SEQUENCE, 0, 0);
      return parseCreateSequence(sql, result);
    }
    boolean unique = input.consumeKeyword(sql, "UNIQUE");
    result.set(
        unique ? SqlCommandType.CREATE_UNIQUE_INDEX : SqlCommandType.CREATE_INDEX,
        0,
        0);
    return parseCreateIndex(sql, result);
  }

  StatusCode parseAlter(CharSequence sql, SqlCommand result) {
    return input.consumeKeyword(sql, "INDEX")
        ? parseAlterIndex(sql, result) : parseAlterTable(sql, result);
  }

  StatusCode parseDrop(CharSequence sql, SqlCommand result) {
    if (input.consumeKeyword(sql, "VIEW")) {
      return parseNamed(sql, result, SqlCommandType.DROP_VIEW, result.writableTableName());
    }
    if (input.consumeKeyword(sql, "SEQUENCE")) {
      return parseNamed(
          sql, result, SqlCommandType.DROP_SEQUENCE, result.writableSequenceName());
    }
    if (input.consumeKeyword(sql, "INDEX")) {
      result.set(SqlCommandType.DROP_INDEX, 0, 0);
      StatusCode status = input.identifier(sql, result.writableIndexName());
      if (status.isOk()) {
        status = input.requireKeyword(sql, "ON");
      }
      return status.isOk()
          ? input.identifier(sql, result.writableTableName()) : status;
    }
    result.set(SqlCommandType.DROP_TABLE, 0, 0);
    StatusCode status = input.requireKeyword(sql, "TABLE");
    return status.isOk()
        ? input.identifier(sql, result.writableTableName()) : status;
  }

  boolean usesReservedObjectName(SqlCommandType type, SqlCommand command) {
    return switch (type) {
      case CREATE_TABLE -> reservedObjectName(command.tableName())
          || reservedReferenceName(command);
      case CREATE_VIEW, DROP_VIEW, DROP_TABLE, ALTER_TABLE_RENAME_COLUMN,
          SHOW_INDEXES -> reservedObjectName(command.tableName());
      case ALTER_TABLE_RENAME ->
          reservedObjectName(command.tableName())
              || reservedObjectName(command.renamedTableName());
      case CREATE_SEQUENCE, DROP_SEQUENCE -> reservedObjectName(command.sequenceName());
      case CREATE_INDEX, CREATE_UNIQUE_INDEX, DROP_INDEX ->
          reservedObjectName(command.indexName())
              || reservedObjectName(command.tableName());
      case ALTER_INDEX_RENAME ->
          reservedObjectName(command.indexName())
              || reservedObjectName(command.renamedIndexName());
      default -> false;
    };
  }

  private StatusCode parseCreateSequence(CharSequence sql, SqlCommand result) {
    StatusCode status = input.identifier(sql, result.writableSequenceName());
    sequenceStart = 1;
    sequenceIncrement = 1;
    sequenceOptions = 0;
    optionConsumed = true;
    while (status.isOk()) {
      status = parseSequenceOption(sql);
      if (!optionConsumed) {
        break;
      }
    }
    if (status.isOk()) {
      result.setSequenceOptions(sequenceStart, sequenceIncrement);
    }
    return status;
  }

  private StatusCode parseSequenceOption(CharSequence sql) {
    optionConsumed = true;
    if (input.consumeKeyword(sql, "START")) {
      if ((sequenceOptions & 1) != 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = parseSequenceNumber(sql, "WITH", false);
      if (status.isOk()) {
        sequenceStart = number.value;
        sequenceOptions |= 1;
      }
      return status;
    }
    if (input.consumeKeyword(sql, "INCREMENT")) {
      if ((sequenceOptions & 2) != 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = parseSequenceNumber(sql, "BY", true);
      if (status.isOk()) {
        sequenceIncrement = number.value;
        sequenceOptions |= 2;
      }
      return status;
    }
    optionConsumed = false;
    return StatusCode.OK;
  }

  private StatusCode parseSequenceNumber(
      CharSequence sql, String separator, boolean nonzero) {
    StatusCode status = input.requireKeyword(sql, separator);
    if (status.isOk()) {
      status = input.number(sql, number);
    }
    return status.isOk() && nonzero && number.value == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : status;
  }

  private StatusCode parseCreateIndex(CharSequence sql, SqlCommand result) {
    StatusCode status = input.requireKeyword(sql, "INDEX");
    if (status.isOk()) {
      status = input.identifier(sql, result.writableIndexName());
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "ON");
    }
    if (status.isOk()) {
      status = input.identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, '(');
    }
    if (status.isOk()) {
      SqlIdentifier column = result.writableNextColumnName();
      status = column == null
          ? StatusCode.RESOURCE_EXHAUSTED : input.identifier(sql, column);
    }
    return status.isOk() ? input.requireCharacter(sql, ')') : status;
  }

  private StatusCode parseAlterIndex(CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.ALTER_INDEX_RENAME, 0, 0);
    StatusCode status = input.identifier(sql, result.writableIndexName());
    if (status.isOk()) {
      status = input.requireKeyword(sql, "RENAME");
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "TO");
    }
    return status.isOk()
        ? input.identifier(sql, result.writableRenamedIndexName()) : status;
  }

  private StatusCode parseAlterTable(CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.ALTER_TABLE_RENAME, 0, 0);
    StatusCode status = input.requireKeyword(sql, "TABLE");
    if (status.isOk()) {
      status = input.identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "RENAME");
    }
    if (status.isOk() && input.consumeKeyword(sql, "COLUMN")) {
      result.set(SqlCommandType.ALTER_TABLE_RENAME_COLUMN, 0, 0);
      status = input.identifier(sql, result.writableNextColumnName());
      if (status.isOk()) {
        status = input.requireKeyword(sql, "TO");
      }
      return status.isOk()
          ? input.identifier(sql, result.writableNextColumnName()) : status;
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "TO");
    }
    return status.isOk()
        ? input.identifier(sql, result.writableRenamedTableName()) : status;
  }

  private StatusCode parseNamed(
      CharSequence sql,
      SqlCommand result,
      SqlCommandType type,
      SqlIdentifier name) {
    result.set(type, 0, 0);
    return input.identifier(sql, name);
  }

  private static int viewDefinitionEnd(CharSequence sql, int start) {
    int end = sql.length();
    while (end > start && Character.isWhitespace(sql.charAt(end - 1))) {
      end--;
    }
    if (end > start && sql.charAt(end - 1) == ';') {
      end--;
      while (end > start && Character.isWhitespace(sql.charAt(end - 1))) {
        end--;
      }
    }
    return end;
  }

  private static boolean reservedObjectName(CharSequence name) {
    String prefix = "_river_";
    if (name == null || name.length() < prefix.length()) {
      return false;
    }
    for (int index = 0; index < prefix.length(); index++) {
      if (SqlParserInput.upper(name.charAt(index))
          != SqlParserInput.upper(prefix.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean reservedReferenceName(SqlCommand command) {
    for (int column = 1; column < command.columnCount(); column++) {
      if (command.columnHasReference(column)
          && reservedObjectName(command.columnReferenceTableName(column))) {
        return true;
      }
    }
    return false;
  }
}

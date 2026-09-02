package io.riverdb.sql;

/** Allocation-free retained-size preflight for one frozen statement template. */
final class SqlTemplateSizer {
  private SqlTemplateSizer() { }

  static long statement(SqlCommand command, SqlQuery query) {
    long bytes = 128L;
    bytes = addStrings(bytes, command.tableName, command.tableAlias);
    bytes = add(bytes, command.textBytesUsed, Byte.BYTES);
    bytes = SqlTemplateRetainedSize.add(bytes, join(command.joinChain));
    bytes = SqlTemplateRetainedSize.add(bytes, query(command));
    bytes = SqlTemplateRetainedSize.add(bytes, mutation(command));
    bytes = SqlTemplateRetainedSize.add(bytes, queryBlocks(query));
    return bytes;
  }

  private static long query(SqlCommand command) {
    long bytes = 192L;
    for (int column = 0; column < command.columnCount; column++) {
      bytes = addStrings(bytes, command.columnNames[column],
          command.columnTableNames[column], command.columnAliases[column]);
      bytes = SqlTemplateRetainedSize.add(
          bytes, expression(command.projections.expression(column)));
    }
    int symbols = command.projections.symbolCount();
    for (int symbol = 0; symbol < symbols; symbol++) {
      bytes = addStrings(bytes, command.projections.symbolTable(symbol),
          command.projections.symbolName(symbol));
    }
    int orders = command.orderBy.count();
    for (int order = 0; order < orders; order++) {
      bytes = addStrings(bytes, command.orderBy.name(order), command.orderBy.qualifier(order));
    }
    int groups = command.grouping.count();
    for (int group = 0; group < groups; group++) {
      bytes = SqlTemplateRetainedSize.add(
          bytes, expression(command.grouping.expression(group)));
    }
    bytes = addStringArrays(
        bytes, command.columnCount, command.columnCount, command.columnCount);
    bytes = addStringArrays(bytes, symbols, symbols);
    bytes = addStringArrays(bytes, orders, orders);
    bytes = add(bytes, command.columnCount, Byte.BYTES);
    bytes = add(bytes, orders, Byte.BYTES);
    bytes = addIntArrays(bytes, command.aggregates.invocationCount(),
        command.aggregates.invocationCount(), command.aggregates.outputCount());
    bytes = addIntArrays(bytes, groups, groups);
    bytes = add(bytes, command.columnCount, SqlTemplateRetainedSize.REFERENCE_BYTES);
    bytes = add(bytes, groups, SqlTemplateRetainedSize.REFERENCE_BYTES);
    return SqlTemplateRetainedSize.add(
        bytes, predicate(command.wherePredicates), predicate(command.booleanHavingPredicates));
  }

  private static long mutation(SqlCommand command) {
    int programs = command.mutationExpressions.programCount();
    long bytes = SqlTemplateRetainedSize.add(160L, SqlTemplateRetainedSize.array(
        programs, SqlTemplateRetainedSize.REFERENCE_BYTES));
    for (int program = 0; program < programs; program++) {
      bytes = SqlTemplateRetainedSize.add(
          bytes, expression(command.mutationExpressions, program));
    }
    int cells = command.insertRowCount * command.insertColumnCount;
    bytes = addValueArrays(bytes, cells);
    bytes = addValueArrays(bytes, command.updateColumnCount);
    return add(bytes, command.updateColumnCount, Integer.BYTES);
  }

  private static long join(SqlJoinChain joins) {
    int roles = joins == null ? 0 : joins.roleCount();
    int stages = joins == null ? 0 : joins.stageCount();
    long bytes = 64L;
    for (int role = 0; role < roles; role++) {
      bytes = addStrings(bytes, joins.tableName(role), joins.alias(role));
    }
    bytes = addStringArrays(bytes, roles, roles);
    bytes = add(bytes, stages, Integer.BYTES);
    bytes = add(bytes, stages, SqlTemplateRetainedSize.REFERENCE_BYTES);
    for (int stage = 0; stage < stages; stage++) {
      bytes = SqlTemplateRetainedSize.add(bytes, predicate(joins.onPredicates(stage)));
    }
    return bytes;
  }

  private static long queryBlocks(SqlQuery query) {
    int count = query == null ? 0 : query.blockCount();
    long bytes = SqlTemplateRetainedSize.add(48L, SqlTemplateRetainedSize.array(
        count, SqlTemplateRetainedSize.REFERENCE_BYTES));
    for (int block = 0; block < count; block++) {
      bytes = SqlTemplateRetainedSize.add(bytes, statement(query.block(block), null));
    }
    return bytes;
  }

  private static long expression(SqlScalarExpression expression) {
    return expressionArrays(expression.nodeCount());
  }

  private static long expression(SqlMutationExpressions expressions, int program) {
    return expressionArrays(expressions.nodeCount(program));
  }

  private static long expressionArrays(int nodes) {
    long bytes = add(64L, nodes, Byte.BYTES);
    bytes = add(bytes, nodes, Long.BYTES);
    bytes = add(bytes, nodes, Long.BYTES);
    return add(bytes, nodes, Integer.BYTES);
  }

  private static long predicate(SqlBooleanPredicateProgram value) {
    long bytes = 192L;
    bytes = add(bytes, value.scalarNodeCount, Byte.BYTES);
    bytes = add(bytes, value.scalarNodeCount, Long.BYTES);
    bytes = add(bytes, value.scalarNodeCount, Long.BYTES);
    bytes = add(bytes, value.scalarNodeCount, Integer.BYTES);
    bytes = add(bytes, value.leafCount * 4, Integer.BYTES);
    bytes = add(bytes, value.leafCount * 4, Integer.BYTES);
    bytes = add(bytes, value.leafCount, Byte.BYTES);
    bytes = add(bytes, value.leafCount, SqlTemplateRetainedSize.REFERENCE_BYTES);
    bytes = add(bytes, value.leafCount, Byte.BYTES);
    bytes = addIntArrays(bytes, value.leafCount, value.leafCount, value.leafCount);
    bytes = add(bytes, value.memberCount, Long.BYTES);
    bytes = add(bytes, value.memberCount, Long.BYTES);
    bytes = add(bytes, value.memberCount, Integer.BYTES);
    bytes = add(bytes, value.memberCount, Byte.BYTES);
    bytes = add(bytes, value.memberCount, Byte.BYTES);
    bytes = add(bytes, value.booleanNodeCount, Byte.BYTES);
    return addIntArrays(bytes, value.booleanNodeCount,
        value.booleanNodeCount, value.booleanNodeCount);
  }

  private static long addValueArrays(long bytes, int count) {
    bytes = add(bytes, count, Long.BYTES);
    bytes = add(bytes, count, Long.BYTES);
    bytes = add(bytes, count, Integer.BYTES);
    bytes = add(bytes, count, Byte.BYTES);
    return add(bytes, count, Byte.BYTES);
  }

  private static long addStringArrays(long bytes, int first, int second) {
    bytes = add(bytes, first, SqlTemplateRetainedSize.REFERENCE_BYTES);
    return add(bytes, second, SqlTemplateRetainedSize.REFERENCE_BYTES);
  }

  private static long addStringArrays(long bytes, int first, int second, int third) {
    bytes = addStringArrays(bytes, first, second);
    return add(bytes, third, SqlTemplateRetainedSize.REFERENCE_BYTES);
  }

  private static long addIntArrays(long bytes, int first, int second) {
    bytes = add(bytes, first, Integer.BYTES);
    return add(bytes, second, Integer.BYTES);
  }

  private static long addIntArrays(long bytes, int first, int second, int third) {
    bytes = addIntArrays(bytes, first, second);
    return add(bytes, third, Integer.BYTES);
  }

  private static long addStrings(long bytes, CharSequence first, CharSequence second) {
    return SqlTemplateRetainedSize.add(bytes,
        SqlTemplateRetainedSize.string(first), SqlTemplateRetainedSize.string(second));
  }

  private static long addStrings(
      long bytes, CharSequence first, CharSequence second, CharSequence third) {
    bytes = addStrings(bytes, first, second);
    return SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.string(third));
  }

  private static long add(long bytes, int count, int width) {
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(count, width));
  }
}

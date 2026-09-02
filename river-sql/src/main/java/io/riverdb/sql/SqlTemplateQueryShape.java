package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Immutable projection, predicate, grouping, aggregate, and ordering syntax. */
final class SqlTemplateQueryShape {
  private final String[] columns;
  private final String[] columnTables;
  private final String[] columnAliases;
  private final boolean[] nullProjections;
  private final String[] symbolTables;
  private final String[] symbolNames;
  private final SqlTemplateExpression[] projections;
  private final SqlTemplatePredicate predicate;
  private final String[] orderColumns;
  private final String[] orderTables;
  private final boolean[] orderDescending;
  private final int[] aggregateKinds;
  private final int[] aggregateOperands;
  private final int[] aggregateOutputs;
  private final int[] groupProjections;
  private final int[] groupOperandProjections;
  private final SqlTemplateExpression[] groupExpressions;
  private final SqlTemplatePredicate having;

  SqlTemplateQueryShape(SqlCommand source) {
    columns = SqlTemplateStrings.copy(source.columnNames, source.columnCount);
    columnTables = SqlTemplateStrings.copy(source.columnTableNames, source.columnCount);
    columnAliases = SqlTemplateStrings.copy(source.columnAliases, source.columnCount);
    nullProjections = Arrays.copyOf(source.nullProjections, source.columnCount);
    int symbols = source.projections.symbolCount();
    symbolTables = new String[symbols];
    symbolNames = new String[symbols];
    for (int symbol = 0; symbol < symbols; symbol++) {
      symbolTables[symbol] = SqlTemplateStrings.copy(source.projections.symbolTable(symbol));
      symbolNames[symbol] = SqlTemplateStrings.copy(source.projections.symbolName(symbol));
    }
    projections = new SqlTemplateExpression[source.columnCount];
    for (int column = 0; column < projections.length; column++) {
      projections[column] = new SqlTemplateExpression(source.projections.expression(column));
    }
    predicate = new SqlTemplatePredicate(source.wherePredicates);
    int orders = source.orderBy.count();
    orderColumns = new String[orders];
    orderTables = new String[orders];
    orderDescending = new boolean[orders];
    for (int order = 0; order < orders; order++) {
      orderColumns[order] = SqlTemplateStrings.copy(source.orderBy.name(order));
      orderTables[order] = SqlTemplateStrings.copy(source.orderBy.qualifier(order));
      orderDescending[order] = source.orderBy.descending(order);
    }
    int aggregates = source.aggregates.invocationCount();
    aggregateKinds = Arrays.copyOf(source.aggregates.kinds, aggregates);
    aggregateOperands = Arrays.copyOf(source.aggregates.operandProjections, aggregates);
    aggregateOutputs = Arrays.copyOf(
        source.aggregates.outputInvocations, source.aggregates.outputCount());
    int groups = source.grouping.count();
    groupProjections = new int[groups];
    groupOperandProjections = new int[groups];
    groupExpressions = new SqlTemplateExpression[groups];
    for (int group = 0; group < groups; group++) {
      groupProjections[group] = source.grouping.projection(group);
      groupOperandProjections[group] = source.grouping.operandProjection(group);
      groupExpressions[group] = new SqlTemplateExpression(source.grouping.expression(group));
    }
    having = new SqlTemplatePredicate(source.booleanHavingPredicates);
  }

  StatusCode restore(SqlCommand target) {
    for (int symbol = 0; symbol < symbolNames.length; symbol++) {
      if (target.registerProjectionSymbol(symbolTables[symbol], symbolNames[symbol]) != symbol) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    for (int column = 0; column < columns.length; column++) {
      SqlIdentifier name = target.writableNextColumnName();
      if (name == null) return StatusCode.RESOURCE_EXHAUSTED;
      name.copyFrom(columns[column]);
      target.columnTableNames[column].copyFrom(columnTables[column]);
      target.columnAliases[column].copyFrom(columnAliases[column]);
      target.nullProjections[column] = nullProjections[column];
      StatusCode status = projections[column].restore(target.projections.expression(column));
      if (!status.isOk()) return status;
    }
    StatusCode status = predicate.restore(target.wherePredicates);
    if (status.isOk()) status = restoreOrder(target);
    if (status.isOk()) status = restoreAggregates(target);
    if (status.isOk()) status = restoreGroups(target);
    return status.isOk() ? having.restore(target.booleanHavingPredicates) : status;
  }

  int parameterMaximum() {
    int maximum = Math.max(predicate.parameterMaximum(), having.parameterMaximum());
    for (SqlTemplateExpression projection : projections) {
      maximum = Math.max(maximum, projection.parameterMaximum());
    }
    for (SqlTemplateExpression group : groupExpressions) {
      maximum = Math.max(maximum, group.parameterMaximum());
    }
    return maximum;
  }

  long byteCharge() {
    long bytes = 192L;
    bytes = addStrings(bytes, columns, columnTables, columnAliases);
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(nullProjections.length, Byte.BYTES));
    bytes = addStrings(bytes, symbolTables, symbolNames, orderColumns);
    bytes = SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.strings(orderTables));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(orderDescending.length, Byte.BYTES));
    bytes = addIntegers(bytes, aggregateKinds, aggregateOperands, aggregateOutputs);
    bytes = addIntegers(bytes, groupProjections, groupOperandProjections);
    bytes = SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.array(
        projections.length, SqlTemplateRetainedSize.REFERENCE_BYTES));
    bytes = SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.array(
        groupExpressions.length, SqlTemplateRetainedSize.REFERENCE_BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, predicate.byteCharge(), having.byteCharge());
    for (SqlTemplateExpression projection : projections) {
      bytes = SqlTemplateRetainedSize.add(bytes, projection.byteCharge());
    }
    for (SqlTemplateExpression group : groupExpressions) {
      bytes = SqlTemplateRetainedSize.add(bytes, group.byteCharge());
    }
    return bytes;
  }

  private static long addStrings(
      long bytes, String[] first, String[] second, String[] third) {
    bytes = SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.strings(first));
    bytes = SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.strings(second));
    return SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.strings(third));
  }

  private static long addIntegers(
      long bytes, int[] first, int[] second, int[] third) {
    bytes = addIntegers(bytes, first, second);
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(third.length, Integer.BYTES));
  }

  private static long addIntegers(long bytes, int[] first, int[] second) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(first.length, Integer.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(second.length, Integer.BYTES));
  }

  private StatusCode restoreOrder(SqlCommand target) {
    for (int order = 0; order < orderColumns.length; order++) {
      SqlIdentifier name = target.writableNextOrderColumnName();
      if (name == null) return StatusCode.RESOURCE_EXHAUSTED;
      name.copyFrom(orderColumns[order]);
      target.writableOrderColumnTableName(order).copyFrom(orderTables[order]);
      target.setDescendingOrder(order, orderDescending[order]);
    }
    return StatusCode.OK;
  }

  private StatusCode restoreAggregates(SqlCommand target) {
    for (int invocation = 0; invocation < aggregateKinds.length; invocation++) {
      if (target.appendAggregateInvocation(
          aggregateKinds[invocation], aggregateOperands[invocation]) != invocation) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    for (int output : aggregateOutputs) {
      if (!target.appendAggregateOutput(output)) return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StatusCode.OK;
  }

  private StatusCode restoreGroups(SqlCommand target) {
    for (int group = 0; group < groupExpressions.length; group++) {
      StatusCode status = groupExpressions[group].restore(target.scalarExpression);
      if (!status.isOk()) return status;
      status = target.appendGroupExpression(groupProjections[group], target.scalarExpression);
      if (!status.isOk()) return status;
      target.grouping.setOperandProjection(group, groupOperandProjections[group]);
    }
    return StatusCode.OK;
  }
}

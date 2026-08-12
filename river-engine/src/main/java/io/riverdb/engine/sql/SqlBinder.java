package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;

/** Resolves parser-owned names and literals into reusable execution state. */
final class SqlBinder {
  StatusCode captureExecutableQuery(BoundSqlStatement bound) {
    return bound.executableQuery.capture(bound.command, bound.query);
  }

  StatusCode bindDataCommand(
      SqlCommand command,
      SqlQuery query,
      BoundSqlStatement bound,
      boolean correlatedScalar,
      boolean correlatedNestedChain,
      boolean recursiveNestedChain,
      boolean recursiveRootCorrelated) {
    bound.updatedColumnCount = 0;
    bound.predicateColumn = -1;
    bound.predicateCount = 0;
    bound.accessPredicate = -1;
    bound.projectedColumnCount = 0;
    if (command.type() == SqlCommandType.COUNT) {
      return bindPredicates(command, query, bound, false, correlatedScalar,
          correlatedNestedChain, recursiveNestedChain, recursiveRootCorrelated);
    }
    if (command.type() == SqlCommandType.COUNT_VALUE
        || isValueAggregate(command.type())) {
      StatusCode status = bindProjections(command, bound);
      if (status.isOk()
          && command.type() == SqlCommandType.SUM
          && bound.table.typeDescriptor(bound.projectedColumns[0])
              != SqlTypeDescriptor.BIGINT) {
        status = StatusCode.DATATYPE_MISMATCH;
      }
      return status.isOk()
          ? bindPredicates(command, query, bound, false, correlatedScalar,
              correlatedNestedChain, recursiveNestedChain, recursiveRootCorrelated)
          : status;
    }
    if (command.type() == SqlCommandType.INSERT) {
      return bindInsertColumns(command, bound);
    }
    if (command.type() == SqlCommandType.SELECT
        || command.type() == SqlCommandType.SCAN) {
      StatusCode status = bindProjections(command, bound);
      return status.isOk()
          ? bindPredicates(command, query, bound, false, correlatedScalar,
              correlatedNestedChain, recursiveNestedChain, recursiveRootCorrelated)
          : status;
    }
    if (command.type() == SqlCommandType.UPDATE) {
      StatusCode status = bindPredicates(command, query, bound, false,
          correlatedScalar, correlatedNestedChain, recursiveNestedChain,
          recursiveRootCorrelated);
      if (command.updateColumnCount() <= 0
          || command.updateColumnCount() != command.columnCount()
          || !status.isOk()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = 0; index < command.updateColumnCount(); index++) {
        int column = bound.table.findColumn(command.columnName(index));
        if (column <= 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        for (int prior = 0; prior < index; prior++) {
          if (bound.updatedColumns[prior] == column) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
        }
        if (command.updateIsNull(index) && !bound.table.isNullable(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (command.updateIsDefault(index) && !bound.table.hasDefault(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (!command.updateIsNull(index)
            && !command.updateIsDefault(index)
            && !command.isRelativeUpdate(index)
            && !SqlTypeDescriptor.canImplicitlyCast(
                command.updateTypeDescriptor(index),
                bound.table.typeDescriptor(column))) {
          return StatusCode.DATATYPE_MISMATCH;
        }
        bound.updatedColumns[index] = column;
        if (command.isRelativeUpdate(index)) {
          int sourceColumn = bound.table.findColumn(
              command.updateSourceColumnName(index));
          if (sourceColumn < 0
              || bound.table.typeDescriptor(column) != SqlTypeDescriptor.BIGINT
              || bound.table.typeDescriptor(sourceColumn)
                  != SqlTypeDescriptor.BIGINT) {
            return StatusCode.DATATYPE_MISMATCH;
          }
          bound.updateSourceColumns[index] = sourceColumn;
        } else {
          bound.updateSourceColumns[index] = -1;
        }
      }
      bound.updatedColumnCount = command.updateColumnCount();
      return StatusCode.OK;
    }
    if (command.type() == SqlCommandType.DELETE) {
      return bindPredicates(command, query, bound, false, correlatedScalar,
          correlatedNestedChain, recursiveNestedChain, recursiveRootCorrelated);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode bindProjections(SqlCommand command, BoundSqlStatement bound) {
    int count = command.isSelectAll()
        ? bound.table.columnCount() : command.columnCount();
    if (count <= 0 || count > bound.projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      if (!command.isSelectAll()
          && command.columnTableName(index).length() > 0
          && !matchesTableQualifier(command, command.columnTableName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (!command.isSelectAll() && command.isNullProjection(index)) {
        if (command.isOrdered()) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        bound.projectedColumns[index] = BoundSqlStatement.NULL_PROJECTION;
        continue;
      }
      int column = command.isSelectAll()
          ? index : bound.table.findColumn(command.columnName(index));
      if (column < 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int previous = 0; previous < index; previous++) {
        if (bound.projectedColumns[previous] == column) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
      bound.projectedColumns[index] = column;
    }
    bound.projectedColumnCount = count;
    return StatusCode.OK;
  }

  int resolveOrderColumn(SqlCommand command, BoundSqlStatement bound) {
    int column = bound.table.findColumn(command.orderColumnName());
    if (column >= 0) {
      return column;
    }
    int resolved = -1;
    for (int index = 0; index < command.columnCount(); index++) {
      if (sameName(command.columnOutputName(index), command.orderColumnName())) {
        if (resolved >= 0 || command.isNullProjection(index)) {
          return -1;
        }
        resolved = bound.table.findColumn(command.columnName(index));
      }
    }
    return resolved;
  }

  StatusCode bindJoin(SqlCommand command, BoundSqlStatement bound) {
    if (matchesTableQualifier(command, command.joinTableName())
        || command.joinTableAlias().length() > 0
            && matchesTableQualifier(command, command.joinTableAlias())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int outerJoinColumn = bound.table.findColumn(command.joinOuterColumnName());
    int innerJoinColumn = bound.joinTable.findColumn(command.joinInnerColumnName());
    if (outerJoinColumn < 0 || innerJoinColumn < 0 || command.columnCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!SqlTypeDescriptor.canCompare(
        bound.table.typeDescriptor(outerJoinColumn),
        bound.joinTable.typeDescriptor(innerJoinColumn))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    for (int index = 0; index < command.columnCount(); index++) {
      if (command.isNullProjection(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int descriptor;
      if (matchesTableQualifier(command, command.columnTableName(index))) {
        int column = bound.table.findColumn(command.columnName(index));
        if (column < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        descriptor = column;
      } else if (matchesJoinTableQualifier(
          command, command.columnTableName(index))) {
        int column = bound.joinTable.findColumn(command.columnName(index));
        if (column < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        descriptor = -column - 1;
      } else {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      bound.projectedColumns[index] = descriptor;
    }
    bound.projectedColumnCount = command.columnCount();
    return bindJoinPredicates(command, bound);
  }

  private StatusCode bindJoinPredicates(
      SqlCommand command, BoundSqlStatement bound) {
    if (command.hasDisjunction()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.predicateCount = command.predicateCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    int accessScore = -1;
    for (int index = 0; index < bound.predicateCount; index++) {
      boolean outer = matchesTableQualifier(
          command, command.predicateTableName(index));
      boolean inner = matchesJoinTableQualifier(
          command, command.predicateTableName(index));
      TableDefinition definition = outer
          ? bound.table : inner ? bound.joinTable : null;
      int column = definition == null
          ? -1 : definition.findColumn(command.predicateColumnName(index));
      if (column < 0
          || command.isColumnPredicate(index)
          || command.isRangePredicate(index)
              && command.predicateUpperExclusive(index)
                  <= command.predicateLowerInclusive(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (!command.isNullPredicate(index)
          && !(command.isLiteralMembership(index)
              && command.literalMembershipCount(index) == 0)
          && !SqlTypeDescriptor.canCompare(
              definition.typeDescriptor(column),
              command.predicateTypeDescriptor(index))) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      bound.predicateColumns[index] = outer ? column : -column - 1;
      if (!outer || command.isNullPredicate(index)
          || !command.isEqualityPredicate(index)
              && !command.isRangePredicate(index)) {
        continue;
      }
      boolean indexed = column == 0
          || bound.table.hasIndexOn(column) && !bound.table.isVarchar(column);
      int score = !indexed ? 0
          : command.isEqualityPredicate(index)
              ? column == 0 || bound.table.hasUniqueIndexOn(column) ? 3 : 2
              : 1;
      if (score > accessScore) {
        accessScore = score;
        bound.accessPredicate = index;
        bound.predicateColumn = column;
      }
    }
    return StatusCode.OK;
  }

  StatusCode bindPredicates(
      SqlCommand command,
      SqlQuery query,
      BoundSqlStatement bound,
      boolean qualified,
      boolean correlatedScalar,
      boolean correlatedNestedChain,
      boolean recursiveNestedChain,
      boolean recursiveRootCorrelated) {
    bound.predicateCount = command.predicateCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    int accessScore = -1;
    for (int index = 0; index < bound.predicateCount; index++) {
      if ((qualified || command.predicateTableName(index).length() > 0)
          && !matchesTableQualifier(command, command.predicateTableName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int column = bound.table.findColumn(command.predicateColumnName(index));
      if (column < 0
          || command.isColumnPredicate(index)
          || command.isRangePredicate(index)
              && command.predicateUpperExclusive(index)
                  <= command.predicateLowerInclusive(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (!command.isNullPredicate(index)
          && !(command.isLiteralMembership(index)
              && command.literalMembershipCount(index) == 0)
          && !(query.hasMembershipPredicate()
              && query.membershipPredicate() == index)
          && !(query.hasScalarPredicate()
              && query.scalarPredicate() == index)
          && !SqlTypeDescriptor.canCompare(
              bound.table.typeDescriptor(column),
              command.predicateTypeDescriptor(index))) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      bound.predicateColumns[index] = column;
      if (command.hasDisjunction()
          || query.hasMembershipPredicate()
              && query.membershipPredicate() == index
          || query.hasScalarPredicate()
              && query.scalarPredicate() == index
          || command.isNullPredicate(index)
          || !command.isEqualityPredicate(index)
              && !command.isRangePredicate(index)) {
        continue;
      }
      boolean indexed = column == 0
          || bound.table.hasIndexOn(column) && !bound.table.isVarchar(column);
      int score = !indexed ? 0
          : command.isEqualityPredicate(index)
              ? column == 0 || bound.table.hasUniqueIndexOn(column) ? 3 : 2
              : 1;
      if (score > accessScore) {
        accessScore = score;
        bound.accessPredicate = index;
        bound.predicateColumn = column;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode bindInsertColumns(
      SqlCommand command, BoundSqlStatement bound) {
    for (int index = 0; index < bound.insertSourceByColumn.length; index++) {
      bound.insertSourceByColumn[index] = -1;
    }
    if (command.columnCount() == 0) {
      if (command.insertColumnCount() != bound.table.columnCount()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = 0; index < bound.table.columnCount(); index++) {
        bound.insertSourceByColumn[index] = index;
      }
    } else {
      if (command.insertColumnCount() != command.columnCount()
          || command.columnCount() > bound.table.columnCount()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int source = 0; source < command.columnCount(); source++) {
        int column = bound.table.findColumn(command.columnName(source));
        if (column < 0 || bound.insertSourceByColumn[column] >= 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        bound.insertSourceByColumn[column] = source;
      }
    }
    for (int rowIndex = 0; rowIndex < command.insertRowCount(); rowIndex++) {
      int keySource = bound.insertSourceByColumn[0];
      if (bound.table.hasIdentity()) {
        if (keySource >= 0 && !command.insertIsDefault(rowIndex, keySource)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      } else {
        if (keySource < 0
            || command.insertIsNull(rowIndex, keySource)
            || command.insertIsDefault(rowIndex, keySource)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (!SqlTypeDescriptor.canImplicitlyCast(
            command.insertTypeDescriptor(rowIndex, keySource),
            bound.table.typeDescriptor(0))) {
          return StatusCode.DATATYPE_MISMATCH;
        }
      }
      for (int column = 1; column < bound.table.columnCount(); column++) {
        int source = bound.insertSourceByColumn[column];
        if (source >= 0
            && command.insertIsDefault(rowIndex, source)
            && !bound.table.hasDefault(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (source >= 0
            && !command.insertIsNull(rowIndex, source)
            && !command.insertIsDefault(rowIndex, source)
            && !SqlTypeDescriptor.canImplicitlyCast(
                command.insertTypeDescriptor(rowIndex, source),
                bound.table.typeDescriptor(column))) {
          return StatusCode.DATATYPE_MISMATCH;
        }
        boolean nullValue = source < 0
            ? !bound.table.hasDefault(column)
            : command.insertIsNull(rowIndex, source);
        if (nullValue && !bound.table.isNullable(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
    }
    return StatusCode.OK;
  }

  static boolean isValueAggregate(SqlCommandType type) {
    return type == SqlCommandType.SUM
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX;
  }

  static boolean isScalarAggregate(SqlCommandType type) {
    return type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || isValueAggregate(type);
  }

  static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  static boolean matchesTableQualifier(
      SqlCommand command, CharSequence qualifier) {
    return sameName(qualifier, command.tableName())
        || command.tableAlias().length() > 0
            && sameName(qualifier, command.tableAlias());
  }

  static boolean matchesJoinTableQualifier(
      SqlCommand command, CharSequence qualifier) {
    return sameName(qualifier, command.joinTableName())
        || command.joinTableAlias().length() > 0
            && sameName(qualifier, command.joinTableAlias());
  }

  static boolean sameName(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }
}

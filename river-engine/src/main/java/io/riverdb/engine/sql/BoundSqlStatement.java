package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Reusable catalog-resolved state borrowed by one statement execution. */
final class BoundSqlStatement {
  static final int NULL_PROJECTION = Integer.MIN_VALUE;

  final SqlCommand command = new SqlCommand();
  final SqlQuery query = new SqlQuery();
  final BoundSqlQuery executableQuery = new BoundSqlQuery();
  final TableDefinition table = new TableDefinition();
  final TableDefinition joinTable = new TableDefinition();
  final TableDefinition scalarTable = new TableDefinition();
  final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updateSourceColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] predicateColumns = new int[SqlCommand.MAXIMUM_PREDICATES];
  final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];

  int predicateColumn;
  int predicateCount;
  int accessPredicate;
  int updatedColumnCount;
  int projectedColumnCount;

  void reset() {
    command.reset();
    query.reset();
    executableQuery.reset();
    table.reset();
    joinTable.reset();
    scalarTable.reset();
    predicateColumn = -1;
    predicateCount = 0;
    accessPredicate = -1;
    updatedColumnCount = 0;
    projectedColumnCount = 0;
  }
}

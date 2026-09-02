package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlQuery;

/** Spill-aware full-row DISTINCT boundary for one UNION node. */
final class SqlUnionDistinctExecution {
  private final int[] columns;
  private final boolean[] descending;
  private final SqlBlockRow candidate = new SqlBlockRow();
  private final SqlBlockRow retained = new SqlBlockRow();
  private final SqlBlockRowStore[] stores;
  private final SqlUnionNodeExecution nodes;
  private SqlBlockSchema schema;

  SqlUnionDistinctExecution(
      SqlUnionNodeExecution owner,
      int maximumColumns,
      int depth,
      SqlSessionShapeBudget budget) {
    nodes = owner;
    columns = new int[maximumColumns];
    descending = new boolean[maximumColumns];
    stores = new SqlBlockRowStore[depth];
    for (int index = 0; index < stores.length; index++) {
      stores[index] = new SqlBlockRowStore(budget);
    }
  }

  void prepare(SqlBlockSchema outputSchema) {
    schema = outputSchema;
    for (int column = 0; column < schema.count(); column++) columns[column] = column;
  }

  StatusCode append(
      SqlQuery query, int node, SqlBlockRowStore output, int depth) {
    if (depth < 0 || depth >= stores.length) return StatusCode.QUERY_TOO_COMPLEX;
    SqlBlockRowStore sorted = stores[depth];
    StatusCode status = sorted.begin(schema, columns, descending, schema.count());
    if (status.isOk()) status = nodes.append(query, query.setLeftNode(node), sorted, depth + 1);
    if (status.isOk()) status = nodes.append(query, query.setRightNode(node), sorted, depth + 1);
    if (status.isOk()) status = sorted.finish();
    boolean available = false;
    while (status.isOk()) {
      status = sorted.next(candidate);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (!status.isOk()) break;
      if (!available || !SqlUnionRowEquality.same(candidate, retained, schema)) {
        status = SqlBlockDistinctPublisher.append(candidate, retained, output);
        available = status.isOk();
      }
    }
    StatusCode closed = sorted.close();
    return status.isOk() ? closed : status;
  }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    for (SqlBlockRowStore store : stores) {
      StatusCode closed = store.close();
      if (status.isOk()) status = closed;
    }
    return status;
  }
}

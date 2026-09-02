package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Presents one joined block and its visible ancestors as packed lexical rows. */
final class SqlJoinedRowProvider implements SqlNestedRowProvider {
  private final int block;
  private final SqlNestedRowProvider ancestors;
  private SqlJoinRoleRows local;

  SqlJoinedRowProvider(int queryBlock, SqlNestedRowProvider ancestorRows) {
    block = queryBlock;
    ancestors = ancestorRows;
  }

  void activate(SqlJoinRoleRows rows) { local = rows; }
  void clear() { local = null; }

  @Override
  public long key(int queryBlock, int role) {
    return queryBlock == block
        ? local == null ? 0 : local.key(role)
        : ancestors == null ? 0 : ancestors.key(queryBlock, role);
  }

  @Override
  public HeapRowResult row(int queryBlock, int role) {
    return queryBlock == block
        ? local == null ? null : local.row(role)
        : ancestors == null ? null : ancestors.row(queryBlock, role);
  }

  @Override
  public TableDefinition table(int queryBlock, int role) {
    return queryBlock == block
        ? local == null ? null : local.table(role)
        : ancestors == null ? null : ancestors.table(queryBlock, role);
  }

  @Override
  public SqlBlockRow blockRow(int queryBlock, int role) {
    return queryBlock == block
        ? null
        : ancestors == null ? null : ancestors.blockRow(queryBlock, role);
  }
}

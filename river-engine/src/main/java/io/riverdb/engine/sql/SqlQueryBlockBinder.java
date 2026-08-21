package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.sql.SqlQuery;

/** Resolves every physical block and canonical program in a subquery graph. */
final class SqlQueryBlockBinder {
  private final SqlBooleanPredicateBinder booleans = new SqlBooleanPredicateBinder();
  private final SqlNestedProjectionBinder projections = new SqlNestedProjectionBinder();

  StatusCode bind(RelationalSession session, BoundSqlStatement bound) {
    BoundSqlQuery query = bound.executableQuery;
    int root = query.sourceBlockCount() - 1;
    query.beginBinding(bound.table, root);
    if (query.edgeCount() == 0) return StatusCode.OK;
    StatusCode status = bindRootRoles(bound, root);
    if (!status.isOk()) return status;
    for (int block = root + 1; block < query.blockCount(); block++) {
      status = resolve(session, bound, block);
      if (!status.isOk()) return status;
    }
    for (int block = root; block < query.blockCount(); block++) {
      SqlCommand command = bound.query.block(block);
      status = booleans.bindNested(command, bound, query, block);
      if (!status.isOk()) return status;
    }
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      status = validateEdge(bound, edge);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode resolve(
      RelationalSession session, BoundSqlStatement bound, int blockIndex) {
    BoundSqlQuery.Block block = bound.executableQuery.block(blockIndex);
    if (block == null || block.isOrdered() || block.columnCount() != 1
        || block.isSelectAll()) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int role = 0; role < block.roleCount(); role++) {
      TableDefinition table = block.writableTable(role);
      StatusCode status = session.resolveTable(block.roleTableName(role), table);
      if (!status.isOk()) return status;
    }
    return projections.bind(
        bound.query.block(blockIndex),
        bound.executableQuery,
        blockIndex,
        bound.nestedProjection(blockIndex),
        block);
  }

  private static StatusCode bindRootRoles(BoundSqlStatement bound, int root) {
    BoundSqlQuery.Block block = bound.executableQuery.block(root);
    SqlJoinChain joins = block == null ? null : block.joinChain();
    if (joins == null) return StatusCode.OK;
    SqlBoundJoinContext context = bound.existingJoinContext(root);
    if (context == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int role = 0; role < joins.roleCount(); role++) {
      TableDefinition table = context.table(role);
      if (table == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      block.bindRoleTable(role, table);
    }
    return StatusCode.OK;
  }

  private static StatusCode validateEdge(BoundSqlStatement bound, int edge) {
    BoundSqlQuery query = bound.executableQuery;
    int kind = query.edgeKind(edge);
    if (kind == SqlQuery.SUBQUERY_EXISTS) return StatusCode.OK;
    int parent = query.edgeParent(edge);
    int child = query.edgeChild(edge);
    int leaf = query.edgeLeaf(edge);
    SqlBoundBooleanPredicateProgram program = bound.nestedBoolean(parent);
    int left = program.resultDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    int right = query.block(child).projectionType();
    boolean leftUnresolved = program.unresolved(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    if (leftUnresolved && right == 0) return StatusCode.DATATYPE_MISMATCH;
    if (leftUnresolved) {
      left = right;
      program.resolveDescriptor(
          leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, left);
    } else if (right == 0) {
      right = left;
      bound.nestedProjection(child).resolveNullProjection(0, right);
      query.block(child).setProjection(
          SqlBoundProjectionPrograms.COMPUTED_PROJECTION, right);
    }
    if (!SqlTypeDescriptor.canCompare(left, right)) return StatusCode.DATATYPE_MISMATCH;
    SqlComparison comparison = query.edgeComparison(edge);
    return kind == SqlQuery.SUBQUERY_SCALAR
            && SqlTypeDescriptor.typeId(left) == SqlTypeDescriptor.TYPE_ID_BOOLEAN
            && comparison != SqlComparison.EQUAL && comparison != SqlComparison.NOT_EQUAL
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.OK;
  }
}

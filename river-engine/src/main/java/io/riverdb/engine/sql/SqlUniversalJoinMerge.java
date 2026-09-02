package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;

/** Sorted universal-role merge input with retained rows and identity sidecar. */
final class SqlUniversalJoinMerge {
  private final SqlBlockRowStore store;
  private final SqlUniversalJoinIdentities identities;
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlUniversalJoinMergeProbe probe = new SqlUniversalJoinMergeProbe();
  private SqlBoundJoinContext context;
  private TableDefinition inner;
  private int stage = -1;
  private int innerColumn = -1;
  private int outerRole = -1;
  private int outerColumn = -1;

  SqlUniversalJoinMerge(SqlSessionShapeBudget budget) {
    store = new SqlBlockRowStore(budget);
    identities = new SqlUniversalJoinIdentities(budget);
  }

  StatusCode begin(
      SqlCommand command, SqlBoundJoinContext joinContext,
      SqlUniversalJoinRows source) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    int selected = joinContext.physicalStrategyStage();
    if (selected < 0 || joinContext.strategy(selected) != SqlJoinStrategy.MERGE) {
      return StatusCode.OK;
    }
    context = joinContext;
    stage = selected;
    innerColumn = context.strategyInnerColumn(stage);
    outerRole = context.strategyOuterRole(stage);
    outerColumn = context.strategyOuterColumn(stage);
    inner = context.table(stage + 1);
    status = prepare();
    if (status.isOk()) status = store.begin(schema, innerColumn, false);
    if (status.isOk()) status = identities.begin();
    if (status.isOk()) status = source.openFullScan(stage + 1);
    while (status.isOk()) {
      status = source.next(stage + 1);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = store.append(source.row(stage + 1));
      if (status.isOk()) status = identities.append(source.key(stage + 1));
    }
    StatusCode runtime = status;
    StatusCode closed = source.closeScan(stage + 1);
    if (runtime.isOk()) runtime = closed;
    if (runtime.isOk()) runtime = store.finish();
    if (runtime.isOk()) runtime = identities.finish();
    return runtime.isOk() ? runtime : failBegin(runtime);
  }

  StatusCode beginProbe(SqlUniversalJoinRows source) {
    return probe.begin(source, store);
  }

  StatusCode nextCandidate(SqlUniversalJoinRows target) {
    return probe.next(stage, store, identities, target);
  }

  boolean handles(int current) { return stage == current; }

  StatusCode close() {
    StatusCode status = store.close();
    StatusCode identityStatus = identities.close();
    schema.reset();
    probe.reset();
    context = null;
    inner = null;
    stage = -1;
    innerColumn = -1;
    outerRole = -1;
    outerColumn = -1;
    return status.isOk() ? identityStatus : status;
  }

  private StatusCode prepare() {
    schema.set(inner.columnCount());
    StatusCode status = schema.status();
    if (status.isOk()) {
      status = probe.prepare(context, inner, innerColumn, outerRole, outerColumn);
    }
    for (int column = 0; status.isOk() && column < inner.columnCount(); column++) {
      schema.setColumn(column, inner.columnName(column),
          inner.typeDescriptor(column), inner.isNullable(column));
      status = schema.status();
    }
    return status;
  }

  private StatusCode failBegin(StatusCode failure) {
    StatusCode closed = close();
    return failure.isOk() ? closed : failure;
  }
}

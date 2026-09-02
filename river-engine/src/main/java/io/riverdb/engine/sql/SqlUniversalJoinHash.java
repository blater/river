package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;

/** Universal-role adapter over the shared bounded hash row store and index. */
final class SqlUniversalJoinHash {
  private final SqlJoinHashWorkspace core;
  private final SqlUniversalJoinIdentities identities;
  private SqlBoundJoinContext context;

  SqlUniversalJoinHash(RelationalSession session, SqlSessionShapeBudget budget) {
    core = new SqlJoinHashWorkspace(session, budget);
    identities = new SqlUniversalJoinIdentities(budget);
  }

  StatusCode begin(
      SqlCommand command, SqlBoundJoinContext joinContext,
      SqlUniversalJoinRows source) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    context = joinContext;
    int stage = SqlJoinHashAdmission.selectedStage(command, context);
    if (stage < 0) return StatusCode.OK;
    int innerRole = stage + 1;
    TableDefinition inner = context.table(innerRole);
    status = core.prepareDecodedBuild(
        inner, context.table(context.strategyOuterRole(stage)), stage,
        context.strategyInnerColumn(stage), context.strategyOuterColumn(stage));
    if (status.isOk()) status = identities.begin();
    if (status.isOk()) status = source.openFullScan(innerRole);
    while (status.isOk()) {
      status = source.next(innerRole);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = core.store.append(source.row(innerRole));
      if (status.isOk()) status = identities.append(source.key(innerRole));
    }
    StatusCode runtime = status;
    StatusCode closed = source.closeScan(innerRole);
    if (runtime.isOk()) runtime = closed;
    if (runtime.isOk()) runtime = core.finishDecodedBuild();
    if (runtime.isOk()) runtime = identities.finish();
    return runtime.isOk() ? runtime : failBegin(runtime);
  }

  StatusCode beginProbe(SqlUniversalJoinRows source) {
    int stage = core.stage();
    int outerRole = context.strategyOuterRole(stage);
    int outerColumn = context.strategyOuterColumn(stage);
    TableDefinition outer = context.table(outerRole);
    return core.beginProbe(
        source.row(outerRole), outerColumn, outer.typeDescriptor(outerColumn));
  }

  StatusCode nextCandidate(SqlUniversalJoinRows target) {
    target.clearCandidate(core.stage() + 1);
    StatusCode status = core.nextDecodedCandidate();
    if (!status.isOk()) return status;
    long position = core.candidatePosition();
    SqlBlockRow row = core.decodedCandidate();
    status = identities.read(position);
    if (!status.isOk()) return status;
    target.borrowCandidate(
        core.stage() + 1, row, identities.identity(), row.key());
    return StatusCode.OK;
  }

  int stage() { return core.stage(); }
  boolean handles(int stage) { return core.active && core.stage() == stage; }
  boolean fallback(int stage) { return handles(stage) && core.fallback(); }

  StatusCode close() {
    StatusCode status = core.close();
    StatusCode identityStatus = identities.close();
    context = null;
    return status.isOk() ? identityStatus : status;
  }

  private StatusCode failBegin(StatusCode failure) {
    StatusCode closed = close();
    return failure.isOk() ? closed : failure;
  }
}

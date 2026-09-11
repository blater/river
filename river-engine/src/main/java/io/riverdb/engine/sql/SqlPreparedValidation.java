package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlParser;
import io.riverdb.tx.api.IsolationLevel;

/** Performs prepared SQL validation under one statement admission frame. */
final class SqlPreparedValidation {
  private final RelationalSession session;
  private final SqlSessionStatementPreparation preparation;
  private final SqlParser parser;
  private final BoundSqlStatement bound;
  private final SqlBindingTableResolver bindingTables;
  private final SqlAtomicStatementLifecycle atomic;

  SqlPreparedValidation(
      RelationalSession relationalSession,
      SqlSessionStatementPreparation statementPreparation,
      SqlParser sqlParser,
      BoundSqlStatement boundStatement,
      SqlBindingTableResolver tableResolver,
      SqlAtomicStatementLifecycle atomicLifecycle) {
    session = relationalSession;
    preparation = statementPreparation;
    parser = sqlParser;
    bound = boundStatement;
    bindingTables = tableResolver;
    atomic = atomicLifecycle;
  }

  StatusCode validatePrepared(
      String sql, SqlPreparedPlan candidate, SqlRetainedBudget budget,
      SqlPreparedValidationResult result) {
    StatusCode status = StatusCode.OK;
    boolean reuse = candidate != null
        && session.matchesPreparedGeneration(candidate.preparationGeneration());
    boolean parsed = !reuse;
    status = prepareCandidate(sql, candidate, reuse);
    boolean began = false;
    if (status.isOk()) {
      status = atomic.begin(IsolationLevel.READ_COMMITTED);
      began = status.isOk();
    }
    if (status.isOk() && reuse
        && !session.matchesPreparedGeneration(candidate.preparationGeneration())) {
      reuse = false;
      parsed = true;
      bound.reset();
      status = parser.parseTemplate(sql, bound.query, bound.command);
    }
    if (status.isOk()) status = resolveTable(reuse);
    long generation = status.isOk() ? session.catalogGeneration() : 0;
    long preparationGeneration = session.matchesPreparedGeneration(generation) ? generation : 0;
    if (began) status = atomic.finish(status);
    if (status.isOk() && reuse) result.reuse(candidate);
    else if (status.isOk()) {
      status = preparation.capturePrepared(
          budget, result, generation, preparationGeneration);
    }
    if (parsed) bound.reset();
    return status;
  }

  private StatusCode prepareCandidate(
      String sql, SqlPreparedPlan candidate, boolean reuse) {
    if (!reuse) {
      bound.reset();
      StatusCode status = parser.parseTemplate(sql, bound.query, bound.command);
      if (!status.isOk()) return status;
    }
    return preparation.authorize(reuse ? candidate.template().type() : bound.command.type());
  }

  private StatusCode resolveTable(boolean reuse) {
    if (reuse || bound.command.tableName().length() == 0) return StatusCode.OK;
    return bindingTables.resolve(session, bound.command.tableName(), bound.table);
  }
}

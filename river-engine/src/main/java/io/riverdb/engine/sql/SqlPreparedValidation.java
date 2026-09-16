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
    boolean reuse = candidate != null
        && session.matchesPreparedGeneration(candidate.preparationGeneration());
    boolean parsed = !reuse;
    StatusCode status = prepareCandidate(sql, candidate, reuse);
    status = beginAdmission(status);
    boolean began = status.isOk();
    if (status.isOk() && reuse
        && !session.matchesPreparedGeneration(candidate.preparationGeneration())) {
      reuse = false;
      parsed = true;
      status = reparse(sql);
    }
    if (status.isOk()) status = resolveTable(reuse);
    long generation = catalogGeneration(status);
    long preparationGeneration = preparedGeneration(generation);
    if (began) status = atomic.finish(status);
    status = publish(status, reuse, candidate, budget, result,
        generation, preparationGeneration);
    if (parsed) bound.reset();
    return status;
  }

  private StatusCode beginAdmission(StatusCode status) {
    return status.isOk() ? atomic.begin(IsolationLevel.READ_COMMITTED) : status;
  }

  private StatusCode reparse(String sql) {
    bound.reset();
    return parser.parseTemplate(sql, bound.query, bound.command);
  }

  private long catalogGeneration(StatusCode status) {
    return status.isOk() ? session.catalogGeneration() : 0;
  }

  private long preparedGeneration(long generation) {
    return session.matchesPreparedGeneration(generation) ? generation : 0;
  }

  private StatusCode publish(
      StatusCode status, boolean reuse, SqlPreparedPlan candidate,
      SqlRetainedBudget budget, SqlPreparedValidationResult result,
      long generation, long preparationGeneration) {
    if (!status.isOk()) return status;
    if (reuse) {
      result.reuse(candidate);
      return StatusCode.OK;
    }
    return preparation.capturePrepared(
        budget, result, generation, preparationGeneration);
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

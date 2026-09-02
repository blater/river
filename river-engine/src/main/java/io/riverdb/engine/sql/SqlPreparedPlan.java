package io.riverdb.engine.sql;

import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.sql.SqlStatementTemplate;

/** Frozen statement syntax plus an atomically replaceable catalog dependency. */
public final class SqlPreparedPlan {
  static final long ACCOUNTED_BYTES = 96L;
  private final SqlStatementTemplate template;
  private final boolean query;
  private volatile long catalogGeneration;

  SqlPreparedPlan(
      SqlStatementTemplate statementTemplate,
      boolean queryStatement,
      long catalogGeneration) {
    template = statementTemplate;
    query = queryStatement;
    this.catalogGeneration = catalogGeneration;
  }

  public SqlStatementTemplate template() { return template; }

  public int parameterCount() { return template.parameterCount(); }

  public boolean query() { return query; }

  /** Catalog publication at which this plan was validated. */
  public long catalogGeneration() { return catalogGeneration; }

  /** Whether this relational plan and result policy are safe inside one program transaction. */
  public boolean acceptsProgramAction(int action) {
    if (!programSafe()) return false;
    return query
        ? action == TransactionProgramAction.EXACT_ONE
            || action == TransactionProgramAction.ZERO_OR_ONE
            || action == TransactionProgramAction.ROW_SET
        : action == TransactionProgramAction.COMMAND;
  }

  public long byteCharge() { return charge(template.byteCharge()); }

  static long estimateByteCharge(
      io.riverdb.sql.SqlCommand command, io.riverdb.sql.SqlQuery query) {
    return charge(SqlStatementTemplate.estimateByteCharge(command, query));
  }

  boolean needsRecompile(RelationalSession session) {
    return !session.matchesCatalogGeneration(catalogGeneration);
  }

  boolean publishRecompile(long catalogGeneration) {
    if (catalogGeneration <= 0) return false;
    this.catalogGeneration = catalogGeneration;
    return true;
  }

  private static long charge(long templateBytes) {
    return templateBytes <= 0 || templateBytes > Long.MAX_VALUE - ACCOUNTED_BYTES
        ? Long.MAX_VALUE : ACCOUNTED_BYTES + templateBytes;
  }

  private boolean programSafe() {
    return switch (template.type()) {
      case INSERT, UPDATE, DELETE, SELECT, SCAN, DISTINCT_SCAN, JOIN_SCAN,
          COUNT, COUNT_VALUE, COUNT_DISTINCT, SUM, AVG, MIN, MAX,
          GROUP_COUNT, GROUP_COUNT_VALUE, GROUP_COUNT_DISTINCT,
          GROUP_SUM, GROUP_AVG, GROUP_MIN, GROUP_MAX -> true;
      default -> false;
    };
  }
}

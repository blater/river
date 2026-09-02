package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Immutable actual-count JOIN roles, stages, and ON predicates. */
final class SqlTemplateJoinShape {
  private final String[] tables;
  private final String[] aliases;
  private final int[] kinds;
  private final SqlTemplatePredicate[] predicates;

  SqlTemplateJoinShape(SqlJoinChain source) {
    int roles = source == null ? 0 : source.roleCount();
    int stages = source == null ? 0 : source.stageCount();
    tables = new String[roles];
    aliases = new String[roles];
    kinds = new int[stages];
    predicates = new SqlTemplatePredicate[stages];
    for (int role = 0; role < roles; role++) {
      tables[role] = SqlTemplateStrings.copy(source.tableName(role));
      aliases[role] = SqlTemplateStrings.copy(source.alias(role));
    }
    for (int stage = 0; stage < stages; stage++) {
      kinds[stage] = source.joinKind(stage);
      predicates[stage] = new SqlTemplatePredicate(source.onPredicates(stage));
    }
  }

  StatusCode restore(SqlCommand target) {
    if (tables.length == 0) return StatusCode.OK;
    StatusCode status = target.ensureJoinChain();
    if (!status.isOk()) return status;
    SqlJoinChain chain = target.writableJoinChain();
    chain.begin(tables[0], aliases[0]);
    for (int stage = 0; stage < kinds.length; stage++) {
      int restored = chain.appendStage(kinds[stage] == SqlJoinChain.LEFT);
      if (restored != stage) return StatusCode.RESOURCE_EXHAUSTED;
      int role = chain.rightRole(stage);
      chain.writableTableName(role).copyFrom(tables[role]);
      chain.writableAlias(role).copyFrom(aliases[role]);
      status = predicates[stage].restore(chain.writableOnPredicates(stage));
      if (!status.isOk()) return status;
      status = chain.validateStage(stage);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  int parameterMaximum() {
    int maximum = -1;
    for (SqlTemplatePredicate predicate : predicates) {
      maximum = Math.max(maximum, predicate.parameterMaximum());
    }
    return maximum;
  }

  long byteCharge() {
    long bytes = SqlTemplateRetainedSize.add(
        64L, SqlTemplateRetainedSize.strings(tables),
        SqlTemplateRetainedSize.strings(aliases));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(kinds.length, Integer.BYTES));
    bytes = SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.array(
        predicates.length, SqlTemplateRetainedSize.REFERENCE_BYTES));
    for (SqlTemplatePredicate predicate : predicates) {
      bytes = SqlTemplateRetainedSize.add(bytes, predicate.byteCharge());
    }
    return bytes;
  }
}

package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Recognizes COUNT argument syntax without owning invocation publication. */
final class SqlAggregateInvocationKind {
  private static final String COUNT_OUTPUT = "count";

  private SqlAggregateInvocationKind() { }

  static int consume(
      SqlParserInput input, CharSequence sql, int requestedKind) {
    if (requestedKind != SqlAggregateKind.COUNT) return requestedKind;
    if (input.consumeCharacter(sql, '*')) return SqlAggregateKind.COUNT;
    return input.consumeKeyword(sql, "DISTINCT")
        ? SqlAggregateKind.COUNT_DISTINCT : SqlAggregateKind.COUNT_VALUE;
  }

  static StatusCode appendCountOutput(SqlCommand command) {
    SqlIdentifier output = command.writableNextColumnName();
    if (output == null) return StatusCode.RESOURCE_EXHAUSTED;
    for (int index = 0; index < COUNT_OUTPUT.length(); index++) {
      output.append(COUNT_OUTPUT.charAt(index));
    }
    return StatusCode.OK;
  }
}

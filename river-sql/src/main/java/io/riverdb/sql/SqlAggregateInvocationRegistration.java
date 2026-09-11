package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Publishes one fully parsed aggregate invocation and its output slot. */
final class SqlAggregateInvocationRegistration {
  private SqlAggregateInvocationRegistration() { }

  static StatusCode append(
      SqlCommand command, int kind, int inputColumn,
      boolean grouped, boolean first) {
    int invocation = command.aggregates.appendInvocation(kind, inputColumn);
    if (invocation < 0 || !command.aggregates.appendOutput(invocation)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (first) command.set(SqlAggregateCommandType.route(kind, grouped), 0, 0);
    return StatusCode.OK;
  }
}

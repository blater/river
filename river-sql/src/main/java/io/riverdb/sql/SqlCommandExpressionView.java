package io.riverdb.sql;

/** Mutation-expression and aggregate accessors for a parsed command. */
final class SqlCommandExpressionView {
  private SqlCommandExpressionView() { }

  static int mutationCount(SqlCommand command) {
    return command.mutationExpressions.programCount();
  }
  static int mutationNodes(SqlCommand command, int expression) {
    return command.mutationExpressions.nodeCount(expression);
  }
  static int mutationOperator(SqlCommand command, int expression, int node) {
    return command.mutationExpressions.operator(expression, node);
  }
  static long mutationOperand(SqlCommand command, int expression, int node) {
    return command.mutationExpressions.operand(expression, node);
  }
  static int mutationDescriptor(SqlCommand command, int expression, int node) {
    return command.mutationExpressions.descriptor(expression, node);
  }
  static int aggregateCount(SqlCommand command) {
    return command.aggregates.invocationCount();
  }
  static int aggregateOutputs(SqlCommand command) {
    return command.aggregates.outputCount();
  }
  static int aggregateKind(SqlCommand command, int invocation) {
    return invocation >= 0 && invocation < aggregateCount(command)
        ? command.aggregates.kind(invocation) : 0;
  }
  static int aggregateOperand(SqlCommand command, int invocation) {
    return invocation >= 0 && invocation < aggregateCount(command)
        ? command.aggregates.operandProjection(invocation) : -1;
  }
  static int aggregateOutput(SqlCommand command, int output) {
    return output >= 0 && output < aggregateOutputs(command)
        ? command.aggregates.outputInvocation(output) : -1;
  }
}

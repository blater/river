package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Statement-owned resolved postfix programs retained at session high water. */
final class SqlBoundProjectionPrograms {
  static final int COMPUTED_PROJECTION = Integer.MIN_VALUE + 1;

  private final SqlProjectionProgramStorage projections;
  private final SqlMutationProgramStorage mutations;

  SqlBoundProjectionPrograms() {
    this(new SqlSessionShapeBudget(null));
  }

  SqlBoundProjectionPrograms(SqlSessionShapeBudget budget) {
    projections = new SqlProjectionProgramStorage(budget);
    mutations = new SqlMutationProgramStorage(budget);
  }

  StatusCode reserve(int programs) { return projections.reserve(programs); }
  StatusCode reserveMutations(int programs) { return mutations.reserve(programs); }

  void reset() {
    projections.reset();
    mutations.reset();
  }

  void begin(int count) { projections.begin(count); }
  void beginMutations(int count) { mutations.begin(count); }
  void beginMutation(int program) { mutations.beginProgram(program); }
  void appendMutation(int program, int operator, long operand, int descriptor) {
    mutations.append(program, operator, operand, descriptor);
  }
  void appendMutation(
      int program, int operator, long operandHigh, long operand, int descriptor) {
    mutations.append(program, operator, operandHigh, operand, descriptor);
  }
  void finishMutation(int program, int descriptor) { mutations.finish(program, descriptor); }
  int mutationCount() { return mutations.count(); }
  int mutationNodeCount(int program) { return mutations.nodeCount(program); }
  int mutationOperator(int program, int node) { return mutations.operator(program, node); }
  long mutationOperand(int program, int node) { return mutations.operand(program, node); }
  long mutationOperandHigh(int program, int node) {
    return mutations.operandHigh(program, node);
  }
  int mutationDescriptor(int program, int node) { return mutations.descriptor(program, node); }
  int mutationResultDescriptor(int program) { return mutations.resultDescriptor(program); }

  void append(int projection, int operator, long operand, int descriptor) {
    projections.append(
        projection, operator, operand, descriptor,
        SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
  }
  void append(
      int projection, int operator,
      long operandHigh, long operand, int descriptor) {
    projections.append(
        projection, operator, operandHigh, operand, descriptor,
        SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
  }
  void append(int projection, int operator, long operand, int descriptor, int scope) {
    projections.append(projection, operator, operand, descriptor, scope);
  }
  void append(
      int projection, int operator, long operandHigh, long operand,
      int descriptor, int scope) {
    projections.append(
        projection, operator, operandHigh, operand, descriptor, scope);
  }
  void finish(int projection, int descriptor, int rawColumn) {
    projections.finish(projection, descriptor, rawColumn);
  }
  void resolveNullProjection(int projection, int descriptor) {
    projections.resolveNull(projection, descriptor);
  }

  StatusCode status() {
    StatusCode status = projections.status();
    return status.isOk() ? mutations.status() : status;
  }
  int count() { return projections.count(); }
  int nodeCount(int projection) { return projections.nodeCount(projection); }
  int operator(int projection, int node) { return projections.operator(projection, node); }
  long operand(int projection, int node) { return projections.operand(projection, node); }
  long operandHigh(int projection, int node) {
    return projections.operandHigh(projection, node);
  }
  int descriptor(int projection, int node) { return projections.descriptor(projection, node); }
  int scope(int projection, int node) { return projections.scope(projection, node); }
  boolean referencesScope(int projection, int scope) {
    return projections.referencesScope(projection, scope);
  }
  int resultDescriptor(int projection) { return projections.resultDescriptor(projection); }
  int rawColumn(int projection) { return projections.rawColumn(projection); }
  boolean computed(int projection) { return projections.computed(projection); }
}

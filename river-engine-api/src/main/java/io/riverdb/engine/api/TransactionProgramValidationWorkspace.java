package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Owned validation scratch; its complete retained footprint is admitted before allocation. */
final class TransactionProgramValidationWorkspace {
  private final TransactionProgramDominators dominators;
  private final TransactionProgramDescriptorSet descriptors;

  private TransactionProgramValidationWorkspace(int steps, int edges, int nodes) {
    dominators = new TransactionProgramDominators(steps, edges);
    descriptors = new TransactionProgramDescriptorSet(nodes);
  }

  static TransactionProgramValidationWorkspace create(int steps, int edges, int nodes) {
    return new TransactionProgramValidationWorkspace(steps, edges, nodes);
  }

  static long retainedBytes(int steps, int edges, int nodes) {
    long bytes = TransactionProgramDominators.retainedBytes(steps, edges);
    return TransactionProgramValidationSizing.add(
        bytes, TransactionProgramDescriptorSet.retainedBytes(nodes));
  }

  StatusCode build(TransactionProgram program) {
    dominators.build(program);
    StatusCode status = descriptors.validate(program);
    return status.isOk() ? dominators.validateReferences(program) : status;
  }
}

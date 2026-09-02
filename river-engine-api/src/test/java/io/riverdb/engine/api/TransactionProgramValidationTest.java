package io.riverdb.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class TransactionProgramValidationTest {
  @Test
  void rejectsConflictingArgumentDescriptorsAtFreeze() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(1, TransactionProgramAction.COMMAND));
    beginParameter(program, 0, SqlTypeDescriptor.BIGINT);
    beginParameter(program, 0, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void rejectsSparseArgumentSlotsAtFreeze() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(1, TransactionProgramAction.COMMAND));
    beginParameter(program, 1, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void rejectsConflictingPriorResultDescriptorsAtFreeze() {
    TransactionProgram program = new TransactionProgram();
    beginStep(program, 1, TransactionProgramAction.EXACT_ONE);
    assertEquals(StatusCode.OK, program.beginStep(2, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.priorResult(0, 0, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.priorResult(0, 0, SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void rejectsResultConsumerOnPathThatBypassesSource() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginGuard(2));
    assertEquals(StatusCode.OK, program.nullValue(SqlTypeDescriptor.BOOLEAN));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    beginStep(program, 2, TransactionProgramAction.EXACT_ONE);
    assertEquals(StatusCode.OK, program.beginStep(3, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.priorResult(1, 0, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void admitsResultConsumerWhenSourceDominatesAndEmptyPathSkipsIt() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(1, TransactionProgramAction.ZERO_OR_ONE));
    assertEquals(StatusCode.OK, program.skipOnEmpty(3));
    assertEquals(StatusCode.OK, program.endStep());
    beginResultParameterStep(program, 2, TransactionProgramAction.COMMAND, 0,
        SqlTypeDescriptor.INTEGER);
    beginStep(program, 3, TransactionProgramAction.COMMAND);
    assertEquals(StatusCode.OK, program.freeze());
  }

  @Test
  void admitsExactOneResultConsumersAtImmediateAndLaterSteps() {
    TransactionProgram program = new TransactionProgram();
    beginStep(program, 1, TransactionProgramAction.EXACT_ONE);
    beginResultParameterStep(program, 2, TransactionProgramAction.COMMAND, 0,
        SqlTypeDescriptor.INTEGER);
    beginResultParameterStep(program, 3, TransactionProgramAction.COMMAND, 0,
        SqlTypeDescriptor.INTEGER);

    assertEquals(StatusCode.OK, program.freeze());
  }

  @Test
  void rejectsZeroOrOneResultConsumerWithoutEmptyBranch() {
    TransactionProgram program = new TransactionProgram();
    beginStep(program, 1, TransactionProgramAction.ZERO_OR_ONE);
    beginResultParameterStep(program, 2, TransactionProgramAction.COMMAND, 0,
        SqlTypeDescriptor.INTEGER);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void rejectsZeroOrOneResultConsumerAtEmptyTarget() {
    TransactionProgram program = zeroOrOneWithConsumerAt(3);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void rejectsZeroOrOneResultConsumerAfterEmptyTarget() {
    TransactionProgram program = zeroOrOneWithConsumerAt(4);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, program.freeze());
  }

  @Test
  void validationScratchIsAdmittedAndReleasedExactly() {
    TrackingLease lease = new TrackingLease();
    TransactionProgram program = new TransactionProgram(lease);
    beginStep(program, 1, TransactionProgramAction.EXACT_ONE);
    beginParameterStep(program, 2, TransactionProgramAction.COMMAND, 0, SqlTypeDescriptor.INTEGER);
    long before = program.retainedBytes();
    assertEquals(StatusCode.OK, program.freeze());
    assertTrue(lease.maximum > before);
    assertEquals(program.retainedBytes(), lease.current);
    assertEquals(0, program.release().stableCode());
    assertEquals(0, lease.current);
  }

  @Test
  void validatesLargeForwardGraphWithoutPathEnumeration() {
    TransactionProgram program = new TransactionProgram();
    for (int step = 0; step < 2_000; step++) {
      beginStep(program, step + 1L, TransactionProgramAction.COMMAND);
    }
    assertEquals(StatusCode.OK, program.freeze());
  }

  private static void beginStep(TransactionProgram program, long handle, int action) {
    assertEquals(StatusCode.OK, program.beginStep(handle, action));
    assertEquals(StatusCode.OK, program.endStep());
  }

  private static TransactionProgram zeroOrOneWithConsumerAt(int consumer) {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK, program.beginStep(1, TransactionProgramAction.ZERO_OR_ONE));
    assertEquals(StatusCode.OK, program.skipOnEmpty(3));
    assertEquals(StatusCode.OK, program.endStep());
    while (program.stepCount() < consumer) {
      beginStep(program, program.stepCount() + 1L, TransactionProgramAction.COMMAND);
    }
    beginResultParameterStep(program, consumer + 1L, TransactionProgramAction.COMMAND, 0,
        SqlTypeDescriptor.INTEGER);
    return program;
  }

  private static void beginParameterStep(
      TransactionProgram program, long handle, int action, int slot, int descriptor) {
    assertEquals(StatusCode.OK, program.beginStep(handle, action));
    beginParameter(program, slot, descriptor);
    assertEquals(StatusCode.OK, program.endStep());
  }

  private static void beginResultParameterStep(
      TransactionProgram program, long handle, int action, int source, int descriptor) {
    assertEquals(StatusCode.OK, program.beginStep(handle, action));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.priorResult(source, 0, descriptor));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
  }

  private static void beginParameter(TransactionProgram program, int slot, int descriptor) {
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(slot, descriptor));
    assertEquals(StatusCode.OK, program.endExpression());
  }

  private static final class TrackingLease implements RetainedMemoryLease {
    long current;
    long maximum;

    @Override
    public StatusCode resize(long bytes) {
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      current = bytes;
      maximum = Math.max(maximum, bytes);
      return StatusCode.OK;
    }

    @Override
    public StatusCode awaitResize(long bytes) { return resize(bytes); }

    @Override
    public long retainedBytes() { return current; }
  }
}

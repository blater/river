package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.sql.SqlPreparedPlan;
import io.riverdb.engine.sql.SqlProgramMemoryLease;
import io.riverdb.engine.sql.SqlRetainedBudget;

/** One canonical program graph with authoritative references to its prepared plans. */
final class RetainedTransactionProgram implements RetainedMemoryLease {
  private static final long REFERENCE_HEADER_BYTES = 128L;
  private final RetainedPreparedStatements prepared;
  private final SqlProgramMemoryLease memory;
  private final TransactionProgram program;
  private SqlPreparedPlan[] plans = new SqlPreparedPlan[0];
  private long[] statementHandles = new long[0];
  private int retainedReferences;
  private long graphBytes;
  private long referenceBytes;

  RetainedTransactionProgram(
      SqlRetainedBudget budget, RetainedPreparedStatements retainedPrepared) {
    prepared = retainedPrepared;
    memory = new SqlProgramMemoryLease(budget);
    program = new TransactionProgram(this);
  }

  StatusCode initialize(TransactionProgram source) {
    if (source == null || !source.isFrozen() || referenceBytes != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int steps = source.stepCount();
    long bytes = REFERENCE_HEADER_BYTES + (long) steps * Long.BYTES * 2;
    StatusCode status = memory.resize(bytes);
    if (!status.isOk()) return status;
    referenceBytes = bytes;
    try {
      plans = new SqlPreparedPlan[steps];
      statementHandles = new long[steps];
    } catch (OutOfMemoryError failure) {
      return failed(StatusCode.RESOURCE_EXHAUSTED);
    }
    for (int step = 0; step < steps; step++) {
      long handle = source.preparedHandle(step);
      SqlPreparedPlan plan = prepared.retain(handle);
      if (plan == null) return failed(StatusCode.INVALID_EXTERNAL_INPUT);
      retainedReferences++;
      plans[step] = plan;
      statementHandles[step] = handle;
      if (!plan.acceptsProgramAction(source.action(step))) {
        return failed(StatusCode.INVALID_EXTERNAL_INPUT);
      }
      if (plan.parameterCount() != source.parameterCount(step)) {
        return failed(StatusCode.PARAMETER_COUNT_MISMATCH);
      }
    }
    status = source.copyTo(program);
    return status.isOk() ? status : failed(status);
  }

  TransactionProgram program() { return program; }
  SqlPreparedPlan plan(int step) { return plans[step]; }

  StatusCode close() {
    StatusCode status = program.release();
    if (!status.isOk()) return status;
    status = releaseReferences();
    if (!status.isOk()) return status;
    status = memory.resize(0);
    if (!status.isOk()) return status;
    plans = new SqlPreparedPlan[0];
    statementHandles = new long[0];
    referenceBytes = 0;
    return StatusCode.OK;
  }

  @Override
  public StatusCode resize(long bytes) {
    if (bytes < 0 || referenceBytes > Long.MAX_VALUE - bytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = memory.resize(referenceBytes + bytes);
    if (status.isOk()) graphBytes = bytes;
    return status;
  }

  @Override public StatusCode awaitResize(long bytes) { return resize(bytes); }
  @Override public long retainedBytes() { return graphBytes; }

  private StatusCode failed(StatusCode primary) {
    StatusCode released = releaseReferences();
    plans = new SqlPreparedPlan[0];
    statementHandles = new long[0];
    StatusCode memoryStatus = memory.resize(0);
    referenceBytes = memoryStatus.isOk() ? 0 : referenceBytes;
    if (!released.isOk()) return released;
    return memoryStatus.isOk() ? primary : memoryStatus;
  }

  private StatusCode releaseReferences() {
    StatusCode status = StatusCode.OK;
    while (retainedReferences > 0) {
      StatusCode released = prepared.releaseReference(statementHandles[--retainedReferences]);
      if (status.isOk()) status = released;
    }
    return status;
  }
}

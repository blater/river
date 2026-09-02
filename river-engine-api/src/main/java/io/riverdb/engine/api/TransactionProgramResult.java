package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Reusable ordered output for one atomic transaction-program invocation. */
public final class TransactionProgramResult {
  private final TransactionProgramResultMemory memory;
  private final TransactionProgramResultMetadata metadata;
  private final TransactionValueArena values;
  private final TransactionProgramResultAdmission admission;
  private int cellCount;
  private long commitSequence;
  private int failingStep = -1;
  private StatusCode primaryStatus = StatusCode.OK;
  private StatusCode rollbackStatus = StatusCode.OK;
  private boolean sessionFenced;

  public TransactionProgramResult() { this(RetainedMemoryLease.unbounded(), null); }

  public TransactionProgramResult(RetainedMemoryLease retainedMemory) {
    this(retainedMemory, null);
  }

  public TransactionProgramResult(
      RetainedMemoryLease retainedMemory,
      TransactionProgramResultAdmission resultAdmission) {
    if (retainedMemory == null) throw new IllegalArgumentException("retainedMemory");
    memory = new TransactionProgramResultMemory(retainedMemory);
    metadata = new TransactionProgramResultMetadata(memory);
    values = new TransactionValueArena(memory);
    admission = resultAdmission;
  }

  public void reset() {
    metadata.reset();
    values.reset();
    cellCount = 0;
    commitSequence = 0;
    failingStep = -1;
    primaryStatus = StatusCode.OK;
    rollbackStatus = StatusCode.OK;
    sessionFenced = false;
  }

  public StatusCode beginStepResult(int programStep, int action, int affected) {
    if (programStep < 0 || !TransactionProgramAction.isValid(action) || affected < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return metadata.beginStep(programStep, action, affected);
  }

  public StatusCode beginRow(int columns) {
    if (metadata.stepCount() == 0 || columns < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return metadata.beginRow(cellCount, columns);
  }

  public StatusCode appendNull(int descriptor) {
    StatusCode status = values.setNull(cellCount, descriptor);
    if (status.isOk()) cellCount++;
    return status;
  }

  public StatusCode appendFixed(int descriptor, long high, long low) {
    StatusCode status = values.setFixed(cellCount, descriptor, high, low);
    if (status.isOk()) cellCount++;
    return status;
  }

  public StatusCode appendDecimal128(int descriptor, long high, long low) {
    StatusCode status = values.setDecimal128(cellCount, descriptor, high, low);
    if (status.isOk()) cellCount++;
    return status;
  }

  public StatusCode appendText(int descriptor, CharSequence source) {
    StatusCode status = values.setText(cellCount, descriptor, source);
    if (status.isOk()) cellCount++;
    return status;
  }

  public void complete(long committedAt) { commitSequence = committedAt; }

  public void fail(
      int programStep, StatusCode primary, StatusCode rollback, boolean fenced) {
    failingStep = programStep;
    primaryStatus = primary == null ? StatusCode.INVARIANT_BROKEN : primary;
    rollbackStatus = rollback == null ? StatusCode.INVARIANT_BROKEN : rollback;
    sessionFenced = fenced;
  }

  public int stepCount() { return metadata.stepCount(); }
  public int programStep(int step) {
    return metadata.validStep(step) ? metadata.programStep(step) : -1;
  }
  public int action(int step) { return metadata.validStep(step) ? metadata.action(step) : 0; }
  public int affectedRows(int step) {
    return metadata.validStep(step) ? metadata.affectedRows(step) : 0;
  }
  public int rowCount(int step) {
    return metadata.validStep(step) ? metadata.rowCount(step) : 0;
  }
  public int firstRow(int step) {
    return metadata.validStep(step) ? metadata.firstRow(step) : -1;
  }
  public int rowCount() { return metadata.rowCount(); }
  public int columnCount(int row) {
    return metadata.validRow(row) ? metadata.columnCount(row) : 0;
  }
  public int firstCell(int row) {
    return metadata.validRow(row) ? metadata.firstCell(row) : -1;
  }
  public long commitSequence() { return commitSequence; }
  public int failingStep() { return failingStep; }
  public StatusCode primaryStatus() { return primaryStatus; }
  public StatusCode rollbackStatus() { return rollbackStatus; }
  public boolean sessionFenced() { return sessionFenced; }
  public int typeDescriptorAt(int row, int column) { return values.descriptor(cell(row, column)); }
  public boolean isNull(int row, int column) { return values.isNull(cell(row, column)); }
  public long valueAt(int row, int column) { return values.low(cell(row, column)); }
  public long highValueAt(int row, int column) { return values.high(cell(row, column)); }
  public int textLengthAt(int row, int column) { return values.textLength(cell(row, column)); }
  public char textCharacterAt(int row, int column, int character) {
    return values.textCharacterAt(cell(row, column), character);
  }
  public long retainedBytes() { return memory.retainedBytes(); }
  public static long maximumRetainedBytes(
      int steps, int rows, int cells, int textCharacters) {
    long metadataBytes = TransactionProgramResultMetadata.maximumRetainedBytes(steps, rows);
    long valueBytes = TransactionValueArenaSizing.maximumRetainedBytes(cells, textCharacters);
    return metadataBytes < 0 || valueBytes < 0 || metadataBytes > Long.MAX_VALUE - valueBytes
        ? -1 : metadataBytes + valueBytes;
  }

  public StatusCode admitCommit() {
    return admission == null ? StatusCode.OK : admission.admit(this);
  }

  public StatusCode release() {
    reset();
    StatusCode status = values.release();
    return status.isOk() ? metadata.release() : status;
  }

  private int cell(int row, int column) {
    if (!metadata.validRow(row) || column < 0 || column >= metadata.columnCount(row)) return -1;
    return metadata.firstCell(row) + column;
  }
}

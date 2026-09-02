package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Reusable primitive step/row structure for one program result. */
final class TransactionProgramResultMetadata {
  private static final long STEP_HEADER_BYTES = 48L;
  private static final long ROW_HEADER_BYTES = 32L;
  private final TransactionProgramResultMemory memory;
  private int[] programSteps = new int[0];
  private int[] actions = new int[0];
  private int[] affectedRows = new int[0];
  private int[] firstRows = new int[0];
  private int[] rowCounts = new int[0];
  private int[] firstCells = new int[0];
  private int[] cellCounts = new int[0];
  private int stepCount;
  private int rowCount;

  TransactionProgramResultMetadata(TransactionProgramResultMemory resultMemory) {
    memory = resultMemory;
  }

  void reset() {
    Arrays.fill(programSteps, 0, stepCount, 0);
    Arrays.fill(actions, 0, stepCount, 0);
    Arrays.fill(affectedRows, 0, stepCount, 0);
    Arrays.fill(firstRows, 0, stepCount, 0);
    Arrays.fill(rowCounts, 0, stepCount, 0);
    Arrays.fill(firstCells, 0, rowCount, 0);
    Arrays.fill(cellCounts, 0, rowCount, 0);
    stepCount = 0;
    rowCount = 0;
  }

  StatusCode beginStep(int programStep, int action, int affected) {
    StatusCode status = ensureSteps(stepCount + 1);
    if (!status.isOk()) return status;
    programSteps[stepCount] = programStep;
    actions[stepCount] = action;
    affectedRows[stepCount] = affected;
    firstRows[stepCount] = rowCount;
    rowCounts[stepCount++] = 0;
    return StatusCode.OK;
  }

  StatusCode beginRow(int firstCell, int columns) {
    StatusCode status = ensureRows(rowCount + 1);
    if (!status.isOk()) return status;
    firstCells[rowCount] = firstCell;
    cellCounts[rowCount] = columns;
    rowCounts[stepCount - 1]++;
    rowCount++;
    return StatusCode.OK;
  }

  StatusCode release() {
    StatusCode status = memory.resizeMetadata(0);
    if (!status.isOk()) return status;
    programSteps = new int[0];
    actions = new int[0];
    affectedRows = new int[0];
    firstRows = new int[0];
    rowCounts = new int[0];
    firstCells = new int[0];
    cellCounts = new int[0];
    stepCount = 0;
    rowCount = 0;
    return StatusCode.OK;
  }

  int stepCount() { return stepCount; }
  int rowCount() { return rowCount; }
  boolean validStep(int step) { return step >= 0 && step < stepCount; }
  boolean validRow(int row) { return row >= 0 && row < rowCount; }
  int programStep(int step) { return programSteps[step]; }
  int action(int step) { return actions[step]; }
  int affectedRows(int step) { return affectedRows[step]; }
  int firstRow(int step) { return firstRows[step]; }
  int rowCount(int step) { return rowCounts[step]; }
  int firstCell(int row) { return firstCells[row]; }
  int columnCount(int row) { return cellCounts[row]; }

  static long maximumRetainedBytes(int steps, int rows) {
    if (steps < 0 || rows < 0) return -1;
    int stepCapacity = retainedCapacity(steps);
    int rowCapacity = retainedCapacity(rows);
    long stepBytes = stepBytes(stepCapacity);
    long rowBytes = rowBytes(rowCapacity);
    return stepBytes > Long.MAX_VALUE - rowBytes ? -1 : stepBytes + rowBytes;
  }

  private StatusCode ensureSteps(int needed) {
    if (needed <= programSteps.length) return StatusCode.OK;
    int capacity = growth(programSteps.length, needed);
    long previous = stepBytes(programSteps.length);
    long bytes = memory.metadataBytes() - previous + stepBytes(capacity);
    StatusCode status = memory.resizeMetadata(bytes);
    if (!status.isOk()) return status;
    try {
      int[] nextProgramSteps = Arrays.copyOf(programSteps, capacity);
      int[] nextActions = Arrays.copyOf(actions, capacity);
      int[] nextAffectedRows = Arrays.copyOf(affectedRows, capacity);
      int[] nextFirstRows = Arrays.copyOf(firstRows, capacity);
      int[] nextRowCounts = Arrays.copyOf(rowCounts, capacity);
      programSteps = nextProgramSteps;
      actions = nextActions;
      affectedRows = nextAffectedRows;
      firstRows = nextFirstRows;
      rowCounts = nextRowCounts;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resizeMetadata(bytes - stepBytes(capacity) + previous);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode ensureRows(int needed) {
    if (needed <= firstCells.length) return StatusCode.OK;
    int capacity = growth(firstCells.length, needed);
    long previous = rowBytes(firstCells.length);
    long bytes = memory.metadataBytes() - previous + rowBytes(capacity);
    StatusCode status = memory.resizeMetadata(bytes);
    if (!status.isOk()) return status;
    try {
      int[] nextFirstCells = Arrays.copyOf(firstCells, capacity);
      int[] nextCellCounts = Arrays.copyOf(cellCounts, capacity);
      firstCells = nextFirstCells;
      cellCounts = nextCellCounts;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resizeMetadata(bytes - rowBytes(capacity) + previous);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static int growth(int current, int needed) {
    int next = current == 0 ? 8 : current << 1;
    return next > current ? Math.max(next, needed) : needed;
  }

  private static int retainedCapacity(int needed) {
    if (needed == 0) return 0;
    int capacity = 8;
    while (capacity < needed) {
      int next = capacity << 1;
      if (next <= capacity) return needed;
      capacity = next;
    }
    return capacity;
  }

  private static long stepBytes(int capacity) {
    return capacity == 0 ? 0 : STEP_HEADER_BYTES + (long) capacity * 5 * Integer.BYTES;
  }

  private static long rowBytes(int capacity) {
    return capacity == 0 ? 0 : ROW_HEADER_BYTES + (long) capacity * 2 * Integer.BYTES;
  }
}

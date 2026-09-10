package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;

/** Caller-owned statement range bound to one transaction identity. */
public final class RelationalDescriptorInsertBatch {
  private TableDescriptor table;
  private long firstLogicalRowId;
  private long transactionId;
  private int expectedRows;
  private int admittedRows;

  public RelationalDescriptorInsertBatch() {
  }

  public void reset() {
    table = null;
    firstLogicalRowId = 0;
    transactionId = 0;
    expectedRows = 0;
    admittedRows = 0;
  }

  StatusCode begin(TableDescriptor descriptor, int rows, long currentTransactionId) {
    reset();
    if (descriptor == null || rows <= 0 || currentTransactionId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    table = descriptor;
    transactionId = currentTransactionId;
    expectedRows = rows;
    return StatusCode.OK;
  }

  StatusCode admit(TableDescriptor descriptor) {
    if (descriptor != table || admittedRows >= expectedRows) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    admittedRows++;
    return StatusCode.OK;
  }

  int rowCount() { return expectedRows; }
  int nextRow() { return admittedRows; }
  boolean canAdmit(TableDescriptor descriptor, long currentTransactionId) {
    return descriptor == table && transactionId == currentTransactionId
        && firstLogicalRowId > 0 && admittedRows < expectedRows;
  }
  boolean rowAdmitted(TableDescriptor descriptor, int row) {
    return descriptor == table && row >= 0 && row < admittedRows;
  }

  boolean belongsTo(long currentTransactionId) {
    return transactionId == currentTransactionId;
  }

  void reserve(long first) { firstLogicalRowId = first; }

  long logicalRowId(int row) { return firstLogicalRowId + row; }
}

package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;

/** Caller-owned exact admission and logical-ID range for one descriptor INSERT statement. */
public final class RelationalDescriptorInsertBatch {
  private static final int MAXIMUM_ROWS =
      io.riverdb.base.sql.SqlShapeLimits.MAX_INSERT_ROWS_PER_STATEMENT;
  private final RelationalDescriptorInsertReceipt receipt;
  private final RelationalDescriptorBatchUniqueKeys uniqueKeys;
  private TableDescriptor table;
  private long firstLogicalRowId;
  private int expectedRows;
  private int admittedRows;
  private int insertedRows;
  private int tupleMutationCount;
  private int tuplePayloadBytes;

  public RelationalDescriptorInsertBatch() {
    this(null, RelationalDescriptorBatchAllocator.STANDARD);
  }

  public RelationalDescriptorInsertBatch(RelationalRetainedBudget retainedBudget) {
    this(retainedBudget, RelationalDescriptorBatchAllocator.STANDARD);
  }

  RelationalDescriptorInsertBatch(
      RelationalRetainedBudget retainedBudget,
      RelationalDescriptorBatchAllocator batchAllocator) {
    receipt = new RelationalDescriptorInsertReceipt(retainedBudget, batchAllocator);
    uniqueKeys = new RelationalDescriptorBatchUniqueKeys(retainedBudget, batchAllocator);
  }

  public void reset() {
    receipt.reset(expectedRows);
    table = null;
    uniqueKeys.reset();
    firstLogicalRowId = 0;
    expectedRows = 0;
    admittedRows = 0;
    insertedRows = 0;
    tupleMutationCount = 0;
    tuplePayloadBytes = 0;
  }

  StatusCode begin(TableDescriptor descriptor, int rows) {
    reset();
    if (descriptor == null || rows <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (rows > MAXIMUM_ROWS) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = receipt.prepare();
    if (!status.isOk()) return status;
    table = descriptor;
    expectedRows = rows;
    return StatusCode.OK;
  }

  StatusCode admit(
      TableDescriptor descriptor, int rowBytes,
      int tupleMutations, int tupleBytes, long contentFingerprint) {
    if (descriptor != table || admittedRows >= expectedRows || rowBytes <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (tupleMutations < 0 || tupleBytes < 0
        || tupleMutationCount > Integer.MAX_VALUE - tupleMutations
        || tuplePayloadBytes > Integer.MAX_VALUE - tupleBytes) {
      return tupleMutations < 0 || tupleBytes < 0
          ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.RESOURCE_EXHAUSTED;
    }
    receipt.capture(admittedRows++, rowBytes, contentFingerprint);
    tupleMutationCount += tupleMutations;
    tuplePayloadBytes += tupleBytes;
    return StatusCode.OK;
  }

  StatusCode admitUnique(long keyId, java.nio.ByteBuffer bytes, int length) {
    return uniqueKeys.add(keyId, bytes, length);
  }

  StatusCode validateUnique(
      io.riverdb.engine.table.IndexedTransactionSession session,
      TableDescriptor descriptor) {
    return descriptor == table ? uniqueKeys.validate(session, descriptor)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  boolean admittedFor(TableDescriptor descriptor) {
    return descriptor == table && admittedRows == expectedRows
        && firstLogicalRowId == 0 && insertedRows == 0;
  }

  int[] mutationLengths() { return receipt.mutationLengths(); }
  int mutationCount() { return expectedRows; }
  int rowCount() { return expectedRows; }
  int tupleMutationCount() { return tupleMutationCount; }
  int tuplePayloadBytes() { return tuplePayloadBytes; }

  void reserve(long first) { firstLogicalRowId = first; }

  boolean mayInsert(TableDescriptor descriptor, int row) {
    return descriptor == table && firstLogicalRowId > 0 && row == insertedRows
        && row < expectedRows;
  }

  boolean matchesEncodedRow(int row, int bytes, long contentFingerprint) {
    return row >= 0 && row < expectedRows
        && receipt.matches(row, bytes, contentFingerprint);
  }

  long logicalRowId(int row) { return firstLogicalRowId + row; }
  void inserted() { insertedRows++; }

  long retainedBytes() {
    return receipt.retainedBytes() + uniqueKeys.retainedBytes();
  }
}

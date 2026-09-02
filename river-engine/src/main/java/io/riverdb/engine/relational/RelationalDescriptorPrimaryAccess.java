package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleProbeResult;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.ByteBuffer;

/** Reusable primary-key point resolution and scalar compatibility workspace. */
final class RelationalDescriptorPrimaryAccess {
  private final IndexedTupleProbeResult probe = new IndexedTupleProbeResult();
  private final RelationalTupleKeyEncoder expectedEncoder = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder actualEncoder = new RelationalTupleKeyEncoder();
  private final SqlValueBuffer scalarValues = new SqlValueBuffer();

  StatusCode resolve(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer primaryValues, RelationalRowIdentityResult result) {
    return resolve(session, table, primaryValues, result, null);
  }

  StatusCode resolveSource(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer primaryValues, LockMode mode,
      RelationalRowIdentityResult result) {
    return resolve(session, table, primaryValues, result, mode);
  }

  private StatusCode resolve(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer primaryValues, RelationalRowIdentityResult result,
      LockMode sourceMode) {
    result.reset();
    if (table.primaryKey() == null || primaryValues == null
        || primaryValues.count() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = expectedEncoder.encodeUser(table.primaryKey(), primaryValues);
    if (!status.isOk()) return status;
    status = sourceMode != null
        ? session.resolveTupleUniqueSource(
            table.tableId(), table.primaryKey().keyId(), table.primaryKey().keyId(),
            table.primaryKey().shape(), expectedEncoder.bytes(), 0,
            expectedEncoder.length(), sourceMode, probe)
        : session.resolveTupleUniquePrefix(
            table.tableId(), table.primaryKey().keyId(), table.primaryKey().keyId(),
            table.primaryKey().shape(), expectedEncoder.bytes(), 0,
            expectedEncoder.length(), probe);
    if (!status.isOk()) return status;
    if (!probe.found()) return StatusCode.CONFLICT;
    result.set(probe.logicalRowId());
    return StatusCode.OK;
  }

  StatusCode validateResolved(TableDescriptor table, SqlValueBuffer values) {
    StatusCode status = actualEncoder.encodeUser(table.primaryKey(), values);
    if (!status.isOk()) return status;
    if (expectedEncoder.length() != actualEncoder.length()) return StatusCode.CONFLICT;
    ByteBuffer expected = expectedEncoder.bytes();
    ByteBuffer actual = actualEncoder.bytes();
    for (int index = 0; index < expectedEncoder.length(); index++) {
      if (expected.get(index) != actual.get(index)) return StatusCode.CONFLICT;
    }
    return StatusCode.OK;
  }

  StatusCode scalarValues(TableDescriptor table, long primaryKey) {
    if (table == null || table.primaryKey() == null
        || table.primaryKey().partCount() != 1
        || table.primaryKey().columnOrdinalAt(0) != 0
        || table.typeDescriptorAt(0) != SqlTypeDescriptor.BIGINT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = scalarValues.reserve(table.columnCount(), table.columnCount(), 0, 0);
    if (status.isOk()) status = scalarValues.clearForSize(table.columnCount());
    for (int column = 0; status.isOk() && column < table.columnCount(); column++) {
      status = column == 0
          ? scalarValues.setFixed(column, table.typeDescriptorAt(column), primaryKey)
          : scalarValues.setNull(column, table.typeDescriptorAt(column));
    }
    return status;
  }

  SqlValueBuffer scalarValues() { return scalarValues; }
}

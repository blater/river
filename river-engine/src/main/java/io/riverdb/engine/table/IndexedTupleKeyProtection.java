package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.ByteBuffer;

/** Lifecycle-safe protection of admitted tuple keys, borrowed until acquisition returns. */
final class IndexedTupleKeyProtection {
  private IndexedTupleKeyProtection() {}

  static StatusCode protectShared(
      IndexedTransactionSession session, long keyId,
      ByteBuffer key, int offset, int length) {
    return protect(session, keyId, key, offset, length,
        io.riverdb.tx.api.lock.LockMode.SHARED);
  }

  static StatusCode protectExclusive(
      IndexedTransactionSession session, long keyId,
      ByteBuffer key, int offset, int length) {
    return protect(session, keyId, key, offset, length,
        io.riverdb.tx.api.lock.LockMode.EXCLUSIVE);
  }

  private static StatusCode protect(
      IndexedTransactionSession session, long keyId,
      ByteBuffer key, int offset, int length,
      io.riverdb.tx.api.lock.LockMode mode) {
    if (!CatalogKeyspace.validKeyId(keyId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.protectKey(
        CatalogKeyspace.INDEX_ROOT_SPACE, keyId, LockMode.SHARED);
    return status.isOk()
        ? session.acquireTupleKey(
            keyId, key, IndexedTupleLockKey.userOffset(key, offset, length),
            IndexedTupleLockKey.userLength(key, offset, length),
            mode)
        : status;
  }
}

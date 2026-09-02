package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Validates local support and scans inbound descriptor dependencies before one root is removed. */
final class SqlDescriptorIndexDependencies {
  private final CatalogObjectCursor cursor = new CatalogObjectCursor();
  private final CatalogObjectResult object = new CatalogObjectResult();
  private final SchemaPin child = new SchemaPin();

  StatusCode check(
      RelationalSession session, TableDescriptor successor, KeyDescriptor removed) {
    if (session == null || successor == null || removed == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!supportsLocalForeignKeys(successor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!removed.isUnique()) return StatusCode.OK;
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginCatalogObjectScan(cursor);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = session.nextCatalogObject(cursor, object);
      if (!status.isOk() || !object.isAvailable()) break;
      if (object.type() != CatalogObjectResult.TABLE) continue;
      status = session.resolveDescriptor(object.name(), child, null);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        continue;
      }
      if (status.isOk() && references(child.descriptor(), removed.keyId())) {
        status = StatusCode.FOREIGN_KEY_VIOLATION;
      }
      StatusCode released = releaseChild();
      if (status.isOk()) status = released;
    }
    StatusCode released = releaseChild();
    if (status.isOk()) status = released;
    if (active) {
      StatusCode closed = session.closeCatalogObjectScan(cursor);
      if (status.isOk()) status = closed;
    }
    return status;
  }

  private StatusCode releaseChild() {
    return child.isActive() ? child.release() : StatusCode.OK;
  }

  private static boolean references(TableDescriptor table, long keyId) {
    for (int index = 0; index < table.foreignKeyCount(); index++) {
      if (table.foreignKeyAt(index).referencedKeyId() == keyId) return true;
    }
    return false;
  }

  static boolean supportsLocalForeignKeys(TableDescriptor table) {
    for (int foreignIndex = 0; foreignIndex < table.foreignKeyCount(); foreignIndex++) {
      KeyDescriptor foreign = table.foreignKeyAt(foreignIndex);
      boolean supported = false;
      for (int keyIndex = 0; keyIndex < table.secondaryKeyCount(); keyIndex++) {
        if (sameParts(table.secondaryKeyAt(keyIndex), foreign)) {
          supported = true;
          break;
        }
      }
      if (!supported) return false;
    }
    return true;
  }

  private static boolean sameParts(KeyDescriptor left, KeyDescriptor right) {
    if (left.partCount() != right.partCount()) return false;
    for (int part = 0; part < left.partCount(); part++) {
      if (left.columnOrdinalAt(part) != right.columnOrdinalAt(part)
          || left.typeDescriptorAt(part) != right.typeDescriptorAt(part)) return false;
    }
    return true;
  }
}

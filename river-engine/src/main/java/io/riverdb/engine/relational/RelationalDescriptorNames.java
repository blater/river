package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import java.nio.ByteBuffer;

/** Durable exact-name bridge from SQL names to catalog-v2 object identifiers. */
final class RelationalDescriptorNames {
  private final IndexedTransactionSession session;
  private final RelationalDatabaseServices services;
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult row = new IndexedScanResult();
  private final ByteBuffer bytes =
      ByteBuffer.allocateDirect(RelationalDescriptorNameRow.MAXIMUM_BYTES);
  private final RelationalDescriptorNameRow candidate = new RelationalDescriptorNameRow();
  private final RelationalDescriptorNameValidation validation;
  private final ObjectIdResult found = new ObjectIdResult();
  private final int[] publicationRowLengths = new int[2];

  RelationalDescriptorNames(
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    session = indexedSession;
    services = databaseServices;
    validation = new RelationalDescriptorNameValidation(indexedSession, databaseServices);
  }

  StatusCode prepareRegistration(CharSequence name) {
    StatusCode status = ensureMissing(name);
    if (!status.isOk()) return status;
    int length = encode(name);
    if (length < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    publicationRowLengths[0] = CatalogObjectHeadCodec.BYTES;
    publicationRowLengths[1] = length;
    return session.preflightPendingMutations(
        publicationRowLengths, 0, publicationRowLengths.length);
  }

  StatusCode register(CharSequence name, long objectId) {
    if (!valid(name) || !RelationalDescriptorKeyspace.validate(objectId).isOk()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int length = encode(name);
    if (length < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    bytes.position(0).limit(length);
    return session.insert(RelationalDescriptorKeyspace.NAME_MAP_SPACE, objectId, bytes);
  }

  StatusCode unregister(CharSequence name) {
    StatusCode status = find(name, found);
    return status.isOk()
        ? session.delete(RelationalDescriptorKeyspace.NAME_MAP_SPACE, found.value) : status;
  }

  StatusCode rename(CharSequence current, CharSequence renamed, long objectId) {
    StatusCode status = owns(current, objectId);
    if (status.isOk()) status = ensureMissing(renamed);
    int length = status.isOk() ? encode(renamed) : -1;
    if (status.isOk() && length < 0) status = StatusCode.INVALID_EXTERNAL_INPUT;
    if (status.isOk()) status = session.preflightPendingMutation(length);
    if (!status.isOk()) return status;
    bytes.position(0).limit(length);
    return session.update(RelationalDescriptorKeyspace.NAME_MAP_SPACE, objectId, bytes);
  }

  StatusCode ensureMissing(CharSequence name) {
    StatusCode status = find(name, found);
    return status == StatusCode.CONFLICT ? StatusCode.OK
        : status.isOk() ? StatusCode.CONFLICT : status;
  }

  StatusCode open(CharSequence name, SchemaPin pin, StatusDetail detail) {
    if (pin == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = find(name, found);
    return status.isOk() ? services.openDescriptor(session, found.value, pin, detail) : status;
  }

  StatusCode validateCommitted() {
    return validation.validate();
  }

  StatusCode owns(CharSequence name, long objectId) {
    StatusCode status = find(name, found);
    return status.isOk() && found.value != objectId ? StatusCode.CONFLICT : status;
  }

  private StatusCode find(CharSequence name, ObjectIdResult result) {
    if (!valid(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int expectedLength = encode(name);
    if (expectedLength < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.value = 0;
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginScan(
        RelationalDescriptorKeyspace.NAME_MAP_SPACE,
        Long.MIN_VALUE,
        RelationalDescriptorKeyspace.NAME_MAP_SPACE + 1,
        Long.MIN_VALUE,
        cursor);
    while (status.isOk()) {
      row.reset();
      status = session.nextScan(cursor, row);
      if (!status.isOk()) break;
      status = candidate.read(row);
      if (status.isOk() && candidate.matches(bytes, expectedLength)) {
        result.value = row.key();
        break;
      }
    }
    StatusCode closed = session.closeScan(cursor);
    if (result.value != 0) return closed.isOk() ? StatusCode.OK : closed;
    return status == StatusCode.CONFLICT && closed.isOk() ? StatusCode.CONFLICT
        : status.isOk() ? closed : status;
  }

  private int encode(CharSequence name) {
    bytes.clear();
    return Utf8Text.encode(name, TableSchema.MAXIMUM_NAME_LENGTH, bytes);
  }

  private static boolean valid(CharSequence name) {
    return name != null && name.length() > 0
        && name.length() <= TableSchema.MAXIMUM_NAME_LENGTH;
  }

  private static final class ObjectIdResult { long value; }
}

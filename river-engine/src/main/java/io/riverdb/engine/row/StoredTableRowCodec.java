package io.riverdb.engine.row;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalStoredRowAccess;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** Bounded codec for River-owned descriptor rows. Instances are caller-owned. */
public final class StoredTableRowCodec {
  private final StoredTableRowDecoder decoder = new StoredTableRowDecoder();
  private final StoredTableRowExternalAdmission externalAdmission =
      new StoredTableRowExternalAdmission();

  public StatusCode encode(
      TableDescriptor descriptor,
      long logicalRowId,
      SqlValueBuffer values,
      ByteBuffer target,
      int start,
      StoredTableRowEncodeResult result) {
    return StoredTableRowEncoder.encode(
        descriptor, logicalRowId, values, target, start, result);
  }

  public StatusCode decode(
      TableDescriptor descriptor,
      long expectedLogicalRowId,
      ByteBuffer source,
      int start,
      int length,
      SqlValueBuffer destination) {
    return decode(descriptor, expectedLogicalRowId, source, start, length,
        destination, null, true);
  }

  public StatusCode decode(
      TableDescriptor descriptor,
      long expectedLogicalRowId,
      ByteBuffer source,
      int start,
      int length,
      SqlValueBuffer destination,
      StoredTableRowFilter filter) {
    return decode(descriptor, expectedLogicalRowId, source, start, length,
        destination, filter, true);
  }

  /** Admits caller-supplied row bytes before publishing values. */
  public StatusCode decode(
      TableDescriptor descriptor,
      long expectedLogicalRowId,
      ByteBuffer source,
      int start,
      int length,
      SqlValueBuffer destination,
      StoredTableRowFilter filter,
      boolean publishText) {
    StatusCode status = externalAdmission.validate(
        descriptor, expectedLogicalRowId, source, start, length);
    return status.isOk() ? decoder.decode(
        descriptor, expectedLogicalRowId, source, start, length, destination,
        filter, publishText) : status;
  }

  /** Decodes a row fetched by a River-owned relational reader. */
  public StatusCode decodeStored(
      RelationalStoredRowAccess access,
      TableDescriptor descriptor,
      long expectedLogicalRowId,
      ByteBuffer source,
      int start,
      int length,
      SqlValueBuffer destination,
      StoredTableRowFilter filter,
      boolean publishText) {
    if (access == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return decoder.decode(descriptor, expectedLogicalRowId, source, start, length,
        destination, filter, publishText);
  }
}

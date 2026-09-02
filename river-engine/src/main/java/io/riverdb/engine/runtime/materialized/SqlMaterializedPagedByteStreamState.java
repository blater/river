package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Mutable operator-owned state; reusable pins, headers, and diagnostics live for the stream. */
final class SqlMaterializedPagedByteStreamState {
  final SqlMaterializedScratchOwner owner;
  final SqlMaterializedScratchFile file;
  final SqlMaterializedScratchFileKind kind;
  final int pageBytes;
  final int payloadBytes;
  int fixedRecordBytes;
  final int flags;
  final ByteBuffer headerBuffer = ByteBuffer.allocate(
      SqlMaterializedScratchFileCodec.FILE_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
  final SqlMaterializedScratchFileCodec.Header header =
      new SqlMaterializedScratchFileCodec.Header();
  final SqlMaterializedScratchFileCodec.PageHeader pageHeader =
      new SqlMaterializedScratchFileCodec.PageHeader();
  final SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
  final SqlMaterializedPageLocation location = new SqlMaterializedPageLocation();
  final StatusDetail internalDetail = new StatusDetail(192);
  long publishedCount;
  long logicalLength;
  boolean failed;
  boolean closed;
  StatusCode failureStatus = StatusCode.OK;
  int copied;
  long position;
  boolean mutated;

  SqlMaterializedPagedByteStreamState(
      SqlMaterializedScratchOwner owningOwner, SqlMaterializedScratchFile retainedFile,
      SqlMaterializedScratchFileKind fileKind, int physicalPageBytes, int fixedBytes,
      int fileFlags) {
    owner = owningOwner;
    file = retainedFile;
    kind = fileKind;
    pageBytes = physicalPageBytes;
    payloadBytes = physicalPageBytes - SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES;
    fixedRecordBytes = fixedBytes;
    flags = fileFlags;
  }
}

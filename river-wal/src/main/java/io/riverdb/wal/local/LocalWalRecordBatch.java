package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Admitted logical records encoded one at a time into provider-owned storage. */
public interface LocalWalRecordBatch {
  int recordCount();

  int payloadBytes(int record);

  StatusCode encodePayload(int record, ByteBuffer target);
}

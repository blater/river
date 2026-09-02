package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Positioned page I/O boundary. Implementations consume the complete supplied page. */
interface SqlMaterializedPageIo {
  long fileIdentity();

  StatusCode read(long filePosition, ByteBuffer target);

  StatusCode write(long filePosition, ByteBuffer source);
}

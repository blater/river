package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolFrameHeader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Completes assembly, endpoint admission, response retry, and publication. */
final class ServerRequestDispatch {
  private ServerRequestDispatch() { }

  static StatusCode process(ProtocolFrameCodec codec, ServerRequestAssembly requests,
      ServerResponseBuffer responses, ByteBuffer request, ProtocolFrameHeader header,
      SessionEndpoint endpoint, OutputStream output) throws IOException {
    StatusCode status = requests.accept(codec, request, header);
    if (!status.isOk()) return status;
    ByteBuffer admitted = requests.admitted();
    if (admitted == null) return StatusCode.RETRY;
    try {
      status = responses.process(endpoint, admitted);
    } finally {
      requests.release();
    }
    try {
      if (!status.isOk()) return status;
      output.write(responses.bytes(), 0, responses.buffer().remaining());
      output.flush();
      return StatusCode.OK;
    } finally {
      endpoint.releasePublishedHighWater();
      responses.releaseHighWater();
    }
  }
}

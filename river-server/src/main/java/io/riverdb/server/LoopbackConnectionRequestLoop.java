package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrameCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Reads, dispatches, and completes framed requests for one connection. */
final class LoopbackConnectionRequestLoop {
  private LoopbackConnectionRequestLoop() { }

  static void serve(
      LoopbackRiverServer server,
      LoopbackRiverServer.ConnectionSlot slot,
      Socket connection,
      SessionEndpoint endpoint,
      InputStream input,
      OutputStream output,
      long initialAuthenticationDeadline,
      int idleTimeoutMillis) throws IOException {
    long authenticationDeadline = initialAuthenticationDeadline;
    while (server.running) {
      int headerBytes = readExact(
          input,
          slot.requestBytes,
          0,
          ProtocolFrameCodec.HEADER_BYTES,
          connection,
          authenticationDeadline);
      if (headerBytes == 0) {
        if (slot.requests.isActive()) {
          server.rejectedFrames.incrementAndGet();
          server.lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
        }
        return;
      }
      if (headerBytes != ProtocolFrameCodec.HEADER_BYTES) {
        server.rejectedFrames.incrementAndGet();
        server.lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
        return;
      }
      slot.request.position(0);
      slot.request.limit(ProtocolFrameCodec.HEADER_BYTES);
      StatusCode headerStatus = server.codec.inspectRequestHeader(
          slot.request, slot.requestHeader);
      if (!headerStatus.isOk()) {
        server.rejectedFrames.incrementAndGet();
        server.lastStatus = headerStatus;
        return;
      }
      int payloadBytes = slot.requestHeader.payloadBytes();
      if (readExact(
          input,
          slot.requestBytes,
          ProtocolFrameCodec.HEADER_BYTES,
          payloadBytes,
          connection,
          authenticationDeadline) != payloadBytes) {
        server.rejectedFrames.incrementAndGet();
        server.lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
        return;
      }
      slot.request.position(0);
      slot.request.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
      StatusCode processed = ServerRequestDispatch.process(
          server.codec, slot.requests, slot.responses, slot.request, slot.requestHeader,
          endpoint, output);
      if (processed == StatusCode.RETRY) continue;
      if (!processed.isOk()) {
        server.rejectedFrames.incrementAndGet();
        server.lastStatus = processed;
        return;
      }
      server.completedRequests.incrementAndGet();
      if (authenticationDeadline != 0 && endpoint.authenticationComplete()) {
        authenticationDeadline = 0;
        connection.setSoTimeout(idleTimeoutMillis);
      }
    }
  }

  private static int readExact(
      InputStream input,
      byte[] target,
      int offset,
      int length,
      Socket connection,
      long deadline) throws IOException {
    int read = 0;
    while (read < length) {
      if (deadline != 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new SocketTimeoutException("authentication deadline expired");
        }
        long millis = (remaining + 999_999L) / 1_000_000L;
        connection.setSoTimeout((int) Math.min(Integer.MAX_VALUE, millis));
      }
      int count = input.read(target, offset + read, length - read);
      if (count < 0) {
        return read;
      }
      read += count;
    }
    return read;
  }
}

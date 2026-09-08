package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.auth.TokenProof;
import io.riverdb.protocol.auth.TlsChannelBinding;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Opens and authenticates a reusable River client transport. */
final class RiverClientConnector {
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int READ_TIMEOUT_MILLIS = 30_000;

  private RiverClientConnector() { }

  static StatusCode connect(
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      RiverClientOpenResult result) {
    return connect("localhost", port, context, token, tokenBytes, result);
  }

  static StatusCode connect(RiverClientConfiguration configuration, RiverClientOpenResult result) {
    if (configuration == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    RiverClientConfiguration.ContextResult contextResult =
        new RiverClientConfiguration.ContextResult();
    StatusCode status = configuration.createPinnedContext(contextResult);
    if (!status.isOk()) return status;
    RiverClientConfiguration.BytesResult tokenResult =
        new RiverClientConfiguration.BytesResult();
    try {
      status = configuration.readToken(tokenResult);
      if (!status.isOk()) return status;
      return connect(configuration.host(), configuration.port(), contextResult.context,
          tokenResult.value, tokenResult.value.length, result);
    } finally {
      tokenResult.clear();
    }
  }

  private static StatusCode connect(
      String host,
      int port,
      SSLContext context,
      byte[] token,
      int tokenBytes,
      RiverClientOpenResult result) {
    if (host == null || port <= 0 || port > 65535 || context == null
        || token == null || result == null || tokenBytes < TokenProof.MINIMUM_TOKEN_BYTES
        || tokenBytes > TokenProof.MAXIMUM_TOKEN_BYTES || tokenBytes > token.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    Socket socket = null;
    byte[] proof = null;
    byte[] channelBinding = null;
    try {
      socket = context.getSocketFactory().createSocket();
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
      socket.setSoTimeout(READ_TIMEOUT_MILLIS);
      SSLSocket secure = (SSLSocket) socket;
      secure.setEnabledProtocols(new String[] {"TLSv1.3"});
      SSLParameters parameters = secure.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      secure.setSSLParameters(parameters);
      secure.startHandshake();
      channelBinding = new byte[TlsChannelBinding.BINDING_BYTES];
      StatusCode status = TlsChannelBinding.export(
          secure.getSession(), channelBinding);
      if (!status.isOk()) {
        RiverClientConnection.closeQuietly(socket);
        return status;
      }
      proof = new byte[TokenProof.PROOF_BYTES];
      RiverClientConnection connection = new RiverClientConnection(
          socket, socket.getInputStream(), socket.getOutputStream());
      status = connection.exchange(ProtocolMessageType.HELLO, null);
      if (status.isOk()) status = connection.response.status();
      if (status.isOk()) {
        status = TokenProof.compute(
            token, tokenBytes, connection.response.challengeHigh(),
            connection.response.challengeLow(), channelBinding, proof);
      }
      if (status.isOk()) {
        status = connection.exchangeBinary(
            ProtocolMessageType.AUTHENTICATE, proof, proof.length);
      }
      if (status.isOk()) status = connection.response.status();
      if (!status.isOk()) {
        connection.fail(status);
        return status;
      }
      status = result.complete(connection);
      if (!status.isOk()) connection.closeSocket();
      return status;
    } catch (IOException failure) {
      if (socket != null) RiverClientConnection.closeQuietly(socket);
      return StatusCode.IO_FAILURE;
    } finally {
      if (proof != null) Arrays.fill(proof, (byte) 0);
      if (channelBinding != null) Arrays.fill(channelBinding, (byte) 0);
    }
  }
}

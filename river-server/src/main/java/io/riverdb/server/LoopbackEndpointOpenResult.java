package io.riverdb.server;

import io.riverdb.base.error.StatusCode;

/** Caller-owned result of opening the protocol endpoint for one connection. */
final class LoopbackEndpointOpenResult {
  private SessionEndpoint endpoint;
  private long authenticationDeadline;
  private StatusCode status;

  void reset() {
    endpoint = null;
    authenticationDeadline = 0;
    status = StatusCode.OK;
  }

  void set(SessionEndpoint opened, long deadline) {
    endpoint = opened;
    authenticationDeadline = deadline;
  }

  void fail(StatusCode failure) {
    status = failure;
  }

  SessionEndpoint endpoint() { return endpoint; }
  long authenticationDeadline() { return authenticationDeadline; }
  StatusCode status() { return status; }
}

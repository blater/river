package io.riverdb.server;

import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMemoryBudget;

/** Slot-lifetime owner that leases every retained connection workspace from one envelope. */
final class ServerConnectionMemory {
  private final ProtocolMemoryBudget budget;
  @SuppressWarnings("unused")
  private final RetainedMemoryLease requestFrame;

  ServerConnectionMemory(ProtocolMemoryBudget sharedBudget) {
    budget = sharedBudget;
    requestFrame = budget.lease();
    if (!requestFrame.resize(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES).isOk()) {
      throw new IllegalArgumentException("request frame memory");
    }
  }

  RetainedMemoryLease lease() {
    return budget.lease();
  }
}

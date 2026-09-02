package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.RowResult;
import org.junit.jupiter.api.Test;

final class ProtocolMemoryBudgetTest {
  @Test
  void includesProgramFloorsAndExecutePhasePeak() {
    int connections = 2;
    ProtocolMemoryBudget budget = ProtocolMemoryBudget.forServer(connections);
    long warm = ProtocolWorkspaceRetention.warmCeilingBytes();
    long sqlFloor = ProtocolUtf8Decoder.retainedFloorBytes()
        + ParameterSet.retainedFloorBytes() * 2
        + RowResult.retainedFloorBytes() * 3;
    long perConnectionFloor = ProtocolFrameCodec.MAXIMUM_FRAME_BYTES * 2L
        + sqlFloor + warm * 2L;
    long base = connections * perConnectionFloor;
    long executeDelta = ProtocolFrameCodec.HEADER_BYTES
        + (long) ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES
        + ProtocolWorkspaceRetention.maximumProgramRequestRetainedBytes() - warm
        + ProtocolWorkspaceRetention.maximumProgramResultRetainedBytes() - warm
        + ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
        - ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    assertTrue(budget.maximumBytes() >= base + executeDelta);
  }

  @Test
  void exactEnvelopeAdmitsItsCalculatedPeakAndOneByteLessRejects() {
    long maximum = ProtocolMemoryBudget.forServer(2).maximumBytes();
    ProtocolMemoryBudget exact = new ProtocolMemoryBudget(maximum);
    RetainedMemoryLease exactLease = exact.lease();
    assertEquals(StatusCode.OK, exactLease.resize(maximum));

    ProtocolMemoryBudget shortBudget = new ProtocolMemoryBudget(maximum - 1);
    RetainedMemoryLease shortLease = shortBudget.lease();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, shortLease.resize(maximum));
    assertEquals(0, shortBudget.retainedBytes());
  }
}

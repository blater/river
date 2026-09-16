package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;

/** Edge columns owned by a diagnostic snapshot. */
public final class LockDeadlockSnapshotEdges {
  private final int edgeCapacity;
  final long[] waiterIds;
  final long[] waiterGenerations;
  final long[] waiterStartOrders;
  final long[] waiterTags;
  final long[] waiterStepTags;
  final long[] blockerIds;
  final long[] blockerGenerations;
  final long[] blockerStartOrders;
  final long[] blockerTags;
  final long[] blockerStepTags;
  final long[] resourceNamespaces;
  final long[] resourceLowerKeys;
  final long[] resourceUpperNamespaces;
  final long[] resourceUpperKeys;
  final long[] resourceDigests;
  final long[] blockingResourceDigests;
  final long[] waiterQueueOrders;
  final long[] blockerQueueOrders;
  final byte[] kinds;
  final byte[] preconditions;
  final byte[] scopes;
  final byte[] requestedModes;
  final byte[] heldModes;
  final byte[] blockerRequestedModes;
  final byte[] waiterQueueKinds;
  final byte[] blockerQueueKinds;
  final byte[] predicateResults;

  LockDeadlockSnapshotEdges(int capacity) {
    edgeCapacity = capacity;
    waiterIds = new long[capacity];
    waiterGenerations = new long[capacity];
    waiterStartOrders = new long[capacity];
    waiterTags = new long[capacity];
    waiterStepTags = new long[capacity];
    blockerIds = new long[capacity];
    blockerGenerations = new long[capacity];
    blockerStartOrders = new long[capacity];
    blockerTags = new long[capacity];
    blockerStepTags = new long[capacity];
    resourceNamespaces = new long[capacity];
    resourceLowerKeys = new long[capacity];
    resourceUpperNamespaces = new long[capacity];
    resourceUpperKeys = new long[capacity];
    resourceDigests = new long[capacity];
    blockingResourceDigests = new long[capacity];
    waiterQueueOrders = new long[capacity];
    blockerQueueOrders = new long[capacity];
    kinds = new byte[capacity];
    preconditions = new byte[capacity];
    scopes = new byte[capacity];
    requestedModes = new byte[capacity];
    heldModes = new byte[capacity];
    blockerRequestedModes = new byte[capacity];
    waiterQueueKinds = new byte[capacity];
    blockerQueueKinds = new byte[capacity];
    predicateResults = new byte[capacity];
  }

  public long waiterTransactionIdAt(int index) { return waiterIds[checked(index)]; }
  public long waiterTransactionGenerationAt(int index) { return waiterGenerations[checked(index)]; }
  public long waiterStartOrderAt(int index) { return waiterStartOrders[checked(index)]; }
  public long waiterDiagnosticTagAt(int index) { return waiterTags[checked(index)]; }
  public long waiterDiagnosticStepTagAt(int index) { return waiterStepTags[checked(index)]; }
  public long blockerTransactionIdAt(int index) { return blockerIds[checked(index)]; }
  public long blockerTransactionGenerationAt(int index) { return blockerGenerations[checked(index)]; }
  public long blockerStartOrderAt(int index) { return blockerStartOrders[checked(index)]; }
  public long blockerDiagnosticTagAt(int index) { return blockerTags[checked(index)]; }
  public long blockerDiagnosticStepTagAt(int index) { return blockerStepTags[checked(index)]; }
  public LockScope resourceScopeAt(int index) {
    return LockExactTable.LOCK_SCOPES[Byte.toUnsignedInt(scopes[checked(index)])];
  }
  public long resourceNamespaceAt(int index) { return resourceNamespaces[checked(index)]; }
  public long resourceLowerKeyAt(int index) { return resourceLowerKeys[checked(index)]; }
  public long resourceUpperNamespaceAt(int index) {
    return resourceUpperNamespaces[checked(index)];
  }
  public long resourceUpperKeyAt(int index) { return resourceUpperKeys[checked(index)]; }
  public long resourceDigestAt(int index) { return resourceDigests[checked(index)]; }
  public long blockingResourceDigestAt(int index) {
    return blockingResourceDigests[checked(index)];
  }
  public LockMode requestedModeAt(int index) {
    return LockExactTable.LOCK_MODES[Byte.toUnsignedInt(requestedModes[checked(index)])];
  }
  public LockMode heldModeAt(int index) {
    int ordinal = Byte.toUnsignedInt(heldModes[checked(index)]) - 1;
    return ordinal < 0 ? null : LockExactTable.LOCK_MODES[ordinal];
  }
  public LockMode blockerRequestedModeAt(int index) {
    int ordinal = Byte.toUnsignedInt(blockerRequestedModes[checked(index)]) - 1;
    return ordinal < 0 ? null : LockExactTable.LOCK_MODES[ordinal];
  }
  public LockQueueKind waiterQueueKindAt(int index) {
    return LockDeadlockDiagnostics.QUEUE_KINDS[
        Byte.toUnsignedInt(waiterQueueKinds[checked(index)])];
  }
  public LockQueueKind blockerQueueKindAt(int index) {
    return LockDeadlockDiagnostics.QUEUE_KINDS[
        Byte.toUnsignedInt(blockerQueueKinds[checked(index)])];
  }
  public long waiterQueueOrderAt(int index) { return waiterQueueOrders[checked(index)]; }
  public long blockerQueueOrderAt(int index) { return blockerQueueOrders[checked(index)]; }
  public LockDeadlockEdgeKind kindAt(int index) {
    return LockDeadlockDiagnostics.EDGE_KINDS[Byte.toUnsignedInt(kinds[checked(index)])];
  }
  public LockGrantPrecondition preconditionAt(int index) {
    return LockDeadlockDiagnostics.PRECONDITIONS[
        Byte.toUnsignedInt(preconditions[checked(index)])];
  }
  public boolean grantPredicateResultAt(int index) {
    return predicateResults[checked(index)] != 0;
  }

  void copyFrom(LockDeadlockSnapshotEdges source) {
    copy(source.waiterIds, waiterIds);
    copy(source.waiterGenerations, waiterGenerations);
    copy(source.waiterStartOrders, waiterStartOrders);
    copy(source.waiterTags, waiterTags);
    copy(source.waiterStepTags, waiterStepTags);
    copy(source.blockerIds, blockerIds);
    copy(source.blockerGenerations, blockerGenerations);
    copy(source.blockerStartOrders, blockerStartOrders);
    copy(source.blockerTags, blockerTags);
    copy(source.blockerStepTags, blockerStepTags);
    copy(source.resourceNamespaces, resourceNamespaces);
    copy(source.resourceLowerKeys, resourceLowerKeys);
    copy(source.resourceUpperNamespaces, resourceUpperNamespaces);
    copy(source.resourceUpperKeys, resourceUpperKeys);
    copy(source.resourceDigests, resourceDigests);
    copy(source.blockingResourceDigests, blockingResourceDigests);
    copy(source.waiterQueueOrders, waiterQueueOrders);
    copy(source.blockerQueueOrders, blockerQueueOrders);
    copy(source.kinds, kinds);
    copy(source.preconditions, preconditions);
    copy(source.scopes, scopes);
    copy(source.requestedModes, requestedModes);
    copy(source.heldModes, heldModes);
    copy(source.blockerRequestedModes, blockerRequestedModes);
    copy(source.waiterQueueKinds, waiterQueueKinds);
    copy(source.blockerQueueKinds, blockerQueueKinds);
    copy(source.predicateResults, predicateResults);
  }

  private static void copy(long[] source, long[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private static void copy(byte[] source, byte[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private int checked(int index) {
    if (index < 0 || index >= edgeCapacity) throw new IndexOutOfBoundsException(index);
    return index;
  }
}

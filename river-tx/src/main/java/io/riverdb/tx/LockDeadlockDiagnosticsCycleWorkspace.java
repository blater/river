package io.riverdb.tx;

/** Reusable cycle capture columns kept beside the diagnostics accumulator. */
final class LockDeadlockDiagnosticsCycleWorkspace {
  final long[] requests;
  final long[] blockers;
  final long[] blockingResources;
  final long[] shape;
  final long[] guardShape;
  final byte[] kinds;
  final byte[] preconditions;

  LockDeadlockDiagnosticsCycleWorkspace(int capacity) {
    requests = new long[capacity];
    blockers = new long[capacity];
    blockingResources = new long[capacity];
    shape = new long[capacity];
    guardShape = new long[capacity];
    kinds = new byte[capacity];
    preconditions = new byte[capacity];
  }
}

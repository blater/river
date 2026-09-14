package io.riverdb.platform.file.nio;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

/**
 * Opt-in per-handle sampler for writes pending at the FileChannel boundary.
 * Enabled handles allocate immutable snapshots per operation; disabled handles allocate nothing here.
 */
final class PendingFileWriteDiagnostics {
  private static final String ENABLE_PROPERTY = "river.diagnostics.pendingFileWrites";
  private static final int UNTRACKED = 0;
  private static final int TRACKED_UNCAPTURED = 1;
  private static final int TRACKED_CAPTURED = 2;

  private PendingFileWriteDiagnostics() { }

  enum OperationKind {
    POSITIONAL_WRITE,
    RESIZE_GROWTH
  }

  static Entry register(Path path) {
    if (!Boolean.getBoolean(ENABLE_PROPERTY)) return null;
    Entry entry = new Entry(Entry.NEXT_HANDLE_ID.incrementAndGet(), path.toString());
    try {
      entry.register();
      return entry;
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static final class Entry {
    private static final AtomicLong NEXT_HANDLE_ID = new AtomicLong();

    private final long handleId;
    private final String path;
    private final AtomicReference<State> state =
        new AtomicReference<>(new State(false, 0, null, 0));
    private final Runnable sampler = this::sample;
    // Only the constructor and the single retired-to-idle transition change registration.
    private boolean registered;

    private Entry(long diagnosticHandleId, String filePath) {
      handleId = diagnosticHandleId;
      path = filePath;
    }

    private void register() {
      FlightRecorder.addPeriodicEvent(PendingFileWriteEvent.class, sampler);
      registered = true;
    }

    int begin(
        OperationKind operationKind, long position, long requestedRemainingBytes) {
      Thread writer = Thread.currentThread();
      long writerThreadId = writer.threadId();
      String writerThreadName = writer.getName();
      while (true) {
        State current = state.get();
        if (current.retired()) return UNTRACKED;
        Invocation captured = current.captured();
        int tracking = TRACKED_UNCAPTURED;
        long skipped = current.skippedOperations();
        if (captured == null) {
          captured = new Invocation(
              operationKind, position, requestedRemainingBytes, writerThreadId, writerThreadName,
              System.nanoTime());
          tracking = TRACKED_CAPTURED;
        } else {
          skipped++;
        }
        State next = new State(
            false, current.inFlightOperations() + 1, captured, skipped);
        // The returned token owns either this invocation's sample or only its in-flight count.
        if (state.compareAndSet(current, next)) return tracking;
      }
    }

    void end(int tracking) {
      if (tracking == UNTRACKED) return;
      while (true) {
        State current = state.get();
        Invocation captured = tracking == TRACKED_CAPTURED ? null : current.captured();
        State next = new State(
            current.retired(), current.inFlightOperations() - 1,
            captured, current.skippedOperations());
        if (state.compareAndSet(current, next)) {
          if (next.retired() && next.inFlightOperations() == 0) unregister();
          return;
        }
      }
    }

    void retire() {
      // Call only after FileChannel.close; later writes are rejected before native entry.
      while (true) {
        State current = state.get();
        if (current.retired()) return;
        State next = new State(
            true, current.inFlightOperations(), current.captured(),
            current.skippedOperations());
        if (state.compareAndSet(current, next)) {
          if (next.inFlightOperations() == 0) unregister();
          return;
        }
      }
    }

    private void unregister() {
      if (!registered) return;
      registered = false;
      try {
        FlightRecorder.removePeriodicEvent(sampler);
      } catch (RuntimeException ignored) {
        // Diagnostics must not change a terminal file status.
      }
    }

    private void sample() {
      State current = state.get();
      Invocation captured = current.captured();
      PendingFileWriteEvent event = new PendingFileWriteEvent();
      event.handleId = handleId;
      event.path = path;
      event.active = current.inFlightOperations() != 0;
      event.sampleAvailable = captured != null;
      event.currentUncapturedOperationCount =
          current.inFlightOperations() - (captured == null ? 0 : 1);
      event.cumulativeSkippedOperationCount = current.skippedOperations();
      if (captured != null) {
        event.operationKind = captured.operationKind().name();
        event.position = captured.position();
        event.requestedRemainingBytes = captured.requestedRemainingBytes();
        event.writerThreadId = captured.writerThreadId();
        event.writerThreadName = captured.writerThreadName();
        event.writeStartMonotonicNanos = captured.writeStartMonotonicNanos();
      }
      event.commit();
    }
  }

  // Each CAS publishes a coherent sample and preserves the captured invocation's ownership.
  private record State(
      boolean retired,
      long inFlightOperations,
      Invocation captured,
      long skippedOperations) { }

  private record Invocation(
      OperationKind operationKind,
      long position,
      long requestedRemainingBytes,
      long writerThreadId,
      String writerThreadName,
      long writeStartMonotonicNanos) { }

  @Name("river.PendingFileWrite")
  @Label("River Pending File Write")
  @Description("Periodic sample of a positional River write pending at the FileChannel boundary")
  @Category({"River", "Storage"})
  @Enabled(true)
  @Period("100 ms")
  @StackTrace(false)
  public static final class PendingFileWriteEvent extends Event {
    @Label("Path") public String path;
    @Label("Diagnostic Handle ID") public long handleId;
    @Label("Operation Kind") public String operationKind;
    @Label("Position") public long position;
    @Label("Requested Remaining Bytes") public long requestedRemainingBytes;
    @Label("Writer Thread ID") public long writerThreadId;
    @Label("Writer Thread Name") public String writerThreadName;
    @Label("Write Start Monotonic Nanoseconds") public long writeStartMonotonicNanos;
    @Label("Active") public boolean active;
    @Label("Sample Available") public boolean sampleAvailable;
    @Label("Current Uncaptured Operation Count") public long currentUncapturedOperationCount;
    @Label("Cumulative Skipped Operation Count") public long cumulativeSkippedOperationCount;
  }
}

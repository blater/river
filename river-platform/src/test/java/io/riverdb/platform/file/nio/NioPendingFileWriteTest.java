package io.riverdb.platform.file.nio;

import static io.riverdb.platform.file.nio.PendingFileWriteDiagnostics.OperationKind.POSITIONAL_WRITE;
import static io.riverdb.platform.file.nio.PendingFileWriteDiagnostics.OperationKind.RESIZE_GROWTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NioPendingFileWriteTest {
  private static final String ENABLE_PROPERTY = "river.diagnostics.pendingFileWrites";
  private static final String EVENT_CLASS =
      "io.riverdb.platform.file.nio.PendingFileWriteDiagnostics$PendingFileWriteEvent";
  private static final String EVENT_NAME = "river.PendingFileWrite";
  private static final long GATE_SECONDS = 30;
  private String previousProperty;

  @BeforeEach
  void enablePerHandleDiagnostics() {
    previousProperty = System.getProperty(ENABLE_PROPERTY);
    System.setProperty(ENABLE_PROPERTY, "true");
  }

  @AfterEach
  void restoreDiagnosticsProperty() {
    if (previousProperty == null) System.clearProperty(ENABLE_PROPERTY);
    else System.setProperty(ENABLE_PROPERTY, previousProperty);
  }

  @Test
  void capturesOneSameHandleWriteAndCountsOverlappingWritesPerHandle(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "pending.dat");
    NioDurableFile samePathHandle = reopen(fixture.directory(), fixture.path().getFileName().toString());
    WriteGate captured = new WriteGate(128);
    WriteGate skipped = new WriteGate(256);
    WriteGate otherHandle = new WriteGate(512);
    ExecutorService capturedWriter = writer("pending-write-captured");
    ExecutorService skippedWriter = writer("pending-write-skipped");
    ExecutorService otherHandleWriter = writer("pending-write-other-handle");
    try {
      installChannel(fixture.file(), captured, skipped);
      installChannel(samePathHandle, otherHandle);
      try (Recording recording = startRecording("pending-write-overlap")) {
        long firstHandleId = diagnosticsHandleId(fixture.file());
        long secondHandleId = diagnosticsHandleId(samePathHandle);
        Object firstDiagnostics = diagnosticsEntry(fixture.file());
        Object secondDiagnostics = diagnosticsEntry(samePathHandle);
        long capturedSubmittedNanos = System.nanoTime();
        Future<WriteOutcome> first = writeAsync(
            capturedWriter, fixture.file(), captured.position, range(8, 2, 6));
        await(captured.entered, "first write did not reach its gate");
        Future<WriteOutcome> overlap = writeAsync(
            skippedWriter, fixture.file(), skipped.position, range(6, 1, 4));
        await(skipped.entered, "overlapping write did not reach its gate");
        long otherHandleSubmittedNanos = System.nanoTime();
        Future<WriteOutcome> independent = writeAsync(
            otherHandleWriter, samePathHandle, otherHandle.position, range(9, 2, 7));
        await(otherHandle.entered, "same-path second handle did not reach its gate");
        pauseForSamples();

        Path jfrDump = root.resolve("pending-write-live.jfr");
        Path jcmdLog = root.resolve("pending-write-jcmd.log");
        int jcmdExit = dumpWithJcmd(recording, jfrDump, jcmdLog);
        List<PendingEvent> liveEvents = readEvents(jfrDump);
        PendingEvent liveCaptured = requireEvent(
            liveEvents, event -> event.path().equals(fixture.path().toString())
                && event.handleId() == firstHandleId
                && event.active() && event.sampleAvailable()
                && event.currentUncapturedOperationCount() == 1
                && event.cumulativeSkippedOperationCount() == 1);
        assertEvent(liveCaptured, POSITIONAL_WRITE, fixture.path(), 128, 4, captured,
            capturedSubmittedNanos);
        assertEquals(1, liveCaptured.currentUncapturedOperationCount());
        assertEquals(1, liveCaptured.cumulativeSkippedOperationCount());
        PendingEvent liveIndependent = requireEvent(
            liveEvents, event -> event.path().equals(fixture.path().toString())
                && event.handleId() == secondHandleId
                && event.active() && event.sampleAvailable());
        assertEvent(liveIndependent, POSITIONAL_WRITE, fixture.path(), 512, 5, otherHandle,
            otherHandleSubmittedNanos);
        assertNotEquals(liveCaptured.handleId(), liveIndependent.handleId());
        System.out.printf(
            "pending-write-jfr pid=%d jcmdExit=%d path=%s handleId=%d position=%d bytes=%d "
                + "writerId=%d startNanos=%d currentUncaptured=%d cumulativeSkipped=%d%n",
            ProcessHandle.current().pid(), jcmdExit, liveCaptured.path(), liveCaptured.handleId(),
            liveCaptured.position(), liveCaptured.requestedRemainingBytes(),
            liveCaptured.writerThreadId(), liveCaptured.writeStartMonotonicNanos(),
            liveCaptured.currentUncapturedOperationCount(),
            liveCaptured.cumulativeSkippedOperationCount());

        captured.release.countDown();
        assertEquals(new WriteOutcome(StatusCode.OK, 4), first.get(5, TimeUnit.SECONDS));
        pauseForSamples();
        skipped.release.countDown();
        otherHandle.release.countDown();
        assertEquals(new WriteOutcome(StatusCode.OK, 3), overlap.get(5, TimeUnit.SECONDS));
        assertEquals(new WriteOutcome(StatusCode.OK, 5), independent.get(5, TimeUnit.SECONDS));
        pauseForSamples();

        assertClose(fixture.file().close());
        assertClose(samePathHandle.close());
        assertFalse(isSamplerRegistered(firstDiagnostics));
        assertFalse(isSamplerRegistered(secondDiagnostics));
        assertFalse(FlightRecorder.removePeriodicEvent(sampler(firstDiagnostics)));
        assertFalse(FlightRecorder.removePeriodicEvent(sampler(secondDiagnostics)));
        Path finalDump = root.resolve("pending-write-overlap-complete.jfr");
        recording.stop();
        recording.dump(finalDump);
        List<PendingEvent> events = eventsForPath(finalDump, fixture.path());
        requireEvent(events, event ->
            event.handleId() == firstHandleId && event.active() && !event.sampleAvailable()
                && event.currentUncapturedOperationCount() == 1
                && event.cumulativeSkippedOperationCount() == 1);
        requireEvent(events, event -> event.handleId() == firstHandleId && !event.active()
            && !event.sampleAvailable() && event.currentUncapturedOperationCount() == 0
            && event.cumulativeSkippedOperationCount() == 1);
      }
    } finally {
      captured.release.countDown();
      skipped.release.countDown();
      otherHandle.release.countDown();
      stop(capturedWriter, skippedWriter, otherHandleWriter);
      assertClose(fixture.file().close());
      assertClose(samePathHandle.close());
      assertClose(fixture.directory().close());
    }
  }

  @Test
  void capturesResizeAndFailedWritesAndAllocatesNoOwnerWhenDisabled(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "resize.dat");
    WriteGate resize = new WriteGate(4095);
    WriteGate failed = new WriteGate(700);
    ExecutorService writer = writer("pending-write-resize-failure");
    NioDurableFile disabledFile = null;
    WriteGate disabledGate = null;
    try {
      installChannel(fixture.file(), resize, failed);
      try (Recording recording = startRecording("pending-write-resize-failure")) {
        long resizeSubmittedNanos = System.nanoTime();
        Future<StatusCode> resizeResult = writer.submit(() -> fixture.file().truncate(4096));
        await(resize.entered, "resize extension did not reach its gate");
        pauseForSamples();
        resize.release.countDown();
        assertEquals(StatusCode.OK, resizeResult.get(5, TimeUnit.SECONDS));
        FileSizeResult size = new FileSizeResult();
        assertEquals(StatusCode.OK, fixture.file().size(size));
        assertEquals(4096, size.sizeBytes());

        failed.fail = true;
        long failedSubmittedNanos = System.nanoTime();
        Future<WriteOutcome> failedWrite =
            writeAsync(writer, fixture.file(), failed.position, range(6, 1, 4));
        await(failed.entered, "failing write did not reach its gate");
        pauseForSamples();
        failed.release.countDown();
        assertEquals(new WriteOutcome(StatusCode.IO_FAILURE, 0),
            failedWrite.get(5, TimeUnit.SECONDS));
        Instant failedCompletedAt = Instant.now();
        pauseForSamples();

        System.setProperty(ENABLE_PROPERTY, "false");
        disabledFile = create(fixture.directory(), "disabled.dat");
        Path disabledPath = fixture.path().getParent().resolve("disabled.dat");
        assertNull(diagnosticsEntry(disabledFile));
        disabledGate = new WriteGate(900);
        installChannel(disabledFile, disabledGate);
        Future<WriteOutcome> disabledWrite = writeAsync(writer, disabledFile,
            disabledGate.position, range(5, 1, 4));
        await(disabledGate.entered, "disabled write did not reach its gate");
        pauseForSamples();
        disabledGate.release.countDown();
        assertEquals(new WriteOutcome(StatusCode.OK, 3),
            disabledWrite.get(5, TimeUnit.SECONDS));

        Path dump = root.resolve("pending-write-resize-failure.jfr");
        recording.stop();
        recording.dump(dump);
        List<PendingEvent> events = readEvents(dump);
        PendingEvent resizeEvent = requireEvent(events, event -> event.path().equals(fixture.path().toString())
            && event.sampleAvailable() && event.position() == 4095
            && event.requestedRemainingBytes() == 1
            && event.operationKind().equals(RESIZE_GROWTH.name())
            && event.writerThreadName().equals("pending-write-resize-failure"));
        PendingEvent failedEvent = requireEvent(events, event -> event.path().equals(fixture.path().toString())
            && event.sampleAvailable() && event.position() == 700
            && event.requestedRemainingBytes() == 3
            && event.operationKind().equals(POSITIONAL_WRITE.name())
            && event.writerThreadName().equals("pending-write-resize-failure"));
        assertEvent(resizeEvent, RESIZE_GROWTH, fixture.path(), 4095, 1, resize,
            resizeSubmittedNanos);
        assertEvent(failedEvent, POSITIONAL_WRITE, fixture.path(), 700, 3, failed,
            failedSubmittedNanos);
        requireEvent(events, event -> event.path().equals(fixture.path().toString())
            && event.startTime().isAfter(failedCompletedAt)
            && !event.active() && !event.sampleAvailable());
        assertTrue(events.stream().noneMatch(event ->
            event.path().equals(disabledPath.toString())));
      }
    } finally {
      resize.release.countDown();
      failed.release.countDown();
      if (disabledGate != null) disabledGate.release.countDown();
      stop(writer);
      if (disabledFile != null) assertClose(disabledFile.close());
      assertClose(fixture.file().close());
      assertClose(fixture.directory().close());
    }
  }

  @Test
  void keepsSamplerRegisteredUntilPendingWriteEndsAfterDirectoryClose(@TempDir Path root)
      throws Exception {
    Fixture fixture = open(root, "close-pending.dat");
    WriteGate gate = new WriteGate(300);
    ExecutorService writer = writer("pending-write-close");
    try {
      installChannel(fixture.file(), gate);
      Object diagnostics = diagnosticsEntry(fixture.file());
      assertNotNull(diagnostics);
      try (Recording recording = startRecording("pending-write-close")) {
        Future<WriteOutcome> pending =
            writeAsync(writer, fixture.file(), gate.position, range(7, 2, 6));
        await(gate.entered, "pending write did not reach its gate");
        pauseForSamples();
        assertTrue(isSamplerRegistered(diagnostics));
        assertEquals(StatusCode.OK, fixture.directory().close());
        Instant closedAt = Instant.now();
        assertTrue(isSamplerRegistered(diagnostics),
            "close must retain the periodic sampler while its write is in flight");
        pauseForSamples();
        gate.release.countDown();
        assertEquals(new WriteOutcome(StatusCode.CLOSED, 0), pending.get(5, TimeUnit.SECONDS));
        assertFalse(isSamplerRegistered(diagnostics),
            "the final pending write must unregister the retired sampler");
        assertFalse(FlightRecorder.removePeriodicEvent(sampler(diagnostics)));
        Path dump = root.resolve("pending-write-closed-handle.jfr");
        recording.stop();
        recording.dump(dump);
        List<PendingEvent> events = eventsForPath(dump, fixture.path());
        assertTrue(events.stream().anyMatch(event -> event.startTime().isAfter(closedAt)
            && event.active()
            && event.sampleAvailable() && event.position() == gate.position));
      }
    } finally {
      gate.release.countDown();
      stop(writer);
      assertClose(fixture.file().close());
      assertClose(fixture.directory().close());
    }
  }

  private static Fixture open(Path root, String name) throws Exception {
    Path directoryPath = root.toRealPath();
    NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        directoryPath, new FatalStateFence(), new NioIoCounters(), 8, opened));
    NioDurableDirectory directory = opened.directory();
    NioDurableFile file = create(directory, name);
    return new Fixture(directory, file, directoryPath.resolve(name));
  }

  private static NioDurableFile create(NioDurableDirectory directory, String name) {
    DirectoryOperationResult result = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile(name, FileIoMode.POSITIONAL, result));
    return (NioDurableFile) result.file();
  }

  private static NioDurableFile reopen(NioDurableDirectory directory, String name) {
    DirectoryOperationResult result = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.reopen(name, FileIoMode.POSITIONAL, result));
    return (NioDurableFile) result.file();
  }

  private static TestFileChannel installChannel(NioDurableFile file, WriteGate... gates)
      throws Exception {
    Field channelField = NioDurableFile.class.getDeclaredField("channel");
    channelField.setAccessible(true);
    TestFileChannel channel = new TestFileChannel((FileChannel) channelField.get(file), gates);
    channelField.set(file, channel);
    return channel;
  }

  private static Object diagnosticsEntry(NioDurableFile file) throws Exception {
    Field field = NioDurableFile.class.getDeclaredField("pendingWriteDiagnostics");
    field.setAccessible(true);
    return field.get(file);
  }

  private static long diagnosticsHandleId(NioDurableFile file) throws Exception {
    Object entry = diagnosticsEntry(file);
    Field field = entry.getClass().getDeclaredField("handleId");
    field.setAccessible(true);
    return field.getLong(entry);
  }

  private static boolean isSamplerRegistered(Object entry) throws Exception {
    Field field = entry.getClass().getDeclaredField("registered");
    field.setAccessible(true);
    return field.getBoolean(entry);
  }

  private static Runnable sampler(Object entry) throws Exception {
    Field field = entry.getClass().getDeclaredField("sampler");
    field.setAccessible(true);
    return (Runnable) field.get(entry);
  }

  private static Recording startRecording(String name) throws Exception {
    Class.forName(EVENT_CLASS);
    Recording recording = new Recording(Configuration.getConfiguration("profile"));
    recording.setName(name);
    recording.setToDisk(true);
    recording.start();
    return recording;
  }

  private static int dumpWithJcmd(Recording recording, Path dump, Path log) throws Exception {
    String jcmd = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "jcmd.exe" : "jcmd").toString();
    Process process = new ProcessBuilder(
        jcmd, Long.toString(ProcessHandle.current().pid()), "JFR.dump",
        "name=" + recording.getName(), "filename=" + dump)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    boolean exited;
    try {
      exited = process.waitFor(15, TimeUnit.SECONDS);
    } finally {
      finish(process);
    }
    String output = java.nio.file.Files.readString(log, StandardCharsets.UTF_8);
    assertTrue(exited, "jcmd JFR.dump timed out: " + output);
    assertEquals(0, process.exitValue(), output);
    return process.exitValue();
  }

  private static void finish(Process process) throws InterruptedException {
    if (!process.isAlive()) return;
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "jcmd process could not be reaped");
    }
  }

  private static List<PendingEvent> readEvents(Path dump) throws Exception {
    List<PendingEvent> events = new ArrayList<>();
    try (RecordingFile file = new RecordingFile(dump)) {
      while (file.hasMoreEvents()) {
        RecordedEvent event = file.readEvent();
        if (EVENT_NAME.equals(event.getEventType().getName())) events.add(pendingEvent(event));
      }
    }
    return events;
  }

  private static List<PendingEvent> eventsForPath(Path dump, Path path) throws Exception {
    List<PendingEvent> matches = new ArrayList<>();
    for (PendingEvent event : readEvents(dump)) {
      if (event.path().equals(path.toString())) matches.add(event);
    }
    return matches;
  }

  private static PendingEvent pendingEvent(RecordedEvent event) {
    return new PendingEvent(
        event.getStartTime(), event.getString("path"), event.getLong("handleId"),
        event.getString("operationKind"),
        event.getLong("position"), event.getLong("requestedRemainingBytes"),
        event.getLong("writerThreadId"), event.getString("writerThreadName"),
        event.getLong("writeStartMonotonicNanos"), event.getBoolean("active"),
        event.getBoolean("sampleAvailable"), event.getLong("currentUncapturedOperationCount"),
        event.getLong("cumulativeSkippedOperationCount"));
  }

  private static PendingEvent requireEvent(
      List<PendingEvent> events, Predicate<PendingEvent> predicate) {
    for (PendingEvent event : events) {
      if (predicate.test(event)) return event;
    }
    throw new AssertionError("required pending-write event missing from " + events);
  }

  private static void assertEvent(
      PendingEvent event, PendingFileWriteDiagnostics.OperationKind operationKind,
      Path path, long position, long bytes, WriteGate gate,
      long submittedNanos) {
    assertEquals(operationKind.name(), event.operationKind());
    assertEquals(path.toString(), event.path());
    assertTrue(event.handleId() > 0);
    assertEquals(position, event.position());
    assertEquals(bytes, event.requestedRemainingBytes());
    assertEquals(bytes, gate.requestedBytes);
    assertEquals(gate.writerThreadId, event.writerThreadId());
    assertEquals(gate.writerThreadName, event.writerThreadName());
    assertTrue(event.writeStartMonotonicNanos() >= submittedNanos);
    assertTrue(event.writeStartMonotonicNanos() <= gate.enteredNanos);
  }

  private static void pauseForSamples() throws InterruptedException {
    Thread.sleep(250);
  }

  private static void await(CountDownLatch latch, String message) throws InterruptedException {
    assertTrue(latch.await(5, TimeUnit.SECONDS), message);
  }

  private static ByteBuffer range(int capacity, int position, int limit) {
    ByteBuffer buffer = ByteBuffer.allocate(capacity);
    buffer.position(position);
    buffer.limit(limit);
    return buffer;
  }

  private static Future<WriteOutcome> writeAsync(
      ExecutorService executor, NioDurableFile file, long position, ByteBuffer bytes) {
    return executor.submit(() -> {
      IoResult result = new IoResult();
      StatusCode status = file.write(position, bytes, result);
      return new WriteOutcome(status, result.bytesTransferred());
    });
  }

  private static ExecutorService writer(String name) {
    return Executors.newSingleThreadExecutor(Thread.ofVirtual().name(name).factory());
  }

  private static void stop(ExecutorService... executors) throws InterruptedException {
    for (ExecutorService executor : executors) executor.shutdownNow();
    for (ExecutorService executor : executors) {
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "write worker did not stop");
    }
  }

  private static void assertClose(StatusCode status) {
    assertTrue(status == StatusCode.OK || status == StatusCode.CLOSED,
        "unexpected close status: " + status);
  }

  private record Fixture(NioDurableDirectory directory, NioDurableFile file, Path path) { }

  private record WriteOutcome(StatusCode status, long bytesTransferred) { }

  private record PendingEvent(
      Instant startTime,
      String path,
      long handleId,
      String operationKind,
      long position,
      long requestedRemainingBytes,
      long writerThreadId,
      String writerThreadName,
      long writeStartMonotonicNanos,
      boolean active,
      boolean sampleAvailable,
      long currentUncapturedOperationCount,
      long cumulativeSkippedOperationCount) { }

  private static final class WriteGate {
    private final long position;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean fail;
    private volatile int requestedBytes;
    private volatile long writerThreadId;
    private volatile String writerThreadName;
    private volatile long enteredNanos;

    private WriteGate(long offset) {
      position = offset;
    }
  }

  private static final class TestFileChannel extends FileChannel {
    private final FileChannel delegate;
    private final Map<Long, WriteGate> gates = new HashMap<>();

    private TestFileChannel(FileChannel file, WriteGate... writeGates) {
      delegate = file;
      for (WriteGate gate : writeGates) gates.put(gate.position, gate);
    }

    @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
    @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
      return delegate.read(dsts, offset, length);
    }
    @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
    @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
      return delegate.write(srcs, offset, length);
    }
    @Override public long position() throws IOException { return delegate.position(); }
    @Override public FileChannel position(long position) throws IOException {
      delegate.position(position);
      return this;
    }
    @Override public long size() throws IOException { return delegate.size(); }
    @Override public FileChannel truncate(long size) throws IOException {
      delegate.truncate(size);
      return this;
    }
    @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
    @Override public long transferTo(long position, long count, WritableByteChannel target)
        throws IOException {
      return delegate.transferTo(position, count, target);
    }
    @Override public long transferFrom(ReadableByteChannel src, long position, long count)
        throws IOException {
      return delegate.transferFrom(src, position, count);
    }
    @Override public int read(ByteBuffer dst, long position) throws IOException {
      return delegate.read(dst, position);
    }
    @Override public int write(ByteBuffer src, long position) throws IOException {
      WriteGate gate = gates.get(position);
      if (gate != null) {
        gate.requestedBytes = src.remaining();
        gate.writerThreadId = Thread.currentThread().threadId();
        gate.writerThreadName = Thread.currentThread().getName();
        gate.enteredNanos = System.nanoTime();
        gate.entered.countDown();
        try {
          if (!gate.release.await(GATE_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("pending write gate timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("pending write gate interrupted", interrupted);
        }
        if (gate.fail) throw new IOException("injected pending write failure");
      }
      return delegate.write(src, position);
    }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size)
        throws IOException {
      return delegate.map(mode, position, size);
    }
    @Override public FileLock lock(long position, long size, boolean shared) throws IOException {
      return delegate.lock(position, size, shared);
    }
    @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
      return delegate.tryLock(position, size, shared);
    }
    @Override protected void implCloseChannel() throws IOException { delegate.close(); }
  }
}

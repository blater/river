package io.riverdb.platform.file.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.IoResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class NioPersistedFileWriteTest {
  private static final Pattern NATIVE_THREAD_ID =
      Pattern.compile("\\\"nativeThreadId\\\":\\\"([0-9]+)\\\"");
  private String previousProperty;
  private String previousLibraryProperty;
  private boolean libraryPropertyChanged;

  @AfterEach
  void restoreDiagnostics() {
    PersistedFileWriteDiagnostics.clearTestSink();
    PersistedFileWriteNative.clearTestInvoker();
    if (previousProperty == null) System.clearProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY);
    else System.setProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY, previousProperty);
    if (libraryPropertyChanged) {
      if (previousLibraryProperty == null) {
        System.clearProperty(PersistedFileWriteNative.LIBRARY_PROPERTY);
      } else {
        System.setProperty(PersistedFileWriteNative.LIBRARY_PROPERTY, previousLibraryProperty);
      }
    }
  }

  @Test
  void persistsBeginBeforeRealWriteAndReturnAfterIt(@TempDir Path root) throws Exception {
    Path target = root.resolve("page.dat");
    RecordingSink sink = enable(target);
    Fixture fixture = open(root, target.getFileName().toString());
    try {
      IoResult result = new IoResult();
      assertEquals(StatusCode.OK, fixture.file().write(0, bytes(4), result));
      assertEquals(4, result.bytesTransferred());
      assertEquals(4, Files.size(target));

      assertEquals(List.of("BEGIN", "RETURN"), sink.events());
      assertEquals(List.of(0L, 4L), sink.targetSizes);
      PersistedFileWriteDiagnostics.Record begin = sink.records.get(0);
      PersistedFileWriteDiagnostics.Record returned = sink.records.get(1);
      PersistedFileWriteDiagnostics.Invocation invocation = begin.invocation();
      assertEquals(invocation.operationId(), returned.invocation().operationId());
      assertEquals(target.toRealPath().toString(), invocation.path());
      assertEquals("POSITIONAL_WRITE", invocation.operationKind());
      assertEquals(0, invocation.position());
      assertEquals(4, invocation.requestedBytes());
      assertEquals(ProcessHandle.current().pid(), invocation.processId());
      assertEquals(Thread.currentThread().threadId(), invocation.javaThreadId());
      assertEquals(Thread.currentThread().getName(), invocation.javaThreadName());
      assertEquals(Thread.currentThread().isVirtual(), invocation.javaThreadVirtual());
      assertEquals("1234", invocation.nativeThreadId());
      assertEquals(getClass().getName(), invocation.caller().className());
      assertEquals("NioPersistedFileWriteTest.java", invocation.caller().fileName());
      assertTrue(invocation.caller().lineNumber() > 0);
      assertTrue(invocation.callerStack().contains(
          "NioPersistedFileWriteTest.persistsBeginBeforeRealWriteAndReturnAfterIt"));
      assertEquals(4, returned.returnedBytes());
    } finally {
      fixture.close();
    }
  }

  @Test
  void beginPersistenceFailurePreventsTargetWrite(@TempDir Path root) throws Exception {
    Path target = root.resolve("blocked.dat");
    RecordingSink sink = enable(target);
    sink.failEvent = "BEGIN";
    Fixture fixture = open(root, target.getFileName().toString());
    try {
      IoResult result = new IoResult();
      assertEquals(StatusCode.IO_FAILURE, fixture.file().write(0, bytes(4), result));
      assertEquals(0, result.bytesTransferred());
      assertEquals(0, Files.size(target));
      assertEquals(List.of("BEGIN"), sink.events());
    } finally {
      fixture.close();
    }
  }

  @Test
  void returnFailurePreservesWrittenCountAndFencesLaterWrites(@TempDir Path root)
      throws Exception {
    Path target = root.resolve("return-failure.dat");
    RecordingSink sink = enable(target);
    sink.failEvent = "RETURN";
    Fixture fixture = open(root, target.getFileName().toString());
    try {
      IoResult first = new IoResult();
      assertEquals(StatusCode.OK, fixture.file().write(0, bytes(4), first));
      assertEquals(4, first.bytesTransferred());
      assertEquals(4, Files.size(target));

      IoResult later = new IoResult();
      assertEquals(StatusCode.IO_FAILURE, fixture.file().write(4, bytes(3), later));
      assertEquals(0, later.bytesTransferred());
      assertEquals(4, Files.size(target));
      assertEquals(List.of("BEGIN", "RETURN"), sink.events());
    } finally {
      fixture.close();
    }
  }

  @Test
  void recordLockIsNotHeldAcrossTargetWrite(@TempDir Path root) throws Exception {
    Path target = root.resolve("concurrent.dat");
    RecordingSink sink = enable(target);
    Fixture fixture = open(root, target.getFileName().toString());
    WriteGate gate = new WriteGate();
    installChannel(fixture.file(), gate);
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    try {
      Future<WriteOutcome> held = executor.submit(() -> write(fixture.file(), 0, 4));
      assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "first target write did not enter");
      Future<WriteOutcome> independent = executor.submit(() -> write(fixture.file(), 4, 3));
      assertEquals(new WriteOutcome(StatusCode.OK, 3), independent.get(5, TimeUnit.SECONDS));
      assertEquals(List.of("BEGIN", "BEGIN", "RETURN"), sink.events());
      gate.release.countDown();
      assertEquals(new WriteOutcome(StatusCode.OK, 4), held.get(5, TimeUnit.SECONDS));
    } finally {
      gate.release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      fixture.close();
    }
  }

  @Test
  void targetFailureKeepsPrimaryOutcomeAndPersistsThrow(@TempDir Path root) throws Exception {
    Path target = root.resolve("target-failure.dat");
    RecordingSink sink = enable(target);
    Fixture fixture = open(root, target.getFileName().toString());
    try {
      closeChannel(fixture.file());
      IoResult result = new IoResult();
      assertEquals(StatusCode.CLOSED, fixture.file().write(0, bytes(4), result));
      assertEquals(0, result.bytesTransferred());
      assertEquals(List.of("BEGIN", "THROW"), sink.events());
      assertTrue(sink.records.get(1).failureType().endsWith("ClosedChannelException"));
    } finally {
      fixture.close();
    }
  }

  @Test
  void disabledPropertyDoesNotCreateDiagnosticState(@TempDir Path root) throws Exception {
    previousProperty = System.getProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY);
    System.clearProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY);
    Path target = root.resolve("disabled.dat");
    RecordingSink sink = new RecordingSink(target);
    PersistedFileWriteDiagnostics.installTestSink(sink);
    Fixture fixture = open(root, target.getFileName().toString());
    try {
      IoResult result = new IoResult();
      StatusCode write = fixture.file().write(0, bytes(2), result);
      if (write != StatusCode.OK) throw new AssertionError(diagnosticFailure());
      assertEquals(2, result.bytesTransferred());
      assertTrue(sink.records.isEmpty());
      Field field = NioDurableFile.class.getDeclaredField("persistedWriteDiagnostics");
      field.setAccessible(true);
      assertEquals(null, field.get(fixture.file()));
    } finally {
      fixture.close();
    }
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void actualApfsSinkRecordsWriteAndGrowthWithFullSync(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    previousProperty = System.getProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY);
    Path log = root.toRealPath().resolve("persisted-writes.jsonl");
    System.setProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY, log.toString());
    installTestNativeInvoker();
    Fixture fixture = open(root, "actual.dat");
    try {
      IoResult result = new IoResult();
      StatusCode write = fixture.file().write(0, bytes(2), result);
      if (write != StatusCode.OK) throw new AssertionError(diagnosticFailure());
      assertEquals(StatusCode.OK, fixture.file().truncate(8));
    } finally {
      fixture.close();
      PersistedFileWriteDiagnostics.clearTestSink();
    }
    List<String> records = Files.readAllLines(log);
    assertEquals(4, records.size());
    assertTrue(records.get(0).contains("\"event\":\"BEGIN\""));
    assertTrue(records.get(0).contains("\"operationKind\":\"POSITIONAL_WRITE\""));
    assertTrue(records.get(0).contains("\"nativeThreadId\":\"1234\""));
    assertTrue(records.get(0).contains("\"callerFile\":\"NioPersistedFileWriteTest.java\""));
    assertTrue(records.get(1).contains("\"event\":\"RETURN\""));
    assertTrue(records.get(2).contains("\"operationKind\":\"RESIZE_GROWTH\""));
    assertTrue(records.get(3).contains("\"event\":\"RETURN\""));
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @EnabledIfEnvironmentVariable(named = "RIVER_PERSISTED_WRITE_NATIVE_LIBRARY", matches = ".+")
  void nativeTrampolinePinsRecordedCarrierThroughActualWrite(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    previousProperty = System.getProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY);
    previousLibraryProperty = System.getProperty(PersistedFileWriteNative.LIBRARY_PROPERTY);
    libraryPropertyChanged = true;
    Path canonicalRoot = root.toRealPath();
    Path log = canonicalRoot.resolve("native-write.jsonl");
    System.setProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY, log.toString());
    System.setProperty(PersistedFileWriteNative.LIBRARY_PROPERTY,
        System.getenv("RIVER_PERSISTED_WRITE_NATIVE_LIBRARY"));
    Fixture fixture = open(root, "native-target.dat");
    WriteGate gate = new WriteGate();
    installChannel(fixture.file(), gate);
    ExecutorService executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("persisted-native-writer").factory());
    try {
      Future<WriteOutcome> write = executor.submit(() -> write(fixture.file(), 0, 4));
      assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "native target write did not enter");
      String begin = Files.readAllLines(log).getFirst();
      Matcher id = NATIVE_THREAD_ID.matcher(begin);
      assertTrue(id.find(), begin);
      String nativeThreadId = id.group(1);
      assertTrue(begin.contains("\"javaThreadName\":\"persisted-native-writer\""), begin);
      assertTrue(begin.contains("\"javaThreadVirtual\":true"), begin);
      assertTrue(begin.contains("NioPersistedFileWriteTest.write("), begin);

      Path samplePath = canonicalRoot.resolve("native-write.sample.txt");
      Process sample = new ProcessBuilder(
          "/usr/bin/sample", Long.toString(ProcessHandle.current().pid()), "1", "1")
          .redirectErrorStream(true)
          .redirectOutput(samplePath.toFile())
          .start();
      boolean sampleExited;
      try {
        sampleExited = sample.waitFor(10, TimeUnit.SECONDS);
      } finally {
        finish(sample);
      }
      String sampled = Files.readString(samplePath);
      assertTrue(sampleExited, "sample did not exit");
      assertEquals(0, sample.exitValue(), sampled);
      String sampledThread = sampledThread(sampled, nativeThreadId);
      assertTrue(sampledThread.contains("PersistedFileWriteNative_writePinned0"),
          "JNI trampoline missing from sampled pinned thread:\n" + sampledThread);
      System.out.printf(
          "persisted-native-match pid=%d nativeThreadId=%s javaThread=%s evidence=%s%n",
          ProcessHandle.current().pid(), nativeThreadId, "persisted-native-writer", canonicalRoot);

      gate.release.countDown();
      assertEquals(new WriteOutcome(StatusCode.OK, 4), write.get(5, TimeUnit.SECONDS));
    } finally {
      gate.release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      fixture.close();
      PersistedFileWriteDiagnostics.clearTestSink();
    }
    List<String> records = Files.readAllLines(log);
    assertEquals(2, records.size());
    assertTrue(records.get(1).contains("\"event\":\"RETURN\""));
    assertTrue(records.get(1).contains("\"returnedBytes\":4"));
  }

  private RecordingSink enable(Path target) {
    previousProperty = System.getProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY);
    System.setProperty(PersistedFileWriteDiagnostics.PATH_PROPERTY, "test");
    RecordingSink sink = new RecordingSink(target);
    PersistedFileWriteDiagnostics.installTestSink(sink);
    installTestNativeInvoker();
    return sink;
  }

  private static void installTestNativeInvoker() {
    PersistedFileWriteNative.installTestInvoker(
        (owner, source, position, operationKind) ->
            owner.writeOnPinnedCarrier(source, position, operationKind, 1234));
  }

  private static Fixture open(Path root, String name) throws Exception {
    NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root.toRealPath(), new FatalStateFence(), new NioIoCounters(), 4, opened));
    NioDurableDirectory directory = opened.directory();
    DirectoryOperationResult created = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile(name, FileIoMode.POSITIONAL, created));
    return new Fixture(directory, (NioDurableFile) created.file());
  }

  private static WriteOutcome write(NioDurableFile file, long position, int count) {
    IoResult result = new IoResult();
    StatusCode status = file.write(position, bytes(count), result);
    return new WriteOutcome(status, result.bytesTransferred());
  }

  private static ByteBuffer bytes(int count) {
    return ByteBuffer.wrap(new byte[count]);
  }

  private static void installChannel(NioDurableFile file, WriteGate gate) throws Exception {
    Field field = NioDurableFile.class.getDeclaredField("channel");
    field.setAccessible(true);
    field.set(file, new GatedFileChannel((FileChannel) field.get(file), gate));
  }

  private static void closeChannel(NioDurableFile file) throws Exception {
    Field field = NioDurableFile.class.getDeclaredField("channel");
    field.setAccessible(true);
    ((FileChannel) field.get(file)).close();
  }

  private static String sampledThread(String sample, String expectedId) {
    Pattern header = Pattern.compile("(?m)^\\s*[0-9]+\\s+Thread_([0-9]+).*$");
    Matcher threads = header.matcher(sample);
    int start = -1;
    int end = sample.length();
    while (threads.find()) {
      if (start >= 0) {
        end = threads.start();
        break;
      }
      if (threads.group(1).equals(expectedId)) start = threads.start();
    }
    assertTrue(start >= 0, "recorded native thread missing from sample: " + expectedId);
    Matcher footer = Pattern.compile(
        "(?m)^(Total number in stack|Sort by top of stack|Binary Images).*$")
        .matcher(sample);
    if (footer.find(start) && footer.start() < end) end = footer.start();
    return sample.substring(start, end);
  }

  private static void finish(Process process) throws InterruptedException {
    if (!process.isAlive()) return;
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "sample process did not stop");
    }
  }

  private static String diagnosticFailure() throws Exception {
    Field writerField = PersistedFileWriteDiagnostics.class.getDeclaredField("sharedWriter");
    writerField.setAccessible(true);
    Object writer = writerField.get(null);
    Field failureField = writer.getClass().getDeclaredField("failure");
    failureField.setAccessible(true);
    Throwable failure = (Throwable) failureField.get(writer);
    StringBuilder message = new StringBuilder();
    while (failure != null) {
      message.append(failure).append("; ");
      failure = failure.getCause();
    }
    return message.toString();
  }

  private record WriteOutcome(StatusCode status, long bytes) { }

  private record Fixture(NioDurableDirectory directory, NioDurableFile file) {
    private void close() {
      file.close();
      directory.close();
    }
  }

  private static final class RecordingSink implements PersistedFileWriteDiagnostics.Sink {
    private final Path target;
    private final List<PersistedFileWriteDiagnostics.Record> records = new ArrayList<>();
    private final List<Long> targetSizes = new ArrayList<>();
    private String failEvent;

    private RecordingSink(Path targetPath) {
      target = targetPath;
    }

    @Override
    public synchronized void persist(PersistedFileWriteDiagnostics.Record record)
        throws IOException {
      records.add(record);
      targetSizes.add(Files.size(target));
      if (record.event().equals(failEvent)) throw new IOException("injected " + failEvent);
    }

    private synchronized List<String> events() {
      return records.stream().map(PersistedFileWriteDiagnostics.Record::event).toList();
    }
  }

  private static final class WriteGate {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
  }

  private static final class GatedFileChannel extends FileChannel {
    private final FileChannel delegate;
    private final WriteGate gate;

    private GatedFileChannel(FileChannel target, WriteGate writeGate) {
      delegate = target;
      gate = writeGate;
    }

    @Override public int write(ByteBuffer source, long position) throws IOException {
      if (position == 0) {
        gate.entered.countDown();
        try {
          if (!gate.release.await(30, TimeUnit.SECONDS)) throw new IOException("gate timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("gate interrupted", interrupted);
        }
      }
      return delegate.write(source, position);
    }

    @Override public int read(ByteBuffer target) throws IOException { return delegate.read(target); }
    @Override public long read(ByteBuffer[] targets, int offset, int length) throws IOException {
      return delegate.read(targets, offset, length);
    }
    @Override public int write(ByteBuffer source) throws IOException { return delegate.write(source); }
    @Override public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
      return delegate.write(sources, offset, length);
    }
    @Override public long position() throws IOException { return delegate.position(); }
    @Override public FileChannel position(long value) throws IOException {
      delegate.position(value);
      return this;
    }
    @Override public long size() throws IOException { return delegate.size(); }
    @Override public FileChannel truncate(long size) throws IOException {
      delegate.truncate(size);
      return this;
    }
    @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
    @Override public long transferTo(long position, long count, WritableByteChannel target)
        throws IOException { return delegate.transferTo(position, count, target); }
    @Override public long transferFrom(ReadableByteChannel source, long position, long count)
        throws IOException { return delegate.transferFrom(source, position, count); }
    @Override public int read(ByteBuffer target, long position) throws IOException {
      return delegate.read(target, position);
    }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size)
        throws IOException { return delegate.map(mode, position, size); }
    @Override public FileLock lock(long position, long size, boolean shared) throws IOException {
      return delegate.lock(position, size, shared);
    }
    @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
      return delegate.tryLock(position, size, shared);
    }
    @Override protected void implCloseChannel() throws IOException { delegate.close(); }
  }
}

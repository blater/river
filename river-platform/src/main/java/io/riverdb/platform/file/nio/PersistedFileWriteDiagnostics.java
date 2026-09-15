package io.riverdb.platform.file.nio;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in durable begin/return records around the NIO positional-write boundary. */
final class PersistedFileWriteDiagnostics {
  static final String PATH_PROPERTY = "river.diagnostics.persistedFileWrites";
  private static final StackWalker STACK_WALKER = StackWalker.getInstance();
  private static final AtomicLong NEXT_HANDLE_ID = new AtomicLong();
  private static final AtomicLong NEXT_OPERATION_ID = new AtomicLong();
  private static final Object REGISTRATION_LOCK = new Object();
  private static String sharedPath;
  private static Writer sharedWriter;
  private static Sink testSink;

  private PersistedFileWriteDiagnostics() { }

  static Entry register(Path targetPath) {
    String configuredPath = System.getProperty(PATH_PROPERTY);
    if (configuredPath == null || configuredPath.isBlank()) return null;
    Writer writer;
    IOException nativeFailure;
    synchronized (REGISTRATION_LOCK) {
      nativeFailure = PersistedFileWriteNative.prepare();
      Path logPath;
      try {
        logPath = Path.of(configuredPath).toAbsolutePath().normalize();
      } catch (RuntimeException invalidPath) {
        writer = new Writer(new FailedSink(
            new IOException("invalid persisted write diagnostic path", invalidPath)));
        return new Entry(
            NEXT_HANDLE_ID.incrementAndGet(), targetPath.toString(), writer, nativeFailure);
      }
      writer = logPath.equals(targetPath.toAbsolutePath().normalize())
          ? new Writer(new FailedSink(
              new IOException("diagnostic log cannot also be a target file")))
          : writer(logPath.toString());
    }
    return new Entry(
        NEXT_HANDLE_ID.incrementAndGet(), targetPath.toString(), writer, nativeFailure);
  }

  private static Writer writer(String configuredPath) {
    if (testSink != null) {
      if (sharedWriter == null) sharedWriter = new Writer(testSink);
      return sharedWriter;
    }
    if (sharedWriter != null) {
      if (configuredPath.equals(sharedPath)) return sharedWriter;
      return new Writer(new FailedSink(new IOException(
          "persisted write diagnostic path changed after initialization")));
    }
    sharedPath = configuredPath;
    try {
      sharedWriter = new Writer(new ApfsSink(Path.of(configuredPath)));
    } catch (IOException failure) {
      sharedWriter = new Writer(new FailedSink(failure));
    }
    return sharedWriter;
  }

  static void installTestSink(Sink replacement) {
    synchronized (REGISTRATION_LOCK) {
      closeShared();
      testSink = replacement;
      sharedPath = null;
      sharedWriter = null;
    }
  }

  static void clearTestSink() {
    synchronized (REGISTRATION_LOCK) {
      closeShared();
      testSink = null;
      sharedPath = null;
      sharedWriter = null;
    }
  }

  static final class Entry {
    private final long handleId;
    private final String path;
    private final Writer writer;
    private final IOException nativeFailure;

    private Entry(
        long diagnosticHandleId,
        String targetPath,
        Writer recordWriter,
        IOException nativeInitializationFailure) {
      handleId = diagnosticHandleId;
      path = targetPath;
      writer = recordWriter;
      nativeFailure = nativeInitializationFailure;
    }

    int write(
        NioDurableFile owner,
        ByteBuffer source,
        long position,
        PendingFileWriteDiagnostics.OperationKind operationKind) throws IOException {
      if (nativeFailure != null) {
        throw new IOException("persisted write native trampoline is unavailable", nativeFailure);
      }
      return PersistedFileWriteNative.write(owner, source, position, operationKind);
    }

    Invocation begin(
        PendingFileWriteDiagnostics.OperationKind operationKind,
        long position,
        long requestedBytes,
        long nativeThreadId) throws IOException {
      Thread thread = Thread.currentThread();
      Trace trace = trace();
      Invocation invocation = new Invocation(
          NEXT_OPERATION_ID.incrementAndGet(), handleId, path, operationKind.name(), position,
          requestedBytes, ProcessHandle.current().pid(), thread.threadId(), thread.getName(),
          thread.isVirtual(), Long.toUnsignedString(nativeThreadId), trace.caller(), trace.stack());
      writer.persist(Record.begin(invocation));
      return invocation;
    }

    void returned(Invocation invocation, int returnedBytes) {
      try {
        writer.persist(Record.returned(invocation, returnedBytes));
      } catch (IOException ignored) {
        // The completed target byte count must remain visible. Writer fencing rejects later writes.
      }
    }

    void failed(Invocation invocation, IOException targetFailure) {
      try {
        writer.persist(Record.failed(invocation, targetFailure));
      } catch (IOException diagnosticFailure) {
        targetFailure.addSuppressed(diagnosticFailure);
      }
    }
  }

  interface Sink {
    void persist(Record record) throws IOException;

    default void close() throws IOException { }
  }

  record Record(
      String event,
      Invocation invocation,
      long wallClockMillis,
      long monotonicNanos,
      int returnedBytes,
      String failureType,
      String failureMessage) {

    private static Record begin(Invocation invocation) {
      return record("BEGIN", invocation, -1, null, null);
    }

    private static Record returned(Invocation invocation, int bytes) {
      return record("RETURN", invocation, bytes, null, null);
    }

    private static Record failed(Invocation invocation, IOException failure) {
      return record("THROW", invocation, -1, failure.getClass().getName(), failure.getMessage());
    }

    private static Record record(
        String event, Invocation invocation, int returnedBytes,
        String failureType, String failureMessage) {
      return new Record(
          event, invocation, System.currentTimeMillis(), System.nanoTime(), returnedBytes,
          failureType, failureMessage);
    }
  }

  record Invocation(
      long operationId,
      long handleId,
      String path,
      String operationKind,
      long position,
      long requestedBytes,
      long processId,
      long javaThreadId,
      String javaThreadName,
      boolean javaThreadVirtual,
      String nativeThreadId,
      Caller caller,
      String callerStack) { }

  record Caller(String className, String methodName, String fileName, int lineNumber) { }

  private record Trace(Caller caller, String stack) { }

  private static Trace trace() {
    return STACK_WALKER.walk(frames -> {
      StringBuilder stack = new StringBuilder(512);
      Caller caller = null;
      for (StackWalker.StackFrame frame : frames.toList()) {
        if (caller == null && (frame.getClassName().startsWith(
            PersistedFileWriteDiagnostics.class.getName())
            || frame.getClassName().equals(PersistedFileWriteNative.class.getName())
            || frame.getClassName().equals(NioDurableFile.class.getName()))) continue;
        if (caller == null) {
          caller = new Caller(
              frame.getClassName(), frame.getMethodName(), value(frame.getFileName()),
              frame.getLineNumber());
        }
        if (!stack.isEmpty()) stack.append('\n');
        stack.append(frame.getClassName()).append('.').append(frame.getMethodName())
            .append('(').append(value(frame.getFileName())).append(':')
            .append(frame.getLineNumber()).append(')');
      }
      if (caller == null) caller = new Caller("UNKNOWN", "UNKNOWN", "UNKNOWN", -1);
      return new Trace(caller, stack.toString());
    });
  }

  private static String value(String text) {
    return text == null ? "UNKNOWN" : text;
  }

  private static void closeShared() {
    if (sharedWriter == null) return;
    try {
      sharedWriter.close();
    } catch (IOException ignored) {
      // Test reconfiguration never runs on the production server path.
    }
  }

  private static final class Writer {
    private final Sink sink;
    private IOException failure;

    private Writer(Sink recordSink) {
      sink = recordSink;
    }

    synchronized void persist(Record record) throws IOException {
      if (failure != null) throw unavailable(failure);
      try {
        sink.persist(record);
      } catch (IOException writeFailure) {
        failure = writeFailure;
        throw writeFailure;
      }
    }

    private synchronized void close() throws IOException {
      sink.close();
    }

    private static IOException unavailable(IOException cause) {
      return new IOException("persisted write diagnostics are unavailable", cause);
    }
  }

  private static final class FailedSink implements Sink {
    private final IOException failure;

    private FailedSink(IOException initializationFailure) {
      failure = initializationFailure;
    }

    @Override
    public void persist(Record record) throws IOException {
      throw new IOException("persisted write diagnostics could not initialize", failure);
    }
  }

  private static final class ApfsSink implements Sink {
    private final RiverFile file;
    private long position;

    private ApfsSink(Path configuredPath) throws IOException {
      if (!"Mac OS X".equals(System.getProperty("os.name"))) {
        throw new IOException("persisted write diagnostics require macOS F_FULLFSYNC");
      }
      Path path = configuredPath.toAbsolutePath().normalize();
      Path parentPath = path.getParent();
      Path fileName = path.getFileName();
      if (parentPath == null || fileName == null) {
        throw new IOException("persisted write diagnostic path must name a file");
      }
      ApfsRiverDaemonFileSystem fileSystem = new ApfsRiverDaemonFileSystem();
      RiverDirectoryResult directoryResult = new RiverDirectoryResult();
      require(fileSystem.openDirectory(parentPath, directoryResult), "open private log directory");
      RiverDirectory directory = directoryResult.directory();
      RiverFile opened = null;
      IOException failure = null;
      try {
        RiverFileResult fileResult = new RiverFileResult();
        require(directory.createFile(fileName.toString(), fileResult), "create log file");
        opened = fileResult.file();
        DirectoryOperationResult forceResult = new DirectoryOperationResult();
        require(directory.force(forceResult), "make log file name durable");
      } catch (IOException initializationFailure) {
        failure = initializationFailure;
      } finally {
        StatusCode close = directory.close();
        if (!close.isOk() && failure == null) {
          failure = statusFailure("close log directory", close);
        }
      }
      if (failure != null) {
        if (opened != null) opened.close();
        throw failure;
      }
      file = opened;
    }

    @Override
    public void persist(Record record) throws IOException {
      byte[] encoded = encode(record).getBytes(StandardCharsets.UTF_8);
      ByteBuffer bytes = ByteBuffer.wrap(encoded);
      IoResult result = new IoResult();
      while (bytes.hasRemaining()) {
        require(file.write(position, bytes, result), "append diagnostic record");
        long count = result.bytesTransferred();
        if (count <= 0) throw new IOException("append diagnostic record made no progress");
        position += count;
      }
      require(file.force(ForceMode.CONTENT_AND_METADATA), "F_FULLFSYNC diagnostic record");
    }

    @Override
    public void close() throws IOException {
      require(file.close(), "close diagnostic log");
    }

    private static String encode(Record record) {
      StringBuilder text = new StringBuilder(512);
      Invocation invocation = record.invocation();
      text.append('{');
      field(text, "event", record.event());
      number(text, "operationId", invocation.operationId());
      number(text, "diagnosticHandleId", invocation.handleId());
      field(text, "targetPath", invocation.path());
      field(text, "operationKind", invocation.operationKind());
      number(text, "position", invocation.position());
      number(text, "requestedBytes", invocation.requestedBytes());
      number(text, "processId", invocation.processId());
      number(text, "javaThreadId", invocation.javaThreadId());
      field(text, "javaThreadName", invocation.javaThreadName());
      bool(text, "javaThreadVirtual", invocation.javaThreadVirtual());
      field(text, "nativeThreadId", invocation.nativeThreadId());
      field(text, "callerClass", invocation.caller().className());
      field(text, "callerMethod", invocation.caller().methodName());
      field(text, "callerFile", invocation.caller().fileName());
      number(text, "callerLine", invocation.caller().lineNumber());
      field(text, "callerStack", invocation.callerStack());
      number(text, "wallClockMillis", record.wallClockMillis());
      number(text, "monotonicNanos", record.monotonicNanos());
      number(text, "returnedBytes", record.returnedBytes());
      field(text, "failureType", record.failureType());
      field(text, "failureMessage", record.failureMessage());
      return text.append("}\n").toString();
    }

    private static void field(StringBuilder target, String name, String value) {
      separator(target);
      quoted(target, name);
      target.append(':');
      if (value == null) target.append("null");
      else quoted(target, value);
    }

    private static void number(StringBuilder target, String name, long value) {
      separator(target);
      quoted(target, name);
      target.append(':').append(value);
    }

    private static void bool(StringBuilder target, String name, boolean value) {
      separator(target);
      quoted(target, name);
      target.append(':').append(value);
    }

    private static void separator(StringBuilder target) {
      if (target.length() > 1) target.append(',');
    }

    private static void quoted(StringBuilder target, String value) {
      target.append('"');
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> target.append("\\\"");
          case '\\' -> target.append("\\\\");
          case '\b' -> target.append("\\b");
          case '\f' -> target.append("\\f");
          case '\n' -> target.append("\\n");
          case '\r' -> target.append("\\r");
          case '\t' -> target.append("\\t");
          default -> {
            if (character < 0x20) {
              target.append("\\u00");
              target.append(Character.forDigit(character >>> 4, 16));
              target.append(Character.forDigit(character & 0xf, 16));
            } else {
              target.append(character);
            }
          }
        }
      }
      target.append('"');
    }

    private static void require(StatusCode status, String operation) throws IOException {
      if (!status.isOk()) throw statusFailure(operation, status);
    }

    private static IOException statusFailure(String operation, StatusCode status) {
      return new IOException(operation + " failed: " + status);
    }
  }
}

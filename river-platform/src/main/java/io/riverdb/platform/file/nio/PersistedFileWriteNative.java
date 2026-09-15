package io.riverdb.platform.file.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Loads and invokes the opt-in macOS carrier-pinning diagnostic trampoline. */
final class PersistedFileWriteNative {
  static final String LIBRARY_PROPERTY = "river.diagnostics.persistedFileWritesNativeLibrary";
  private static String loadedPath;
  private static Invoker testInvoker;

  private PersistedFileWriteNative() { }

  @SuppressWarnings("restricted")
  static synchronized IOException prepare() {
    if (testInvoker != null) return null;
    String configured = System.getProperty(LIBRARY_PROPERTY);
    if (configured == null || configured.isBlank()) {
      return new IOException("persisted write diagnostics require " + LIBRARY_PROPERTY);
    }
    String path;
    try {
      path = Path.of(configured).toAbsolutePath().normalize().toString();
    } catch (RuntimeException invalidPath) {
      return new IOException("invalid persisted write native library path", invalidPath);
    }
    if (loadedPath != null) {
      return loadedPath.equals(path) ? null
          : new IOException("persisted write native library path changed after initialization");
    }
    try {
      System.load(path);
      loadedPath = path;
      return null;
    } catch (LinkageError | SecurityException failure) {
      return new IOException("could not load persisted write native library", failure);
    }
  }

  static int write(
      NioDurableFile owner,
      ByteBuffer source,
      long position,
      PendingFileWriteDiagnostics.OperationKind operationKind) throws IOException {
    Invoker invoker;
    synchronized (PersistedFileWriteNative.class) {
      invoker = testInvoker;
    }
    return invoker == null
        ? writePinned0(owner, source, position, operationKind)
        : invoker.write(owner, source, position, operationKind);
  }

  static synchronized void installTestInvoker(Invoker replacement) {
    testInvoker = replacement;
  }

  static synchronized void clearTestInvoker() {
    testInvoker = null;
  }

  private static native int writePinned0(
      NioDurableFile owner,
      ByteBuffer source,
      long position,
      PendingFileWriteDiagnostics.OperationKind operationKind) throws IOException;

  interface Invoker {
    int write(
        NioDurableFile owner,
        ByteBuffer source,
        long position,
        PendingFileWriteDiagnostics.OperationKind operationKind) throws IOException;
  }
}

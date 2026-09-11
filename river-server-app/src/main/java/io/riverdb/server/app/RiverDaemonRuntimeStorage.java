package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

final class RiverDaemonRuntimeStorage {
  private static final int MAX_RECORD_BYTES = 8192;

  private RiverDaemonRuntimeStorage() {
  }

  static RiverDaemonRuntimeModel.ReadResult read(RiverFile file) {
    FileSizeResult size = new FileSizeResult();
    StatusCode status = file.size(size);
    if (!status.isOk()) return new RiverDaemonRuntimeModel.ReadResult(status, null);
    if (size.sizeBytes() <= 0 || size.sizeBytes() > MAX_RECORD_BYTES) {
      return new RiverDaemonRuntimeModel.ReadResult(StatusCode.CORRUPTION, null);
    }
    byte[] bytes = new byte[(int) size.sizeBytes()];
    ByteBuffer target = ByteBuffer.wrap(bytes);
    IoResult io = new IoResult();
    long position = 0;
    while (position < size.sizeBytes()) {
      target.position((int) position);
      status = file.read(position, target, io);
      if (!status.isOk()) return new RiverDaemonRuntimeModel.ReadResult(status, null);
      if (io.bytesTransferred() <= 0) {
        return new RiverDaemonRuntimeModel.ReadResult(StatusCode.IO_FAILURE, null);
      }
      position += io.bytesTransferred();
    }
    return new RiverDaemonRuntimeModel.ReadResult(StatusCode.OK, bytes);
  }

  static StatusCode write(RiverFile file, byte[] bytes) {
    try {
      ByteBuffer source = ByteBuffer.wrap(bytes);
      IoResult io = new IoResult();
      long position = 0;
      while (source.hasRemaining()) {
        StatusCode status = file.write(position, source, io);
        if (!status.isOk() || io.bytesTransferred() <= 0) {
          return status.isOk() ? StatusCode.IO_FAILURE : status;
        }
        position += io.bytesTransferred();
      }
      return file.force(ForceMode.CONTENT_AND_METADATA);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  static StatusCode force(RiverDirectory directory) {
    return directory.force(new DirectoryOperationResult());
  }

  static String runtimeName(String datadir) {
    return HexFormat.of().formatHex(digest(datadir.getBytes(StandardCharsets.UTF_8)))
        + ".properties";
  }

  static Path runtimePath(Path runtimeRoot, String datadir) {
    return runtimeRoot.resolve(runtimeName(datadir));
  }

  static StatusCode openRuntime(
      RiverDaemonFileSystem filesystem, Path runtimeRoot, String datadir, RiverFileResult result) {
    if (filesystem == null || runtimeRoot == null || datadir == null || result == null
        || !validDirectoryPath(runtimeRoot.toString()) || !validDirectoryPath(datadir)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDirectoryResult rootResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(runtimeRoot, rootResult);
    if (!status.isOk()) return status;
    RiverDirectory root = rootResult.directory();
    status = root.openFile(runtimeName(datadir), RiverOpenMode.EXISTING, result);
    StatusCode close = root.close();
    if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) {
      result.file().close();
      result.reset();
      return close;
    }
    return status;
  }

  static RiverDaemonRuntimeModel.RuntimeRecord expectedRuntime(
      RiverDaemonRuntimeRecords.Metadata metadata) {
    return new RiverDaemonRuntimeModel.RuntimeRecord(
        metadata.datadir,
        metadata.incarnation.high(),
        metadata.incarnation.low(),
        metadata.owner.pid,
        metadata.owner.start,
        metadata.listenAddress,
        metadata.listenPort,
        metadata.clientConfig,
        metadata.credentialGeneration,
        metadata.readyFile,
        metadata.owner.nonce);
  }

  static boolean validDirectoryPath(String value) {
    return RiverDaemonIdentityRecords.validDatadir(value);
  }

  static boolean validText(String value) {
    if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) return false;
    }
    return true;
  }

  static boolean validAddress(String value) {
    return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}

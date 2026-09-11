package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import javax.net.ssl.SSLContext;

/** Validated, immutable view of one riverd-generated client.properties file. */
public final class RiverClientConfiguration {
  private static final int MAX_RECORD_BYTES = 8192;

  private final RiverDaemonFileSystem fileSystem;
  private final DatabaseIncarnation incarnation;
  private final long credentialGeneration;
  private final String host;
  private final int port;
  private final Path certificateFile;
  private final byte[] certificateDigest;
  private final Path tokenFile;

  RiverClientConfiguration(
      RiverDaemonFileSystem fileSystem,
      DatabaseIncarnation incarnation,
      long credentialGeneration,
      String host,
      int port,
      Path certificateFile,
      byte[] certificateDigest,
      Path tokenFile) {
    this.fileSystem = fileSystem;
    this.incarnation = incarnation;
    this.credentialGeneration = credentialGeneration;
    this.host = host;
    this.port = port;
    this.certificateFile = certificateFile;
    this.certificateDigest = certificateDigest;
    this.tokenFile = tokenFile;
  }

  public static StatusCode load(
      Path path, RiverDaemonFileSystem fileSystem, RiverClientConfigurationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (fileSystem == null || !RiverClientFileReader.validAbsolutePath(path)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    BytesResult bytes = new BytesResult();
    StatusCode status = RiverClientFileReader.readBounded(
        fileSystem, path, MAX_RECORD_BYTES, bytes);
    if (!status.isOk()) {
      bytes.clear();
      return status;
    }
    try {
      return RiverClientConfigurationParser.parse(fileSystem, bytes.value, result);
    } finally {
      bytes.clear();
    }
  }

  /** Loads a generated client file using the selected platform filesystem owner. */
  public static StatusCode load(Path path, RiverClientConfigurationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDaemonFileSystemResult selected = new RiverDaemonFileSystemResult();
    StatusCode status = RiverDaemonFileSystems.current(selected);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    return load(path, selected.fileSystem(), result);
  }

  public DatabaseIncarnation incarnation() {
    return incarnation;
  }

  public long credentialGeneration() {
    return credentialGeneration;
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public Path certificateFile() {
    return certificateFile;
  }

  public Path tokenFile() {
    return tokenFile;
  }

  /** Opens and authenticates using only this validated configuration. */
  public StatusCode connect(RiverClientOpenResult result) {
    return RiverClientConnector.connect(this, result);
  }

  RiverDaemonFileSystem fileSystem() {
    return fileSystem;
  }

  byte[] certificateDigest() {
    return certificateDigest;
  }

  static final class BytesResult {
    byte[] value;

    void clear() {
      if (value != null) Arrays.fill(value, (byte) 0);
      value = null;
    }
  }

  static final class ContextResult {
    SSLContext context;

    void reset() {
      context = null;
    }

    void complete(SSLContext opened) {
      context = opened;
    }
  }

}

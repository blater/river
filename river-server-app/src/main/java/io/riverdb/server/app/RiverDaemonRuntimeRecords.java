package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.protocol.ProtocolFrameCodec;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Runtime records; readiness output publication remains launcher-owned. */
public final class RiverDaemonRuntimeRecords {
  // Process/runtime records use the accepted process-record framing bound, which is larger
  // than the identity lock-record bound.
  private static final int MAX_RECORD_BYTES = 8192;
  private static final String RUNTIME_FORMAT = "riverd-runtime-v2";
  private static final String READY_FORMAT = "riverd-ready-v2";

  private RiverDaemonRuntimeRecords() {
  }

  /**
   * Removes stale runtime and ready records after the identity owner has validated the database
   * and credential contents. The identity lock must remain held throughout.
   */
  public static StatusCode recoverStale(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity,
      Path runtimeRoot) {
    if (filesystem == null || identity == null || identity.directory() == null
        || identity.lock() == null || identity.priorOwner() == null
        || datadir == null || runtimeRoot == null
        || !validDirectoryPath(datadir.toString())
        || !validDirectoryPath(runtimeRoot.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    String canonicalDatadir = datadir.toString();
    if (!canonicalDatadir.equals(identity.priorOwner().datadir)) {
      return StatusCode.CORRUPTION;
    }
    RiverDirectoryResult runtimeRootResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(runtimeRoot, runtimeRootResult);
    if (!status.isOk()) return status;
    RiverDirectory runtimeDirectory = runtimeRootResult.directory();
    status = recoverStaleWithRuntimeRoot(runtimeDirectory, runtimeRoot, filesystem,
        canonicalDatadir, identity);
    StatusCode closeStatus = runtimeDirectory.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return closeStatus;
    }
    return status;
  }

  private static StatusCode recoverStaleWithRuntimeRoot(
      RiverDirectory runtimeRoot,
      Path runtimeRootPath,
      RiverDaemonFileSystem filesystem,
      String canonicalDatadir,
      RiverDaemonIdentity.IdentityResult identity) {
    String runtimeName = runtimeName(canonicalDatadir);

    RiverFileResult runtimeResult = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(runtimeName, RiverOpenMode.EXISTING, runtimeResult);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    RiverFile runtimeFile = runtimeResult.file();
    FileIdentity runtimeIdentity = runtimeFile.identity();
    ReadResult runtimeRead = read(runtimeFile);
    StatusCode closeStatus = runtimeFile.close();
    if (!runtimeRead.status.isOk()) status = runtimeRead.status;
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    RuntimeRecord runtime = status.isOk() ? parseRuntime(runtimeRead.bytes) : null;
    if (status.isOk() && (runtime == null || runtimeIdentity == null
        || !runtime.matches(canonicalDatadir, identity.incarnation(), identity.priorOwner()))) {
      status = StatusCode.CORRUPTION;
    }
    if (!status.isOk()) return status;

    ReadyTarget ready = null;
    if (!"none".equals(runtime.readyFile)) {
      ready = openReady(filesystem, runtime, canonicalDatadir, runtimeRootPath);
      if (!ready.status.isOk()) return ready.status;
    }

    if (ready != null && ready.present) {
      status = ready.parent.removeOwned(ready.name, ready.identity,
          new DirectoryOperationResult());
      if (status.isOk()) status = force(ready.parent);
    }
    if (status.isOk()) {
      status = runtimeRoot.removeOwned(runtimeName, runtimeIdentity,
          new DirectoryOperationResult());
      if (status.isOk()) status = force(runtimeRoot);
    }
    if (ready != null) {
      closeStatus = ready.parent.close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
    }
    return status;
  }

  /**
   * Removes only the records owned by the current lifecycle owner. Missing records are already
   * clean; malformed, mismatched, and unrelated records remain untouched and fail closed.
   */
  static StatusCode cleanupCurrent(
      RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity,
      Path runtimeRoot,
      Metadata metadata) {
    if (filesystem == null || identity == null || identity.directory() == null
        || identity.lock() == null || metadata == null || !metadata.valid()
        || runtimeRoot == null || !validDirectoryPath(runtimeRoot.toString())
        || !runtimeRoot.equals(metadata.runtimeRoot)
        || !metadata.incarnation.equals(identity.incarnation())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDirectoryResult runtimeRootResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(runtimeRoot, runtimeRootResult);
    if (!status.isOk()) return status;
    RiverDirectory runtimeDirectory = runtimeRootResult.directory();
    status = cleanupCurrentWithRuntimeRoot(
        filesystem, runtimeDirectory, metadata);
    StatusCode closeStatus = runtimeDirectory.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return closeStatus;
    }
    return status;
  }

  private static StatusCode cleanupCurrentWithRuntimeRoot(
      RiverDaemonFileSystem filesystem,
      RiverDirectory runtimeRoot,
      Metadata metadata) {
    RuntimeRecord runtime = null;
    FileIdentity runtimeIdentity = null;
    RiverFileResult runtimeResult = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(runtimeName(metadata.datadir),
        RiverOpenMode.EXISTING, runtimeResult);
    if (status != StatusCode.CONFLICT) {
      if (!status.isOk()) return status;
      RiverFile runtimeFile = runtimeResult.file();
      runtimeIdentity = runtimeFile.identity();
      ReadResult runtimeRead = read(runtimeFile);
      StatusCode closeStatus = runtimeFile.close();
      status = runtimeRead.status;
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
      runtime = status.isOk() ? parseRuntime(runtimeRead.bytes) : null;
      if (status.isOk() && (runtime == null || runtimeIdentity == null
          || !runtime.matches(metadata.datadir, metadata.incarnation, metadata.owner)
          || !metadata.readyFile.equals(runtime.readyFile))) {
        status = StatusCode.CORRUPTION;
      }
      if (!status.isOk()) return status;
    } else {
      status = StatusCode.OK;
    }
    RuntimeRecord expectedRuntime = runtime == null ? expectedRuntime(metadata) : runtime;
    ReadyTarget ready = null;
    if (!"none".equals(metadata.readyFile)) {
      ready = openReady(filesystem, expectedRuntime, metadata.datadir,
          metadata.runtimeRoot);
      if (!ready.status.isOk()) return ready.status;
    }

    if (ready != null && ready.present) {
      status = ready.parent.removeOwned(ready.name, ready.identity,
          new DirectoryOperationResult());
      if (status.isOk()) status = force(ready.parent);
    }
    if (status.isOk() && runtimeIdentity != null) {
      status = runtimeRoot.removeOwned(runtimeName(metadata.datadir), runtimeIdentity,
          new DirectoryOperationResult());
      if (status.isOk()) status = force(runtimeRoot);
    }
    if (ready != null) {
      StatusCode closeStatus = ready.parent.close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
    }
    return status;
  }

  static StatusCode publishRuntime(RiverDirectory runtimeRoot, Metadata metadata) {
    if (runtimeRoot == null || metadata == null || !metadata.valid()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    String stageName = ".runtime-" + metadata.owner.nonce + ".stage";
    String body = runtimeBody(metadata);
    return publish(runtimeRoot, stageName, runtimeName(metadata.datadir), body);
  }

  /** Publishes the optional canonical ready-file record through its verified parent. */
  static StatusCode publishReady(
      RiverDaemonFileSystem filesystem,
      Metadata metadata,
      Path readyPath,
      String serverCertificateSha256) {
    if (filesystem == null || metadata == null || !metadata.valid()
        || readyPath == null || serverCertificateSha256 == null
        || !serverCertificateSha256.matches("[0-9a-f]{64}")
        || !metadata.readyFile.equals(readyPath.toString())
        || "none".equals(metadata.readyFile)
        || !validDirectoryPath(metadata.runtimeRoot.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    Path parentPath = readyPath.getParent();
    Path targetPath = readyPath.getFileName() == null ? null : readyPath;
    if (parentPath == null || targetPath == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (!status.isOk()) return status;
    RiverDirectory parent = parentResult.directory();
    String targetName = targetPath.getFileName().toString();
    String stageName = "." + targetName + ".riverd-ready-" + metadata.owner.nonce + ".stage";
    status = publish(parent, stageName, targetName,
        readyBody(metadata, serverCertificateSha256));
    StatusCode closeStatus = parent.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    return status;
  }

  static final class Metadata {
    final String datadir;
    final DatabaseIncarnation incarnation;
    final RiverDaemonIdentityRecords.LockRecord owner;
    final String listenAddress;
    final int listenPort;
    final long credentialGeneration;
    final String riverVersion;
    final String clientConfig;
    final String readyFile;
    final Path runtimeRoot;

    Metadata(
        String datadir,
        DatabaseIncarnation incarnation,
        RiverDaemonIdentityRecords.LockRecord owner,
        String listenAddress,
        int listenPort,
        long credentialGeneration,
        String riverVersion,
        String clientConfig,
        Path readyFile,
        Path runtimeRoot) {
      this.datadir = datadir;
      this.incarnation = incarnation;
      this.owner = owner;
      this.listenAddress = listenAddress;
      this.listenPort = listenPort;
      this.credentialGeneration = credentialGeneration;
      this.riverVersion = riverVersion;
      this.clientConfig = clientConfig;
      this.readyFile = readyFile == null ? "none" : readyFile.toString();
      this.runtimeRoot = runtimeRoot;
    }

    private boolean valid() {
      return validDirectoryPath(datadir) && runtimeRoot != null
          && validDirectoryPath(runtimeRoot.toString())
          && incarnation != null && incarnation.isValid()
          && owner != null && owner.datadir.equals(datadir)
          && owner.high == incarnation.high() && owner.low == incarnation.low()
          && owner.pid > 0 && owner.start >= 0
          && owner.nonce.matches("[0-9a-f]{32}") && validAddress(listenAddress)
          && listenPort >= 1 && listenPort <= 65535 && credentialGeneration > 0
          && validText(riverVersion) && validDirectoryPath(clientConfig)
          && ("none".equals(readyFile) || validDirectoryPath(readyFile));
    }
  }

  private static StatusCode publish(
      RiverDirectory directory, String stageName, String targetName, String body) {
    RiverFileResult stageResult = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
    if (!status.isOk()) return status;
    RiverFile stage = stageResult.file();
    status = write(stage, body.getBytes(StandardCharsets.UTF_8));
    if (status.isOk()) {
      status = directory.publishExclusive(stage, stageName, targetName,
          new DirectoryOperationResult());
    }
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    if (status.isOk()) status = force(directory);
    return status;
  }

  private static String runtimeBody(Metadata metadata) {
    return RiverDaemonIdentityRecords.record(List.of(
        "format=" + RUNTIME_FORMAT,
        "datadir=" + metadata.datadir,
        "database-incarnation-high=" + metadata.incarnation.high(),
        "database-incarnation-low=" + metadata.incarnation.low(),
        "pid=" + metadata.owner.pid,
        "process-start-epoch-millis=" + metadata.owner.start,
        "listen-address=" + metadata.listenAddress,
        "listen-port=" + metadata.listenPort,
        "client-config=" + metadata.clientConfig,
        "credential-generation=" + metadata.credentialGeneration,
        "ready-file=" + metadata.readyFile,
        "owner-nonce=" + metadata.owner.nonce));
  }

  private static String readyBody(Metadata metadata, String serverCertificateSha256) {
    Path datadir = Path.of(metadata.datadir);
    return RiverDaemonIdentityRecords.record(List.of(
        "format=" + READY_FORMAT,
        "datadir=" + metadata.datadir,
        "database-incarnation-high=" + metadata.incarnation.high(),
        "database-incarnation-low=" + metadata.incarnation.low(),
        "data=" + datadir.resolve(RiverDaemonIdentity.DATABASE_NAME),
        "identity=" + datadir.resolve(RiverDaemonIdentity.INSTANCE_FILE),
        "runtime-file=" + runtimePath(metadata.runtimeRoot, metadata.datadir),
        "listen-address=" + metadata.listenAddress,
        "listen-port=" + metadata.listenPort,
        "pid=" + metadata.owner.pid,
        "protocol=" + protocolTag(),
        "transport=tls-v1.3",
        "client-config=" + metadata.clientConfig,
        "server-certificate-sha256=" + serverCertificateSha256,
        "owner-nonce=" + metadata.owner.nonce,
        "status=ready"));
  }

  private static ReadyTarget openReady(
      RiverDaemonFileSystem filesystem, RuntimeRecord runtime, String datadir,
      Path runtimeRoot) {
    Path path = Path.of(runtime.readyFile);
    Path parentPath = path.getParent();
    if (parentPath == null) return ReadyTarget.failure(StatusCode.INVALID_EXTERNAL_INPUT);
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (!status.isOk()) return ReadyTarget.failure(status);
    RiverDirectory parent = parentResult.directory();
    String name = path.getFileName().toString();
    RiverFileResult fileResult = new RiverFileResult();
    status = parent.openFile(name, RiverOpenMode.EXISTING, fileResult);
    if (status == StatusCode.CONFLICT) {
      return new ReadyTarget(parent, null, null, false, StatusCode.OK);
    }
    if (!status.isOk()) {
      return closeReadyParent(parent, status);
    }
    RiverFile file = fileResult.file();
    FileIdentity objectIdentity = file.identity();
    ReadResult readResult = read(file);
    StatusCode closeStatus = file.close();
    if (!readResult.status.isOk()) {
      return closeReadyParent(parent, readResult.status);
    }
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return closeReadyParent(parent, closeStatus);
    }
    ReadyRecord ready = parseReady(readResult.bytes);
    if (ready == null || objectIdentity == null
        || !ready.matches(runtime, datadir, runtimePath(runtimeRoot, datadir).toString())) {
      return closeReadyParent(parent, StatusCode.CORRUPTION);
    }
    return new ReadyTarget(parent, name, objectIdentity, true, StatusCode.OK);
  }

  private static ReadyTarget closeReadyParent(RiverDirectory parent, StatusCode primary) {
    StatusCode closeStatus = parent.close();
    if (primary.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return ReadyTarget.failure(closeStatus);
    }
    return ReadyTarget.failure(primary);
  }

  static ReadResult read(RiverFile file) {
    FileSizeResult size = new FileSizeResult();
    StatusCode status = file.size(size);
    if (!status.isOk()) return new ReadResult(status, null);
    if (size.sizeBytes() <= 0 || size.sizeBytes() > MAX_RECORD_BYTES) {
      return new ReadResult(StatusCode.CORRUPTION, null);
    }
    byte[] bytes = new byte[(int) size.sizeBytes()];
    ByteBuffer target = ByteBuffer.wrap(bytes);
    IoResult io = new IoResult();
    long position = 0;
    while (position < size.sizeBytes()) {
      target.position((int) position);
      status = file.read(position, target, io);
      if (!status.isOk()) return new ReadResult(status, null);
      if (io.bytesTransferred() <= 0) return new ReadResult(StatusCode.IO_FAILURE, null);
      position += io.bytesTransferred();
    }
    return new ReadResult(StatusCode.OK, bytes);
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

  /** Opens the one runtime record for a datadir and transfers only the file capability. */
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

  private static RuntimeRecord expectedRuntime(Metadata metadata) {
    return new RuntimeRecord(
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

  private static String protocolTag() {
    return "river-v" + ProtocolFrameCodec.VERSION;
  }

  private static boolean validDirectoryPath(String value) {
    return RiverDaemonIdentityRecords.validDatadir(value);
  }

  private static boolean validText(String value) {
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

  private static Envelope envelope(byte[] bytes, int fieldCount, String format) {
    if (bytes == null) return null;
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException failure) {
      return null;
    }
    int end = text.indexOf("record-sha256=");
    if (end <= 0) return null;
    String prefix = text.substring(0, end);
    if (!prefix.endsWith("\n")) return null;
    String[] fields = prefix.substring(0, prefix.length() - 1).split("\\n", -1);
    String checksumLine = text.substring(end + "record-sha256=".length());
    if (fields.length != fieldCount || !checksumLine.endsWith("\n")) return null;
    String checksum = checksumLine.substring(0, checksumLine.length() - 1);
    if (!checksum.matches("[0-9a-f]{64}")) return null;
    byte[] expected = digest(prefix.getBytes(StandardCharsets.UTF_8));
    boolean valid = checksum.equals(HexFormat.of().formatHex(expected));
    Arrays.fill(expected, (byte) 0);
    return valid && format.equals(value(fields[0], "format="))
        ? new Envelope(fields, checksum) : null;
  }

  private static String value(String field, String key) {
    return field.startsWith(key) ? field.substring(key.length()) : "";
  }

  private static long canonicalLong(String value) {
    if (value == null || value.isEmpty()) throw new NumberFormatException();
    long parsed = Long.parseLong(value);
    if (!Long.toString(parsed).equals(value)) throw new NumberFormatException();
    return parsed;
  }

  private static int canonicalPort(String value) {
    long parsed = canonicalLong(value);
    if (parsed < 1 || parsed > 65535) throw new NumberFormatException();
    return (int) parsed;
  }

  static RuntimeRecord parseRuntime(byte[] bytes) {
    Envelope envelope = envelope(bytes, 12, RUNTIME_FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      return new RuntimeRecord(
          value(fields[1], "datadir="),
          canonicalLong(value(fields[2], "database-incarnation-high=")),
          canonicalLong(value(fields[3], "database-incarnation-low=")),
          canonicalLong(value(fields[4], "pid=")),
          canonicalLong(value(fields[5], "process-start-epoch-millis=")),
          value(fields[6], "listen-address="),
          canonicalPort(value(fields[7], "listen-port=")),
          value(fields[8], "client-config="),
          canonicalLong(value(fields[9], "credential-generation=")),
          value(fields[10], "ready-file="), value(fields[11], "owner-nonce="), envelope.checksum);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static ReadyRecord parseReady(byte[] bytes) {
    Envelope envelope = envelope(bytes, 16, READY_FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      return new ReadyRecord(value(fields[1], "datadir="),
          canonicalLong(value(fields[2], "database-incarnation-high=")),
          canonicalLong(value(fields[3], "database-incarnation-low=")),
          value(fields[4], "data="), value(fields[5], "identity="),
          value(fields[6], "runtime-file="), value(fields[7], "listen-address="),
          canonicalPort(value(fields[8], "listen-port=")),
          canonicalLong(value(fields[9], "pid=")), value(fields[10], "protocol="),
          value(fields[11], "transport="), value(fields[12], "client-config="),
          value(fields[13], "server-certificate-sha256="),
          value(fields[14], "owner-nonce="), value(fields[15], "status="));
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static final class RuntimeRecord {
    final String datadir;
    final long high;
    final long low;
    final long pid;
    final long start;
    final String address;
    final int port;
    final String clientConfig;
    final long generation;
    final String readyFile;
    final String nonce;
    final String checksum;

    RuntimeRecord(String datadir, long high, long low, long pid, long start,
        String address, int port, String clientConfig, long generation, String readyFile,
        String nonce) {
      this(datadir, high, low, pid, start, address, port, clientConfig, generation,
          readyFile, nonce, null);
    }

    RuntimeRecord(String datadir, long high, long low, long pid, long start,
        String address, int port, String clientConfig, long generation, String readyFile,
        String nonce, String checksum) {
      this.datadir = datadir;
      this.high = high;
      this.low = low;
      this.pid = pid;
      this.start = start;
      this.address = address;
      this.port = port;
      this.clientConfig = clientConfig;
      this.generation = generation;
      this.readyFile = readyFile;
      this.nonce = nonce;
      this.checksum = checksum;
    }

    boolean matches(String expectedDatadir, DatabaseIncarnation incarnation,
        RiverDaemonIdentityRecords.LockRecord owner) {
      return expectedDatadir.equals(datadir) && high == incarnation.high() && low == incarnation.low()
          && pid == owner.pid && start == owner.start
          && nonce.equals(owner.nonce) && validAddress(address)
          && port >= 1 && port <= 65535 && validDirectoryPath(clientConfig)
          && generation > 0 && ("none".equals(readyFile) || validDirectoryPath(readyFile));
    }
  }

  private static final class ReadyRecord {
    final String datadir;
    final long high;
    final long low;
    final String data;
    final String identity;
    final String runtimeFile;
    final String address;
    final int port;
    final long pid;
    final String protocol;
    final String transport;
    final String clientConfig;
    final String certificate;
    final String nonce;
    final String status;

    ReadyRecord(String datadir, long high, long low, String data, String identity,
        String runtimeFile, String address, int port, long pid, String protocol,
        String transport, String clientConfig, String certificate, String nonce, String status) {
      this.datadir = datadir;
      this.high = high;
      this.low = low;
      this.data = data;
      this.identity = identity;
      this.runtimeFile = runtimeFile;
      this.address = address;
      this.port = port;
      this.pid = pid;
      this.protocol = protocol;
      this.transport = transport;
      this.clientConfig = clientConfig;
      this.certificate = certificate;
      this.nonce = nonce;
      this.status = status;
    }

    boolean matches(RuntimeRecord runtime, String expectedDatadir, String expectedRuntimePath) {
      return expectedDatadir.equals(datadir) && high == runtime.high && low == runtime.low
          && Path.of(expectedDatadir).resolve("database").toString().equals(data)
          && Path.of(expectedDatadir).resolve("instance.properties").toString().equals(identity)
          && expectedRuntimePath.equals(runtimeFile)
          && address.equals(runtime.address)
          && port == runtime.port && pid == runtime.pid && protocolTag().equals(protocol)
          && "tls-v1.3".equals(transport) && clientConfig.equals(runtime.clientConfig)
          && certificate.matches("[0-9a-f]{64}") && nonce.equals(runtime.nonce)
          && "ready".equals(status);
    }
  }

  private static final class ReadyTarget {
    final RiverDirectory parent;
    final String name;
    final FileIdentity identity;
    final boolean present;
    final StatusCode status;

    ReadyTarget(
        RiverDirectory parent,
        String name,
        FileIdentity identity,
        boolean present,
        StatusCode status) {
      this.parent = parent;
      this.name = name;
      this.identity = identity;
      this.present = present;
      this.status = status;
    }

    static ReadyTarget failure(StatusCode status) {
      return new ReadyTarget(null, null, null, false, status);
    }
  }

  static final class ReadResult {
    final StatusCode status;
    final byte[] bytes;

    ReadResult(StatusCode status, byte[] bytes) {
      this.status = status;
      this.bytes = bytes;
    }
  }

  private static final class Envelope {
    final String[] fields;
    final String checksum;

    Envelope(String[] fields, String checksum) {
      this.fields = fields;
      this.checksum = checksum;
    }
  }
}

package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;

final class RiverDaemonRuntimeModel {
  private RiverDaemonRuntimeModel() {
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

    boolean matches(String expectedDatadir, io.riverdb.base.id.DatabaseIncarnation incarnation,
        RiverDaemonIdentityRecords.LockRecord owner) {
      return expectedDatadir.equals(datadir) && high == incarnation.high() && low == incarnation.low()
          && pid == owner.pid && start == owner.start && nonce.equals(owner.nonce)
          && RiverDaemonRuntimeStorage.validAddress(address) && port >= 1 && port <= 65535
          && RiverDaemonRuntimeStorage.validDirectoryPath(clientConfig) && generation > 0
          && ("none".equals(readyFile) || RiverDaemonRuntimeStorage.validDirectoryPath(readyFile));
    }
  }

  static final class ReadyRecord {
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
          && java.nio.file.Path.of(expectedDatadir).resolve("database").toString().equals(data)
          && java.nio.file.Path.of(expectedDatadir).resolve("instance.properties").toString()
              .equals(identity)
          && expectedRuntimePath.equals(runtimeFile) && address.equals(runtime.address)
          && port == runtime.port && pid == runtime.pid
          && RiverDaemonRuntimeCodec.protocolTag().equals(protocol)
          && "tls-v1.3".equals(transport) && clientConfig.equals(runtime.clientConfig)
          && certificate.matches("[0-9a-f]{64}") && nonce.equals(runtime.nonce)
          && "ready".equals(status);
    }
  }

  static final class ReadyTarget {
    final RiverDirectory parent;
    final String name;
    final FileIdentity identity;
    final boolean present;
    final StatusCode status;

    ReadyTarget(RiverDirectory parent, String name, FileIdentity identity,
        boolean present, StatusCode status) {
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
}

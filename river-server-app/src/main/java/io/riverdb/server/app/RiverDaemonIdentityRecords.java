package io.riverdb.server.app;

import io.riverdb.base.id.DatabaseIncarnation;
import java.nio.file.Path;

/** Concrete codecs and validated values for the bounded riverd identity records. */
final class RiverDaemonIdentityRecords {
  static final String INSTANCE_FORMAT = "riverd-instance-v1";
  static final String BOOTSTRAP_FORMAT = "riverd-bootstrap-v3";
  static final String LOCK_FORMAT = "riverd-lock-v2";

  private RiverDaemonIdentityRecords() {
  }

  static InstanceRecord parseInstance(byte[] bytes) {
    RiverDaemonRecordEnvelope.Envelope envelope =
        RiverDaemonRecordEnvelope.decodePadded(bytes, 4, INSTANCE_FORMAT);
    if (envelope == null || !"initial-wal-generation=1".equals(envelope.fields[3])) return null;
    try {
      long high = canonicalLong(value(envelope.fields[1], "database-incarnation-high="));
      long low = canonicalLong(value(envelope.fields[2], "database-incarnation-low="));
      return new InstanceRecord(DatabaseIncarnation.of(high, low), 1L);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static LockRecord parseLock(byte[] bytes) {
    RiverDaemonRecordEnvelope.Envelope envelope =
        RiverDaemonRecordEnvelope.decodePadded(bytes, 7, LOCK_FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      long high = canonicalLong(value(fields[2], "database-incarnation-high="));
      long low = canonicalLong(value(fields[3], "database-incarnation-low="));
      long pid = canonicalLong(value(fields[4], "pid="));
      long start = canonicalLong(value(fields[5], "process-start-epoch-millis="));
      String datadir = value(fields[1], "datadir=");
      String nonce = value(fields[6], "owner-nonce=");
      if (!DatabaseIncarnation.of(high, low).isValid() || pid <= 0 || start < 0
          || !validDatadir(datadir)
          || !nonce.matches("[0-9a-f]{32}")) return null;
      return new LockRecord(datadir, high, low, pid, start, nonce);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  static BootstrapRecord parseBootstrap(byte[] bytes) {
    RiverDaemonRecordEnvelope.Envelope envelope =
        RiverDaemonRecordEnvelope.decodePadded(bytes, 10, BOOTSTRAP_FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      long high = canonicalLong(value(fields[1], "database-incarnation-high="));
      long low = canonicalLong(value(fields[2], "database-incarnation-low="));
      long pid = canonicalLong(value(fields[3], "pid="));
      long start = canonicalLong(value(fields[4], "process-start-epoch-millis="));
      String nonce = value(fields[5], "attempt-nonce=");
      String database = value(fields[6], "database-name=");
      String security = value(fields[7], "security-name=");
      String staging = value(fields[8], "staging-name=");
      String instanceStage = value(fields[9], "instance-stage-name=");
      if (!DatabaseIncarnation.of(high, low).isValid() || pid <= 0 || start < 0
          || !nonce.matches("[0-9a-f]{32}")
          || !RiverDaemonIdentity.DATABASE_NAME.equals(database)
          || !RiverDaemonIdentity.SECURITY_NAME.equals(security)) return null;
      return new BootstrapRecord(DatabaseIncarnation.of(high, low), pid, start, nonce,
          staging, instanceStage);
    } catch (RuntimeException failure) {
      return null;
    }
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

  static boolean validDatadir(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) return false;
    }
    try {
      Path path = Path.of(value);
      return path.isAbsolute() && path.normalize().toString().equals(value)
          && path.getFileName() != null && path.getFileName().toString().indexOf('=') < 0;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  static final class InstanceRecord {
    final DatabaseIncarnation incarnation;
    final long generation;

    InstanceRecord(DatabaseIncarnation incarnation, long generation) {
      this.incarnation = incarnation;
      this.generation = generation;
    }
  }

  static final class LockRecord {
    final String datadir;
    final long high;
    final long low;
    final long pid;
    final long start;
    final String nonce;

    LockRecord(String datadir, long high, long low, long pid, long start, String nonce) {
      this.datadir = datadir;
      this.high = high;
      this.low = low;
      this.pid = pid;
      this.start = start;
      this.nonce = nonce;
    }
  }

  static final class BootstrapRecord {
    final DatabaseIncarnation incarnation;
    final long high;
    final long low;
    final long pid;
    final long start;
    final String nonce;
    final String stagingName;
    final String instanceStageName;

    BootstrapRecord(DatabaseIncarnation incarnation, long pid, long start, String nonce,
        String stagingName, String instanceStageName) {
      this.incarnation = incarnation;
      this.high = incarnation.high();
      this.low = incarnation.low();
      this.pid = pid;
      this.start = start;
      this.nonce = nonce;
      this.stagingName = stagingName;
      this.instanceStageName = instanceStageName;
    }
  }
}

package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Admits one runtime configuration and publishes it only after every check succeeds. */
final class RiverRuntimeConfigLoader {
  private RiverRuntimeConfigLoader() {}

  static StatusCode load(
      Path databaseDirectory,
      RiverRuntimeConfig.Result result,
      StatusDetail detail) {
    return load(databaseDirectory, Runtime.getRuntime().maxMemory(), result, detail);
  }

  static StatusCode load(
      Path databaseDirectory,
      long maximumMemoryBytes,
      RiverRuntimeConfig.Result result,
      StatusDetail detail) {
    reset(result, detail);
    if (databaseDirectory == null || maximumMemoryBytes <= 0
        || result == null || detail == null) {
      return invalidArguments(detail);
    }
    String temporaryDirectory;
    try {
      temporaryDirectory = System.getProperty("java.io.tmpdir");
    } catch (SecurityException failure) {
      detail.set(StatusCode.IO_FAILURE).append("cannot read java.io.tmpdir");
      return StatusCode.IO_FAILURE;
    }
    return loadAfterReset(
        databaseDirectory,
        maximumMemoryBytes,
        temporaryDirectory,
        result,
        detail,
        RuntimeSpillDirectory.DEFAULT_PROBE);
  }

  static StatusCode load(
      Path databaseDirectory,
      long maximumMemoryBytes,
      CharSequence temporaryDirectory,
      RiverRuntimeConfig.Result result,
      StatusDetail detail,
      RuntimeSpillProbe spillProbe) {
    reset(result, detail);
    if (databaseDirectory == null
        || maximumMemoryBytes <= 0
        || temporaryDirectory == null
        || result == null
        || detail == null
        || spillProbe == null) {
      return invalidArguments(detail);
    }
    return loadAfterReset(
        databaseDirectory,
        maximumMemoryBytes,
        temporaryDirectory,
        result,
        detail,
        spillProbe);
  }

  private static StatusCode loadAfterReset(
      Path databaseDirectory,
      long maximumMemoryBytes,
      CharSequence temporaryDirectory,
      RiverRuntimeConfig.Result result,
      StatusDetail detail,
      RuntimeSpillProbe spillProbe) {
    Path normalizedDatabase;
    try {
      normalizedDatabase = databaseDirectory.toAbsolutePath().normalize();
    } catch (InvalidPathException | SecurityException failure) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("invalid database directory: ")
          .append(databaseDirectory.toString());
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    RuntimeConfigProperties properties = new RuntimeConfigProperties();
    StatusCode status = RuntimeConfigFileParser.parse(
        normalizedDatabase.resolve(RiverRuntimeConfig.FILE_NAME), properties, detail);
    if (!status.isOk()) return status;
    RuntimeConfigAdmission admission = new RuntimeConfigAdmission();
    status = admission.admit(properties, maximumMemoryBytes, detail);
    if (!status.isOk()) return status;
    Path spillDirectory = RuntimeSpillDirectory.resolve(
        normalizedDatabase,
        properties.spillDirectory(),
        temporaryDirectory,
        detail,
        spillProbe);
    if (spillDirectory == null) return detail.code();
    result.set(admission.toConfig(spillDirectory));
    return StatusCode.OK;
  }

  private static void reset(
      RiverRuntimeConfig.Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
  }

  private static StatusCode invalidArguments(StatusDetail detail) {
    if (detail != null) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("invalid runtime configuration load arguments");
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}

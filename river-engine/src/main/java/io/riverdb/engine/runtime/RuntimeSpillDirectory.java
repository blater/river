package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves and probes the database-local spill-directory boundary. */
final class RuntimeSpillDirectory {
  static final RuntimeSpillProbe DEFAULT_PROBE = new RuntimeSpillProbe() {
    @Override
    public Path create(Path directory) throws IOException {
      return Files.createTempFile(directory, "river-probe-", ".tmp");
    }

    @Override
    public void delete(Path probe) throws IOException {
      Files.delete(probe);
    }
  };

  private RuntimeSpillDirectory() {}

  static Path resolve(
      Path databaseDirectory,
      String configured,
      CharSequence temporaryDirectory,
      StatusDetail detail,
      RuntimeSpillProbe spillProbe) {
    if (configured == null && RuntimeConfigText.asciiBlank(temporaryDirectory)) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("invalid java.io.tmpdir: empty path");
      return null;
    }
    Path requested;
    try {
      requested = configured == null
          ? Path.of(temporaryDirectory.toString())
          : Path.of(configured);
      if (!requested.isAbsolute()) requested = databaseDirectory.resolve(requested);
      requested = requested.toAbsolutePath().normalize();
      if (Files.exists(requested) && !Files.isDirectory(requested)) {
        return notDirectory(detail, requested);
      }
      Files.createDirectories(requested);
      Path real = requested.toRealPath();
      if (!Files.isDirectory(real)) return notDirectory(detail, real);
      Path probe = spillProbe.create(real);
      try {
        spillProbe.delete(probe);
      } catch (IOException failure) {
        detail.set(StatusCode.IO_FAILURE)
            .append("cannot remove spill probe: ")
            .append(probe.toString());
        return null;
      }
      return real;
    } catch (InvalidPathException failure) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("invalid spill directory: ")
          .append(configured == null ? temporaryDirectory : configured);
      return null;
    } catch (IOException | SecurityException failure) {
      detail.set(StatusCode.IO_FAILURE)
          .append("cannot access spill directory: ")
          .append(configured == null ? temporaryDirectory : configured);
      return null;
    }
  }

  private static Path notDirectory(StatusDetail detail, Path path) {
    detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
        .append("spill path is not a directory: ")
        .append(path.toString());
    return null;
  }
}

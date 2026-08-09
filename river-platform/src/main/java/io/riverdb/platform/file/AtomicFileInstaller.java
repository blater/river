package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Resumable same-directory file installation.
 *
 * <p>Each call attempts at most one named boundary. {@code OK} can therefore mean safe progress
 * rather than terminal completion; callers check {@link AtomicInstallProgress#isComplete()}.
 * {@code RETRY} with {@code completionPending=true} means an operation was applied but its
 * completion was withheld, and the next call polls that completion without repeating the
 * operation. {@link AtomicInstallPhase#RECOVERY_REQUIRED} requires reopen-based recovery.
 */
public interface AtomicFileInstaller {
  StatusCode advance(
      AtomicInstallRequest request,
      AtomicInstallProgress progress,
      AtomicInstallResult result);
}

package io.riverdb.platform.file.nio;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

final class NioDurableFile implements DurableFile {
  private static final int MAX_ZERO_PROGRESS = 16;

  private final NioDurableDirectory owner;
  private final FileChannel channel;
  private final long generation;
  private final int slot;
  private final long slotEpoch;
  private final PendingFileWriteDiagnostics.Entry pendingWriteDiagnostics;
  private final PersistedFileWriteDiagnostics.Entry persistedWriteDiagnostics;
  private final ByteBuffer extensionByte = ByteBuffer.allocate(1);
  /** Guards mapping identity, dirty publication, force pins, and terminal-state handoff. */
  private final Object mappedLifecycle = new Object();
  private volatile boolean closed;
  private volatile boolean generationRetired;
  private final NioMappedWindow mappedHeader;
  private final NioMappedWindow mappedData;
  private boolean mappedMetadataDirty;
  private long mappedMetadataEpoch;
  private boolean mappedForceActive;
  private long mappedForceEnd;
  private MappedForceGate mappedForceGate;

  NioDurableFile(
      NioDurableDirectory owner,
      FileChannel channel,
      long generation,
      int slot,
      long slotEpoch,
      Path path,
      FileIoMode mode) {
    this.owner = owner;
    this.channel = channel;
    this.generation = generation;
    this.slot = slot;
    this.slotEpoch = slotEpoch;
    mappedHeader = mode == FileIoMode.MAPPED ? new NioMappedWindow(channel, 4096) : null;
    mappedData = mode == FileIoMode.MAPPED ? new NioMappedWindow(channel, NioMappedWindow.BYTES) : null;
    pendingWriteDiagnostics = PendingFileWriteDiagnostics.register(path);
    persistedWriteDiagnostics = PersistedFileWriteDiagnostics.register(path);
  }

  @Override
  public StatusCode read(long position, ByteBuffer target, IoResult result) {
    if (target == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (position < 0 || target.isReadOnly()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (mappedData != null) return transferMapped(position, target, result, false);
    int transferred = 0;
    int zeroProgress = 0;
    try {
      while (target.hasRemaining()) {
        int read = channel.read(target, position + transferred);
        if (read < 0) {
          break;
        }
        if (read == 0) {
          if (++zeroProgress == MAX_ZERO_PROGRESS) {
            result.setBytesTransferred(transferred);
            owner.counters().recordRead(transferred);
            return StatusCode.RETRY;
          }
          continue;
        }
        zeroProgress = 0;
        transferred += read;
      }
      result.setBytesTransferred(transferred);
      owner.counters().recordRead(transferred);
      return StatusCode.OK;
    } catch (IOException failure) {
      result.setBytesTransferred(transferred);
      owner.counters().recordRead(transferred);
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode write(long position, ByteBuffer source, IoResult result) {
    if (source == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (position < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (position > Long.MAX_VALUE - source.remaining()) return StatusCode.RESOURCE_EXHAUSTED;
    if (mappedData != null) return transferMapped(position, source, result, true);
    int transferred = 0;
    int initialPosition = source.position();
    int zeroProgress = 0;
    try {
      while (source.hasRemaining()) {
        int written = writeChannel(
            source, position + transferred,
            PendingFileWriteDiagnostics.OperationKind.POSITIONAL_WRITE);
        if (written == 0) {
          if (++zeroProgress == MAX_ZERO_PROGRESS) {
            result.setBytesTransferred(transferred);
            owner.counters().recordWrite(transferred);
            return StatusCode.RETRY;
          }
          continue;
        }
        zeroProgress = 0;
        transferred += written;
      }
      result.setBytesTransferred(transferred);
      owner.counters().recordWrite(transferred);
      return StatusCode.OK;
    } catch (IOException failure) {
      int observed = source.position() - initialPosition;
      int completed = Math.max(transferred, observed);
      result.setBytesTransferred(completed);
      owner.counters().recordWrite(completed);
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode force(ForceMode mode) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (mode == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return forceInternal(0, 0, mode, false);
  }

  @Override
  public StatusCode force(long startOffset, long endOffset, ForceMode mode) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (mode == null || startOffset < 0 || endOffset <= startOffset) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return forceInternal(startOffset, endOffset, mode, true);
  }

  private StatusCode forceInternal(
      long startOffset, long endOffset, ForceMode mode, boolean range) {
    if (mappedData == null) {
      try {
        channel.force(mode == ForceMode.CONTENT_AND_METADATA);
        owner.counters().recordForce();
        return StatusCode.OK;
      } catch (IOException failure) {
        return NioStatusMapper.known(failure);
      }
    }
    boolean interrupted = false;
    boolean capturedHeader;
    boolean capturedData;
    boolean capturedMetadata;
    long capturedMetadataEpoch;
    MappedForceGate forceGate;
    try {
      synchronized (mappedLifecycle) {
        interrupted = awaitMappedForceLocked();
        if (closed) return closedStatus();
        mappedForceActive = true;
        mappedForceEnd = range ? endOffset : Long.MAX_VALUE;
        capturedHeader = mappedHeader.captureForce(startOffset, endOffset, range);
        capturedData = mappedData.captureForce(startOffset, endOffset, range);
        capturedMetadata = mode == ForceMode.CONTENT_AND_METADATA && mappedMetadataDirty;
        capturedMetadataEpoch = mappedMetadataEpoch;
        forceGate = mappedForceGate;
      }
      boolean succeeded = false;
      StatusCode status;
      try {
        if (forceGate != null) {
          forceGate.afterSnapshot(capturedHeader || capturedData, capturedMetadata);
        }
        mappedHeader.forceCaptured();
        mappedData.forceCaptured();
        if (capturedMetadata) channel.force(true);
        succeeded = true;
        owner.counters().recordForce();
        status = StatusCode.OK;
      } catch (UncheckedIOException failure) {
        status = NioStatusMapper.known(failure.getCause());
      } catch (IOException failure) {
        status = NioStatusMapper.known(failure);
      } finally {
        synchronized (mappedLifecycle) {
          mappedHeader.completeCapturedForce(succeeded);
          mappedData.completeCapturedForce(succeeded);
          if (succeeded && capturedMetadata && mappedMetadataEpoch == capturedMetadataEpoch) {
            mappedMetadataDirty = false;
          }
          mappedForceActive = false;
          mappedForceEnd = 0;
          mappedLifecycle.notifyAll();
        }
      }
      return status;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @Override
  public StatusCode truncate(long sizeBytes) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (sizeBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (mappedData == null) return resize(sizeBytes);
    boolean interrupted = false;
    try {
      synchronized (mappedLifecycle) {
        interrupted = awaitMappedForceLocked();
        if (closed) return closedStatus();
        try {
          try { mappedData.release(); }
          finally { mappedHeader.release(); }
          markMappedMetadataDirtyLocked();
          return resize(sizeBytes);
        } catch (UncheckedIOException failure) {
          return NioStatusMapper.known(failure.getCause());
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private StatusCode resize(long sizeBytes) {
    try {
      long currentSize = channel.size();
      if (sizeBytes < currentSize) {
        channel.truncate(sizeBytes);
      } else if (sizeBytes > currentSize) {
        extensionByte.clear();
        int zeroProgress = 0;
        while (extensionByte.hasRemaining()) {
          int written = writeChannel(
              extensionByte, sizeBytes - 1,
              PendingFileWriteDiagnostics.OperationKind.RESIZE_GROWTH);
          if (written == 0 && ++zeroProgress == MAX_ZERO_PROGRESS) return StatusCode.RETRY;
        }
        owner.counters().recordWrite(1);
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode size(FileSizeResult result) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      result.setSizeBytes(channel.size());
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode close() {
    synchronized (mappedLifecycle) {
      if (closed) return StatusCode.CLOSED;
      closed = true;
    }
    StatusCode mappedStatus = closeMappings();
    StatusCode channelStatus;
    try {
      channelStatus = owner.closeHandle(this, channel, generation, slot, slotEpoch);
    } finally {
      retireWriteDiagnosticsAfterChannelClose();
    }
    return mappedStatus.isOk() ? channelStatus : mappedStatus;
  }

  private int writeChannel(
      ByteBuffer source, long position,
      PendingFileWriteDiagnostics.OperationKind operationKind) throws IOException {
    PersistedFileWriteDiagnostics.Entry persistedDiagnostics = persistedWriteDiagnostics;
    return persistedDiagnostics == null
        ? writeOnPinnedCarrier(source, position, operationKind, 0)
        : persistedDiagnostics.write(this, source, position, operationKind);
  }

  // Invoked by the opt-in JNI trampoline on its pinned carrier.
  int writeOnPinnedCarrier(
      ByteBuffer source,
      long position,
      PendingFileWriteDiagnostics.OperationKind operationKind,
      long nativeThreadId) throws IOException {
    PersistedFileWriteDiagnostics.Entry persistedDiagnostics = persistedWriteDiagnostics;
    PersistedFileWriteDiagnostics.Invocation persisted = persistedDiagnostics == null
        ? null : persistedDiagnostics.begin(
            operationKind, position, source.remaining(), nativeThreadId);
    PendingFileWriteDiagnostics.Entry diagnostics = pendingWriteDiagnostics;
    int tracking = diagnostics == null
        ? 0 : diagnostics.begin(operationKind, position, source.remaining());
    int written = 0;
    IOException targetFailure = null;
    try {
      written = channel.write(source, position);
    } catch (IOException failure) {
      targetFailure = failure;
    } finally {
      if (diagnostics != null) diagnostics.end(tracking);
    }
    if (targetFailure != null) {
      if (persistedDiagnostics != null) persistedDiagnostics.failed(persisted, targetFailure);
      throw targetFailure;
    }
    if (persistedDiagnostics != null) persistedDiagnostics.returned(persisted, written);
    return written;
  }

  private void retireWriteDiagnosticsAfterChannelClose() {
    PendingFileWriteDiagnostics.Entry diagnostics = pendingWriteDiagnostics;
    if (diagnostics != null && !channel.isOpen()) diagnostics.retire();
  }

  private StatusCode transferMapped(long position, ByteBuffer buffer, IoResult result, boolean write) {
    int initial = buffer.position();
    StatusCode status = StatusCode.OK;
    boolean interrupted = false;
    try {
      try {
        synchronized (mappedLifecycle) {
          long end = write ? Long.MAX_VALUE : channel.size();
          while (buffer.hasRemaining() && position < end) {
            NioMappedWindow window = position < 4096 ? mappedHeader : mappedData;
            if (mappedForceActive
                && (!window.covers(position) || (write && position < mappedForceEnd))) {
              interrupted |= awaitMappedForceLocked();
            }
            if (closed) {
              status = closedStatus();
              break;
            }
            if (window.map(position, write)) markMappedMetadataDirtyLocked();
            int count = (int) Math.min(
                buffer.remaining(), Math.min(window.remaining(position), end - position));
            if (position < 4096) count = (int) Math.min(count, 4096 - position);
            if (write) window.write(position, buffer, count);
            else window.read(position, buffer, count);
            position += count;
          }
        }
      } catch (UncheckedIOException failure) {
        status = NioStatusMapper.known(failure.getCause());
      } catch (IOException failure) {
        status = NioStatusMapper.known(failure);
      } catch (OutOfMemoryError failure) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      int transferred = buffer.position() - initial;
      result.setBytesTransferred(transferred);
      if (write) owner.counters().recordWrite(transferred);
      else owner.counters().recordRead(transferred);
      return status;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private StatusCode closeMappings() {
    if (mappedData == null) return StatusCode.OK;
    boolean interrupted = false;
    try {
      synchronized (mappedLifecycle) {
        interrupted = awaitMappedForceLocked();
        try { mappedData.close(); }
        finally { mappedHeader.close(); }
      }
      return StatusCode.OK;
    } catch (UncheckedIOException failure) {
      return NioStatusMapper.known(failure.getCause());
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  void installMappedForceGate(MappedForceGate forceGate) {
    synchronized (mappedLifecycle) {
      if (mappedForceActive) throw new IllegalStateException("mapped force is active");
      mappedForceGate = forceGate;
    }
  }

  private boolean awaitMappedForceLocked() {
    boolean interrupted = false;
    while (mappedForceActive) {
      try {
        mappedLifecycle.wait();
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    return interrupted;
  }

  private void markMappedMetadataDirtyLocked() {
    mappedMetadataDirty = true;
    mappedMetadataEpoch++;
  }

  private StatusCode closedStatus() {
    return generationRetired ? StatusCode.CANCELLED : StatusCode.CLOSED;
  }

  StatusCode closeForGenerationChange() {
    synchronized (mappedLifecycle) {
      generationRetired = true;
      closed = true;
    }
    StatusCode mappedStatus = closeMappings();
    try {
      channel.close();
      return mappedStatus;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    } finally {
      retireWriteDiagnosticsAfterChannelClose();
    }
  }

  @FunctionalInterface
  interface MappedForceGate {
    void afterSnapshot(boolean dataDirty, boolean metadataDirty) throws IOException;
  }
}

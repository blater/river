package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Database-shared synchronized cache for materialized scratch pages. Owner identities are issued,
 * closed, and never reused by the scratch-owner lifecycle above this pool.
 */
public final class SqlMaterializedPagePool {
  /** Conservative addressability charge for bounded descriptors and the two primitive indexes. */
  public static final int MAXIMUM_METADATA_BYTES_PER_FRAME = 256;

  private final int pageBytes;
  private final SqlMaterializedPageFrames frames;
  private final SqlMaterializedLongPairIndex pages;
  private final SqlMaterializedOwnerIndex owners;
  private final SqlMaterializedPageLocation location = new SqlMaterializedPageLocation();
  private final SqlMaterializedPageClock clock = new SqlMaterializedPageClock();
  private final SqlMaterializedScratchFileCodec.PageHeader loadedHeader =
      new SqlMaterializedScratchFileCodec.PageHeader();
  private final StatusDetail loadedDetail = new StatusDetail(160);
  private int clockHand;
  private boolean closed;

  private SqlMaterializedPagePool(
      int physicalPageBytes,
      SqlMaterializedPageFrames cacheFrames,
      int pageIndexCapacity,
      int ownerIndexCapacity) {
    pageBytes = physicalPageBytes;
    frames = cacheFrames;
    pages = new SqlMaterializedLongPairIndex(pageIndexCapacity);
    owners = new SqlMaterializedOwnerIndex(ownerIndexCapacity);
  }

  public static StatusCode create(
      int pageBytes, int frameCount, SqlMaterializedPagePoolResult target) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (pageBytes <= SqlMaterializedPageMapping.PAGE_HEADER_BYTES
        || (pageBytes & 7) != 0 || frameCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int pageCapacity = SqlMaterializedLongPairIndex.capacity(frameCount, 2);
    int ownerCapacity = SqlMaterializedLongPairIndex.capacity(frameCount, 2);
    if (pageCapacity < 0 || ownerCapacity < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    try {
      SqlMaterializedPageFrames localFrames = new SqlMaterializedPageFrames(frameCount);
      SqlMaterializedPagePool localPool = new SqlMaterializedPagePool(
          pageBytes, localFrames, pageCapacity, ownerCapacity);
      target.set(localPool);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public synchronized StatusCode reserve(long owner, int count) {
    if (closed) return StatusCode.CLOSED;
    if (owner <= 0 || count <= 0 || count > frames.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (ownerFailed(owner)) return StatusCode.IO_FAILURE;
    int retained = owners.reservation(owner);
    if (retained > frames.count() - count) return StatusCode.RESOURCE_EXHAUSTED;
    clock.begin(clockHand, frames.count());
    int selected = selectReservationCandidates(owner, count);
    if (selected < count) {
      clearSelected(selected);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = prepareReservation(selected);
    if (!status.isOk()) {
      clearSelected(selected);
      return status;
    }
    if (!owners.reserve(owner, count)) {
      clearSelected(selected);
      return StatusCode.INVARIANT_BROKEN;
    }
    for (int index = 0; index < selected; index++) {
      int frame = frames.candidates[index];
      frames.reservationOwners[frame] = owner;
      frames.selected[frame] = false;
    }
    clockHand = clock.cursor();
    return StatusCode.OK;
  }

  public synchronized StatusCode releaseReservation(long owner) {
    if (closed) return StatusCode.CLOSED;
    if (owner <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return releaseReservationInternal(owner)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  public synchronized StatusCode releaseReservation(long owner, int count) {
    if (closed) return StatusCode.CLOSED;
    if (owner <= 0 || count <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return releaseReservationInternal(owner, count)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  synchronized StatusCode pinExisting(
      SqlMaterializedPageIo io, long owner, long pageNumber,
      SqlMaterializedPagePin target) {
    return pin(io, owner, pageNumber, true, target);
  }

  synchronized StatusCode pinNew(
      SqlMaterializedPageIo io, long owner, long pageNumber,
      SqlMaterializedPagePin target) {
    return pin(io, owner, pageNumber, false, target);
  }

  synchronized StatusCode markDirty(SqlMaterializedPagePin pin) {
    int frame = activeFrame(pin);
    if (frame < 0) return StatusCode.NOT_OWNER;
    if (frames.state[frame] == SqlMaterializedPageFrames.RETIRED) {
      return StatusCode.CLOSED;
    }
    frames.dirty[frame] = true;
    frames.referenced[frame] = true;
    pin.markDirty();
    return StatusCode.OK;
  }

  public synchronized StatusCode flushFile(long owner, long fileIdentity) {
    if (closed) return StatusCode.CLOSED;
    if (owner <= 0 || fileIdentity <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (matchingPins(owner, fileIdentity) != 0) return StatusCode.INVARIANT_BROKEN;
    for (int frame = 0; frame < frames.count(); frame++) {
      if (frames.fileOwners[frame] == owner
          && frames.fileIdentities[frame] == fileIdentity) {
        StatusCode status = prepare(frame);
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  synchronized StatusCode unpin(SqlMaterializedPagePin pin) {
    int frame = activeFrame(pin);
    if (frame < 0) {
      if (pin != null && pin.pool() == this) pin.clear();
      return StatusCode.NOT_OWNER;
    }
    frames.pins[frame]--;
    pin.clear();
    if (frames.pins[frame] == 0
        && frames.state[frame] == SqlMaterializedPageFrames.RETIRED) {
      finishRetired(frame);
    }
    return StatusCode.OK;
  }

  public synchronized StatusCode invalidateFile(long owner, long fileIdentity) {
    if (owner <= 0 || fileIdentity <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pinned = matchingPins(owner, fileIdentity);
    if (pinned != 0) return StatusCode.INVARIANT_BROKEN;
    for (int frame = 0; frame < frames.count(); frame++) {
      if (frames.fileOwners[frame] == owner
          && frames.fileIdentities[frame] == fileIdentity) {
        long reservation = frames.reservationOwners[frame];
        discard(frame, false);
        frames.reservationOwners[frame] = reservation;
      }
    }
    return StatusCode.OK;
  }

  public synchronized StatusCode invalidateOwner(long owner) {
    if (owner <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pinned = matchingPins(owner, 0);
    releaseReservationInternal(owner);
    owners.clear(owner);
    for (int frame = 0; frame < frames.count(); frame++) {
      if (frames.fileOwners[frame] == owner) retireOrDiscard(frame);
    }
    return pinned == 0 ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  public synchronized StatusCode close() {
    if (closed) return StatusCode.OK;
    closed = true;
    StatusCode status = StatusCode.OK;
    for (int frame = 0; frame < frames.count(); frame++) {
      frames.reservationOwners[frame] = 0;
      if (frames.pins[frame] != 0) {
        status = StatusCode.INVARIANT_BROKEN;
        retire(frame);
      } else {
        discard(frame, true);
      }
    }
    return status;
  }

  synchronized int allocatedFrameCount() {
    int count = 0;
    for (ByteBuffer buffer : frames.buffers) {
      if (buffer != null) count++;
    }
    return count;
  }

  synchronized int reservationCount(long owner) {
    return owners.reservation(owner);
  }

  synchronized int pinnedCount(long owner) {
    return matchingPins(owner, 0);
  }

  public int pageBytes() { return pageBytes; }
  public int frameCount() { return frames.count(); }

  private StatusCode pin(
      SqlMaterializedPageIo io, long owner, long pageNumber, boolean existing,
      SqlMaterializedPagePin target) {
    StatusCode validation = validatePin(io, owner, pageNumber, target);
    if (!validation.isOk()) return validation;
    if (ownerFailed(owner)) return StatusCode.IO_FAILURE;
    long filePosition = location.filePosition();
    int cached = pages.find(io.fileIdentity(), pageNumber);
    if (cached >= 0) return pinCached(cached, owner, existing, target);
    boolean ownerReservation = hasReservation(owner);
    clock.begin(clockHand, frames.count());
    StatusCode evictionFailure = StatusCode.OK;
    int frame;
    while ((frame = clock.next(frames, owner, ownerReservation)) >= 0) {
      StatusCode status = prepare(frame);
      if (!status.isOk()) {
        evictionFailure = status;
        continue;
      }
      status = allocate(frame);
      if (!status.isOk()) return status;
      evict(frame);
      status = load(frame, io, pageNumber, filePosition, existing);
      if (!status.isOk()) return status;
      status = publishPin(frame, io, owner, pageNumber, target);
      if (!status.isOk()) return status;
      clockHand = clock.cursor();
      return StatusCode.OK;
    }
    return evictionFailure.isOk() ? StatusCode.RESOURCE_EXHAUSTED : evictionFailure;
  }

  private StatusCode validatePin(
      SqlMaterializedPageIo io, long owner, long pageNumber,
      SqlMaterializedPagePin target) {
    if (closed) return StatusCode.CLOSED;
    if (io == null || io.fileIdentity() <= 0 || owner <= 0 || pageNumber < 0
        || target == null || target.active()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return SqlMaterializedPageMapping.physicalPosition(pageNumber, pageBytes, location);
  }

  private StatusCode pinCached(
      int frame, long owner, boolean existing, SqlMaterializedPagePin target) {
    if (!existing) return StatusCode.CONFLICT;
    if (frames.fileOwners[frame] != owner) return StatusCode.NOT_OWNER;
    long reserved = frames.reservationOwners[frame];
    if (reserved != 0 && reserved != owner) return StatusCode.RESOURCE_EXHAUSTED;
    if (frames.pins[frame] != 0) return StatusCode.RESOURCE_EXHAUSTED;
    frames.pins[frame] = 1;
    frames.referenced[frame] = true;
    frames.buffers[frame].clear().order(ByteOrder.BIG_ENDIAN);
    target.attach(
        this, frame, frames.generations[frame], owner, frames.buffers[frame],
        frames.dirty[frame]);
    return StatusCode.OK;
  }

  private int selectReservationCandidates(long owner, int count) {
    int selected = 0;
    int frame;
    while (selected < count && (frame = clock.next(frames, owner, false)) >= 0) {
      frames.selected[frame] = true;
      frames.candidates[selected++] = frame;
    }
    return selected;
  }

  private StatusCode prepareReservation(int selected) {
    for (int index = 0; index < selected; index++) {
      StatusCode status = prepare(frames.candidates[index]);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private void clearSelected(int selected) {
    for (int index = 0; index < selected; index++) {
      frames.selected[frames.candidates[index]] = false;
    }
  }

  private StatusCode prepare(int frame) {
    if (frames.failed[frame]) return StatusCode.IO_FAILURE;
    if (frames.state[frame] != SqlMaterializedPageFrames.READY
        || !frames.dirty[frame]) return StatusCode.OK;
    StatusCode status = SqlMaterializedPageMapping.physicalPosition(
        frames.pageNumbers[frame], pageBytes, location);
    if (!status.isOk()) return status;
    status = SqlMaterializedScratchFileCodec.encodePageHeader(
        frames.buffers[frame], frames.fileIdentities[frame], frames.pageNumbers[frame],
        frames.buffers[frame].getInt(24));
    if (!status.isOk()) return status;
    frames.state[frame] = SqlMaterializedPageFrames.WRITING;
    status = frames.io[frame].write(location.filePosition(), frames.buffers[frame]);
    frames.state[frame] = SqlMaterializedPageFrames.READY;
    if (status.isOk()) {
      frames.dirty[frame] = false;
    } else {
      frames.failed[frame] = true;
      owners.fail(frames.fileOwners[frame]);
    }
    return status;
  }

  private StatusCode allocate(int frame) {
    if (frames.buffers[frame] != null) return StatusCode.OK;
    try {
      frames.buffers[frame] = ByteBuffer.allocateDirect(pageBytes);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode load(
      int frame,
      SqlMaterializedPageIo io,
      long pageNumber,
      long filePosition,
      boolean existing) {
    ByteBuffer buffer = frames.buffers[frame];
    frames.state[frame] = SqlMaterializedPageFrames.LOADING;
    StatusCode status;
    if (existing) {
      status = io.read(filePosition, buffer);
      if (status.isOk()) {
        loadedDetail.reset();
        status = SqlMaterializedScratchFileCodec.validatePageHeader(
            buffer, io.fileIdentity(), pageNumber, loadedHeader, loadedDetail);
      }
    } else {
      zero(buffer);
      status = StatusCode.OK;
    }
    if (!status.isOk()) discard(frame, false);
    return status;
  }

  private StatusCode publishPin(
      int frame, SqlMaterializedPageIo io, long owner, long pageNumber,
      SqlMaterializedPagePin target) {
    if (!pages.put(io.fileIdentity(), pageNumber, frame)) {
      discard(frame, false);
      return StatusCode.INVARIANT_BROKEN;
    }
    frames.io[frame] = io;
    frames.fileIdentities[frame] = io.fileIdentity();
    frames.pageNumbers[frame] = pageNumber;
    frames.fileOwners[frame] = owner;
    frames.generations[frame] = nextGeneration(frames.generations[frame]);
    frames.pins[frame] = 1;
    frames.dirty[frame] = false;
    frames.failed[frame] = false;
    frames.referenced[frame] = true;
    frames.state[frame] = SqlMaterializedPageFrames.READY;
    frames.buffers[frame].clear().order(ByteOrder.BIG_ENDIAN);
    target.attach(this, frame, frames.generations[frame], owner, frames.buffers[frame], false);
    return StatusCode.OK;
  }

  private int activeFrame(SqlMaterializedPagePin pin) {
    if (pin == null || pin.pool() != this) return -1;
    int frame = pin.frame();
    if (frame < 0 || frame >= frames.count()) return -1;
    byte state = frames.state[frame];
    if (state != SqlMaterializedPageFrames.READY
        && state != SqlMaterializedPageFrames.RETIRED
        || frames.generations[frame] != pin.generation()
        || frames.fileOwners[frame] != pin.owner()
        || frames.pins[frame] != 1) return -1;
    return frame;
  }

  private void evict(int frame) {
    if (frames.state[frame] == SqlMaterializedPageFrames.READY) {
      pages.remove(frames.fileIdentities[frame], frames.pageNumbers[frame]);
    }
    clearDescriptor(frame);
  }

  private void retireOrDiscard(int frame) {
    if (frames.pins[frame] == 0) discard(frame, false);
    else retire(frame);
  }

  private void retire(int frame) {
    if (frames.state[frame] == SqlMaterializedPageFrames.READY) {
      pages.remove(frames.fileIdentities[frame], frames.pageNumbers[frame]);
    }
    frames.reservationOwners[frame] = 0;
    frames.dirty[frame] = false;
    frames.failed[frame] = false;
    frames.referenced[frame] = false;
    frames.selected[frame] = false;
    frames.state[frame] = SqlMaterializedPageFrames.RETIRED;
  }

  private void finishRetired(int frame) {
    discard(frame, closed);
  }

  private void discard(int frame, boolean releaseBuffer) {
    if (frames.state[frame] == SqlMaterializedPageFrames.READY) {
      pages.remove(frames.fileIdentities[frame], frames.pageNumbers[frame]);
    }
    clearDescriptor(frame);
    if (releaseBuffer) frames.buffers[frame] = null;
  }

  private void clearDescriptor(int frame) {
    frames.io[frame] = null;
    frames.fileIdentities[frame] = 0;
    frames.pageNumbers[frame] = 0;
    frames.fileOwners[frame] = 0;
    frames.generations[frame] = nextGeneration(frames.generations[frame]);
    frames.pins[frame] = 0;
    frames.dirty[frame] = false;
    frames.failed[frame] = false;
    frames.referenced[frame] = false;
    frames.selected[frame] = false;
    frames.state[frame] = SqlMaterializedPageFrames.FREE;
  }

  private int matchingPins(long owner, long fileIdentity) {
    int count = 0;
    for (int frame = 0; frame < frames.count(); frame++) {
      if (frames.fileOwners[frame] == owner
          && (fileIdentity == 0 || frames.fileIdentities[frame] == fileIdentity)) {
        count += frames.pins[frame];
      }
    }
    return count;
  }

  private boolean releaseReservationInternal(long owner) {
    int count = owners.reservation(owner);
    return count > 0 && releaseReservationInternal(owner, count);
  }

  private boolean releaseReservationInternal(long owner, int count) {
    int available = 0;
    for (int frame = 0; frame < frames.count(); frame++) {
      if (frames.reservationOwners[frame] == owner) available++;
    }
    if (available < count) return false;
    if (!owners.release(owner, count)) return false;
    int remaining = count;
    for (int frame = 0; frame < frames.count(); frame++) {
      if (remaining > 0 && frames.reservationOwners[frame] == owner) {
        frames.reservationOwners[frame] = 0;
        remaining--;
      }
    }
    return remaining == 0;
  }

  private boolean hasReservation(long owner) {
    return owners.reservation(owner) > 0;
  }

  private boolean ownerFailed(long owner) {
    return owners.failed(owner);
  }

  private void zero(ByteBuffer buffer) {
    buffer.clear();
    while (buffer.remaining() >= Long.BYTES) buffer.putLong(0);
    while (buffer.hasRemaining()) buffer.put((byte) 0);
    buffer.clear();
  }

  private static long nextGeneration(long generation) {
    return generation == Long.MAX_VALUE ? 1 : generation + 1;
  }
}

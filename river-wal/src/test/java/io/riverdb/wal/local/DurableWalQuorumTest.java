package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class DurableWalQuorumTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(311, 313);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static volatile long allocationGuard;

  @Test
  void forcesEachRecordToARealDurableQuorum(@TempDir Path root) throws IOException {
    Node primary = createNode(root.resolve("primary"));
    Node followerOne = createNode(root.resolve("follower-one"));
    Node followerTwo = createNode(root.resolve("follower-two"));
    assertEquals(
        StatusCode.OK,
        primary.wal.enableDurableQuorum(
            new LocalWal[] {followerOne.wal, followerTwo.wal}, 2));
    long primaryForces = primary.counters.forceCalls();
    long followerOneForces = followerOne.counters.forceCalls();
    long followerTwoForces = followerTwo.counters.forceCalls();

    appendUnforced(primary.wal, 17, 1, 101);
    appendUnforced(primary.wal, 19, 2, 103);
    LocalWalForceResult force = new LocalWalForceResult();
    assertEquals(StatusCode.OK, primary.wal.forcePending(force));
    assertEquals(2, force.recordCount());
    assertEquals(2, primary.wal.requiredDurableNodeCount());
    assertEquals(3, primary.wal.availableDurableNodeCount());
    assertEquals(2, primary.wal.quorumDurableCommitSequence());
    assertEquals(4 * Long.BYTES, primary.wal.replicatedPayloadBytes());
    assertEquals(primaryForces + 1, primary.counters.forceCalls());
    assertEquals(followerOneForces + 1, followerOne.counters.forceCalls());
    assertEquals(followerTwoForces + 1, followerTwo.counters.forceCalls());
    assertEquals(primary.wal.durableEnd(), followerOne.wal.durableEnd());
    assertEquals(primary.wal.durableEnd(), followerTwo.wal.durableEnd());
    assertEquals(StatusCode.OK, primary.wal.releaseForcedBatch());

    long firstRecord = 64;
    close(primary);
    close(followerOne);
    close(followerTwo);
    primary = reopenNode(root.resolve("primary"));
    followerOne = reopenNode(root.resolve("follower-one"));
    followerTwo = reopenNode(root.resolve("follower-two"));
    assertRecord(primary.wal, firstRecord, 17, 1, 101);
    assertRecord(followerOne.wal, firstRecord, 17, 1, 101);
    assertRecord(followerTwo.wal, firstRecord, 17, 1, 101);
    assertEquals(2, primary.wal.currentCommitSequence());
    assertEquals(2, followerOne.wal.currentCommitSequence());
    assertEquals(2, followerTwo.wal.currentCommitSequence());
    close(primary);
    close(followerOne);
    close(followerTwo);
  }

  @Test
  void authenticatesAndReplicatesAStreamAcrossMultipleForceBatches(
      @TempDir Path root) throws IOException {
    Node primary = createNode(root.resolve("primary"));
    Node followerOne = createNode(root.resolve("follower-one"));
    Node followerTwo = createNode(root.resolve("follower-two"));
    assertEquals(StatusCode.OK, primary.wal.enableDurableQuorum(
        new LocalWal[] {followerOne.wal, followerTwo.wal}, 2));

    LocalWalLogicalStream stream = new LocalWalLogicalStream();
    assertEquals(StatusCode.OK, primary.wal.beginLogicalStream(37, 9, 2, stream));
    assertEquals(StatusCode.CONFLICT,
        primary.wal.reserve(1, new LocalWalReservation()));
    LocalWalGroupAppendResult append = new LocalWalGroupAppendResult();
    LocalWalForceResult force = new LocalWalForceResult();
    assertEquals(StatusCode.OK,
        primary.wal.appendLogicalStreamContinuation(
            stream, new LongBatch(211, 223), append));
    assertEquals(StatusCode.OK, primary.wal.forceLogicalStreamBatch(stream, force));
    assertEquals(StatusCode.OK, primary.wal.releaseLogicalStreamBatch(stream));
    assertEquals(true, stream.isActive());

    assertEquals(StatusCode.OK,
        primary.wal.appendLogicalStreamFinal(
            stream, new LongBatch(227, 229), 7, append));
    assertEquals(StatusCode.OK, primary.wal.forceLogicalStreamBatch(stream, force));
    assertEquals(StatusCode.OK, primary.wal.releaseLogicalStreamBatch(stream));
    assertEquals(false, stream.isActive());
    assertEquals(7, primary.wal.quorumDurableCommitSequence());
    assertEquals(primary.wal.tailEnd(), followerOne.wal.tailEnd());
    assertEquals(primary.wal.tailEnd(), followerTwo.wal.tailEnd());
    assertEquals(4L * Long.BYTES * 2, primary.wal.replicatedPayloadBytes());

    close(primary);
    close(followerOne);
    close(followerTwo);
    primary = reopenNode(root.resolve("primary"));
    followerOne = reopenNode(root.resolve("follower-one"));
    followerTwo = reopenNode(root.resolve("follower-two"));
    assertEquals(7, primary.wal.currentCommitSequence());
    assertEquals(7, followerOne.wal.currentCommitSequence());
    assertEquals(7, followerTwo.wal.currentCommitSequence());
    assertEquals(primary.wal.tailEnd(), followerOne.wal.tailEnd());
    assertEquals(primary.wal.tailEnd(), followerTwo.wal.tailEnd());
    close(primary);
    close(followerOne);
    close(followerTwo);
  }

  @Test
  void continuesWithOneFailedFollowerWhenTwoDurableNodesRemain(
      @TempDir Path root) throws IOException {
    Node primary = createNode(root.resolve("primary"));
    Node followerOne = createNode(root.resolve("follower-one"));
    Node followerTwo = createNode(root.resolve("follower-two"));
    assertEquals(
        StatusCode.OK,
        primary.wal.enableDurableQuorum(
            new LocalWal[] {followerOne.wal, followerTwo.wal}, 2));
    assertEquals(StatusCode.OK, followerOne.wal.close());

    LocalWalReservation reservation = reserve(primary.wal, 107);
    assertEquals(
        StatusCode.OK,
        primary.wal.publish(
            reservation, 23, 1, 1, 5, 1, new LocalWalAppendResult()));
    assertEquals(2, primary.wal.availableDurableNodeCount());
    assertEquals(1, primary.wal.quorumDurableCommitSequence());
    assertEquals(primary.wal.durableEnd(), followerTwo.wal.durableEnd());
    assertEquals(StatusCode.CLOSED, followerOne.wal.reserve(1, new LocalWalReservation()));

    close(primary);
    assertEquals(StatusCode.OK, followerOne.directory.close());
    close(followerTwo);
  }

  @Test
  void fencesPrimaryRatherThanGuessingAfterQuorumLoss(@TempDir Path root)
      throws IOException {
    Node primary = createNode(root.resolve("primary"));
    Node followerOne = createNode(root.resolve("follower-one"));
    Node followerTwo = createNode(root.resolve("follower-two"));
    assertEquals(
        StatusCode.OK,
        primary.wal.enableDurableQuorum(
            new LocalWal[] {followerOne.wal, followerTwo.wal}, 2));
    assertEquals(StatusCode.OK, followerOne.wal.close());
    assertEquals(StatusCode.OK, followerTwo.wal.close());

    LocalWalReservation reservation = reserve(primary.wal, 109);
    assertEquals(
        StatusCode.FENCED,
        primary.wal.publish(
            reservation, 29, 1, 1, 5, 1, new LocalWalAppendResult()));
    assertEquals(1, primary.wal.availableDurableNodeCount());
    assertEquals(StatusCode.FENCED, primary.wal.reserve(1, new LocalWalReservation()));
    assertEquals(StatusCode.OK, primary.wal.close());
    assertEquals(StatusCode.OK, primary.directory.close());
    assertEquals(StatusCode.OK, followerOne.directory.close());
    assertEquals(StatusCode.OK, followerTwo.directory.close());
  }

  @Test
  void rejectsSameLengthFollowerHistoryWithDifferentContent(@TempDir Path root)
      throws IOException {
    Node primary = createNode(root.resolve("primary"));
    Node follower = createNode(root.resolve("follower"));
    LocalWalReservation primaryReservation = reserve(primary.wal, 113);
    LocalWalReservation followerReservation = reserve(follower.wal, 127);
    assertEquals(
        StatusCode.OK,
        primary.wal.publish(
            primaryReservation, 31, 1, 1, 5, 1, new LocalWalAppendResult()));
    assertEquals(
        StatusCode.OK,
        follower.wal.publish(
            followerReservation, 31, 1, 1, 5, 1, new LocalWalAppendResult()));

    assertEquals(
        StatusCode.CORRUPTION,
        primary.wal.enableDurableQuorum(new LocalWal[] {follower.wal}, 2));
    close(primary);
    close(follower);
  }

  @Test
  void warmedQuorumForceReusesProductionCarriers(@TempDir Path root)
      throws IOException {
    ThreadMXBean bean = allocationBean();
    Node primary = createNode(root.resolve("primary"));
    Node followerOne = createNode(root.resolve("follower-one"));
    Node followerTwo = createNode(root.resolve("follower-two"));
    assertEquals(
        StatusCode.OK,
        primary.wal.enableDurableQuorum(
            new LocalWal[] {followerOne.wal, followerTwo.wal}, 2));
    LocalWalReservation reservation = new LocalWalReservation();
    LocalWalAppendResult appended = new LocalWalAppendResult();
    for (int index = 0; index < 20; index++) {
      exerciseQuorum(primary.wal, reservation, appended, index + 1L);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 20; index < 60; index++) {
      exerciseQuorum(primary.wal, reservation, appended, index + 1L);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(
        allocated <= 1024,
        "warmed durable quorum allocated bytes: " + allocated);
    assertEquals(60L * Long.BYTES * 2, primary.wal.replicatedPayloadBytes());
    close(primary);
    close(followerOne);
    close(followerTwo);
  }

  private static void appendUnforced(
      LocalWal wal,
      long transactionId,
      long commitSequence,
      long value) {
    LocalWalReservation reservation = reserve(wal, value);
    assertEquals(
        StatusCode.OK,
        wal.appendUnforced(
            reservation,
            transactionId,
            commitSequence,
            1,
            5,
            1,
            new LocalWalAppendResult()));
  }

  private static LocalWalReservation reserve(LocalWal wal, long value) {
    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(Long.BYTES, reservation));
    reservation.writablePayload().putLong(value);
    return reservation;
  }

  private static void exerciseQuorum(
      LocalWal wal,
      LocalWalReservation reservation,
      LocalWalAppendResult appended,
      long value) {
    allocationGuard += wal.reserve(Long.BYTES, reservation).ordinal();
    reservation.writablePayload().putLong(value);
    allocationGuard += wal.publish(
        reservation, value, value, 1, 5, 1, appended).ordinal();
    allocationGuard += appended.endOffset();
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(bean instanceof ThreadMXBean);
    ThreadMXBean allocationBean = (ThreadMXBean) bean;
    Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
    Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled());
    return allocationBean;
  }

  private static void assertRecord(
      LocalWal wal,
      long offset,
      long transactionId,
      long commitSequence,
      long value) {
    LocalWalReadResult read = new LocalWalReadResult();
    assertEquals(StatusCode.OK, wal.read(offset, read));
    assertEquals(transactionId, read.header().transactionId());
    assertEquals(commitSequence, read.header().commitSequence());
    assertEquals(value, read.payload().getLong(0));
  }

  private static Node createNode(Path path) throws IOException {
    Files.createDirectory(path);
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(path, counters);
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.create(directory, DATABASE, GENERATION, result));
    return new Node(directory, result.wal(), counters);
  }

  private static Node reopenNode(Path path) {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(path, counters);
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(
        StatusCode.OK,
        LocalWal.openExisting(directory, DATABASE, GENERATION, result));
    return new Node(directory, result.wal(), counters);
  }

  private static NioDurableDirectory openDirectory(
      Path path,
      NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            path, new FatalStateFence(), counters, 8, result));
    return result.directory();
  }

  private static void close(Node node) {
    assertEquals(StatusCode.OK, node.wal.close());
    assertEquals(StatusCode.OK, node.directory.close());
  }

  private record Node(
      NioDurableDirectory directory,
      LocalWal wal,
      NioIoCounters counters) {
  }

  private static final class LongBatch implements LocalWalRecordBatch {
    private final long[] values;

    LongBatch(long... recordValues) {
      values = recordValues;
    }

    @Override
    public int recordCount() {
      return values.length;
    }

    @Override
    public int payloadBytes(int record) {
      return Long.BYTES;
    }

    @Override
    public StatusCode encodePayload(int record, ByteBuffer target) {
      target.putLong(values[record]);
      return StatusCode.OK;
    }
  }
}

package io.riverdb.testkit.tx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.version.VersionPointer;
import io.riverdb.tx.api.version.VersionReadResult;
import io.riverdb.tx.api.version.VersionRecord;
import io.riverdb.tx.spi.RecoveryTransactionView;
import org.junit.jupiter.api.Test;

final class DeterministicTransactionReviewRegressionTest {
  @Test
  void versionPointersAreDatabaseQualifiedAndStaleReuseCannotAlias() {
    DeterministicTransactionProvider first = provider(1, 2);
    DeterministicTransactionProvider foreign = provider(9, 10);
    TransactionContext firstContext = context(1, 2);
    TransactionContext foreignContext = context(9, 10);
    VersionPointer firstPointer = append(first, firstContext, (byte) 11);
    VersionPointer foreignPointer = append(foreign, foreignContext, (byte) 22);
    VersionReadResult read = new VersionReadResult().useDestination(new byte[1], 0, 1);

    assertEquals(StatusCode.CONFLICT, foreign.readVersion(firstPointer, read, detail()));
    assertEquals(StatusCode.CONFLICT, first.readVersion(foreignPointer, read, detail()));
    assertEquals(StatusCode.OK, first.readVersion(firstPointer, read, detail()));
    assertArrayEquals(new byte[] {11}, read.destinationArray());

    byte[] retainedCallerBytes = read.destinationArray();
    assertEquals(StatusCode.OK, first.reclaimVersionForTest(firstPointer, detail()));
    VersionPointer replacement = append(first, firstContext, (byte) 33);
    assertEquals(StatusCode.CONFLICT, first.readVersion(firstPointer, read, detail()));
    VersionReadResult replacementRead =
        new VersionReadResult().useDestination(new byte[1], 0, 1);
    assertEquals(StatusCode.OK, first.readVersion(replacement, replacementRead, detail()));
    assertArrayEquals(new byte[] {33}, replacementRead.destinationArray());
    assertArrayEquals(new byte[] {11}, retainedCallerBytes);
  }

  private static DeterministicTransactionProvider provider(long databaseHigh, long databaseLow) {
    DeterministicTransactionProvider provider =
        new DeterministicTransactionProvider(databaseHigh, databaseLow, 3, 1, 1, 8, 1);
    RecoveryTransactionView active = new RecoveryTransactionView().set(
        databaseHigh, databaseLow, 1, TransactionState.ACTIVE, 1, 1, 0, 0, 0);
    assertEquals(StatusCode.OK, provider.storeRecoveryView(active, detail()));
    return provider;
  }

  private static TransactionContext context(long databaseHigh, long databaseLow) {
    DeterministicSnapshot snapshot = new DeterministicSnapshot(
        databaseHigh, databaseLow, 1, 0, new long[] {1}, 1);
    return new TransactionContext(
        databaseHigh,
        databaseLow,
        1,
        IsolationLevel.REPEATABLE_READ,
        snapshot,
        CancellationToken.NONE);
  }

  private static VersionPointer append(
      DeterministicTransactionProvider provider,
      TransactionContext context,
      byte value) {
    VersionRecord record = new VersionRecord().set(
        1, 0, 0, 0, 0, new byte[] {value}, 0, 1);
    VersionPointer pointer = new VersionPointer();
    assertEquals(StatusCode.OK, provider.appendVersion(context, record, pointer, detail()));
    return pointer;
  }

  private static StatusDetail detail() {
    return new StatusDetail(0);
  }
}

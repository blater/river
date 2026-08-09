package io.riverdb.testkit.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.spi.RecoveryTransactionView;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DeterministicTransactionOwnershipTest {
  @Test
  void mutationFromNonOwnerThreadIsRejectedWithoutChangingState() throws InterruptedException {
    DeterministicTransactionProvider provider =
        new DeterministicTransactionProvider(1, 2, 3, 2, 1, 8, 1);
    RecoveryTransactionView view = new RecoveryTransactionView().set(
        1, 2, 1, TransactionState.ACTIVE, 1, 1, 0, 0, 0);
    AtomicReference<StatusCode> observed = new AtomicReference<>();
    Thread other = new Thread(
        () -> observed.set(provider.storeRecoveryView(view, new StatusDetail(0))));
    other.start();
    other.join();

    assertEquals(StatusCode.NOT_OWNER, observed.get());
    assertEquals(
        StatusCode.RETRY,
        provider.lookupRecoveryTransaction(1, 2, 1, new RecoveryTransactionView(),
            new StatusDetail(0)));
  }
}

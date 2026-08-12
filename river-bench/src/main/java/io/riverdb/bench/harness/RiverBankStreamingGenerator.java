package io.riverdb.bench.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** River-owned relational banking generator; it does not reproduce an external dataset. */
public final class RiverBankStreamingGenerator {
  public static final int VERSION = 2;
  public static final int MAX_BRANCHES = 100_000;
  public static final int MAX_ACCOUNTS = 10_000_000;
  public static final long MAX_TRANSACTIONS = 2_000_000_000L;

  static final String ACCOUNTS_HEADER =
      "account_id\tbranch_id\tcustomer_id\topened_at_epoch_ms\tstatus"
          + "\tbalance_minor\trisk_band\n";
  static final String TRANSACTIONS_HEADER =
      "transaction_id\toccurred_at_epoch_ms\ttype\tfrom_account_id"
          + "\tto_account_id\tamount_minor\tidempotency_key\n";

  private static final long EPOCH_MILLIS = 1_577_836_800_000L;
  private static final long FIVE_YEARS_MILLIS = 157_852_800_000L;
  private static final String[] TYPES = {
      "transfer", "deposit", "withdrawal", "card_authorize", "card_reverse"
  };

  public StreamingWorkloadPlan plan(long seed, RiverBankScale scale) {
    if (!valid(scale)) {
      return StreamingWorkloadPlan.invalidScale();
    }
    String common = "schema=riverbank_v2;scale=" + scale.name()
        + ";branches=" + scale.branchCount()
        + ";accounts=" + scale.accountCount()
        + ";transactions=" + scale.transactionCount()
        + ";hot_accounts=" + scale.hotAccountCount()
        + ";hot_selection_rate=80_percent"
        + ";timestamps=monotonic_uniform_epoch_millis_2020_2024"
        + ";amount_minor=1..250000;external_dataset=none";
    StreamingWorkloadArtifact accounts = new StreamingWorkloadArtifact(
        "riverbank_accounts",
        VERSION,
        seed,
        scale.accountCount(),
        "riverbank.accounts.v2",
        common + ";table=accounts;primary_key=account_id;branch_fk=logical",
        (output, scratch) -> writeAccounts(output, scratch, seed, scale));
    StreamingWorkloadArtifact transactions = new StreamingWorkloadArtifact(
        "riverbank_transactions",
        VERSION,
        seed,
        scale.transactionCount(),
        "riverbank.transactions.v2",
        common + ";table=transactions;account_fk=from_or_to_when_present"
            + ";idempotency_key=unique",
        (output, scratch) -> writeTransactions(output, scratch, seed, scale));
    return StreamingWorkloadPlan.planned(List.of(accounts, transactions));
  }

  private static boolean valid(RiverBankScale scale) {
    return scale != null
        && scale.name() != null
        && scale.name().matches("[a-z][a-z0-9_]{1,31}")
        && scale.branchCount() >= 1
        && scale.branchCount() <= MAX_BRANCHES
        && scale.accountCount() >= 2
        && scale.accountCount() <= MAX_ACCOUNTS
        && scale.accountCount() % 2 == 0
        && scale.branchCount() <= scale.accountCount()
        && scale.transactionCount() >= 1
        && scale.transactionCount() <= MAX_TRANSACTIONS
        && scale.hotAccountCount() >= 1
        && scale.hotAccountCount() <= scale.accountCount();
  }

  private static StreamingGenerationResult writeAccounts(
      OutputStream output,
      byte[] scratch,
      long seed,
      RiverBankScale scale) throws IOException {
    if (!BoundedTsvOutput.validScratch(scratch)) {
      return new StreamingGenerationResult(
          StreamingGenerationStatus.INVALID_SCRATCH_BUFFER, 0, 0);
    }
    BoundedTsvOutput tsv = new BoundedTsvOutput(output, scratch);
    long rows = 0;
    try {
      tsv.appendAscii(ACCOUNTS_HEADER);
      for (int account = 1; account <= scale.accountCount(); account++) {
        long sequence = account - 1L;
        tsv.appendLong(account);
        tsv.append('\t');
        tsv.appendLong((account - 1L) % scale.branchCount() + 1);
        tsv.append('\t');
        tsv.appendLong((account + 1L) / 2L);
        tsv.append('\t');
        tsv.appendLong(EPOCH_MILLIS
            + DeterministicValues.bounded(seed, sequence, 1, FIVE_YEARS_MILLIS));
        tsv.append('\t');
        tsv.appendAscii(DeterministicValues.bounded(seed, sequence, 2, 100) < 98
            ? "active" : "frozen");
        tsv.append('\t');
        tsv.appendLong(10_000L
            + DeterministicValues.bounded(seed, sequence, 3, 10_000_000L));
        tsv.append('\t');
        tsv.appendLong(DeterministicValues.bounded(seed, sequence, 4, 5));
        tsv.append('\n');
        rows++;
      }
      tsv.finish();
    } catch (ArithmeticException exception) {
      return new StreamingGenerationResult(
          StreamingGenerationStatus.BYTE_COUNT_OVERFLOW, rows, Long.MAX_VALUE);
    }
    return completed(rows, scale.accountCount(), tsv.byteCount());
  }

  private static StreamingGenerationResult writeTransactions(
      OutputStream output,
      byte[] scratch,
      long seed,
      RiverBankScale scale) throws IOException {
    if (!BoundedTsvOutput.validScratch(scratch)) {
      return new StreamingGenerationResult(
          StreamingGenerationStatus.INVALID_SCRATCH_BUFFER, 0, 0);
    }
    BoundedTsvOutput tsv = new BoundedTsvOutput(output, scratch);
    long rows = 0;
    try {
      tsv.appendAscii(TRANSACTIONS_HEADER);
      for (long sequence = 0; sequence < scale.transactionCount(); sequence++) {
        int type = (int) DeterministicValues.bounded(seed, sequence, 10, TYPES.length);
        long from = selectedAccount(seed, sequence, 11, scale);
        long to = selectedAccount(seed, sequence, 12, scale);
        if (to == from) {
          to = to == scale.accountCount() ? 1 : to + 1;
        }
        tsv.appendLong(sequence + 1);
        tsv.append('\t');
        tsv.appendLong(transactionTimestamp(sequence, scale.transactionCount()));
        tsv.append('\t');
        tsv.appendAscii(TYPES[type]);
        tsv.append('\t');
        if (type != 1) {
          tsv.appendLong(from);
        }
        tsv.append('\t');
        if (type == 0 || type == 1) {
          tsv.appendLong(to);
        }
        tsv.append('\t');
        tsv.appendLong(1 + DeterministicValues.bounded(seed, sequence, 13, 250_000));
        tsv.append('\t');
        tsv.appendAscii("rb2-");
        tsv.appendLong(seed);
        tsv.append('-');
        tsv.appendLong(sequence + 1);
        tsv.append('\n');
        rows++;
      }
      tsv.finish();
    } catch (ArithmeticException exception) {
      return new StreamingGenerationResult(
          StreamingGenerationStatus.BYTE_COUNT_OVERFLOW, rows, Long.MAX_VALUE);
    }
    return completed(rows, scale.transactionCount(), tsv.byteCount());
  }

  private static long selectedAccount(
      long seed,
      long sequence,
      long lane,
      RiverBankScale scale) {
    boolean hot = DeterministicValues.bounded(seed, sequence, lane, 100) < 80;
    int bound = hot ? scale.hotAccountCount() : scale.accountCount();
    return 1 + DeterministicValues.bounded(seed, sequence, lane + 20, bound);
  }

  private static long transactionTimestamp(long sequence, long transactionCount) {
    if (transactionCount == 1) {
      return EPOCH_MILLIS;
    }
    double fraction = (double) sequence / (double) (transactionCount - 1);
    long offset = (long) (fraction * (FIVE_YEARS_MILLIS - 1));
    return EPOCH_MILLIS + offset;
  }

  private static StreamingGenerationResult completed(
      long actualRows,
      long expectedRows,
      long bytes) {
    StreamingGenerationStatus status = actualRows == expectedRows
        ? StreamingGenerationStatus.GENERATED
        : StreamingGenerationStatus.ROW_COUNT_MISMATCH;
    return new StreamingGenerationResult(status, actualRows, bytes);
  }
}

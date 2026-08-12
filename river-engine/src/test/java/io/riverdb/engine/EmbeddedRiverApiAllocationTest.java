package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverApiAllocationTest {
  private static final String QUERY =
      "SELECT id, balance FROM accounts WHERE region=7";
  private static final String MEMBERSHIP_QUERY =
      "SELECT id FROM accounts WHERE balance IN "
          + "(SELECT balance FROM accounts WHERE region=7)";
  private static volatile long allocationGuard;

  @Test
  void warmedCommandAndStreamingBoundaryReuseCarriers(@TempDir Path root) {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);

    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(
            root,
            DatabaseIncarnation.of(0x415049414c4c4f43L, 0x4154494f4e303031L),
            WalGeneration.of(1),
            4,
            opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    QueryOpenResult queryResult = new QueryOpenResult();
    RowResult row = new RowResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (1, 100, 7)", command));
    assertEquals(StatusCode.OK, session.beginQuery(QUERY, queryResult));
    RiverQuery query = queryResult.query();
    assertEquals(StatusCode.OK, query.close(command));

    for (int index = 0; index < 100; index++) {
      exercise(session, queryResult, query, row, command);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exercise(session, queryResult, query, row, command);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(
        allocated <= 512,
        "warmed engine API command/query path allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void exercise(
      RiverSession session,
      QueryOpenResult queryResult,
      RiverQuery query,
      RowResult row,
      CommandResult command) {
    allocationGuard += session.execute(
        "SELECT balance FROM accounts WHERE id=1", command).ordinal();
    allocationGuard += command.valueAt(0);
    allocationGuard += session.beginQuery(QUERY, queryResult).ordinal();
    allocationGuard += query.next(row).ordinal();
    allocationGuard += row.key();
    allocationGuard += query.next(row).ordinal();
    allocationGuard += row.isAvailable() ? 1 : 0;
    allocationGuard += query.close(command).ordinal();
    allocationGuard += session.beginQuery(MEMBERSHIP_QUERY, queryResult).ordinal();
    allocationGuard += query.next(row).ordinal();
    allocationGuard += row.key();
    allocationGuard += query.next(row).ordinal();
    allocationGuard += query.close(command).ordinal();
  }
}

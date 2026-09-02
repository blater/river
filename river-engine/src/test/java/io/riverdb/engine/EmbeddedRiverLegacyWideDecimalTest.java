package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverLegacyWideDecimalTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4c45474143595744L, 0x45444543494d414cL);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final String MONEY = "123456789012345678901234567890123456.78";
  private static final String UPDATED_MONEY = "-98765432109876543210987654321098765.43";
  private static final String SCALED = "1234.567890123456789012";
  private static final String UPDATED_SCALED = "9999.000000000000000001";
  private static final String SAME_LOW_SCALED = "9980.553255926290448385";

  @Test
  void identityTablePreservesWideDecimalsAcrossMutationAndReopen(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    RiverSession session = session(database);
    CommandResult result = new CommandResult();

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE legacy_wide ("
            + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + "money DECIMAL(38,2) NOT NULL,scaled DECIMAL(22,18) NOT NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO legacy_wide(money,scaled) VALUES (" + MONEY + "," + SCALED + ")",
        result));
    assertEquals(1, result.key());
    assertValues(session, result, MONEY, SCALED);

    assertEquals(StatusCode.OK, session.execute(
        "UPDATE legacy_wide SET money=" + UPDATED_MONEY + ",scaled=" + UPDATED_SCALED
            + " WHERE id=1", result));
    assertValues(session, result, UPDATED_MONEY, UPDATED_SCALED);
    assertEquals(
        new BigDecimal(UPDATED_SCALED).unscaledValue().longValue(),
        new BigDecimal(SAME_LOW_SCALED).unscaledValue().longValue());
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO legacy_wide(money,scaled) VALUES (1.00," + SAME_LOW_SCALED + ")",
        result));
    assertEquals(2, result.key());
    assertCount(session, result, UPDATED_SCALED, 1);
    assertEquals(StatusCode.FEATURE_NOT_SUPPORTED, session.execute(
        "CREATE TABLE rejected_wide_default ("
            + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + "amount DECIMAL(38,2) DEFAULT 1.00)", result));
    assertEquals(StatusCode.FEATURE_NOT_SUPPORTED, session.execute(
        "CREATE TABLE rejected_wide_check ("
            + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + "amount DECIMAL(22,18) CHECK (amount>0.000000000000000000))", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = session(database);
    assertValues(session, result, UPDATED_MONEY, UPDATED_SCALED);
    assertCount(session, result, UPDATED_SCALED, 1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static RiverSession session(RiverDatabase database) {
    SessionOpenResult opened = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static void assertValues(
      RiverSession session, CommandResult result, String money, String scaled) {
    assertEquals(StatusCode.OK, session.execute(
        "SELECT money,scaled FROM legacy_wide WHERE id=1", result));
    assertDecimal(result, 0, money);
    assertDecimal(result, 1, scaled);
  }

  private static void assertDecimal(CommandResult result, int column, String expected) {
    BigInteger unscaled = new BigDecimal(expected).unscaledValue();
    assertEquals(unscaled.shiftRight(Long.SIZE).longValue(),
        result.decimalUnscaledHighAt(column));
    assertEquals(unscaled.longValue(), result.decimalUnscaledLowAt(column));
  }

  private static void assertCount(
      RiverSession session, CommandResult result, String scaled, long expected) {
    assertEquals(StatusCode.OK, session.execute(
        "SELECT COUNT(*) FROM legacy_wide WHERE scaled=" + scaled, result));
    assertEquals(expected, result.valueAt(0));
  }
}

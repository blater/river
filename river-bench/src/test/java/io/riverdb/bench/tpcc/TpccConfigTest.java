package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TpccConfigTest {
  @Test
  void defaultsToPromotedOneWarehouseProfile() {
    TpccConfig config = TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9999"});
    assertTrue(config.standardOneWarehouse());
    assertEquals(1, config.warehouses());
    assertEquals(10, config.terminals());
    assertEquals(1_800, config.measured().toSeconds());
    assertEquals(32, config.batchRows());
    assertEquals(TpccScheduling.STANDARD, config.scheduling());
    assertEquals(TpccPhase.LOAD_RUN_CHECKPOINT, config.phase());
    assertEquals(null, config.jfr());
  }

  @Test
  void warehouseScaleDefaultsToOneTerminalPerDistrict() {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--warehouses=3"
    });
    assertEquals(3, config.warehouses());
    assertEquals(30, config.terminals());
    assertTrue(config.standardScale());
    assertFalse(config.standardOneWarehouse());

    TpccConfig tiny = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny", "--warehouses=3", "--terminals=7"
    });
    assertEquals(7, tiny.terminals());
  }

  @Test
  void tinyProfileIsExplicitAndBounded() {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny", "--warmup-seconds=1",
        "--measured-seconds=2", "--fresh-load=false"
    });
    assertFalse(config.standardOneWarehouse());
    assertFalse(config.freshLoad());
    assertEquals(100, config.itemCount());
    assertEquals(30, config.customersPerDistrict());

    TpccConfig stress = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny", "--scheduling=no-wait-stress"
    });
    assertEquals(32, stress.maximumAttempts());
    TpccConfig explicit = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny", "--scheduling=no-wait-stress",
        "--maximum-attempts=7"
    });
    assertEquals(7, explicit.maximumAttempts());

    TpccConfig profiled = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--jfr=profile.jfr"
    });
    assertEquals("profile.jfr", profiled.jfr().toString());
  }

  @Test
  void rejectsUnknownAndOutOfBoundOptions() {
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--batch-rows=64"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--wat=true"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--terminals=9"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--warehouses=0"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--warehouses=101"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {
            "--url=jdbc:river://localhost:9", "--warehouses=2", "--terminals=10"
        }));
  }
}

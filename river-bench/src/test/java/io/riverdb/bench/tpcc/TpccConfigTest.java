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
    assertEquals(TpccWorkloadMix.STANDARD, config.mix());
    assertEquals(TpccIsolationContract.SERIALIZABLE, config.isolation());
    assertEquals(TpccPhase.LOAD_RUN_CHECKPOINT, config.phase());
    assertEquals(null, config.jfr());
  }

  @Test
  void parsesDiagnosticMixAndIsolationWithoutPromotingIt() {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny",
        "--mix=new-order-payment-50-50", "--isolation=mixed-diagnostic"
    });
    assertEquals(TpccWorkloadMix.NEW_ORDER_PAYMENT_50_50, config.mix());
    assertEquals(TpccIsolationContract.MIXED_DIAGNOSTIC, config.isolation());
    assertFalse(config.isolation().common());

    assertThrows(IllegalArgumentException.class, () -> TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny", "--evidence=alpha3",
        "--isolation=mixed-diagnostic"
    }));
    assertThrows(IllegalArgumentException.class, () -> TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny", "--evidence=alpha3",
        "--mix=payment"
    }));
  }

  @Test
  void parsesP0BlockerIsolationMixes() {
    TpccConfig newOrderDelivery = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny",
        "--mix=new-order-delivery-50-50"
    });
    assertEquals(TpccWorkloadMix.NEW_ORDER_DELIVERY_50_50, newOrderDelivery.mix());

    TpccConfig newOrderStockLevel = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9999", "--tiny",
        "--mix=new-order-stock-level-50-50"
    });
    assertEquals(TpccWorkloadMix.NEW_ORDER_STOCK_LEVEL_50_50, newOrderStockLevel.mix());
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
  void rejectsUnknownAndSemanticallyInvalidOptions() {
    TpccConfig scaled = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--batch-rows=64",
        "--warehouses=101", "--terminals=1010"
    });
    assertEquals(64, scaled.batchRows());
    assertEquals(101, scaled.warehouses());
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--wat=true"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--terminals=9"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {"--url=jdbc:river://localhost:9", "--warehouses=0"}));
    assertThrows(IllegalArgumentException.class,
        () -> TpccConfig.parse(new String[] {
            "--url=jdbc:river://localhost:9", "--warehouses=2", "--terminals=10"
        }));
  }
}

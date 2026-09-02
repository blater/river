package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TpccMultiWarehouseInputTest {
  @Test
  void terminalsMapAcrossWarehouseDistrictHomes() {
    TpccConfig config = multiWarehouseConfig();
    assertEquals(new TpccTerminalHome(1, 1), TpccTerminalHome.at(config, 0));
    assertEquals(new TpccTerminalHome(1, 10), TpccTerminalHome.at(config, 9));
    assertEquals(new TpccTerminalHome(2, 1), TpccTerminalHome.at(config, 10));
    assertEquals(new TpccTerminalHome(4, 10), TpccTerminalHome.at(config, 39));
  }

  @Test
  void newOrderUsesHomeWarehouseAndStandardRemoteSupplyRate() {
    TpccConfig config = multiWarehouseConfig();
    TpccInputs.NewOrder input = new TpccInputs.NewOrder();
    TpccValues values = new TpccValues(0x4E45_574FL);
    int remote = 0;
    int lines = 0;
    for (int order = 0; order < 2_000; order++) {
      input.generate(values, config, 3, 7);
      assertEquals(3, input.warehouse);
      assertEquals(7, input.district);
      for (int line = 0; line < input.lines; line++) {
        int supply = input.supplyWarehouse[line];
        assertTrue(supply >= 1 && supply <= config.warehouses());
        if (supply != input.warehouse) remote++;
        lines++;
      }
    }
    double rate = (double) remote / lines;
    assertTrue(rate > 0.005 && rate < 0.015, "remote New-Order supply rate=" + rate);
  }

  @Test
  void paymentUsesHomeWarehouseAndStandardRemoteCustomerRate() {
    TpccConfig config = multiWarehouseConfig();
    TpccInputs.Payment input = new TpccInputs.Payment();
    TpccValues values = new TpccValues(0x5041_594DL);
    int remote = 0;
    for (int payment = 0; payment < 4_000; payment++) {
      input.generate(values, config, 2, 6);
      assertEquals(2, input.warehouse);
      assertEquals(6, input.district);
      assertTrue(input.customerDistrict >= 1 && input.customerDistrict <= config.districts());
      if (input.customerWarehouse != input.warehouse) {
        assertNotEquals(input.warehouse, input.customerWarehouse);
        remote++;
      }
    }
    double rate = remote / 4_000.0;
    assertTrue(rate > 0.12 && rate < 0.18, "remote Payment customer rate=" + rate);
  }

  @Test
  void oneWarehouseNeverGeneratesRemoteAccess() {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny"
    });
    TpccValues values = new TpccValues(7);
    TpccInputs.NewOrder order = new TpccInputs.NewOrder();
    TpccInputs.Payment payment = new TpccInputs.Payment();
    for (int iteration = 0; iteration < 200; iteration++) {
      order.generate(values, config, 1, 4);
      for (int line = 0; line < order.lines; line++) {
        assertEquals(1, order.supplyWarehouse[line]);
      }
      payment.generate(values, config, 1, 4);
      assertEquals(1, payment.customerWarehouse);
      assertEquals(4, payment.customerDistrict);
    }
  }

  private static TpccConfig multiWarehouseConfig() {
    return TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny", "--warehouses=4"
    });
  }
}

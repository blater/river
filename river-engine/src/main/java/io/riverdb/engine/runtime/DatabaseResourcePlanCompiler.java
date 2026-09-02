package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Checked compiler for the database-open memory inequality and typed capacities. */
final class DatabaseResourcePlanCompiler {
  private DatabaseResourcePlanCompiler() {}

  static StatusCode compile(
      DatabaseResourcePlanRequest request, DatabaseResourcePlan.Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!valid(request) || !sessionCapacitiesSupported(request)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!deliveryFits(request)) return StatusCode.RESOURCE_EXHAUSTED;
    long ownerBytes = multiply(request.minimumOwnerBytes, request.maximumOwners);
    long required = add(request.fixedRuntimeBytes, request.progressReserveBytes);
    required = add(required, ownerBytes);
    required = add(required, request.lockProviderBytes);
    required = add(required, request.maximumDeliveryAccountedBytes);
    if (ownerBytes < 0 || required < 0 || required > request.maximumAccountedBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long accountedCapacity = request.maximumAccountedBytes
        - request.fixedRuntimeBytes - request.progressReserveBytes - ownerBytes;
    try {
      result.set(new DatabaseResourcePlan(request, accountedCapacity));
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static boolean valid(DatabaseResourcePlanRequest request) {
    return request != null && request.maximumAccountedBytes > 0
        && request.fixedRuntimeBytes >= 0 && request.progressReserveBytes >= 0
        && request.minimumOwnerBytes > 0 && request.maximumDeliveryAccountedBytes > 0
        && request.maximumOwners > 0 && request.writeEntryCapacity > 0
        && request.lockProviderBytes > 0 && request.stagedPageCapacity > 0
        && request.walByteCapacity > 0 && request.maximumDeliveryWriteEntries > 0
        && request.maximumDeliveryStagedPages > 0
        && request.maximumDeliveryWalBytes > 0;
  }

  private static boolean deliveryFits(DatabaseResourcePlanRequest request) {
    return request.maximumDeliveryWriteEntries <= request.writeEntryCapacity
        && request.maximumDeliveryStagedPages <= request.stagedPageCapacity
        && request.maximumDeliveryWalBytes <= request.walByteCapacity;
  }

  private static boolean sessionCapacitiesSupported(DatabaseResourcePlanRequest request) {
    return request.writeEntryCapacity <= Integer.MAX_VALUE
        && request.maximumDeliveryWriteEntries <= Integer.MAX_VALUE;
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }

  private static long multiply(long value, int count) {
    return value <= 0 || count <= 0 || value > Long.MAX_VALUE / count ? -1 : value * count;
  }
}

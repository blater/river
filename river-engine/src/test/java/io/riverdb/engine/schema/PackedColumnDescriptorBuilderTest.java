package io.riverdb.engine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class PackedColumnDescriptorBuilderTest {
  @Test
  void everyArrayFailureIsTransactionalAndRetryable() {
    byte[] name = new byte[65];
    for (int index = 0; index < name.length; index++) name[index] = 'a';
    ByteBuffer encoded = ByteBuffer.wrap(name);
    for (int failure = 1; failure <= 7; failure++) {
      FailingAllocator allocator = new FailingAllocator(failure);
      PackedColumnDescriptorBuilder builder = new PackedColumnDescriptorBuilder(allocator);
      StatusCode status = builder.begin(1, 128);
      if (!status.isOk()) {
        assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
        assertEquals(StatusCode.OK, builder.begin(1, 128));
      }
      status = builder.reserve(1, name.length);
      if (!status.isOk()) {
        assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
        assertEquals(0, builder.count());
        assertEquals(StatusCode.OK, builder.reserve(1, name.length));
      }
      assertEquals(StatusCode.OK, builder.putReserved(
          0, SqlTypeDescriptor.BIGINT, false, encoded, 0, name.length, 0));
      assertEquals(StatusCode.OK, builder.publishReserved(1, name.length));
      ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
      status = builder.finish(result, null);
      if (!status.isOk()) {
        assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
        assertNull(result.value());
        assertEquals(1, builder.count());
        assertEquals(StatusCode.OK, builder.finish(result, null));
      }
      assertNotNull(result.value());
      assertEquals(1, result.value().count());
    }
  }

  private static final class FailingAllocator implements PackedColumnArrayAllocator {
    private final int failure;
    private int allocation;

    FailingAllocator(int failAt) { failure = failAt; }

    @Override
    public int[] integers(int size) {
      fail();
      return new int[size];
    }

    @Override
    public byte[] bytes(int size) {
      fail();
      return new byte[size];
    }

    private void fail() {
      allocation++;
      if (allocation == failure) throw new OutOfMemoryError("injected");
    }
  }
}

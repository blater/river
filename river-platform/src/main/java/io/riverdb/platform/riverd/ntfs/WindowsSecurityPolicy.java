package io.riverdb.platform.riverd.ntfs;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** Owns Windows security-descriptor, token, owner, and DACL policy checks. */
@SuppressWarnings("restricted")
final class WindowsSecurityPolicy {
  private static final int SECURITY_INFORMATION_OWNER = 1;
  private static final int SECURITY_INFORMATION_DACL = 4;
  private static final int SE_FILE_OBJECT = 1;
  private static final int TOKEN_QUERY = 8;
  private static final int TOKEN_USER = 1;
  private static final int ACL_SIZE_INFORMATION = 2;
  private static final int ACCESS_ALLOWED_ACE_TYPE = 0;
  private static final int ACCESS_DENIED_ACE_TYPE = 1;

  private WindowsSecurityPolicy() { }

  static int verifyOwnerAndDacl(MemorySegment handle, boolean privateOnly) {
    // Query the verified handle. SYSTEM and built-in administrators are the privileged
    // authorities excluded by the portable threat boundary; other users remain restricted.
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment ownerOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment daclOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      int error = (int) WindowsNativeBindings.SECURITY_INFO.invokeExact(
          WindowsNativeBindings.callState().segment, handle, SE_FILE_OBJECT,
          SECURITY_INFORMATION_OWNER | SECURITY_INFORMATION_DACL, ownerOut, MemorySegment.NULL,
          daclOut, MemorySegment.NULL, descriptorOut);
      if (error != 0) return error;
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment process = (MemorySegment) WindowsNativeBindings.CURRENT_PROCESS.invokeExact();
        if ((int) WindowsNativeBindings.OPEN_TOKEN.invokeExact(
            WindowsNativeBindings.callState().segment, process, TOKEN_QUERY, tokenOut) == 0) {
          return WindowsNativeBindings.lastError();
        }
        MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
        try {
          MemorySegment sizeOut = arena.allocate(ValueLayout.JAVA_INT);
          int ignored = (int) WindowsNativeBindings.TOKEN_INFO.invokeExact(
              WindowsNativeBindings.callState().segment, token, TOKEN_USER,
              MemorySegment.NULL, 0, sizeOut);
          int size = sizeOut.get(ValueLayout.JAVA_INT, 0);
          if (size <= 0) return WindowsNativeBindings.lastError();
          MemorySegment tokenBytes = arena.allocate(size, 8);
          if ((int) WindowsNativeBindings.TOKEN_INFO.invokeExact(
              WindowsNativeBindings.callState().segment, token, TOKEN_USER, tokenBytes,
              size, sizeOut) == 0) {
            return WindowsNativeBindings.lastError();
          }
          MemorySegment tokenSid = tokenBytes.get(ValueLayout.ADDRESS, 0);
          MemorySegment owner = ownerOut.get(ValueLayout.ADDRESS, 0);
          if ((int) WindowsNativeBindings.EQUAL_SID.invokeExact(owner, tokenSid) == 0) {
            return WindowsFileBridge.STATUS_ACCESS_DENIED;
          }
          MemorySegment dacl = daclOut.get(ValueLayout.ADDRESS, 0);
          if (dacl.equals(MemorySegment.NULL)) return WindowsFileBridge.STATUS_ACCESS_DENIED;
          MemorySegment aclInfo = arena.allocate(12, 4);
          if ((int) WindowsNativeBindings.ACL_INFO.invokeExact(
              WindowsNativeBindings.callState().segment, dacl, aclInfo, 12,
              ACL_SIZE_INFORMATION) == 0) {
            return WindowsNativeBindings.lastError();
          }
          int count = aclInfo.get(ValueLayout.JAVA_INT, 0);
          MemorySegment aceOut = arena.allocate(ValueLayout.ADDRESS);
          for (int index = 0; index < count; index++) {
            if ((int) WindowsNativeBindings.GET_ACE.invokeExact(
                WindowsNativeBindings.callState().segment, dacl, index, aceOut) == 0) {
              return WindowsNativeBindings.lastError();
            }
            MemorySegment acePointer = aceOut.get(ValueLayout.ADDRESS, 0);
            MemorySegment aceHeader = acePointer.reinterpret(4);
            int aceSize = Short.toUnsignedInt(aceHeader.get(ValueLayout.JAVA_SHORT, 2));
            if (aceSize < 8) return WindowsFileBridge.STATUS_ACCESS_DENIED;
            MemorySegment ace = acePointer.reinterpret(aceSize);
            int type = Byte.toUnsignedInt(ace.get(ValueLayout.JAVA_BYTE, 0));
            if ((ace.get(ValueLayout.JAVA_BYTE, 1) & 8) != 0) continue; // INHERIT_ONLY_ACE
            if (type == ACCESS_ALLOWED_ACE_TYPE) {
              MemorySegment sid = ace.asSlice(8);
              int mask = ace.get(ValueLayout.JAVA_INT, 4);
              boolean other = (int) WindowsNativeBindings.EQUAL_SID.invokeExact(owner, sid) == 0
                  && (int) WindowsNativeBindings.WELL_KNOWN_SID.invokeExact(sid, 71) == 0 // owner rights
                  && (int) WindowsNativeBindings.WELL_KNOWN_SID.invokeExact(sid, 22) == 0 // local system
                  && (int) WindowsNativeBindings.WELL_KNOWN_SID.invokeExact(sid, 26) == 0; // administrators
              if (other && (privateOnly || (mask & (WindowsFileBridge.FILE_ADD_FILE
                  | WindowsFileBridge.FILE_ADD_SUBDIRECTORY | WindowsFileBridge.FILE_DELETE_CHILD
                  | WindowsFileBridge.FILE_APPEND_DATA | WindowsFileBridge.FILE_WRITE_EA
                  | WindowsFileBridge.FILE_WRITE_ATTRIBUTES | WindowsFileBridge.DELETE
                  | WindowsFileBridge.WRITE_DAC | WindowsFileBridge.WRITE_OWNER
                  | 0x40000000 | 0x10000000)) != 0)) {
                return WindowsFileBridge.STATUS_ACCESS_DENIED;
              }
            } else if (type != ACCESS_DENIED_ACE_TYPE) {
              return WindowsFileBridge.STATUS_ACCESS_DENIED;
            }
          }
          return WindowsNativeBindings.STATUS_SUCCESS;
        } finally {
          WindowsFileBridge.close(token);
        }
      } finally {
        MemorySegment ignored = (MemorySegment) WindowsNativeBindings.LOCAL_FREE.invokeExact(
            WindowsNativeBindings.callState().segment, descriptor);
      }
    } catch (Throwable failure) {
      throw abi("Windows security descriptor", failure);
    }
  }

  static MemorySegment privateSecurityDescriptor(Arena arena) {
    try {
      MemorySegment text = wideString(arena, "D:P(A;;FA;;;OW)");
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment sizeOut = arena.allocate(ValueLayout.JAVA_INT);
      int ok = (int) WindowsNativeBindings.CONVERT_SDDL.invokeExact(
          WindowsNativeBindings.callState().segment, text, 1, descriptorOut, sizeOut);
      if (ok == 0) {
        WindowsNativeBindings.setStatus(WindowsNativeBindings.lastError());
        return MemorySegment.NULL;
      }
      return descriptorOut.get(ValueLayout.ADDRESS, 0);
    } catch (Throwable failure) {
      throw abi("ConvertStringSecurityDescriptorToSecurityDescriptorW", failure);
    }
  }

  private static MemorySegment wideString(Arena arena, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
    MemorySegment result = arena.allocate(bytes.length + 2L, 2);
    result.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
    return result;
  }

  private static AssertionError abi(String operation, Throwable failure) {
    return new AssertionError("Windows FFM ABI invocation failed: " + operation, failure);
  }
}

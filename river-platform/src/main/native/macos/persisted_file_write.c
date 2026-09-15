#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

JNIEXPORT jint JNICALL
Java_io_riverdb_platform_file_nio_PersistedFileWriteNative_writePinned0(
    JNIEnv *env,
    jclass native_class,
    jobject owner,
    jobject source,
    jlong position,
    jobject operation_kind) {
  (void) native_class;
  uint64_t native_thread_id = 0;
  int status = pthread_threadid_np(NULL, &native_thread_id);
  if (status != 0) {
    jclass exception_class = (*env)->FindClass(env, "java/io/IOException");
    if (exception_class != NULL) {
      char message[96];
      (void) snprintf(
          message, sizeof(message), "pthread_threadid_np failed: %d", status);
      (void) (*env)->ThrowNew(env, exception_class, message);
    }
    return 0;
  }

  jclass owner_class = (*env)->GetObjectClass(env, owner);
  if (owner_class == NULL) return 0;
  jmethodID callback = (*env)->GetMethodID(
      env,
      owner_class,
      "writeOnPinnedCarrier",
      "(Ljava/nio/ByteBuffer;JLio/riverdb/platform/file/nio/"
          "PendingFileWriteDiagnostics$OperationKind;J)I");
  if (callback == NULL) return 0;
  return (*env)->CallIntMethod(
      env, owner, callback, source, position, operation_kind, (jlong) native_thread_id);
}

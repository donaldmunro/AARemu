#include <string>
#include <sstream>
#include <unordered_set>

#include <arpa/inet.h>

#ifdef ANDROID_LOG
#include <android/log.h>
#define  LOG_TAG    "framebuffer.so"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#endif

#include "to_augmented_reality_android_em_recorder_NativeFrameBuffer.h"
#include "framebuffer.hh"

std::unordered_set<void *> INSTANCES;
std::string field;

jint throw_jni(JNIEnv *env, const char *message, std::string exname ="java/lang/RuntimeException")
//--------------------------------------------------------------------------------------------
{
   jclass klass;
   klass = env->FindClass(exname.c_str());
   if (klass != NULL)
      return env->ThrowNew(klass, message);
   else
#ifdef ANDROID_LOG
      LOGE("Error finding Java exception '%s'", exname.c_str());
#endif
   return false;
}

FrameBuffer *getBuffer(JNIEnv *env, jobject instance)
//-----------------------------------------------
{
   jclass c = env->GetObjectClass(instance);
   if (c == nullptr)
   {
#ifdef ANDROID_LOG
      LOGE("Error obtaining Java class NativeFrameBuffer");
#endif
//      throw_jni(env, "Error obtaining Java class");
      return nullptr;
   }
   jfieldID id = env->GetFieldID(c, field.c_str(), "Ljava/nio/ByteBuffer;");
   if (id == nullptr)
   {
      std::stringstream ss;
      ss << "Error obtaining Java field " << field.c_str();
#ifdef ANDROID_LOG
      LOGE("%s", ss.str().c_str());
#endif
//      throw_jni(env, ss.str().c_str());
      return nullptr;
   }
   jobject obj = env->GetObjectField(instance, id);
   if (obj == nullptr)
   {
      std::stringstream ss;
      ss << "Error obtaining Java field " << field.c_str();
#ifdef ANDROID_LOG
      LOGE("%s", ss.str().c_str());
#endif
//      throw_jni(env, ss.str().c_str());
      return nullptr;
   }
   return (FrameBuffer*) env->GetDirectBufferAddress(obj);
}

JNIEXPORT jobject JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_construct
(JNIEnv *env, jclass klass, jint count, jint size, jstring fieldname, jboolean mustCompress)
//-------------------------------------------------------------------------------------------------
{
   FrameBuffer *instance = new FrameBuffer(count, (size_t) size, (mustCompress != JNI_FALSE));
   INSTANCES.insert((void *) instance);
   const char *psz = env->GetStringUTFChars(fieldname, 0);
   field = std::string(psz);
   env->ReleaseStringUTFChars(fieldname, psz);
   return env->NewDirectByteBuffer((void*) instance, sizeof(*instance));
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_destroy
(JNIEnv *env, jclass klass, jobject ref)
//-----------------------------------------------------------------------------------------------
{
   if (ref == nullptr)
      return;
   FrameBuffer* instance = (FrameBuffer*) env->GetDirectBufferAddress(ref);
   if (instance != nullptr)
   {
      delete instance;
      INSTANCES.erase((void *) instance);
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_push
(JNIEnv *env, jobject instance, jlong timestamp, jbyteArray data, jint retries)
//---------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
   {
      //if (env->IsSameObject(data, nullptr))
      if (retries == 0) retries = 1;
      if (data == nullptr)
         buffer->push((int64_t) timestamp, nullptr, retries);
      else
      {
         jbyte *buf = (jbyte *) env->GetPrimitiveArrayCritical(data, 0);
         buffer->push((int64_t) timestamp, reinterpret_cast<unsigned char *>(buf), retries);
         env->ReleasePrimitiveArrayCritical(data, buf, 0);
      }
//#ifdef ANDROID_LOG
//      LOGI("Q size %d", buffer->size());
//#endif
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_pushYUV
(JNIEnv *env, jobject instance, jlong timestamp, jobject Ybuffer, jint yLen, jobject Ubuffer, jint uLen, jint uStride,
 jobject Vbuffer, jint vLen, jint vStride,  jint retries)
//-------------------------------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
   {
      unsigned char *Y = nullptr, *U = nullptr, *V = nullptr;
      int ylen = 0, ulen = 0, vlen = 0, ustride = 0, vstride;
      Y = (unsigned char*) env->GetDirectBufferAddress(Ybuffer);
      U = (unsigned char*) env->GetDirectBufferAddress(Ubuffer);
      V = (unsigned char*) env->GetDirectBufferAddress(Vbuffer);
      ylen = yLen; ulen = uLen; vlen = vLen;
      ustride = uStride; vstride = vStride;
      if (retries == 0) retries = 1;
      buffer->push_YUV((int64_t) timestamp, Y, yLen, U, uLen, uStride, V, vLen, vStride, retries);
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_bufferOff
(JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->buffering(false);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_bufferOn
(JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->buffering(true);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_bufferClear
(JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
   {
      bool keep_buffering = buffer->buffering();
      buffer->buffering(false);
      FrameBufferData *buf;
      while ( (buf = buffer->pop()) != nullptr)
         delete buf;
      buffer->buffering(keep_buffering);
   }
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_bufferEmpty
(JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if ( (buffer) && (buffer->size() == 0) )
      return JNI_TRUE;
   return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_startTimestamp
(JNIEnv *env, jobject instance, jlong ts)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->timeoffset((int64_t) ts);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_writeFile
(JNIEnv *env, jobject instance, jstring filename)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
   {
      const char *psz = env->GetStringUTFChars(filename, 0);
      std::string file(psz);
      env->ReleaseStringUTFChars(filename, psz);
      buffer->filename(file);
   }
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_closeFile
  (JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->close();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_flushFile
  (JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->flush();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_writeOn
(JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->writing(true);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_writeOff
(JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->writing(false);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_writeCount
      (JNIEnv *env, jobject instance)
//-----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      return buffer->written();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_stop
  (JNIEnv *env, jobject instance)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      buffer->stop();
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_openRead
  (JNIEnv *env, jobject instance, jstring fileName)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
   {
      if (env->IsSameObject(fileName, nullptr))
      {
         if (buffer->read_open())
            return JNI_TRUE;
      }
      else
      {
         const char *psz = env->GetStringUTFChars(fileName, 0);
         if (psz == nullptr)
            return false;
         std::string filename(psz);
         env->ReleaseStringUTFChars(fileName, psz);
         if (buffer->read_open(filename))
            return JNI_TRUE;
      }
   }
   return JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_read
  (JNIEnv *env, jobject instance, jlongArray timestampRef, jintArray sizeRef, jobject frameBuf)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
   {
      int64_t ts;
      int size;
      unsigned char *buf = (unsigned char *) env->GetDirectBufferAddress(frameBuf);
      long pos = buffer->read(ts, size, buf);
      if (pos >= 0)
      {
         jlong *timestamp_arr = env->GetLongArrayElements(timestampRef, nullptr);
         timestamp_arr[0] = (jlong) ts;
         env->ReleaseLongArrayElements(timestampRef, timestamp_arr, 0);
//#ifdef ANDROID_LOG
//         LOGI("Read timestamp %ld", (long)ts);
//#endif

         jint* size_arr = env->GetIntArrayElements(sizeRef, nullptr);
         size_arr[0] = size;
         env->ReleaseIntArrayElements(sizeRef, size_arr, 0);
      }
      return pos;
   }
}

JNIEXPORT jlong JNICALL Java_to_augmented_reality_android_em_recorder_NativeFrameBuffer_readPos
      (JNIEnv *env, jobject instance, jlong offset)
//----------------------------------------------------------------------------------------------
{
   FrameBuffer *buffer = getBuffer(env, instance);
   if (buffer)
      return (jlong) buffer->readoffset((int64_t) offset);
}


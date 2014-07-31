/*
* Copyright (C) 2014 Donald Munro.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include <jni.h>
#include <android/log.h>

#include "to_augmented_reality_android_em_recorder_RGBAtoRGB.h"

#define  LOG_TAG    "RGBAtoRGB.so"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

typedef struct tag_thread_parameters
{
   unsigned char *rgba;
   int rgba_len;
   unsigned char *rgb; 
   int rgb_len;
   int stripe;
   
} thread_parameters;

volatile int running_threads = 0;

pthread_mutex_t running_mutex = PTHREAD_MUTEX_INITIALIZER;

unsigned short red_mask = 0xF800;
unsigned short green_mask = 0x7E0;
unsigned short blue_mask = 0x1F;

#define RGBA_STRIDE 4
#define RGB_STRIDE 3

void *stripe_thread(void *param)
//------------------------------
{ 
   int i, j;
   thread_parameters *params = (thread_parameters *) param;
   unsigned char *rgba = params->rgba, *rgb = params->rgb; 
   
   for (i=params->stripe, j=params->stripe; i<params->rgba_len; i+= RGBA_STRIDE)
   {
      rgb[j] = rgba[i];
      j += RGB_STRIDE;
   }
   pthread_mutex_lock(&running_mutex);
   running_threads--;
   pthread_mutex_unlock(&running_mutex);
}

JNIEXPORT void 
JNICALL Java_to_augmented_reality_android_em_recorder_RGBAtoRGB_nativeRGBAtoRGB(JNIEnv *env, 
                                                                                jclass class, 
                                                                                jbyteArray jrgba, 
                                                                                jbyteArray jrgb)
//--------------------------------------------------------------------------------------------
{
   unsigned char* prgba = (unsigned char*) (*env)->GetByteArrayElements(env, jrgba, NULL);
   unsigned char* prgb = (unsigned char*) (*env)->GetByteArrayElements(env, jrgb, NULL);
   jsize len_rgba = (*env)->GetArrayLength(env, jrgba);
   jsize len_rgb = (*env)->GetArrayLength(env, jrgb);   
   pthread_t threads[3];
   thread_parameters params[3];   
   int thread, ret;
   
   for (thread =0; thread<3; thread++)
   {
      params[thread].rgba = prgba;
      params[thread].rgba_len = len_rgba;
      params[thread].rgb = prgb;
      params[thread].rgb_len = len_rgb;
      params[thread].stripe = thread;   
      if ( (ret = pthread_create(&threads[thread], NULL, stripe_thread, (void *) &params[thread])) != 0)
      {
         LOGE("pthread_create failed ! error=%d", ret);
         (*env)->ReleaseByteArrayElements(env, jrgba, (jbyte *) prgba, 0);
         (*env)->ReleaseByteArrayElements(env, jrgb, (jbyte *) prgb, 0);
         return;
      }
      else
      {
         pthread_mutex_lock(&running_mutex);
         running_threads++;
         pthread_mutex_unlock(&running_mutex);
      }
   }
   struct timespec ts;
   ts.tv_sec = 0;
   ts.tv_nsec = 100000000L; // 100ms
   while (running_threads > 0)
   {
      if (nanosleep(&ts, NULL) < 0)
         break;
   }
   if (running_threads <= 0)
   {
      for (thread =0; thread<3; thread++)
         pthread_join(threads[thread], NULL);
   }
   
   (*env)->ReleaseByteArrayElements(env, jrgba, (jbyte *) prgba, 0);
   (*env)->ReleaseByteArrayElements(env, jrgb, (jbyte *) prgb, 0);
}

void *rgb565_thread(void *param)
//------------------------------
{ 
   int i, j;
   thread_parameters *params = (thread_parameters *) param;
   unsigned char *rgba = params->rgba;
   unsigned short *rgb = (unsigned short *) params->rgb; 
   int half = params->rgba_len >> 1;
   int start =0, jstart=0, end =-1;
   
   if (params->stripe == 0)
   {
      start = 0;
      jstart = 0;
      end = half;
   }
   else
   {
       start = half;
       jstart = half >> 2;
       end = params->rgba_len;
   }
   for (i=start, j=jstart; i<end; i+=4, j++)
   {      
      if ((i+2)<end)
      {
         unsigned short r = (rgba[i] << 11) & red_mask;
         unsigned short g = (rgba[i+1] << 5) & green_mask;
         unsigned short b = rgba[i+2] & blue_mask;
         rgb[j] = r | g | b; 
      }
   }
   pthread_mutex_lock(&running_mutex);
   running_threads--;
   pthread_mutex_unlock(&running_mutex);
}

JNIEXPORT void 
JNICALL Java_to_augmented_reality_android_em_recorder_RGBAtoRGB_nativeRGBAtoRGB565(JNIEnv *env, 
                                                                                   jclass class, 
                                                                                   jbyteArray jrgba, 
                                                                                   jbyteArray jrgb)
//--------------------------------------------------------------------------------------------------
{
   unsigned char* prgba = (unsigned char*) (*env)->GetByteArrayElements(env, jrgba, NULL);
   unsigned char* prgb =  (unsigned char*) (*env)->GetByteArrayElements(env, jrgb, NULL);
   jsize len_rgba = (*env)->GetArrayLength(env, jrgba);
   jsize len_rgb = (*env)->GetArrayLength(env, jrgb);   
   pthread_t threads[2];   
   thread_parameters params[2];   
   int thread, ret;
   //memset(prgb, 0 , len_rgb);
   for (thread =0; thread<2; thread++)
   {
      params[thread].rgba = prgba;
      params[thread].rgba_len = len_rgba;
      params[thread].rgb = prgb;
      params[thread].rgb_len = len_rgb;
      params[thread].stripe = thread;   
      if ( (ret = pthread_create(&threads[thread], NULL, rgb565_thread, (void *) &params[thread])) != 0)
      {
         LOGE("pthread_create failed ! error=%d", ret);
         (*env)->ReleaseByteArrayElements(env, jrgba, (jbyte *) prgba, 0);
         (*env)->ReleaseByteArrayElements(env, jrgb, (jbyte *) prgb, 0);
         return;
      }
      else
      {
         pthread_mutex_lock(&running_mutex);
         running_threads++;
         pthread_mutex_unlock(&running_mutex);
      }
   }
   struct timespec ts;
   ts.tv_sec = 0;
   ts.tv_nsec = 100000000L; // 100ms
   while (running_threads > 0)
   {
      if (nanosleep(&ts, NULL) < 0)
         break;
   }
   if (running_threads <= 0)
   {
      for (thread =0; thread<2; thread++)
         pthread_join(threads[thread], NULL);
   }
   
   (*env)->ReleaseByteArrayElements(env, jrgba, (jbyte *) prgba, 0);
   (*env)->ReleaseByteArrayElements(env, jrgb, (jbyte *) prgb, 0);	                                                                                   
}



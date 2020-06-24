#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

unsigned char jrgba[] = { 1,2,3,255, 4,5,6,255, 7,8,9,255, 10,11,12,255, 13,14,15,255, 16,17,18,255,
                          19,20,21,255, 22,23,24,255, 25,26,27,255, 28,29,30,255 };
unsigned char jrgb[30];                         

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
         printf("%u %d %d %d  %0X %0X %0X %0X\n", params->stripe, rgba[i], rgba[i+1], rgba[i+2], r, g, b, rgb[j]);
      }
   }
   printf("j = %d\n", j);
   pthread_mutex_lock(&running_mutex);
   running_threads--;
   pthread_mutex_unlock(&running_mutex);
}

int main(int argc, char **argv)
{
   int ret;
   pthread_t threads[3];
   thread_parameters params[3];   
   memset(jrgb, 0 , sizeof(jrgb));
   int thread;
   for (thread =0; thread<2; thread++)
   {
      params[thread].rgba = jrgba;
      params[thread].rgba_len = sizeof(jrgba);
      params[thread].rgb = jrgb;
      params[thread].rgb_len = sizeof(jrgb);
      params[thread].stripe = thread;   
      if ( (ret = pthread_create(&threads[thread], NULL, rgb565_thread, (void *) &params[thread])) != 0)
      {
         perror("Thread creation");
         exit(1);
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
   unsigned short *rgb = (unsigned short *) jrgb;
   for (thread=0; thread<sizeof(jrgba)/4; thread++)
   {
      register unsigned short pixel = rgb[thread];
      unsigned char r = (pixel & red_mask) >> 11;
      unsigned char g = (pixel & green_mask) >> 5;
      unsigned char b = (pixel & blue_mask);   
      printf("%3d %3d %3d 255 %0X", r, g, b, pixel);
   }
   printf("\n");
   
}

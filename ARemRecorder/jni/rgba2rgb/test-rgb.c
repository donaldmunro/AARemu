#include <stdio.h>
#include <stdlib.h>
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

int main(int argc, char **argv)
{
   pthread_t threads[3];
   thread_parameters params[3];   
   int thread;
   for (thread =0; thread<3; thread++)
   {
      params[thread].rgba = jrgba;
      params[thread].rgba_len = sizeof(jrgba);
      params[thread].rgb = jrgb;
      params[thread].rgb_len = sizeof(jrgb);
      params[thread].stripe = thread;   
      if (pthread_create(&threads[thread], NULL, stripe_thread, (void *) &params[thread]) != 0)
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
      for (thread =0; thread<3; thread++)
         pthread_join(threads[thread], NULL);
   }
   for (thread=0; thread<sizeof(jrgb); thread++)
      printf("%3d ", jrgb[thread]);
   printf("\n");
   
}

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

package to.augmented.reality.android.em;

import android.content.Context;
import android.content.pm.*;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

public class LocationThread extends HandlerThread
//================================================
{
   final static private String TAG = LocationThread.class.getSimpleName();
   final static public int MSG_QUIT = 1;
   static final private String LOCATION_SECURITY_ERROR =
         "Security exception when setting GPS mock location. Check if ACCESS_MOCK_LOCATION, " +
         "ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION permission is set in AndroidManifest.xml and " +
         "mock location are enabled in Developer settings on the device.";
   final static private int LOCATION_UPDATE_TIME = 500; //ms
   final static private float LOCATION_UPDATE_DISTANCE = 0.5f;

   private final Context context;

   private LocationListener locationCallback = null;

   private Handler handler = null;
   private Location dummyLocation = null;

   public Handler getHandler() { return handler; }

   private ARCamera camera = null;

   volatile Location lastLocation = null;
   public Location getLastLocation() { return lastLocation;  }

   public LocationThread(int priority, ARCamera camera, Context context, LocationListener locationListener)
   //----------------------------------------------------------------------------------------------------
   {
      super("SensorHandler", priority);
      this.camera = camera;
      this.context = context;
      this.locationCallback = locationListener;
      if (locationListener == null)
         throw new RuntimeException("LocationListener is null");
   }

   public void stopLocationHandler() { handler.dispatchMessage(Message.obtain(handler, MSG_QUIT)); }

   @Override
   protected void finalize() throws Throwable
   //----------------------------------------
   {
      super.finalize();
      onStopLocationThread();
   }

   @Override
   protected void onLooperPrepared()
   //-------------------------------
   {
      super.onLooperPrepared();
      onPrepareSensors();
      handler = new Handler(getLooper())
      {
         @Override
         public void handleMessage(Message msg)
         //------------------------------------
         {
            try
            {
               switch (msg.what)
               {
                  case MSG_QUIT:
                     onStopLocationThread();
                     break;
               }
            }
            catch (Throwable e)
            {
               Log.e(TAG, "", e);
            }
         }
      };
   }

   protected void onPrepareSensors()
   //--------------------------------------------
   {
      final LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      if (dummyLocation != null)
         onStopLocationThread();
      if (locationCallback != null)
         locationManager.removeUpdates(locationCallback);

      dummyLocation = camera.getRecordedLocation();
      boolean isDebugging = (context.getApplicationContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
      if ( (dummyLocation != null) && (isDebugging) )
      {
         try
         {
            try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Throwable _t) {}
            locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, false, true, true, 0, 5);
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, dummyLocation);
         }
         catch (SecurityException e)
         {
            Log.e(TAG, LOCATION_SECURITY_ERROR, e);
            Toast.makeText(context, "Exception setting mock GPS location: " + LOCATION_SECURITY_ERROR, Toast.LENGTH_LONG).show();
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_UPDATE_TIME, LOCATION_UPDATE_DISTANCE,
                                                   locationCallback);
         }
      }
      else if (! isDebugging)
      {
         Toast.makeText(context, "Cannot use mock location in non-debug mode", Toast.LENGTH_LONG).show();
         return;

      }
   }

   private void onStopLocationThread()
   //---------------------------------
   {
      LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      if (dummyLocation != null)
      {
         try
         {
            locationManager.clearTestProviderLocation(LocationManager.GPS_PROVIDER);
            locationManager.clearTestProviderStatus(LocationManager.GPS_PROVIDER);
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
            locationManager.clearTestProviderEnabled(LocationManager.GPS_PROVIDER);
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
         }
         catch (SecurityException e)
         {
            Log.e(TAG, LOCATION_SECURITY_ERROR, e);
            Toast.makeText(context, "Exception removing mock GPS location: " + LOCATION_SECURITY_ERROR, Toast.LENGTH_LONG).show();
         }
         dummyLocation = null;
      }
   }

}

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

package to.augmented.reality.android.em.recorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

// Permissions are checked at startup in RecorderActivity - Android Studio just ignores this annotation.
@SuppressLint({"NewApi", "MissingPermission, UseCheckPermission"})
@SuppressWarnings({"ResourceType"}) //this fixes it
public class LocationHandler implements LocationListener, Bufferable, Freezeable
//==============================================================================
{
   final static private String TAG = LocationHandler.class.getSimpleName();
   final static private int RECORD_SIZE = (Long.SIZE + Byte.SIZE + Double.SIZE*3 + Float.SIZE) / 8;

   Location lastLocation;
   boolean isWaitingGPS = true, isWaitingNetLoc = true;
   File recordingDir;
   LocationManager locationManager;
   File locationFile;
   DataOutputStream locationWriter;
   DataInputStream locationReader = null;
   RecorderActivity activity;
   volatile boolean mustWrite = false;
   private long startTimestamp = 0;
   private int writeCount = 0;
   private long filePos = 0;

   @SuppressLint({"NewApi", "MissingPermission, UseCheckPermission"})
   public LocationHandler(RecorderActivity activity, File dir) throws FileNotFoundException
   //--------------------------------------------------------------------------------------
   {
      //M permissions previously checked and queried in onResume and then again before creating class; ignore dumb Android Studio errors
      if (! activity.hasPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
         throw new SecurityException("Location permissions denied");
      this.activity = activity;
      locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
      recordingDir = dir;
      locationFile = new File(dir, "location");
      locationWriter =
            new DataOutputStream(new BufferedOutputStream(new FileOutputStream(locationFile, true), 32768));
      lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
      if (lastLocation == null)
         lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
      if (lastLocation == null)
         lastLocation =
               RecorderActivity.createLocation(false, 90.0f, 0.0f, 0); // Too bad if you're at the South Pole
      if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
         isWaitingGPS = false;
      if (locationManager != null)
      {
         if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, this, null);
         if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, this, null);
      }
   }

   @Override public void bufferOn() {  }

   @Override public void bufferOff() {  }

   @Override public void bufferClear() { }

   @Override public boolean bufferEmpty() { return true; }

   @Override public void writeFile(File f) throws IOException
   //--------------------------------------------------------
   {
      if (f.getParentFile() != null)
         recordingDir = f.getParentFile();
      if (recordingDir == null)
      {
         recordingDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "ARRecorder");
         recordingDir.mkdirs();
         if ( (! recordingDir.isDirectory()) || (! recordingDir.canWrite()) )
            recordingDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    "ARRecorder");
         recordingDir.mkdirs();
      }
      if ( (! recordingDir.isDirectory()) || (! recordingDir.canWrite()) )
         throw new RuntimeException("Cannot create recording file in directory " + recordingDir.getAbsolutePath());
      locationFile = new File(recordingDir, f.getName());
      if (locationWriter != null)
      {
         try { locationWriter.close(); } catch (Exception e) { Log.e(TAG, "", e); }
         locationWriter = null;
      }
      locationWriter =
            new DataOutputStream(new BufferedOutputStream(new FileOutputStream(locationFile, true), 32768));
   }

   @Override public boolean closeFile() throws IOException
   //-----------------------------------------------------
   {
      if (locationWriter != null)
      {
         locationWriter.close();
         return true;
      }
      else
         return false;
   }

   @Override public void flushFile()
   //-----------------------------------------------------
   {
      if (locationWriter != null)
         try { locationWriter.flush(); } catch (IOException e) { Log.e(TAG, "", e); }
   }

   @Override public File writeFile() { return locationFile; }

   @Override public void writeOff() { mustWrite = false; }

   @Override public void writeOn() { mustWrite = true; }

   @Override public void push(long timestamp, byte[] data) {  }

   @Override public void startTimestamp(long timestamp) { this.startTimestamp = timestamp; }

   @Override public int writeCount() { return writeCount; }

   @Override public long writeSize()
   //-------------------------------
   {
      if (locationFile != null)
      {
         flushFile();
         return locationFile.length();
      }
      else
         return 0L;
   }

   @Override
   public void stop()
   //----------------
   {
      if (locationManager == null)
         locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
      locationManager.removeUpdates(this);
      writeCount = 0;
   }

   @Override
   public void onLocationChanged(Location location)
   //---------------------------------------------
   {
      boolean isGPSLocation = location.getProvider().equalsIgnoreCase(LocationManager.GPS_PROVIDER);
      if ((isGPSLocation) && (isWaitingGPS))
      {
         activity.onGpsConnected(true);
         isWaitingNetLoc = isWaitingGPS = false;
         locationManager.removeUpdates(this);
         if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 5.0f, this, null);
      }
      else if ((!isGPSLocation) && (isWaitingNetLoc))
      {
         activity.onNetLocationConnected(true);
         isWaitingNetLoc = false;
         locationManager.removeUpdates(this);
         if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 5.0f, this, null);
         if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
         {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, this, null);
            isWaitingGPS = true;
         }
      }
      float accuracy = location.getAccuracy();
      if (accuracy >= 50) // || (location.getSpeed() > 5) )
         return;
      if (location.distanceTo(lastLocation) >= accuracy)
      {
         if (mustWrite)
         {
            long timestamp = SystemClock.elapsedRealtimeNanos() - startTimestamp;
            try
            {
               locationWriter.writeLong(timestamp);
               if (isGPSLocation)
                  locationWriter.writeByte('G');
               else
                  locationWriter.writeByte('N');
               locationWriter.writeDouble(location.getLatitude());
               locationWriter.writeDouble(location.getLongitude());
               locationWriter.writeDouble(location.getAltitude());
               locationWriter.writeFloat(accuracy);
               writeCount++;
            }
            catch (Exception e)
            {
               Log.e(TAG, "Error writing location update to " + locationFile.getAbsolutePath(), e);
            }
         }
         activity.onLocationChanged(location);
         lastLocation = location;
      }
   }

   @Override
   public boolean openForReading()
   //-----------------------------
   {
      if ( (locationFile == null) || (! locationFile.exists()) )
         return false;
      try
      {
         locationReader = new DataInputStream(new BufferedInputStream(new FileInputStream(locationFile), 32768));
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return false;
      }
   }

   @Override
   public boolean openForReading(File f)
   //-----------------------------------
   {
      if ( (f == null) || (! f.isFile()) || (! f.canRead()) )
         return false;
      if (locationReader != null)
      {
         try { locationReader.close(); } catch (Exception _e) {}
      }
      locationFile = f;
      try
      {
         locationReader = new DataInputStream(new BufferedInputStream(new FileInputStream(locationFile), 32768));
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return false;
      }
   }

   @Override
   public BufferData read() throws IOException
   //----------------------------------------
   {
      try
      {
         long ts = locationReader.readLong();
         char provider = (char) (locationReader.readByte() & 0xFF);
         double latitude = locationReader.readDouble();
         double longitude = locationReader.readDouble();
         double altitude = locationReader.readDouble();
         float accuracy = locationReader.readFloat();
         filePos += RECORD_SIZE;
         BufferData data = new BufferData();
         data.timestamp = ts;
         data.data = RecorderActivity.createLocation((provider == 'G'), latitude, longitude, altitude, accuracy);
         data.fileOffset = filePos;
         return data;
      }
      catch (EOFException e)
      {
         return null;
      }
   }

   @Override public long readPos(long offset) { throw new RuntimeException("readPos not supported"); };

   // @formatter:off
   @Override
   public void onStatusChanged(String provider, int status, Bundle extras) { }

   @Override
   public void onProviderEnabled(String provider) { }

   @Override
   public void onProviderDisabled(String provider)
   //-------------------------------------------------------
   {
      if (provider.equalsIgnoreCase(LocationManager.GPS_PROVIDER))
      {
         activity.onGpsConnected(false);
         locationManager.removeUpdates(this);
         isWaitingNetLoc = true;
         isWaitingGPS = false;
         if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, this, null);
      }
   }

   @Override
   public void freeze(Bundle B)
   //--------------------------
   {
      B.putBoolean("isWaitingGPS", isWaitingGPS);
      B.putBoolean("isWaitingNetLoc", isWaitingNetLoc);
      B.putBoolean("mustWrite", mustWrite);
      B.putString("recordingDir", recordingDir.getAbsolutePath());
      B.putString("locationFile", locationFile.getAbsolutePath());
      B.putInt("writeCount", writeCount);
   }

   @Override
   public void thaw(Bundle B)
   //-------------------------
   {
      isWaitingGPS = B.getBoolean("isWaitingGPS");
      isWaitingNetLoc = B.getBoolean("isWaitingNetLoc");
      mustWrite = B.getBoolean("mustWrite");
      recordingDir = new File(B.getString("recordingDir"));
      locationFile = new File(B.getString("locationFile"));
      writeCount = B.getInt("writeCount");
   }
}

package to.augmented.reality.android.em.recorder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Locale;

public class Three60RecordingResult extends Activity
//==================================================
{
   private File recordingDir;
   private float[] recordingIncrements;
   private int[] indices;
   private int[] kludges;
   ListView listView;
   CheckBox checkBoxDelete;
   ResultsAdapter adapter;
   int selectedItem = 0;

   @Override
   protected void onCreate(Bundle savedInstanceState)
   //------------------------------------------------
   {
      super.onCreate(savedInstanceState);
      setContentView(R.layout.three60_recording_result);
      final Intent intent = getIntent();
      recordingDir = new File(intent.getStringExtra("recordingDir"));
      recordingIncrements = intent.getFloatArrayExtra("recordingIncrements");
      indices = intent.getIntArrayExtra("indices");
      kludges = intent.getIntArrayExtra("kludges");
      listView = (ListView) findViewById(R.id.listViewResults);
      checkBoxDelete = (CheckBox) findViewById(R.id.checkBoxDeleteOthers);
      View header = getLayoutInflater().inflate(R.layout.three60_recording_result_header, null);
      listView.addHeaderView(header);
      adapter = new ResultsAdapter(this);
      listView.setAdapter(adapter);
  //    listView.setItemsCanFocus(true);
//      listView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
      listView.setOnItemClickListener(new AdapterView.OnItemClickListener()
      {
         @Override public void onItemClick(AdapterView<?> parent, final View view, int position, long id)
         {
            selectedItem = position - 1;
            adapter.notifyDataSetChanged();
         }
      });
      listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
      {
         @Override
         public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
         {
            selectedItem = position - 1;
         }

         @Override public void onNothingSelected(AdapterView<?> parent) { }
      });
      listView.setSelection(0);
   }

   public void onOK(View view)
   //-------------------------
   {
      if (selectedItem < 0)
      {
         Toast.makeText(this, "Select an item to use as the frames file", Toast.LENGTH_LONG).show();
         return;
      }
      float recordingIncrement = recordingIncrements[indices[selectedItem]];
      File framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
                                                             recordingIncrement));
      if (! framesFile.exists())
         Toast.makeText(this, "File " + framesFile.getAbsolutePath() + " not found", Toast.LENGTH_LONG).show();
      else
      {
         File fn = new File(recordingDir, recordingDir.getName() + ".frames");
         framesFile.renameTo(fn);
         framesFile = fn;
         File headerFile = new File(recordingDir, recordingDir.getName() + ".head");
         File newHeaderFile = new File(recordingDir, recordingDir.getName() + ".head.new");
         PrintWriter headerWriter;
         BufferedReader headerReader;
         boolean isAppending = false;
         try
         {
            headerReader = new BufferedReader(new FileReader(headerFile));
         }
         catch (Exception e)
         {
            headerReader = null;
         }
         try
         {
            headerWriter = new PrintWriter(newHeaderFile);
         }
         catch (Exception e)
         {
            try
            {
               headerWriter = new PrintWriter(new FileWriter(headerFile, true));
               isAppending = true;
            }
            catch (Exception ee)
            {
               Toast.makeText(this, "Error opening header file " + headerFile.getAbsolutePath() + " for appending",
                              Toast.LENGTH_LONG).show();
               headerWriter = null;
            }
         }

         if (headerWriter != null)
         {
            if ( (headerReader != null) && (! isAppending) )
            {
               String line;
               try
               {
                  while ( (line = headerReader.readLine()) != null)
                  {
                     if ( (line.contains("FramesFile")) || (line.contains("Increment")) )
                        continue;
                     headerWriter.println(line);
                  }
               }
               catch (Exception e)
               {
                  try
                  {
                     headerWriter = new PrintWriter(new FileWriter(headerFile, true));
                     isAppending = true;
                  }
                  catch (Exception ee)
                  {
                     Toast.makeText(this, "Error opening header file " + headerFile.getAbsolutePath() + " for appending",
                                    Toast.LENGTH_LONG).show();
                     headerWriter = null;
                  }
               }
            }
            headerWriter.println(String.format(Locale.US, "FramesFile=%s", framesFile.getAbsolutePath()));
            headerWriter.println(String.format(Locale.US, "Increment=%6.1f", recordingIncrement));
            headerWriter.close();
            if ( (! isAppending) && (newHeaderFile.exists()) && (newHeaderFile.length() > 0) )
            {
               headerFile.delete();
               newHeaderFile.renameTo(headerFile);
            }
            if (headerReader != null)
               try { headerReader.close(); } catch (Exception _e) {}
         }
         if (checkBoxDelete.isChecked())
         {
            for (int i=0; i<recordingIncrements.length; i++)
            {
               float increment = recordingIncrements[i];
               File f = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(), increment));
               f.delete();
            }
         }
         setResult(1);
         finish();
      }
   }

   private class ResultsAdapter extends BaseAdapter
   //===============================================
   {
      private final LayoutInflater inflater;

      public ResultsAdapter(Context context) { inflater = LayoutInflater.from(context); }

      @Override
      public View getView(int position, View convertView, ViewGroup parent)
      //-------------------------------------------------------------------
      {
         if (position > recordingIncrements.length)
            return getView(position, convertView, parent);
         //LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
         final View rowView = inflater.inflate(R.layout.three60_recording_result_row, null);
         TextView incrementText = (TextView) rowView.findViewById(R.id.three60_result_increment);
         TextView fileText = (TextView) rowView.findViewById(R.id.three60_result_filename);
         TextView kludgeText = (TextView) rowView.findViewById(R.id.three60_result_kludges);
         if (position == selectedItem)
         {
            incrementText.setTextColor(Color.GREEN);
            fileText.setTextColor(Color.GREEN);
            kludgeText.setTextColor(Color.GREEN);
         }
         else
         {
            incrementText.setTextColor(Color.GRAY);
            fileText.setTextColor(Color.GRAY);
            kludgeText.setTextColor(Color.GRAY);
         }
         int i = indices[position];
         incrementText.setText(String.format(Locale.US, "%.1f", recordingIncrements[i]));
         fileText.setText(String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(), recordingIncrements[i]));
         kludgeText.setText(Integer.toString(kludges[position]));
         return rowView;

      }

      @Override public int getCount() { return indices.length; }

      @Override
      public String getItem(int position)
      {
         int i = indices[position];
         return String.format(Locale.US, "%.1f", recordingIncrements[i]);
      }

      @Override
      public long getItemId(int position)
      {
         return position;
      }
   }

}

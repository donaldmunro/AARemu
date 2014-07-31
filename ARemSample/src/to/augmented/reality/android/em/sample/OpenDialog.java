/*
   Copyright [2013] [Donald Munro]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package to.augmented.reality.android.em.sample;

import android.app.*;
import android.os.*;
import android.text.*;
import android.util.*;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.util.*;

public class OpenDialog extends DialogFragment
//============================================
{
   static final String LOGTAG = OpenDialog.class.getSimpleName();

   File dir = null;
   String[] files = null;
   String fileName = null;

   EditText textFileName = null;
   ListView listViewFiles = null;
   ArrayAdapter<String> listAdapter = null;

   static public OpenDialog instance(File dir)
   //-----------------------------------------
   {
      OpenDialog dialog = new OpenDialog();
      Bundle bundle = new Bundle();
      bundle.putString("dir", dir.getAbsolutePath());
      dialog.setArguments(bundle);
      return dialog;
   }

   public OpenDialog() { }

   @Override
   public void onCreate(Bundle savedInstanceState)
   //---------------------------------------------
   {
      super.onCreate(savedInstanceState);
      setCancelable(true);
      setStyle(DialogFragment.STYLE_NORMAL, 0);
   }

   @Override
   public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle)
   //-----------------------------------------------------------------------------------
   {
      getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
      getDialog().setCanceledOnTouchOutside(true);
      final View v;
      try { v = inflater.inflate(R.layout.open_dialog, container, false); } catch (InflateException e) { Log.e(LOGTAG, "OpenDialog inflation error", e); return null; }
      if (bundle == null)
         bundle = getArguments();
      if (bundle != null)
      {
         String s = bundle.getString("dir");
         if (s != null)
            dir = new File(s);
      }
      if (dir == null)
      {
         Log.e(LOGTAG, "OpenDialog directory not specified");
         return null;
      }
      fileName = bundle.getString("file");
      files = readDir(dir);
      if (files == null)
         return null;

      textFileName = (EditText) v.findViewById(R.id.openName);
      listViewFiles = (ListView) v.findViewById(R.id.openList);
      final Button buttonOK = (Button) v.findViewById(R.id.openOK);
      final Button buttonCancel = (Button) v.findViewById(R.id.openCancel);
      listAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_dropdown_item_1line, files);
      listViewFiles.setAdapter(listAdapter);
      textFileName.addTextChangedListener(new TextWatcher()
      //==========================================
      {
         @Override
         public void beforeTextChanged(CharSequence s, int start, int count, int after)
         {
         }

         @Override
         public void onTextChanged(CharSequence s, int start, int before, int count)
         {
         }

         @Override
         public void afterTextChanged(Editable ed)
         //-------------------------------------------------
         {
            String s = ed.toString();
            if (s.trim().isEmpty())
            {
               fileName = null;
               listAdapter.getFilter().filter(null);
            }
            else
            {
               listAdapter.getFilter().filter(s);
               if (! s.endsWith(".head"))
                  s = s + ".head";
               File path = new File(dir, s);
               if (path.exists())
                  fileName = s;
            }
         }
      });
      InputFilter filter = new RestrictedCharFilter();
      textFileName.setFilters(new InputFilter[]{filter});
      listViewFiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
      //====================================================================
      {
         @Override
         public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
         //----------------------------------------------------------------------------------
         {
            fileName = listAdapter.getItem(position);
            textFileName.setText(fileName);
         }

         @Override public void onNothingSelected(AdapterView<?> parent) {  }
      });
      listViewFiles.setOnItemClickListener(new AdapterView.OnItemClickListener()
      {
         @Override
         public void onItemClick(AdapterView<?> parent, View view, int position, long id)
         //------------------------------------------------------------------------------
         {
            fileName = listAdapter.getItem(position);
            textFileName.setText(fileName);
         }
      });
      final DialogCloseable activity = (DialogCloseable) getActivity();
      buttonOK.setOnClickListener(new View.OnClickListener()
      //====================================================
      {
         @Override public void onClick(View v) {  activity.onDialogClosed(dir, fileName, false); dismiss();}
      });
      buttonCancel.setOnClickListener(new View.OnClickListener()
      //====================================================
      {
         @Override public void onClick(View v) {  activity.onDialogClosed(dir, fileName, true); dismiss(); }
      });
      return v;
   }

   @Override
   public void onSaveInstanceState(Bundle bundle)
   //--------------------------------------------
   {
      super.onSaveInstanceState(bundle);
      if (dir != null)
         bundle.putString("dir", dir.getAbsolutePath());
      if (fileName != null)
         bundle.putString("file", fileName);
   }

   @Override
   public void onResume()
   //--------------------
   {
      super.onResume();
      DisplayMetrics dm = getActivity().getResources().getDisplayMetrics();
      int width = dm.widthPixels;
      int height = dm.heightPixels;
      int resId = getActivity().getResources().getIdentifier("status_bar_height", "dimen", "android");
      if (resId > 0)
         height -= getResources().getDimensionPixelSize(resId);
      TypedValue typedValue = new TypedValue();
      if (getActivity().getTheme().resolveAttribute(android.R.attr.actionBarSize, typedValue, true))
         height -= getResources().getDimensionPixelSize(typedValue.resourceId);
      Window window = getDialog().getWindow();
      window.setLayout(width, height);
      window.setGravity(Gravity.CENTER);

      if ( (files == null) && (dir != null) )
      {
         files = readDir(dir);
         listAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_dropdown_item_1line, files);
         listViewFiles.setAdapter(listAdapter);
      }
      if (fileName != null)
         textFileName.setText(fileName);
   }

   protected String[] readDir(File dir)
   //----------------------------------
   {
      if (! dir.exists())
         return new String[0];
      if (! dir.isDirectory())
      {
         Log.e(LOGTAG, dir.getAbsolutePath() + " is not a directory");
         return null;
      }
      File[] af = dir.listFiles(new FilenameFilter()
      {
         @Override
         public boolean accept(File dir, String name)
         //------------------------------------------
         {
            int p = name.lastIndexOf(".");
            if (p > 0)
            {
               String ext = name.substring(p);
               if (ext.trim().equalsIgnoreCase(".head"))
               {
                  String basename = name.substring(0, p);
                  File ff = new File(dir, basename + ".frames");
                  if (ff.exists())
                     return true;
               }
            }
            return false;
         }
      });
      String[] afn = new String[af.length];
      int i = 0;
      for (File f : af)
         afn[i++] = f.getName();
      Arrays.sort(afn);
      return afn;
   }

   public interface DialogCloseable
   //==============================
   {
      public void onDialogClosed(File dir, String filename, boolean isCancelled);
   }

   protected class RestrictedCharFilter implements InputFilter
   //=========================================================
   {
      public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend)
      //-----------------------------------------------------------------------------------------------------
      {
         for (int i = start; i < end; i++)
         {
            char ch = source.charAt(i);
            if ( (! Character.isLetterOrDigit(ch)) && (ch != '_') && (ch != '-') && (ch != ' ') && (ch != '.'))
               return "";
         }
         return null;
      }
   }

}

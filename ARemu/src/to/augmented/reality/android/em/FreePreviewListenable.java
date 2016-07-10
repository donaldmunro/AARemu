package to.augmented.reality.android.em;

/**
 * Created by root on 08/07/16.
 */
public interface FreePreviewListenable
//====================================
{
   /**
    * Called when the Preview starts
    */
   void onStarted();

   /**
    * Called at the end of preview. If repeat is on then it could be called on multiple occasions in which case the
    * iterno parameter will reflect the iteration count.
    * @param iterNo The iteration count if repeat is on
    * @return true to continue, false to stop preview. If repeat is not on and true is returned then a single repeat
    * will be carried out.
    */
   boolean onComplete(int iterNo);

   void onError(String msg, Exception e);
}

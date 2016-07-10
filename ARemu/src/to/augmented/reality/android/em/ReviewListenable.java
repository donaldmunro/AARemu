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

/**
 * Review callback interface.
 */
public interface ReviewListenable
//===============================
{
   /**
    * Called before starting review
    */
   void onReviewStart();

   /**
    * Called before displaying the review frame for the specified bearing.
    * @param bearing
    */
   void onReview(float bearing);

   /**
    * Called after displaying the review frame for the specified bearing.
    * @param bearing
    */
   void onReviewed(float bearing);

   /**
    * Called when the review completes or is terminated.
    */
   void onReviewComplete();
}

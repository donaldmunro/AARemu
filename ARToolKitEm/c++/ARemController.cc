#include <algorithm>

#define protected public
#define private public
#include "ARWrapper/VideoSource.h"
#undef protected
#undef private

#include "ARemController.h"
#include "VideoSourceFactory.h"

bool ARemController::startRunning(const char *vconf, const char *cparaName, const char *cparaBuff,
                                  const long cparaBuffLen)
//-----------------------------------------------------------------------------------------------
{
   if (state != BASE_INITIALISED)
   {
      LOGE("ARController::startRunning(): Error: not initialized, exiting, returning false");
      return false;
   }
   m_videoSource0 = createVideoSource(vconf, cparaName, cparaBuff, cparaBuffLen, "ARController::startRunning()");
   if (m_videoSource0 == nullptr)
      return false;

   m_videoSourceIsStereo = false;
   state = WAITING_FOR_VIDEO;
   stateWaitingMessageLogged = false;
   return true;
}

bool ARemController::startRunningStereo(const char *vconfL, const char *cparaNameL, const char *cparaBuffL, const long cparaBuffLenL,
                                        const char *vconfR, const char *cparaNameR, const char *cparaBuffR, const long cparaBuffLenR,
                                        const char *transL2RName, const char *transL2RBuff, const long transL2RBuffLen)
//-----------------------------------------------------------------------------------------------------------------------------------
{
   if (state != BASE_INITIALISED)
   {
      LOGE("ARController::startRunningStereo(): Error: not initialized, exiting, returning false");
      return false;
   }

   if (transL2RName)
   {
      if (arParamLoadExt(transL2RName, m_transL2R) < 0)
      {
         LOGE("ARController::startRunningStereo(): Error: arParamLoadExt, exiting, returning false");
         return false;
      }
   }
   else if (transL2RBuff && transL2RBuffLen > 0)
   {
      if (arParamLoadExtFromBuffer(transL2RBuff, transL2RBuffLen, m_transL2R) < 0)
      {
         LOGE("ARController::startRunningStereo(): Error: arParamLoadExtFromBuffer, exiting, returning false");
         return false;
      }
   }
   else
   {
      logv(AR_LOG_LEVEL_ERROR, "ARController::startRunningStereo(): Error: transL2R not specified, exiting, returning false");
      return false;
   }
   arParamDispExt(m_transL2R);

   m_videoSource0 =
         createVideoSource(vconfL, cparaNameL, cparaBuffL, cparaBuffLenL, "ARController::startRunningStereo()");
   if (m_videoSource0 == nullptr)
      return false;
   m_videoSource1 =
         createVideoSource(vconfR, cparaNameR, cparaBuffR, cparaBuffLenR, "ARController::startRunningStereo()");
   if (m_videoSource1 == nullptr)
   {
      delete m_videoSource0;
      return false;
   }

   m_videoSourceIsStereo = true;
   state = WAITING_FOR_VIDEO;
   stateWaitingMessageLogged = false;
   return true;
}

VideoSource* ARemController::createVideoSource(const char *vconf, const char *cparaName, const char *cparaBuff,
                                               const long cparaBuffLen, const char *tag)
//----------------------------------------------------------------------------------------------------------
{
   std::string vconfs(vconf), device, format;
   std::transform(vconfs.begin(), vconfs.end(), vconfs.begin(), ::tolower);
   VideoSource* videoSource = nullptr;
   if (vconfArg(vconfs, "-format", format) >= 0)
       format = trim(format);
   else
      format = "";
   if (vconfArg(vconfs, "-device", device, 0, true) >= 0)
   {
      if ( (device != "aremu") && (device != "arem") )
         device = "android";
      else
         isEmulation = true;
   }

   videoSource = videoSourceFactory.newVideoSource(device.c_str(), format.c_str());
   if (videoSource == nullptr)
   {
      LOGE("Error creating video source");
      return nullptr;
   }
   std::string s = format;
   std::transform(s.begin(), s.end(), s.begin(), ::toupper);
   s = "-device=Android -format=" + s;
   videoSource->configure(s.c_str(), cparaName, cparaBuff, cparaBuffLen);
   if (! videoSource->open())
   {
      LOGE("ARController::startRunning(): Error: unable to open video source, exiting, returning false");
      delete videoSource;
      return nullptr;
   }
   if (isEmulation)
      ((ARemVideoSource *) videoSource)->setPixelFormat(format);
   return videoSource;
}

//#if TARGET_PLATFORM_ANDROID
bool ARemController::videoAcceptImage(JNIEnv *env, jobject obj, const int videoSourceIndex, jbyteArray pinArray,
                                      jint width, jint height, jint cameraIndex, jboolean cameraIsFrontFacing)
//-------------------------------------------------------------------------------------------------------------
{
   if (videoSourceIndex < 0 || videoSourceIndex > (m_videoSourceIsStereo ? 1 : 0)) return false;

   int ret = true;
   lockVideoSource();

   VideoSource *vs = (videoSourceIndex == 0 ? m_videoSource0 : m_videoSource1);
   if (! vs)
      ret = false;
   else
   {
      AndroidVideoSource* avs = (AndroidVideoSource *)vs;

      if (!avs->isRunning())
      {
         if (!avs->getVideoReadyAndroid(width, height, cameraIndex, cameraIsFrontFacing))
         {
            ret = false;
            goto done;
         }
         if (!avs->isRunning())
            goto done;
      }

      if (!pinArray)
      { // Sanity check.
         ret = false;
         goto done;
      }

      if (avs->getPixelFormat() == AR_PIXEL_FORMAT_NV21)
      {
         env->GetByteArrayRegion(pinArray, 0, avs->getFrameSize(), (jbyte *)avs->getFrame());
         avs->acceptImage(NULL);
      }
      else
      {
         if (jbyte* buff = env->GetByteArrayElements(pinArray, NULL))
         {
            if (isEmulation)
               ((ARemVideoSource *) avs)->acceptImage((unsigned char *)buff); //acceptImage not virtual
            else
               avs->acceptImage((unsigned char *)buff);
            env->ReleaseByteArrayElements(pinArray, buff, JNI_ABORT); // JNI_ABORT -> don't copy back changes on the native side to the Java side.
         }
      }
   }

   done:
   unlockVideoSource();

   return ret;
}
//#endif

long ARemController::vconfArg(std::string& vconf, std::string key, std::string& value, unsigned long pos,
                              bool is_erase)
//------------------------------------------------------------------------------------------------------
{
   value = "";
   long end = -1L;
   unsigned long p = vconf.find(key, pos);
   if (p == std::string::npos)
      return -1;
   unsigned long start = p;
   unsigned long pp = vconf.find("=", p);
   if (pp == std::string::npos)
      return -1;
   std::string k = trim(vconf.substr(p, pp - p));
   if (k == key)
   {
      p = pp + 1;
      if (vconf[p] == '"')
      {
         p++;
         pp = vconf.find("\"", p);
         if (pp == std::string::npos)
            end = pp = vconf.length();
         else
            end = pp + 1;
      }
      else
      {
         pp = vconf.find_first_of("\t\n ", p);
         if (pp == std::string::npos)
            end = pp = vconf.length();
         else
            end = pp;
      }
      value = trim(vconf.substr(p, pp - p));
      if ( (value.front() == '"') && (value.back() == '"') )
         value = value.substr(1, value.length() - 2);
   }
   else
      return -1L;
   if (is_erase)
   {
      vconf.erase(vconf.begin() + start, vconf.begin() + end);
      end -= (end - start);
   }
   return end;
}

std::string ARemController::trim(const std::string &str, std::string chars)
//--------------------------------------------------------------------------
{
   if (str.length() == 0)
      return str;
   unsigned long b = str.find_first_not_of(chars);
   unsigned long e = str.find_last_not_of(chars);
   if (b == std::string::npos) return "";
   return std::string(str, b, e - b + 1);
}

bool ARemController::update()
//---------------------------
{
   if (state != DETECTION_RUNNING)
   {
      if (state != WAITING_FOR_VIDEO)
      {
         // State is NOTHING_INITIALISED or BASE_INITIALISED.
         logv(AR_LOG_LEVEL_ERROR, "ARWrapper::ARController::update(): Error-if (state != WAITING_FOR_VIDEO) true, exiting returning false");
         return false;

      } else {

         // First check there is a video source and it's open.
         if (!m_videoSource0 || !m_videoSource0->isOpen() || (m_videoSourceIsStereo && (!m_videoSource1 || !m_videoSource1->isOpen()))) {
            logv(AR_LOG_LEVEL_ERROR, "ARWrapper::ARController::update(): Error-no video source or video source is closed, exiting returning false");
            return false;
         }

         // Video source is open, check whether it's running.
         // This path is only exercised on Android, since ARToolKit video goes straight to running.
         // If it's not running, return to caller now.
         if (!m_videoSource0->isRunning() || (m_videoSourceIsStereo && !m_videoSource1->isRunning())) {

            if (!stateWaitingMessageLogged) {
               logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): \"Waiting for video\" message logged, exiting returning true");
               stateWaitingMessageLogged = true;
            }
            return true;
         }

         // Initialise the parts of the AR tracking that rely on information from the video source.
         // Compute the projection matrix.
#if TARGET_PLATFORM_ANDROID || TARGET_PLATFORM_IOS || TARGET_PLATFORM_WINRT
         arglCameraFrustumRHf(&m_videoSource0->cparamLT->param, m_projectionNearPlane, m_projectionFarPlane, m_projectionMatrix0);
         if (m_videoSourceIsStereo) arglCameraFrustumRHf(&m_videoSource1->cparamLT->param, m_projectionNearPlane, m_projectionFarPlane, m_projectionMatrix1);
#else
         arglCameraFrustumRH(&m_videoSource0->getCameraParameters()->param, m_projectionNearPlane, m_projectionFarPlane, m_projectionMatrix0);
            if (m_videoSourceIsStereo) arglCameraFrustumRH(&m_videoSource1->getCameraParameters()->param, m_projectionNearPlane, m_projectionFarPlane, m_projectionMatrix1);
#endif
         m_projectionMatrixSet = true;
         logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): Video ready, computed projection matrix using near=%f far=%f",
              m_projectionNearPlane, m_projectionFarPlane);
         logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): setting state to DETECTION_RUNNING");
         state = DETECTION_RUNNING;
      }
   }

   // Get frame(s);
   ARUint8 *image0, *image1 = NULL;
   int frameStamp0, frameStamp1;
   image0 = m_videoSource0->frameBuffer;//->getFrame();
   if (!image0)
   {
      logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): m_videoSource0->getFrame() called but no frame returned, exiting returning true");
      return true;
   }
   if (m_videoSourceIsStereo) {
      image1 = m_videoSource1->frameBuffer;//->getFrame();
      if (!image1) {
         logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): m_videoSource1->getFrame() called but no frame returned, exiting returning true");
         return true;
      }
   }

   // Check framestamp(s);
//   if (isEmulation)
//      frameStamp0 = ((ARemVideoSource *) m_videoSource0)->getFrameStamp();
//   else
      frameStamp0 = m_videoSource0->frameStamp;//->getFrameStamp();
   if (frameStamp0 == m_videoSourceFrameStamp0)
   {
      logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): if (frameStamp0 == m_videoSourceFrameStamp0) true, exiting returning true");
      return true;
   }
   if (m_videoSourceIsStereo) {
      frameStamp1 = m_videoSource1->frameStamp; //->getFrameStamp();
      if (frameStamp1 == m_videoSourceFrameStamp1) {
         logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): if (frameStamp1 == m_videoSourceFrameStamp1) true, exiting returning true");
         return true;
      }
      m_videoSourceFrameStamp1 = frameStamp1;
   }
   m_videoSourceFrameStamp0 = frameStamp0;
   //logv("ARController::update() gotFrame");

   //
   // Detect markers.
   //

   if (doMarkerDetection) {
      logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): if (doMarkerDetection) true");

      ARMarkerInfo *markerInfo0 = NULL;
      ARMarkerInfo *markerInfo1 = NULL;
      int markerNum0 = 0;
      int markerNum1 = 0;

      if (!m_arHandle0 || (m_videoSourceIsStereo && !m_arHandle1)) {
         if (!initAR()) {
            logv(AR_LOG_LEVEL_ERROR, "ARController::update(): Error initialising AR, exiting returning false");
            return false;
         }
      }

      if (m_arHandle0) {
         if (arDetectMarker(m_arHandle0, image0) < 0) {
            logv(AR_LOG_LEVEL_ERROR, "ARController::update(): Error: arDetectMarker(), exiting returning false");
            return false;
         }
         markerInfo0 = arGetMarker(m_arHandle0);
         markerNum0 = arGetMarkerNum(m_arHandle0);
      }
      if (m_videoSourceIsStereo && m_arHandle1) {
         if (arDetectMarker(m_arHandle1, image1) < 0) {
            logv(AR_LOG_LEVEL_ERROR, "ARController::update(): Error: arDetectMarker(), exiting returning false");
            return false;
         }
         markerInfo1 = arGetMarker(m_arHandle1);
         markerNum1 = arGetMarkerNum(m_arHandle1);
      }

      // Update square markers.
      bool success = true;
      if (!m_videoSourceIsStereo) {
         for (std::vector<ARMarker *>::iterator it = markers.begin(); it != markers.end(); ++it) {
            if ((*it)->type == ARMarker::SINGLE) {
               success &= ((ARMarkerSquare *)(*it))->updateWithDetectedMarkers(markerInfo0, markerNum0, m_ar3DHandle);
            } else if ((*it)->type == ARMarker::MULTI) {
               success &= ((ARMarkerMulti *)(*it))->updateWithDetectedMarkers(markerInfo0, markerNum0, m_ar3DHandle);
            }
         }
      } else {
         for (std::vector<ARMarker *>::iterator it = markers.begin(); it != markers.end(); ++it) {
            if ((*it)->type == ARMarker::SINGLE) {
               success &= ((ARMarkerSquare *)(*it))->updateWithDetectedMarkersStereo(markerInfo0, markerNum0, markerInfo1, markerNum1, m_ar3DStereoHandle, m_transL2R);
            } else if ((*it)->type == ARMarker::MULTI) {
               success &= ((ARMarkerMulti *)(*it))->updateWithDetectedMarkersStereo(markerInfo0, markerNum0, markerInfo1, markerNum1, m_ar3DStereoHandle, m_transL2R);
            }
         }
      }
   } // doMarkerDetection

#if HAVE_NFT
   if (doNFTMarkerDetection) {
        logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): if (doNFTMarkerDetection) true");

        if (!m_kpmHandle || !m_ar2Handle) {
            if (!initNFT()) {
                logv(AR_LOG_LEVEL_ERROR, "ARController::update(): Error initialising NFT, exiting returning false");
                return false;
            }
        }
        if (!trackingThreadHandle) {
            loadNFTData();
        }

        if (trackingThreadHandle) {

            // Do KPM tracking.
            float err;
            float trackingTrans[3][4];

            if (m_kpmRequired) {
                if (!m_kpmBusy) {
                    trackingInitStart(trackingThreadHandle, image0);
                    m_kpmBusy = true;
                } else {
                    int ret;
                    int pageNo;
                    ret = trackingInitGetResult(trackingThreadHandle, trackingTrans, &pageNo);
                    if (ret != 0) {
                        m_kpmBusy = false;
                        if (ret == 1) {
                            if (pageNo >= 0 && pageNo < PAGES_MAX) {
								if (surfaceSet[pageNo]->contNum < 1) {
									//logv("Detected page %d.\n", pageNo);
									ar2SetInitTrans(surfaceSet[pageNo], trackingTrans); // Sets surfaceSet[page]->contNum = 1.
								}
                            } else {
                                logv(AR_LOG_LEVEL_ERROR, "ARController::update(): Detected bad page %d", pageNo);
                            }
                        } else /*if (ret < 0)*/ {
                            //logv("No page detected.");
                        }
                    }
                }
            }

            // Do AR2 tracking and update NFT markers.
            int page = 0;
            int pagesTracked = 0;
            bool success = true;
            ARdouble *transL2R = (m_videoSourceIsStereo ? (ARdouble *)m_transL2R : NULL);

            for (std::vector<ARMarker *>::iterator it = markers.begin(); it != markers.end(); ++it) {
                if ((*it)->type == ARMarker::NFT) {

                    if (surfaceSet[page]->contNum > 0) {
                        if (ar2Tracking(m_ar2Handle, surfaceSet[page], image0, trackingTrans, &err) < 0) {
                            //logv("Tracking lost on page %d.", page);
                            success &= ((ARMarkerNFT *)(*it))->updateWithNFTResults(-1, NULL, NULL);
                        } else {
                            //logv("Tracked page %d (pos = {% 4f, % 4f, % 4f}).\n", page, trackingTrans[0][3], trackingTrans[1][3], trackingTrans[2][3]);
                            success &= ((ARMarkerNFT *)(*it))->updateWithNFTResults(page, trackingTrans, (ARdouble (*)[4])transL2R);
                            pagesTracked++;
                        }
                    }

                    page++;
                }
            }

            m_kpmRequired = (pagesTracked < (m_nftMultiMode ? page : 1));

        } // trackingThreadHandle
    } // doNFTMarkerDetection
#endif // HAVE_NFT
   logv(AR_LOG_LEVEL_DEBUG, "ARWrapper::ARController::update(): exiting, returning true");

   return true;
}

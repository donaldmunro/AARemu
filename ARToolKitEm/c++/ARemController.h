#ifndef AREMCONTROLLER_H
#define AREMCONTROLLER_H

#define private public  // WARNING : PG X rated
#include "ARWrapper/ARController.h"
#define protected public
#include "ARWrapper/VideoSource.h"
#undef protected
#undef private
#include "ARWrapper/ARMarker.h"
#include "ARemVideoSource.h"
#include "VideoSourceFactory.h"

class ARemController : public ARController
//========================================
{
protected:
   VideoSource* createVideoSource(const char *vconf, const char *cparaName, const char *cparaBuff,
                                  const long cparaBuffLen, const char *tag);
   VideoSourceFactory videoSourceFactory;
   bool isEmulation = false;

public:
   ARemController() {}
   ARemController(VideoSourceFactory &factory) { videoSourceFactory = factory; }
   virtual ~ARemController() { }
   virtual const char* getARToolKitVersion() { return ARController::getARToolKitVersion(); }
   virtual int getError() { return ARController::getError(); }
   virtual bool initialiseBase(const int patternSize = AR_PATT_SIZE1, const int patternCountMax = AR_PATT_NUM_MAX)
   //-------------------------------------------------------------------------------------------------------------
   {
      return ARController::initialiseBase(patternSize, patternCountMax);
   }
   virtual bool startRunning(const char* vconf, const char* cparaName, const char* cparaBuff, const long cparaBuffLen);
   virtual bool startRunningStereo(const char* vconfL, const char* cparaNameL, const char* cparaBuffL, const long cparaBuffLenL,
                                   const char* vconfR, const char* cparaNameR, const char* cparaBuffR, const long cparaBuffLenR,
                                   const char* transL2RName, const char* transL2RBuff, const long transL2RBuffLen);
   virtual bool update(); // {return ARController::update(); }
   virtual bool canAddMarker() { return ARController::canAddMarker(); }
   virtual void setProjectionNearPlane(const ARdouble plane) { ARController::setProjectionNearPlane(plane); }
   virtual void setProjectionFarPlane(const ARdouble plane) { ARController::setProjectionFarPlane(plane); }
   virtual ARdouble projectionNearPlane(void) { return ARController::projectionNearPlane(); }
   virtual ARdouble projectionFarPlane(void) { return ARController::projectionFarPlane(); }
//#if TARGET_PLATFORM_ANDROID
   virtual bool videoAcceptImage(JNIEnv* env, jobject obj, const int videoSourceIndex, jbyteArray pinArray, jint width,
                                 jint height, jint cameraIndex, jboolean cameraIsFrontFacing);
//   {
//      return ARController::videoAcceptImage(env, obj, videoSourceIndex, pinArray, width, height, cameraIndex, cameraIsFrontFacing);
//   }
//#endif
   virtual bool videoParameters(const int videoSourceIndex, int *width, int *height, AR_PIXEL_FORMAT *pixelFormat)
   {
      return ARController::videoParameters(videoSourceIndex, width, height, pixelFormat);
   }
   virtual bool isRunning() { return ARController::isRunning(); }
   virtual bool stopRunning() { return ARController::stopRunning();}
   virtual bool shutdown() { return ARController::shutdown();}
   virtual bool getProjectionMatrix(const int source, ARdouble proj[16]) { return ARController::getProjectionMatrix(source, proj); }
   virtual int addMarker(const char* cfg) { return ARController::addMarker(cfg); }
   virtual bool removeMarker(int UID) { return ARController::removeMarker(UID); }
   virtual int removeAllMarkers() {return ARController::removeAllMarkers(); }
   virtual unsigned int countMarkers() { return ARController::countMarkers(); }
   virtual ARMarker* findMarker(int UID) { return ARController::findMarker(UID); }
   virtual bool capture() { return ARController::capture(); }
   virtual bool updateTexture(const int source, Color* buffer) { return ARController::updateTexture(source, buffer); }
   virtual bool updateTexture32(const int source, uint32_t *buffer) { return ARController::updateTexture32(source, buffer); }
#ifndef _WINRT
   virtual bool updateTextureGL(const int source, const int textureID) { return ARController::updateTextureGL(source, textureID); }
#endif // !_WINRT
   virtual void setDebugMode(bool debug) { ARController::setDebugMode(debug); }
   virtual bool getDebugMode() const { return ARController::getDebugMode(); }
   virtual void setImageProcMode(int mode) { ARController::setImageProcMode(mode); }
   virtual int getImageProcMode() const { return ARController::getImageProcMode(); }
   virtual void setThreshold(int thresh) { ARController::setThreshold(thresh); }
   virtual int getThreshold() const { return ARController::getThreshold(); }
   virtual void setThresholdMode(int mode) { ARController::setThresholdMode(mode); }
   virtual int getThresholdMode() const { return ARController::getThresholdMode(); }
   virtual void setLabelingMode(int mode) { ARController::setLabelingMode(mode); }
   virtual int getLabelingMode() const { return ARController::getLabelingMode(); }
   virtual void setPatternDetectionMode(int mode) { ARController::setPatternDetectionMode(mode); }
   virtual int getPatternDetectionMode() const { return ARController::getPatternDetectionMode(); }
   virtual void setPattRatio(float ratio) { ARController::setPattRatio(ratio); }
   virtual float getPattRatio() const { return ARController::getPattRatio(); }
   virtual void setMatrixCodeType(int type) { ARController::setMatrixCodeType(type); }
   virtual int getMatrixCodeType() const { return ARController::getMatrixCodeType(); }
   virtual void setNFTMultiMode(bool on) { ARController::setNFTMultiMode(on); }
   virtual bool getNFTMultiMode() const { return ARController::getNFTMultiMode(); }
   virtual bool updateDebugTexture(const int source, Color* buffer) { return ARController::updateDebugTexture(source, buffer); }
   virtual bool updateDebugTexture32(const int source, uint32_t* buffer) { return ARController::updateDebugTexture32(source, buffer); }
//   virtual bool getPatternImage(int patternID, Color* buffer) { return ARController::getPatternImage(patternID, buffer); }
   virtual bool loadOpticalParams(const char *name, const char *buffer, const long buffLen, ARdouble *fovy_p,
                                  ARdouble *aspect_p, ARdouble m[16], ARdouble p[16])
   //--------------------------------------------------------------------------------------------------------
   {
      return ARController::loadOpticalParams(name, buffer, buffLen, fovy_p, aspect_p, m, p);
   }

   static long vconfArg(std::string& vconf, std::string key, std::string& value, unsigned long pos = 0,
                        bool is_erase =false);
   static std::string trim(const std::string &str, std::string chars =" \t");
};

#endif

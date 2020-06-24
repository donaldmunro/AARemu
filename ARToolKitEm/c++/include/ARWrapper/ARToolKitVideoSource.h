/*
 *  ARToolKitVideoSource.h
 *  ARToolKit5
 *
 *  This file is part of ARToolKit.
 *
 *  ARToolKit is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  ARToolKit is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with ARToolKit.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  As a special exception, the copyright holders of this library give you
 *  permission to link this library with independent modules to produce an
 *  executable, regardless of the license terms of these independent modules, and to
 *  copy and distribute the resulting executable under terms of your choice,
 *  provided that you also meet, for each linked independent module, the terms and
 *  conditions of the license of that module. An independent module is a module
 *  which is neither derived from nor based on this library. If you modify this
 *  library, you may extend this exception to your version of the library, but you
 *  are not obligated to do so. If you do not wish to do so, delete this exception
 *  statement from your version.
 *
 *  Copyright 2015 Daqri, LLC.
 *  Copyright 2010-2015 ARToolworks, Inc.
 *
 *  Author(s): Julian Looser, Philip Lamb
 *
 */

#ifndef ARTOOLKITVIDEOSOURCE_H
#define ARTOOLKITVIDEOSOURCE_H

#include <ARWrapper/VideoSource.h>

#if !TARGET_PLATFORM_ANDROID

/**
 * Video source implementation using the ARToolKit arVideo module. This video source uses ARToolKit's 
 * built in video capture functions, and can therefore be used on any platform on which arVideo works, 
 * including Windows, Linux and OSX.
 */
class ARToolKitVideoSource : public VideoSource {

protected:

    AR2VideoParamT *gVid;

public:

	ARToolKitVideoSource();

	virtual const char* getName();

	virtual bool open();
    
    bool open2();

	virtual bool captureFrame();

	virtual bool close();

};

#endif

#endif // !#ifndef ARTOOLKITVIDEOSOURCE_H

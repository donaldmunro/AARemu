/*
 *	videoImage.h
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
 *  Copyright 2012-2015 ARToolworks, Inc.
 *
 *  Author(s): Philip Lamb
 *
 */

#ifndef AR_VIDEO_IMAGE_H
#define AR_VIDEO_IMAGE_H


#include <AR/ar.h>
#include <AR/video.h>

#ifdef  __cplusplus
extern "C" {
#endif

typedef struct _AR2VideoParamImageT AR2VideoParamImageT;


int                    ar2VideoDispOptionImage     ( void );
AR2VideoParamImageT   *ar2VideoOpenImage           ( const char *config );
int                    ar2VideoCloseImage          ( AR2VideoParamImageT *vid );
int                    ar2VideoGetIdImage          ( AR2VideoParamImageT *vid, ARUint32 *id0, ARUint32 *id1 );
int                    ar2VideoGetSizeImage        ( AR2VideoParamImageT *vid, int *x,int *y );
AR_PIXEL_FORMAT        ar2VideoGetPixelFormatImage ( AR2VideoParamImageT *vid );
AR2VideoBufferT       *ar2VideoGetImageImage       ( AR2VideoParamImageT *vid );
int                    ar2VideoCapStartImage       ( AR2VideoParamImageT *vid );
int                    ar2VideoCapStopImage        ( AR2VideoParamImageT *vid );

int                    ar2VideoGetParamiImage      ( AR2VideoParamImageT *vid, int paramName, int *value );
int                    ar2VideoSetParamiImage      ( AR2VideoParamImageT *vid, int paramName, int  value );
int                    ar2VideoGetParamdImage      ( AR2VideoParamImageT *vid, int paramName, double *value );
int                    ar2VideoSetParamdImage      ( AR2VideoParamImageT *vid, int paramName, double  value );
int                    ar2VideoGetParamsImage      ( AR2VideoParamImageT *vid, const int paramName, char **value );
int                    ar2VideoSetParamsImage      ( AR2VideoParamImageT *vid, const int paramName, const char  *value );

int ar2VideoSetBufferSizeImage(AR2VideoParamImageT *vid, const int width, const int height);
int ar2VideoGetBufferSizeImage(AR2VideoParamImageT *vid, int *width, int *height);


#ifdef  __cplusplus
}
#endif
#endif

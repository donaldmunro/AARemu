/*
 *  AR2/coord.h
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
 *  Copyright 2006-2015 ARToolworks, Inc.
 *
 *  Author(s): Hirokazu Kato, Philip Lamb
 *
 */

#ifndef AR2_COORD_H
#define AR2_COORD_H

#include <AR2/config.h>
#include <AR2/imageSet.h>
#include <AR2/featureSet.h>

#ifdef __cplusplus
extern "C" {
#endif

/*   coord.c   */
int ar2MarkerCoord2ScreenCoord( const ARParamLT *cparamLT, const float  trans[3][4], const float  mx, const float  my, float  *sx, float  *sy );
int ar2MarkerCoord2ScreenCoord2( const ARParamLT *cparamLT, const float  trans[3][4], const float  mx, const float  my, float  *sx, float  *sy );

int ar2ScreenCoord2MarkerCoord( const ARParamLT *cparamLT, const float  trans[3][4], const float  sx, const float  sy, float  *mx, float  *my );

int ar2MarkerCoord2ImageCoord( const int xsize, const int ysize, const float dpi, const float  mx, const float  my, float  *ix, float  *iy );

int ar2ImageCoord2MarkerCoord2( const int xsize, const int ysize, const float dpi, const float  ix, const float  iy, float  *mx, float  *my );

#if AR2_CAPABLE_ADAPTIVE_TEMPLATE
int ar2GetImageValue( const ARParamLT *cparamLT, const float  trans[3][4], const AR2ImageT *image,
                      const float sx, const float sy, const int blurLevel, ARUint8 *pBW );
int ar2GetImageValue2( const ARParamLT *cparamLT, const float  trans[3][4], const AR2ImageT *image,
                       const float sx, const float sy, const int blurLevel, ARUint8 *pBW1, ARUint8 *pBW2, ARUint8 *pBW3 );
#else
int ar2GetImageValue( const ARParamLT *cparamLT, const float trans[3][4], const AR2ImageT *image,
                      const float sx, const float sy, ARUint8 *pBW );
#endif

#ifdef __cplusplus
}
#endif
#endif

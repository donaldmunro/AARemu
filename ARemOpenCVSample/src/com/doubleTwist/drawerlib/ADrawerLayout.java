/*
* Copyright (C) 2014 doubleTwist Corporation.
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

package com.doubleTwist.drawerlib;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.view.MotionEventCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.*;
import android.widget.LinearLayout;
import to.augmented.reality.android.em.opencv.sample.*;

import java.util.ArrayList;
import java.util.HashMap;

public class ADrawerLayout extends ViewGroup {
    private static final String TAG = "ADrawerLayout";
    private final static boolean DEBUG = false;
    private final static boolean DEBUG_TOUCH = false;

    View mContent;
    View mContentDimmer;
    View mLeft;
    View mRight;
    View mBottom;
    View mTop;

    HashMap<View, Rect> mLayoutBounds = new HashMap<View, Rect>();
    HashMap<View, Rect> mLayoutBoundsWithoutPeek = new HashMap<View, Rect>();

    // If true, the layout will only intercept touches in the peek area
    //
    // NOTE: this overrides mRestrictTouchesToArea
    //
    boolean[] mRestrictTouchesToPeekArea;
    Rect mPeekSize;

    // For each drawer we can set a specific area that can be used
    // to drag the drawer
    //
    // Areas are relative to the initial drawer area and only apply
    // when the drawer is open
    //
    // NOTE: this is overriden by mRestrictTouchesToPeekArea
    //
    Rect[] mRestrictTouchesToArea = new Rect[4];

    // If true, innerMargins apply to all elements, otherwise, only the respective
    // sub-views (i.e. top or bottom only apply to the side views, left or right margins
    // only apply to the top or bottom views)
    //
    // NOTE: this is particularly useful when using the drawer in a action bar
    // 'overlay' themed activity
    //
    boolean[] mGlobalInnerMargins;
    Rect mInnerMargins;

    private int mTouchSlop;
    private VelocityTracker mVelocityTracker;

    public ADrawerLayout(Context ctx) {
        this(ctx, null, 0);
    }

    public ADrawerLayout(Context ctx, AttributeSet attrs) {
        this(ctx, attrs, 0);
    }

    public ADrawerLayout(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);

        ViewConfiguration vc = ViewConfiguration.get(ctx);
        mTouchSlop = vc.getScaledTouchSlop();
        mVelocityTracker = VelocityTracker.obtain();

        // Read the attributes passed in the XML view declaration
        TypedArray a = ctx.obtainStyledAttributes(attrs, R.styleable.ADrawerLayout);
        try {
            Rect innerMargins = new Rect();
            innerMargins.left =  a.getDimensionPixelSize(R.styleable
                    .ADrawerLayout_innerMarginLeft, 0);
            innerMargins.top =  a.getDimensionPixelSize(R.styleable.ADrawerLayout_innerMarginTop,
                    0);
            innerMargins.right =  a.getDimensionPixelSize(R.styleable
                    .ADrawerLayout_innerMarginRight, 0);
            innerMargins.bottom =  a.getDimensionPixelSize(R.styleable
                    .ADrawerLayout_innerMarginBottom, 0);
            setInnerMargins(innerMargins);

            boolean innerIsGlobalLeft = a.getBoolean(R.styleable
                    .ADrawerLayout_innerMarginIsGlobalLeft, false);
            boolean innerIsGlobalTop = a.getBoolean(R.styleable
                    .ADrawerLayout_innerMarginIsGlobalTop, false);
            boolean innerIsGlobalRight = a.getBoolean(R.styleable
                    .ADrawerLayout_innerMarginIsGlobalRight, false);
            boolean innerIsGlobalBottom = a.getBoolean(R.styleable
                    .ADrawerLayout_innerMarginIsGlobalBottom, false);
            setInnerIsGlobal(innerIsGlobalLeft, innerIsGlobalTop, innerIsGlobalRight, innerIsGlobalBottom);

            Rect peekSize = new Rect();
            peekSize.left =  a.getDimensionPixelSize(R.styleable.ADrawerLayout_peekSizeLeft, 0);
            peekSize.top =  a.getDimensionPixelSize(R.styleable.ADrawerLayout_peekSizeTop, 0);
            peekSize.right = a.getDimensionPixelSize(R.styleable.ADrawerLayout_peekSizeRight, 0);
            peekSize.bottom = a.getDimensionPixelSize(R.styleable.ADrawerLayout_peekSizeBottom, 0);
            setPeekSize(peekSize);

            boolean restrictToPeekLeft = a.getBoolean(R.styleable
                    .ADrawerLayout_restrictToPeekLeft, true);
            boolean restrictToPeekTop = a.getBoolean(R.styleable.ADrawerLayout_restrictToPeekTop,
                    true);
            boolean restrictToPeekRight = a.getBoolean(R.styleable
                    .ADrawerLayout_restrictToPeekRight, true);
            boolean restrictToPeekBottom = a.getBoolean(R.styleable
                    .ADrawerLayout_restrictToPeekBottom, true);
            setRestrictTouchesToPeekArea(restrictToPeekLeft, restrictToPeekTop,
                    restrictToPeekRight, restrictToPeekBottom);
        } finally {
            a.recycle();
        }
    }

    public interface DrawerLayoutListener {
        public void onBeginScroll(ADrawerLayout dl, DrawerState state);
        public void onOffsetChanged(ADrawerLayout dl, DrawerState state, float offsetXNorm,
                                    float offsetYNorm, int offsetX, int offsetY);
        public void onPreClose(ADrawerLayout dl, DrawerState state);
        public void onPreOpen(ADrawerLayout dl, DrawerState state);
        public void onClose(ADrawerLayout dl, DrawerState state, int closedDrawerId);
        public void onOpen(ADrawerLayout dl, DrawerState state);
    }

    private DrawerLayoutListener mListener;

    public void setListener(DrawerLayoutListener listener) {
        mListener = listener;
    }

    private boolean isRuntimePostGingerbread() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.GINGERBREAD_MR1;
    }

    public void setInnerIsGlobal(boolean flag) {
        setInnerIsGlobal(flag, flag, flag, flag);
    }

    public void setInnerIsGlobal(boolean l, boolean t, boolean r, boolean b) {
        if( mGlobalInnerMargins == null )
            mGlobalInnerMargins = new boolean[4];

        mGlobalInnerMargins[0] = l;
        mGlobalInnerMargins[1] = t;
        mGlobalInnerMargins[2] = r;
        mGlobalInnerMargins[3] = b;
    }

    public void setRestrictTouchesToPeekArea(boolean flag) {
        setRestrictTouchesToPeekArea(flag, flag, flag, flag);
    }

    public void setRestrictTouchesToPeekArea(boolean l, boolean t, boolean r, boolean b) {
        if( mRestrictTouchesToPeekArea == null )
            mRestrictTouchesToPeekArea = new boolean[4];

        mRestrictTouchesToPeekArea[0] = l;
        mRestrictTouchesToPeekArea[1] = t;
        mRestrictTouchesToPeekArea[2] = r;
        mRestrictTouchesToPeekArea[3] = b;
    }

    public void setRestrictTouchesToArea(int drawerId, int dimension) {
        if (dimension < 0)
            throw new IllegalArgumentException("Touch area needs a positive value");

        Rect r = new Rect();
        switch(drawerId) {
            case LEFT_DRAWER:
                r.left = 0; r.right = dimension;
                r.top = Integer.MIN_VALUE; r.bottom = Integer.MAX_VALUE;
                if (dimension >= 0) mRestrictTouchesToArea[0] = r;
                else mRestrictTouchesToArea[0] = null;
                return;
            case TOP_DRAWER:
                r.left = Integer.MIN_VALUE; r.right = Integer.MAX_VALUE;
                r.top = 0; r.bottom = dimension;
                if (dimension >= 0) mRestrictTouchesToArea[1] = r;
                else mRestrictTouchesToArea[1] = null;
                return;
            case RIGHT_DRAWER:
                r.left = -dimension; r.right = 0;
                r.top = Integer.MIN_VALUE; r.bottom = Integer.MAX_VALUE;
                if (dimension >= 0) mRestrictTouchesToArea[2] = r;
                else mRestrictTouchesToArea[2] = null;
                return;
            case BOTTOM_DRAWER:
                r.left = Integer.MIN_VALUE; r.right = Integer.MAX_VALUE;
                r.top = -dimension; r.bottom = 0;
                if (dimension >= 0) mRestrictTouchesToArea[3] = r;
                else mRestrictTouchesToArea[3] = null;
                return;
        }
    }

    public void setRestrictTouchesToArea(Rect[] areas) {
        if (areas.length != 4)
            throw new IllegalArgumentException("setRestrictTouchesToArea(Rect[]) requires an " +
                    "array with 4 elements.");

        mRestrictTouchesToArea = areas;
    }

    public void setInnerMargins(Rect r) {
        mInnerMargins = r;
        requestLayout();
    }

    public void setInnerMargins(int l, int t, int r, int b) {
        mInnerMargins.left = l;
        mInnerMargins.top = t;
        mInnerMargins.right = r;
        mInnerMargins.bottom = b;
        requestLayout();
    }

    public void setPeekSize(Rect r) {
        mPeekSize = r;
        requestLayout();
    }

    public void setPeekSize(int l, int t, int r, int b) {
        mPeekSize.left = l;
        mPeekSize.top = t;
        mPeekSize.right = r;
        mPeekSize.bottom = b;
        requestLayout();
    }

    @Override
    public void setPadding (int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        requestLayout();
    }

    protected boolean mHasMeasured = false;

    protected boolean hasMeasured() {
        return mHasMeasured;
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if( DEBUG ) Log.d(TAG, "onMeasure: "+getMeasuredWidth()+"x"+getMeasuredHeight());

        int w = getMeasuredWidth();
        int h = getMeasuredHeight();
        int contentWSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.getMode(widthMeasureSpec));
        int contentHSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.getMode(heightMeasureSpec));
        if( mContent != null ) measureChild(mContent, contentWSpec, contentHSpec);
        if( mContentDimmer != null ) measureChild(mContentDimmer, contentWSpec, contentHSpec);

        w = w - mInnerMargins.left - mInnerMargins.right;
        h = h - mInnerMargins.top - mInnerMargins.bottom;
        int drawerWInnerMarginSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.getMode
                (widthMeasureSpec));
        int drawerHInnerMarginSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.getMode
                (heightMeasureSpec));
        if( mLeft != null )
            measureChild(mLeft, !mGlobalInnerMargins[0] ? contentWSpec : drawerWInnerMarginSpec,
                    drawerHInnerMarginSpec);
        if( mRight != null )
            measureChild(mRight, !mGlobalInnerMargins[2] ? contentWSpec : drawerWInnerMarginSpec,
                    drawerHInnerMarginSpec);
        if( mTop != null )
            measureChild(mTop, drawerWInnerMarginSpec, !mGlobalInnerMargins[1] ? contentHSpec :
                    drawerHInnerMarginSpec);
        if( mBottom != null )
            measureChild(mBottom, drawerWInnerMarginSpec, !mGlobalInnerMargins[3] ? contentHSpec
                    : drawerHInnerMarginSpec);

        mHasMeasured = true;
    }

    @Override
    protected void measureChild (View v, int widthSpec, int heightSpec) {
        int wMode, hMode, wSpec, hSpec;
        int maxW = MeasureSpec.getSize(widthSpec);
        int maxH = MeasureSpec.getSize(heightSpec);
        LayoutParams params = (LayoutParams) v.getLayoutParams();

        if (DEBUG) {
            Log.d(TAG, " == VIEW == " + v.toString());
            Log.d(TAG, "params.width: " + params.width);
            Log.d(TAG, "params.height: " + params.height);
            Log.d(TAG, "maxW: " + maxW);
            Log.d(TAG, "maxH: " + maxH);
        }

        if (params.width == LayoutParams.WRAP_CONTENT) {
            wMode = MeasureSpec.AT_MOST;
            wSpec = MeasureSpec.makeMeasureSpec(maxW, wMode);
        } else if (params.width == LayoutParams.MATCH_PARENT) {
            wMode = MeasureSpec.EXACTLY;
            wSpec = MeasureSpec.makeMeasureSpec(maxW, wMode);
        } else {
            wMode = MeasureSpec.EXACTLY;
            wSpec = MeasureSpec.makeMeasureSpec(Math.min(maxW, params.width), wMode);
        }
        if (params.height == LayoutParams.WRAP_CONTENT) {
            hMode = MeasureSpec.AT_MOST;
            hSpec = MeasureSpec.makeMeasureSpec(maxH, hMode);
        } else if(params.height == LayoutParams.MATCH_PARENT) {
            hMode = MeasureSpec.EXACTLY;
            hSpec = MeasureSpec.makeMeasureSpec(maxH, hMode);
        } else {
            hMode = MeasureSpec.EXACTLY;
            hSpec = MeasureSpec.makeMeasureSpec(Math.min(maxH, params.height), hMode);
        }

        v.measure(wSpec, hSpec);

        if (DEBUG) {
            Log.d(TAG, " == VIEW == " + v.toString());
            Log.d(TAG, "mwidth: " + v.getMeasuredWidth());
            Log.d(TAG, "mheight: " + v.getMeasuredHeight());
        }
    }

    protected void onLayout (boolean changed, int l, int t, int r, int b) {
        if (DEBUG) Log.d(TAG, "onLayout: "+l+","+t+","+r+","+b);

        if (mPendingSavedState != null) {
            mPendingSavedState.run();
            mPendingSavedState = null;
        }

        int left, top, right, bottom;

        left = getPaddingLeft();
        right = r - l - getPaddingRight();
        top = getPaddingTop();
        bottom = b - t - getPaddingBottom();

        if( mContent != null ) layoutView(mContent, left, top, right, bottom);
        if( mContentDimmer != null ) layoutView(mContentDimmer, left, top, right, bottom);

        for (int i=0; i<getChildCount(); i++) {
            View child = getChildAt(i);
            boolean hasGlobalInnerMargins = false;
            int leftChild, topChild, rightChild, bottomChild;
            if (child == mLeft || child == mRight) {
                hasGlobalInnerMargins = child == mLeft ? mGlobalInnerMargins[0] :
                        mGlobalInnerMargins[2];
                leftChild = left + (hasGlobalInnerMargins ? mInnerMargins.left : 0);
                rightChild = right - (hasGlobalInnerMargins ? mInnerMargins.right : 0);
                topChild = top + mInnerMargins.top;
                bottomChild = bottom - mInnerMargins.bottom;
            } else if (child == mTop || child == mBottom) {
                hasGlobalInnerMargins = child == mTop ? mGlobalInnerMargins[1] :
                        mGlobalInnerMargins[3];
                leftChild = left + mInnerMargins.left;
                rightChild = right - mInnerMargins.right;
                topChild = top + (hasGlobalInnerMargins ? mInnerMargins.top : 0);
                bottomChild = bottom - (hasGlobalInnerMargins ? mInnerMargins.bottom : 0);
            } else {
                continue;
            }

            layoutView(child, leftChild, topChild, rightChild, bottomChild);
        }

        adjustScrollStates(mCurrentScrollX, mCurrentScrollY);

        for (Runnable run : mMeasurePendingRunnables) {
            run.run();
        }
        mMeasurePendingRunnables.clear();
    }

    protected void layoutView (View v, int l, int t, int r, int b) {
        LayoutParams params = (LayoutParams) v.getLayoutParams();

        Rect bounds = new Rect();
        Rect boundsWithoutPeek = new Rect();
        int gravity = params.gravity;
        switch (gravity) {
            case Gravity.RIGHT:
                if (DEBUG) Log.d(TAG, "gravity: right");
                bounds.left = r - v.getMeasuredWidth() - mPeekSize.right;
                bounds.top = t;
                bounds.right = r - mPeekSize.right;
                bounds.bottom = t + v.getMeasuredHeight();
                v.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
                boundsWithoutPeek = new Rect(bounds);
                boundsWithoutPeek.offset(mPeekSize.right, 0);
                mMinScrollX = -bounds.width();
                break;
            case Gravity.TOP:
                if (DEBUG) Log.d(TAG, "gravity: top");
                bounds.left = l;
                bounds.top = t + mPeekSize.top;
                bounds.right = v.getMeasuredWidth();
                bounds.bottom = t + v.getMeasuredHeight() + mPeekSize.top;
                v.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
                boundsWithoutPeek = new Rect(bounds);
                boundsWithoutPeek.offset(0, -mPeekSize.top);
                mMaxScrollY = bounds.height();
                break;
            case Gravity.BOTTOM:
                if (DEBUG) Log.d(TAG, "gravity: bottom");
                bounds.left = l;
                bounds.top = b - v.getMeasuredHeight() - mPeekSize.bottom;
                bounds.right = l + v.getMeasuredWidth();
                bounds.bottom = b - mPeekSize.bottom;
                v.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
                boundsWithoutPeek = new Rect(bounds);
                boundsWithoutPeek.offset(0, mPeekSize.bottom);
                mMinScrollY = -bounds.height();
                break;
            case Gravity.LEFT:
                if (DEBUG) Log.d(TAG, "gravity: left");
                bounds.left = l + mPeekSize.left;
                bounds.top = t;
                bounds.right = l + v.getMeasuredWidth() + mPeekSize.left;
                bounds.bottom = t + v.getMeasuredHeight();
                v.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
                mMaxScrollX = bounds.width();
                boundsWithoutPeek = new Rect(bounds);
                boundsWithoutPeek.offset(-mPeekSize.left, 0);
                break;
            default:
                if (DEBUG) Log.d(TAG, "gravity: default");
                bounds.left = l;
                bounds.top = t;
                bounds.right = l + v.getMeasuredWidth();
                bounds.bottom = t + v.getMeasuredHeight();
                v.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
                boundsWithoutPeek = new Rect(bounds);
                break;
        }

        if (DEBUG) {
            Log.d(TAG, " == VIEW LAYOUT == " + v.toString());
            Log.d(TAG, "bounds: " + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
        }

        if (mLayoutBounds.containsKey(v)) mLayoutBounds.remove(v);
        mLayoutBounds.put(v, bounds);
        if (mLayoutBoundsWithoutPeek.containsKey(v)) mLayoutBoundsWithoutPeek.remove(v);
        mLayoutBoundsWithoutPeek.put(v, boundsWithoutPeek);
    }

    public void setParalaxFactorX(float factor) {
        mContentParalaxFactorX = factor;
    }

    public void setParalaxFactorY(float factor) {
        mContentParalaxFactorY = factor;
    }

    public float getParalaxFactorX() { return mContentParalaxFactorX; }

    public float getParalaxFactorY() { return mContentParalaxFactorY; }

    // 1f means that the content moves 1 px for every px the drawer moves horizontally
    float mContentParalaxFactorX = 0.0f;
    // 1f means that the content moves 1 px for every px the drawer moves vertically
    float mContentParalaxFactorY = 0.0f;

    int dx, dy;
    int dxx, dyy;
    float animScale = 1.f;
    float animAlpha = 1.f;
    float animRotX = 0.f;
    float animRotY = 0.f;

    boolean[] mDimContent = new boolean[] {true, true, true, true};
    boolean[] mAnimateScrolling = new boolean[] {true, true, true, true};

    public void setDimContent (boolean flag) {
        setAnimateScrolling(flag, flag, flag, flag);
    }

    public void setDimContent (boolean left, boolean top, boolean right, boolean bottom) {
        setDimContent(LEFT_DRAWER, left);
        setDimContent(TOP_DRAWER, top);
        setDimContent(RIGHT_DRAWER, right);
        setDimContent(BOTTOM_DRAWER, bottom);
    }

    public void setDimContent (int drawer, boolean flag) {
        if (drawer == LEFT_DRAWER)
            mDimContent[0] = flag;
        else if (drawer == TOP_DRAWER)
            mDimContent[1] = flag;
        else if (drawer == RIGHT_DRAWER)
            mDimContent[2] = flag;
        else if (drawer == BOTTOM_DRAWER)
            mDimContent[3] = flag;
    }

    public void setAnimateScrolling (boolean flag) {
        setAnimateScrolling(flag, flag, flag, flag);
    }

    public void setAnimateScrolling (boolean left, boolean top, boolean right, boolean bottom) {
        setAnimateScrolling(LEFT_DRAWER, left);
        setAnimateScrolling(TOP_DRAWER, top);
        setAnimateScrolling(RIGHT_DRAWER, right);
        setAnimateScrolling(BOTTOM_DRAWER, bottom);
    }

    public void setAnimateScrolling (int drawer, boolean flag) {
        if (drawer == LEFT_DRAWER)
            mAnimateScrolling[0] = flag;
        else if (drawer == TOP_DRAWER)
            mAnimateScrolling[1] = flag;
        else if (drawer == RIGHT_DRAWER)
            mAnimateScrolling[2] = flag;
        else if (drawer == BOTTOM_DRAWER)
            mAnimateScrolling[3] = flag;
    }

    boolean dimLeft, dimTop, dimRight, dimBottom;
    boolean animateLeft, animateTop, animateRight, animateBottom;

    protected boolean isClosed () {
        return mDrawerState.mScrollState == IDLE && mDrawerState.mActiveDrawer == NO_DRAWER;
    }

    public static class AnimationParameters {
        public AnimationParameters(float scale, float alpha, float rotX, float rotY) {
            mScale = scale;
            mAlpha = alpha;
            mRotX = rotX;
            mRotY = rotY;
        }

        public float mScale = 0.f;
        public float mAlpha = 0.f;
        public float mRotX = 0.f;
        public float mRotY = 0.f;
    }

    AnimationParameters[] mAnimationParams = new AnimationParameters[] {
            new AnimationParameters(0.f, 0.f, 0.f, 0.f),
            new AnimationParameters(0.f, 0.f, 0.f, 0.f),
            new AnimationParameters(0.f, 0.f, 0.f, 0.f),
            new AnimationParameters(0.f, 0.f, 0.f, 0.f)
    };

    public AnimationParameters getAnimationParameters (int drawerId) {
        if (drawerId == ADrawerLayout.LEFT_DRAWER)
            return mAnimationParams[0];
        else if (drawerId == ADrawerLayout.TOP_DRAWER)
            return mAnimationParams[1];
        else if (drawerId == ADrawerLayout.RIGHT_DRAWER)
            return mAnimationParams[2];
        else if (drawerId == ADrawerLayout.BOTTOM_DRAWER)
            return mAnimationParams[3];
        return null;
    }

    public void setAnimationParameters (AnimationParameters[] params) {
        if (params.length != 4)
            throw new IllegalArgumentException();

        mAnimationParams = params;
    }

    public void setAnimationParameters (int drawerId, AnimationParameters params) {
        if (drawerId == ADrawerLayout.LEFT_DRAWER)
            mAnimationParams[0] = params;
        else if (drawerId == ADrawerLayout.TOP_DRAWER)
            mAnimationParams[1] = params;
        else if (drawerId == ADrawerLayout.RIGHT_DRAWER)
            mAnimationParams[2] = params;
        else if (drawerId == ADrawerLayout.BOTTOM_DRAWER)
            mAnimationParams[3] = params;
    }

    AnimationParameters aparams;
    AnimationParameters mNoAnimationParameters = new AnimationParameters(0f, 0f, 0f, 0f);

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void adjustScrollStates (float x, float y) {
        dx = Math.round(x);
        dy = Math.round(y);

        if( mContent != null ) {
            dxx = Math.round(dx * mContentParalaxFactorX);
            dyy = Math.round(dy * mContentParalaxFactorY);
            setDXYCompat(mContent, dxx, dyy);

            animateLeft = mAnimateScrolling[0] && mDrawerState.mActiveDrawer == LEFT_DRAWER;
            animateTop = mAnimateScrolling[1] && mDrawerState.mActiveDrawer == TOP_DRAWER;
            animateRight = mAnimateScrolling[2] && mDrawerState.mActiveDrawer == RIGHT_DRAWER;
            animateBottom = mAnimateScrolling[3] && mDrawerState.mActiveDrawer == BOTTOM_DRAWER;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
                if (animateLeft)
                    aparams = mAnimationParams[0];
                else if (animateTop)
                    aparams = mAnimationParams[1];
                else if (animateRight)
                    aparams = mAnimationParams[2];
                else if (animateBottom)
                    aparams = mAnimationParams[3];
                else
                    aparams = mNoAnimationParameters;

                animScale = 1 - aparams.mScale * getScrollFraction();
                animAlpha = 1 - aparams.mAlpha * getScrollFraction();
                animRotX = -90.f * aparams.mRotX * getScrollFraction();
                animRotY = -90.f * aparams.mRotY * getScrollFraction();
                if (mContent.getScaleX() != animScale) mContent.setScaleX(animScale);
                if (mContent.getScaleY() != animScale) mContent.setScaleY(animScale);
                if (mContent.getAlpha() != animAlpha) mContent.setAlpha(animAlpha);
                if (mContent.getRotationY() != animRotY) mContent.setRotationY(animRotY);
                if (mContent.getRotationX() != animRotX) mContent.setRotationX(animRotX);
            }
        }

        if (mContentDimmer != null) {
            dimLeft = mDimContent[0] && mDrawerState.mActiveDrawer == LEFT_DRAWER;
            dimTop = mDimContent[1] && mDrawerState.mActiveDrawer == TOP_DRAWER;
            dimRight = mDimContent[2] && mDrawerState.mActiveDrawer == RIGHT_DRAWER;
            dimBottom = mDimContent[3] && mDrawerState.mActiveDrawer == BOTTOM_DRAWER;

            // dxx = Math.round(dx);
            // if( mDrawerState.mActiveDrawer == LEFT_DRAWER ) dxx += mPeekSize.left;
            // if( mDrawerState.mActiveDrawer == RIGHT_DRAWER ) dxx -= mPeekSize.right;
            // dyy = Math.round(dy);
            // if( mDrawerState.mActiveDrawer == TOP_DRAWER ) dyy += mPeekSize.top;
            // if( mDrawerState.mActiveDrawer == BOTTOM_DRAWER ) dyy -= mPeekSize.bottom;

            if (isClosed() || dimLeft || dimTop || dimRight || dimBottom) {
                // dxx = Math.round(dx * mContentParalaxFactorX);
                // dyy = Math.round(dy * mContentParalaxFactorY);
                dxx = dx;
                dyy = dy;
                if (mPeekSize != null) {
                    if (dimLeft) dxx += mPeekSize.left;
                    if (dimRight) dxx -= mPeekSize.right;
                    if (dimTop) dyy += mPeekSize.top;
                    if (dimBottom) dyy -= mPeekSize.bottom;
                }
                setDXYCompat(mContentDimmer, dxx, dyy);

                alpha = getScrollFraction();
                alpha = Math.min(1.f, Math.max(0.f, alpha));
                mContentDimmer.setBackgroundColor(Color.argb(Math.round(alpha * Color.alpha(mDimmingColor)),
                        Color.red(mDimmingColor), Color.green(mDimmingColor), Color.blue(mDimmingColor)));
            }
        }

        if (mLeft != null) {
            dxx = -mLeft.getMeasuredWidth() + Math.max(0, dx);
            dyy = dy;
            setDXYCompat(mLeft, dxx, dyy);
        }

        if (mRight != null) {
            dxx = mRight.getMeasuredWidth() + Math.min(0, dx);
            dyy = dy;
            setDXYCompat(mRight, dxx, dyy);
        }

        if (mTop != null) {
            dxx = dx;
            dyy = -mTop.getMeasuredHeight() + Math.max(0, dy);
            setDXYCompat(mTop, dxx, dyy);
        }

        if (mBottom != null) {
            dxx = dx;
            dyy = mBottom.getMeasuredHeight() + Math.min(0, dy);
            setDXYCompat(mBottom, dxx, dyy);
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void setDXYCompat (View v, int dx, int dy) {
        if (isRuntimePostGingerbread()) {
            v.setTranslationX(dx);
            v.setTranslationY(dy);
        } else {
            Rect bounds = mLayoutBounds.get(v);
            v.layout(bounds.left + dx,
                    bounds.top + dy,
                    bounds.right + dx,
                    bounds.bottom + dy);

            // This method does not work so well
            // v.setAnimation(null);
            // TranslateAnimation ta = new TranslateAnimation(dx, dx, dy, dy);
            // ta.setFillAfter(true);
            // ta.setFillEnabled(true);
            // mLeft.startAnimation(ta);
        }
    }

    int mDimmingColor = Color.argb(0xaa, 0x00, 0x00, 0x00);
    float alpha;

    public void setDimmingColor(int color) {
        mDimmingColor = color;
    }

    public int getDimmingColor() {
        return mDimmingColor;
    }

    @Override
    public void dispatchDraw (Canvas c) {
        super.dispatchDraw(c);
    }

    @Override
    protected void onFinishInflate () {
        super.onFinishInflate();

        if (DEBUG) Log.d(TAG, "onFinishInflate");

        readViews();
        addContentDimmer();
    }

    private int getMainContentIdx () {
        View v;
        for(int i=0; i<getChildCount(); i++) {
            v = getChildAt(i);
            if( v == mContent ) return i;
        }
        return -1;
    }

    OnTouchListener mDimmerTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            return !(mDrawerState.mScrollState == IDLE
                    && mDrawerState.mActiveDrawer == NO_DRAWER);
        }
    };

    private boolean mDimmerInterceptsTouches = true;

    private static int DIMMER_VIEW_ID = 0x78787878;

    private View addContentDimmer () {
        mContentDimmer = this.findViewById(DIMMER_VIEW_ID);
        if (mContentDimmer == null) {
            mContentDimmer = new View(getContext());
            mContentDimmer.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            mContentDimmer.setBackgroundColor(mDimmingColor);
            // int mainContentIdx = getMainContentIdx();
            // if( mainContentIdx >= 0 ) addView(mContentDimmer, mainContentIdx+1);
            // else addView(mContentDimmer);
            addView(mContentDimmer);
        }
        if (mDimmerInterceptsTouches)
            mContentDimmer.setOnTouchListener(mDimmerTouchListener);
        return mContentDimmer;
    }

    private void readViews () {
        mContent = getChildAt(0);
        for (int i=1, count=getChildCount(); i<count; i++) {
            View v = getChildAt(i);
            if( v.getId() == DIMMER_VIEW_ID)
                continue;
            LayoutParams params = (LayoutParams) v.getLayoutParams();
            switch (params.gravity) {
                case Gravity.LEFT: // left
                    mLeft = v;
                    mDrawerState.mLeftEnabled = true;
                    break;
                case Gravity.RIGHT: // right
                    mRight = v;
                    mDrawerState.mRightEnabled = true;
                    break;
                case Gravity.TOP: // top
                    mTop = v;
                    mDrawerState.mTopEnabled = true;
                    break;
                case Gravity.BOTTOM: // bottom
                    mBottom = v;
                    mDrawerState.mBottomEnabled = true;
                    break;
                default:
                    mLeft = v;
                    break;
            }
        }
    }

    public void setScrollingEnabled (boolean left, boolean top, boolean right, boolean bottom) {
        setScrollingEnabled(LEFT_DRAWER, left);
        setScrollingEnabled(TOP_DRAWER, top);
        setScrollingEnabled(RIGHT_DRAWER, right);
        setScrollingEnabled(BOTTOM_DRAWER, bottom);
    }

    public boolean setScrollingEnabled (int section, boolean flag) {
        switch (section) {
            case LEFT_DRAWER:
                if( flag && mLeft == null ) return false;
                mDrawerState.mLeftEnabled = flag;
                return true;
            case RIGHT_DRAWER:
                if( flag && mRight == null ) return false;
                mDrawerState.mRightEnabled = flag;
                return true;
            case TOP_DRAWER:
                if( flag && mTop == null ) return false;
                mDrawerState.mTopEnabled = flag;
                return true;
            case BOTTOM_DRAWER:
                if( flag && mBottom == null ) return false;
                mDrawerState.mBottomEnabled = flag;
                return true;
        }
        return false;
    }

    private boolean isScrollingForSectionEnabled (int section) {
        switch (section) {
            case LEFT_DRAWER:
                return mDrawerState.mLeftEnabled;
            case RIGHT_DRAWER:
                return mDrawerState.mRightEnabled;
            case TOP_DRAWER:
                return mDrawerState.mTopEnabled;
            case BOTTOM_DRAWER:
                return mDrawerState.mBottomEnabled;
        }
        return false;
    }

    private static class HistoryEvent {
        public PointF mCoords = new PointF(0,0);
        public int mPointerId = 0;
    }

    private HistoryEvent mDownEvent = new HistoryEvent();
    private HistoryEvent mLastMoveEvent = new HistoryEvent();
    private boolean mIsScrolling = false;
    private float mCurrentScrollX = 0;
    private float mCurrentScrollY = 0;
    private float mMaxScrollX = 0;
    private float mMinScrollX = 0;
    private float mMaxScrollY = 0;
    private float mMinScrollY = 0;

    public final static int IDLE = 0;
    public final static int SCROLLING = 1;
    public final static int SNAP_OPEN = 2;
    public final static int SNAP_CLOSE = 3;
    public final static int EXPLICIT_OPEN = 4;
    public final static int EXPLICIT_CLOSE = 5;

    public final static int NO_DRAWER = 0;
    public final static int LEFT_DRAWER = 1;
    public final static int RIGHT_DRAWER = 2;
    public final static int TOP_DRAWER = 3;
    public final static int BOTTOM_DRAWER = 4;

    public static class DrawerState {
        public int mScrollState = IDLE;
        public int mActiveDrawer = NO_DRAWER;

        boolean mLeftEnabled = false;
        boolean mRightEnabled = false;
        boolean mTopEnabled = false;
        boolean mBottomEnabled = false;

        boolean mDraggingEnabled = true;
    }

    private DrawerState mDrawerState = new DrawerState();

    public void setDraggingEnabled (boolean flag) {
        mDrawerState.mDraggingEnabled = flag;
    }

    private float getScrollFractionX () {
        boolean horizScrolling = mDrawerState.mActiveDrawer == LEFT_DRAWER || mDrawerState
                .mActiveDrawer == RIGHT_DRAWER;
        return horizScrolling ? getScrollFraction() : 0f;
    }

    private float getScrollFractionY () {
        boolean vertScrolling = mDrawerState.mActiveDrawer == TOP_DRAWER || mDrawerState
                .mActiveDrawer == BOTTOM_DRAWER;
        return vertScrolling ? getScrollFraction() : 0f;
    }

    private float getScrollFraction() {
        if (mDrawerState.mActiveDrawer == LEFT_DRAWER) {
            return mLeft == null ? 0f :
                    mCurrentScrollX/(float)(mLeft.getMeasuredWidth()-mPeekSize.left);
        } else if (mDrawerState.mActiveDrawer == RIGHT_DRAWER) {
            return mRight == null ? 0f :
                    -mCurrentScrollX/(float)(mRight.getMeasuredWidth()-mPeekSize.right);
        } else if (mDrawerState.mActiveDrawer == TOP_DRAWER) {
            return mTop == null ? 0f :
                    mCurrentScrollY/(float)(mTop.getMeasuredHeight()-mPeekSize.top);
        } else if (mDrawerState.mActiveDrawer == BOTTOM_DRAWER) {
            return mBottom == null ? 0f :
                    -mCurrentScrollY/(float)(mBottom.getMeasuredHeight()-mPeekSize.bottom);
        } else {
            return 0.f;
        }
    }

    private float scrollFractionToSizeOffset(int drawer, float fraction) {
        if (drawer == LEFT_DRAWER)
            return mLeft != null ? fraction * (mLeft.getMeasuredWidth()-mPeekSize.left) : 0.f;
        else if (drawer == RIGHT_DRAWER)
            return mRight != null ? -fraction * (mRight.getMeasuredWidth()-mPeekSize.right) : 0.f;
        else if (drawer == TOP_DRAWER)
            return mTop != null ? fraction * (mTop.getMeasuredHeight()-mPeekSize.top) : 0.f;
        else if (drawer == BOTTOM_DRAWER)
            return mBottom != null ? -fraction * (mBottom.getMeasuredHeight()-mPeekSize.bottom) : 0.f;
        else
            return 0.f;
    }

    public void open (int drawer) {
        open(drawer, true);
    }

    ArrayList<Runnable> mMeasurePendingRunnables = new ArrayList<Runnable>();

    public void open (final int drawer, final boolean animate) {
        // Defer action to a runnable if the views haven't been measured yet
        if (!hasMeasured()) {
            mMeasurePendingRunnables.add(new Runnable() {
                @Override
                public void run() {
                    open(drawer, animate);
                }
            });
            return;
        };
        if (drawer == LEFT_DRAWER && mLeft != null) {
            mDrawerState.mActiveDrawer = LEFT_DRAWER;
            mTargetOffsetX = mLeft.getMeasuredWidth()-mPeekSize.left;
            mTargetOffsetY = 0;
        } else if (drawer == RIGHT_DRAWER && mRight != null) {
            mDrawerState.mActiveDrawer = RIGHT_DRAWER;
            mDrawerState.mScrollState = EXPLICIT_OPEN;
            mTargetOffsetX = -mRight.getMeasuredWidth()+mPeekSize.right;
            mTargetOffsetY = 0;
        } else if (drawer == TOP_DRAWER && mTop != null) {
            mDrawerState.mActiveDrawer = TOP_DRAWER;
            mDrawerState.mScrollState = EXPLICIT_OPEN;
            mTargetOffsetX = 0;
            mTargetOffsetY = mTop.getMeasuredHeight()-mPeekSize.top;
        } else if (drawer == BOTTOM_DRAWER && mBottom != null) {
            mDrawerState.mActiveDrawer = BOTTOM_DRAWER;
            mDrawerState.mScrollState = EXPLICIT_OPEN;
            mTargetOffsetX = 0;
            mTargetOffsetY = -mBottom.getMeasuredHeight()+mPeekSize.bottom;
        } else {
            return;
        }

        if (animate) {
            mDrawerState.mScrollState = EXPLICIT_OPEN;
        } else {
            mDrawerState.mScrollState = IDLE;
            mCurrentScrollY = mTargetOffsetY;
            mCurrentScrollX = mTargetOffsetX;
        }

        mLastT = System.currentTimeMillis();
        this.post(mStepRunnable);
    }

    public DrawerState getState () {
        return mDrawerState;
    }

    public void open () {
        open(LEFT_DRAWER, true);
    }

    public void close (final int drawer, final boolean animate) {
        boolean valid = false;
        valid = valid || drawer == LEFT_DRAWER && mLeft != null;
        valid = valid || drawer == RIGHT_DRAWER && mRight != null;
        valid = valid || drawer == TOP_DRAWER && mTop != null;
        valid = valid || drawer == BOTTOM_DRAWER && mBottom != null;
        if (!valid)
            return;

        // Defer action to a runnable if the views haven't been measured yet
        if (!hasMeasured()) {
            mMeasurePendingRunnables.add(new Runnable() {
                @Override
                public void run() {
                    close(drawer, animate);
                }
            });
            return;
        };

        mTargetOffsetX = 0;
        mTargetOffsetY = 0;

        if (animate) {
            mDrawerState.mActiveDrawer = drawer;
            mDrawerState.mScrollState = EXPLICIT_CLOSE;
        } else {
            mDrawerState.mActiveDrawer = NO_DRAWER;
            mDrawerState.mScrollState = IDLE;
            mCurrentScrollY = mTargetOffsetY;
            mCurrentScrollX = mTargetOffsetX;
        }

        mLastT = System.currentTimeMillis();
        this.post(mStepRunnable);
    }

    public void close (int drawer) {
        close(drawer, true);
    }

    public void close () {
        close(mDrawerState.mActiveDrawer, true);
    }

    private void snap () {
        mVelocityTracker.computeCurrentVelocity(1, 10);
        if (mDrawerState.mActiveDrawer == LEFT_DRAWER) {
            if (mVelocityTracker.getXVelocity() > 0) {
                mDrawerState.mScrollState = SNAP_OPEN;
                mTargetOffsetX = mLeft.getMeasuredWidth()-mPeekSize.left;
                if (mListener != null) mListener.onPreOpen(this, mDrawerState);
            } else {
                mDrawerState.mScrollState = SNAP_CLOSE;
                mTargetOffsetX = 0;
                if (mListener != null) mListener.onPreClose(this, mDrawerState);
            }
            mTargetOffsetY = 0;
        } else if (mDrawerState.mActiveDrawer == RIGHT_DRAWER) {
            if (mVelocityTracker.getXVelocity() < 0) {
                mDrawerState.mScrollState = SNAP_OPEN;
                mTargetOffsetX = -mRight.getMeasuredWidth()+mPeekSize.right;
                if (mListener != null) mListener.onPreOpen(this, mDrawerState);
            } else {
                mDrawerState.mScrollState = SNAP_CLOSE;
                mTargetOffsetX = 0;
                if (mListener != null) mListener.onPreClose(this, mDrawerState);
            }
            mTargetOffsetY = 0;
        } else if (mDrawerState.mActiveDrawer == TOP_DRAWER) {
            if (mVelocityTracker.getYVelocity() > 0) {
                mDrawerState.mScrollState = SNAP_OPEN;
                mTargetOffsetY = mTop.getMeasuredHeight()-mPeekSize.top;
                if (mListener != null) mListener.onPreOpen(this, mDrawerState);
            } else {
                mDrawerState.mScrollState = SNAP_CLOSE;
                mTargetOffsetY = 0;
                if (mListener != null) mListener.onPreClose(this, mDrawerState);
            }
            mTargetOffsetX = 0;
        } else if (mDrawerState.mActiveDrawer == BOTTOM_DRAWER) {
            if (mVelocityTracker.getYVelocity() < 0) {
                mDrawerState.mScrollState = SNAP_OPEN;
                mTargetOffsetY = -mBottom.getMeasuredHeight()+mPeekSize.bottom;
                if (mListener != null) mListener.onPreOpen(this, mDrawerState);
            } else {
                mDrawerState.mScrollState = SNAP_CLOSE;
                mTargetOffsetY = 0;
                if (mListener != null) mListener.onPreClose(this, mDrawerState);
            }
            mTargetOffsetX = 0;
        }
        mLastT = System.currentTimeMillis();
        this.post(mStepRunnable);
    }

    private Runnable mStepRunnable = new Runnable() {
        @Override
        public void run() {
            stepAnimation();
            adjustScrollStates(mCurrentScrollX, mCurrentScrollY);
        }
    };

    private float targetDiffX;
    private float targetDiffY;
    private float a = 13.33f;
    private float dt;
    private float adt;
    private double mLastT;
    private float mTargetOffsetX = 0;
    private float mTargetOffsetY = 0;

    private void stepAnimation () {
        dt = (float) (System.currentTimeMillis()-mLastT);
        if (dt < 1) {
            this.post(mStepRunnable);
            return;
        }
        dt = Math.min(dt, 50); /* If we get less than 20fps force the animation to go slower */
        dt *= .001f;
        adt = Math.min(1f, a * dt);
        mLastT = System.currentTimeMillis();

        targetDiffX = (mTargetOffsetX-mCurrentScrollX);
        targetDiffY = (mTargetOffsetY-mCurrentScrollY);
        if (Math.abs(targetDiffX) < 1) mCurrentScrollX = mTargetOffsetX;
        else mCurrentScrollX += targetDiffX > 0 ? Math.max(1, targetDiffX * adt) :
                Math.min(-1, targetDiffX * adt);
        if (Math.abs(targetDiffY) < 1) mCurrentScrollY = mTargetOffsetY;
        else mCurrentScrollY += targetDiffY > 0 ? Math.max(1, targetDiffY * adt) :
                Math.min(-1, targetDiffY * adt);

        if (mTargetOffsetX != mCurrentScrollX || mTargetOffsetY != mCurrentScrollY) {
            this.post(mStepRunnable);
            notifyOffset();
        } else {
            mDrawerState.mScrollState = IDLE;
            if (mCurrentScrollX == 0f && mCurrentScrollY == 0f) {
                notifyOffset();
                int closedDrawerId = mDrawerState.mActiveDrawer;
                mDrawerState.mActiveDrawer = NO_DRAWER;
                if (mListener != null) mListener.onClose(this, mDrawerState, closedDrawerId);
            } else {
                notifyOffset();
                if (mListener != null) mListener.onOpen(this, mDrawerState);
            }
        }

    }

    public boolean isOpen (int drawer) {
        boolean expandedScrollFraction = false;
        switch (drawer) {
            case BOTTOM_DRAWER:
                expandedScrollFraction = getScrollFractionY() == 1.f;
                break;
            case TOP_DRAWER:
                expandedScrollFraction = getScrollFractionY() == 1.f;
                break;
            case LEFT_DRAWER:
                expandedScrollFraction = getScrollFractionX() == 1.f;
                break;
            case RIGHT_DRAWER:
                expandedScrollFraction = getScrollFractionX() == 1.f;
                break;
        }
        boolean open = mDrawerState.mScrollState == IDLE && mDrawerState.mActiveDrawer == drawer;
        return open && expandedScrollFraction;
    }

    public boolean isClosed (int drawer) {
        return !isOpen(drawer);
    }

    public boolean isOpening (int drawer) {
        return isOpening(drawer, .66f);
    }

    public boolean isOpening (int drawer, float threshold) {
        if (mDrawerState.mActiveDrawer != drawer)
            return false;

        return isOpening(threshold);
    }

    private boolean isOpening (float threshold) {
        return mDrawerState.mScrollState == EXPLICIT_OPEN || mDrawerState.mScrollState == SNAP_OPEN
                || (mDrawerState.mScrollState == SCROLLING && getScrollFraction() > threshold);
    }


    public boolean isClosing (int drawer) {
        return isClosing(drawer, .33f);
    }

    public boolean isClosing (int drawer, float threshold) {
        if (mDrawerState.mActiveDrawer != drawer)
            return false;

        return isClosing(threshold);
    }

    public boolean isClosing (float threshold) {
        return mDrawerState.mScrollState == EXPLICIT_CLOSE || mDrawerState.mScrollState == SNAP_CLOSE
                || (mDrawerState.mScrollState == SCROLLING && getScrollFraction() < threshold);
    }

    private void notifyOffset () {
        notifyOffset(mListener);
    }

    private void notifyOffset (DrawerLayoutListener listener) {
        if (listener != null) {
            listener.onOffsetChanged(this, mDrawerState, getScrollFractionX(), getScrollFractionY(),
                    Math.round(mCurrentScrollX), Math.round(mCurrentScrollY));
        }
    }

    boolean mStartedTracking = false;

    private View getDrawerView (int drawerId) {
        switch(drawerId) {
            case NO_DRAWER:
                return mContent;
            case LEFT_DRAWER:
                return mLeft;
            case RIGHT_DRAWER:
                return mRight;
            case TOP_DRAWER:
                return mTop;
            case BOTTOM_DRAWER:
                return mBottom;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent (MotionEvent ev) {
        // Here we actually handle the touch event (e.g. if the action is ACTION_MOVE,
        // scroll this container).
        // This method will only be called if the touch event was intercepted in
        // onInterceptTouchEvent

        if (DEBUG_TOUCH) Log.d(TAG, "onTouchEvent");

        if (!mDrawerState.mDraggingEnabled)
            return false;

        if (mVelocityTracker != null)
            mVelocityTracker.addMovement(ev);

        final int action = MotionEventCompat.getActionMasked(ev);

        if (!mIsScrolling) {
            if (DEBUG_TOUCH) Log.d(TAG, "NOT SCROLLING IS NOW SCROLLING");
            int drawer = preProcessScrollMotionEvent(ev);
            postProcessScrollMotionEvent(drawer, ev);
            if (action == MotionEvent.ACTION_UP) {
                if (mDrawerState.mScrollState == IDLE)
                    processIdleUp(ev);
            }
            return true; // needs to be true so we keep getting these values
        }

        if (DEBUG_TOUCH) Log.d(TAG, "DOING THE TOUCH STUFF");

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mIsScrolling = false;
            mStartedTracking = false;
            snap();
            mVelocityTracker.clear();
        }

        if (action == MotionEvent.ACTION_MOVE) {
            int ptrIdx = ev.findPointerIndex(mDownEvent.mPointerId);
            if (DEBUG_TOUCH) Log.d(TAG, "ptrIdx: " + ptrIdx);
            if (ptrIdx >= ev.getPointerCount() || ptrIdx < 0)
                return true;

            if (mDrawerState.mActiveDrawer == LEFT_DRAWER || mDrawerState.mActiveDrawer ==
                    RIGHT_DRAWER) {
                mCurrentScrollX += mStartedTracking ? ev.getX(ptrIdx) - mLastMoveEvent.mCoords.x :
                        ev.getX(ptrIdx) - mDownEvent.mCoords.x;
                mCurrentScrollX = Math.max(mMinScrollX + mPeekSize.right, mCurrentScrollX);
                mCurrentScrollX = Math.min(mMaxScrollX - mPeekSize.left, mCurrentScrollX);
            } else if (mDrawerState.mActiveDrawer == TOP_DRAWER || mDrawerState.mActiveDrawer ==
                    BOTTOM_DRAWER) {
                mCurrentScrollY += mStartedTracking ? ev.getY(ptrIdx) - mLastMoveEvent.mCoords.y :
                        ev.getY(ptrIdx) - mDownEvent.mCoords.y;
                mCurrentScrollY = Math.max(mMinScrollY + mPeekSize.bottom, mCurrentScrollY);
                mCurrentScrollY = Math.min(mMaxScrollY - mPeekSize.top, mCurrentScrollY);
            }

            if (!mStartedTracking)
                mStartedTracking = true;
            mLastMoveEvent.mCoords.x = ev.getX(ptrIdx);
            mLastMoveEvent.mCoords.y = ev.getY(ptrIdx);

            // Log.d(TAG, "=======================");
            // Log.d(TAG, "mCurrentScrollX: "+mCurrentScrollX);
            // Log.d(TAG, "mCurrentScrollY: "+mCurrentScrollY);
            // Log.d(TAG, "mMinScrollY: "+mMinScrollY);
            // Log.d(TAG, "mMaxScrollY: "+mMaxScrollY);
            // Log.d(TAG, "mPeekSize.top: "+mPeekSize.top);
            // Log.d(TAG, "mPeekSize.bottom: "+mPeekSize.bottom);

            adjustScrollStates(mCurrentScrollX, mCurrentScrollY);
            notifyOffset();
        }

        return true;
    }

    private boolean mTouchOutsideToClose = true;

    private boolean processIdleUp(MotionEvent ev) {
        if (!mTouchOutsideToClose)
            return false;

        if (mDrawerState.mActiveDrawer == NO_DRAWER)
            return false;
        if (mDownEvent  == null)
            return false;

        boolean inside = touchIsInDrawerArea(mDrawerState.mActiveDrawer, ev);
        inside &= inside && touchIsInDrawerArea(mDrawerState.mActiveDrawer, mDownEvent.mCoords.x,
                mDownEvent.mCoords.y);

        if (!inside)
            close(mDrawerState.mActiveDrawer);
        return !inside;
    }

    private boolean mRequestDisallowIntercept = false;

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {

        Log.d("HOBO", "requestedDisallowIntercept");

        mRequestDisallowIntercept = disallowIntercept;
        super.requestDisallowInterceptTouchEvent(disallowIntercept);

        // Do we need this?
        // super.requestDisallowInterceptTouchEvent(disallowIntercept);

//        944         if (!mLeftDragger.isEdgeTouched(ViewDragHelper.EDGE_LEFT) &&
//                945                 !mRightDragger.isEdgeTouched(ViewDragHelper.EDGE_RIGHT)) {
//            946             // If we have an edge touch we want to skip this and track it for later instead.
//            947             super.requestDisallowInterceptTouchEvent(disallowIntercept);
//            948         }
//        949         mDisallowInterceptRequested = disallowIntercept;
//        950         if (disallowIntercept) {
//            951             closeDrawers(true);
//            952         }
//        953     }
    }

    /*
     * This method JUST determines whether we want to intercept the motion.
     * If we return true, onTouchEvent will be called and we do the actual
     * scrolling there.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mDrawerState.mDraggingEnabled)
            return false;

        final int action = MotionEventCompat.getActionMasked(ev);

        if (action == MotionEvent.ACTION_DOWN)
            mRequestDisallowIntercept = false;

        if (mRequestDisallowIntercept)
            return false;

        if (mVelocityTracker != null)
            mVelocityTracker.addMovement(ev);

        if (mDrawerState.mScrollState != IDLE)
            return true;

        // Always handle the case of the touch gesture being complete.
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            if (DEBUG_TOUCH) Log.d(TAG, "UP OR CANCEL: RELEASING SCROLL");
            mIsScrolling = false;
            mVelocityTracker.clear();
            if (action == MotionEvent.ACTION_UP) {
                processIdleUp(ev);
            }
            mRequestDisallowIntercept = false;
            return false; // Do not intercept touch event, let the child handle it
        }

        int drawer = preProcessScrollMotionEvent(ev);
        boolean intercept = drawer >= 0;
        boolean aDrawerIsOpen = mDrawerState.mScrollState == IDLE
                && mDrawerState.mActiveDrawer != NO_DRAWER;

        // Let touches go through if they are in the area of an open drawer
        if (!intercept && aDrawerIsOpen) {
            if (touchIsInDrawerArea(mDrawerState.mActiveDrawer, ev)) {
                if (DEBUG_TOUCH) Log.d(TAG, "NOT INTERCEPTING BECAUSE A DRAWER IS OPEN");
                return false;
            }
        }

        postProcessScrollMotionEvent(drawer, ev);

        if (DEBUG_TOUCH) Log.d(TAG, "intercept/aDrawerIsOpen: " + intercept + "/" + aDrawerIsOpen);

        // Intercept events unless we are in the initial idle position
        return intercept || aDrawerIsOpen;
    }

    private boolean touchIsInDrawerArea (int drawerId, MotionEvent ev) {
        return touchIsInDrawerArea(drawerId, ev.getX(), ev.getY());
    }

    private boolean touchIsInDrawerArea (int drawerId, float x, float y) {
        Rect drawerBounds = null;
        switch (drawerId) {
            case LEFT_DRAWER:
                drawerBounds = mLayoutBoundsWithoutPeek.get(mLeft);
                break;
            case RIGHT_DRAWER:
                drawerBounds = mLayoutBoundsWithoutPeek.get(mRight);
                break;
            case TOP_DRAWER:
                drawerBounds = mLayoutBoundsWithoutPeek.get(mTop);
                break;
            case BOTTOM_DRAWER:
                drawerBounds = mLayoutBoundsWithoutPeek.get(mBottom);
                break;
        }

        return drawerBounds == null ? false :
                drawerBounds.contains((int) x, (int) y);
    }

    private boolean canScroll (int axis, float diff) {
        if( axis == MotionEvent.AXIS_X ) {
            if (mDrawerState.mActiveDrawer == BOTTOM_DRAWER || mDrawerState.mActiveDrawer ==
                    TOP_DRAWER)
                return false;
            else if (diff < 0)
                return !(mDrawerState.mActiveDrawer == NO_DRAWER && !isScrollingForSectionEnabled
                        (RIGHT_DRAWER));
            else
                return !(mDrawerState.mActiveDrawer == NO_DRAWER && !isScrollingForSectionEnabled
                        (LEFT_DRAWER));
        } else if (axis == MotionEvent.AXIS_Y) {
            if (mDrawerState.mActiveDrawer == LEFT_DRAWER || mDrawerState.mActiveDrawer ==
                    RIGHT_DRAWER)
                return false;
            else if (diff < 0)
                return !(mDrawerState.mActiveDrawer == NO_DRAWER && !isScrollingForSectionEnabled
                        (BOTTOM_DRAWER));
            else
                return !(mDrawerState.mActiveDrawer == NO_DRAWER && !isScrollingForSectionEnabled
                        (TOP_DRAWER));
        } else {
            throw new IllegalArgumentException();
        }
    }

    private void postProcessScrollMotionEvent (int drawer, MotionEvent ev) {
        if (drawer >= 0 && mDrawerState.mScrollState == IDLE) {
            mIsScrolling = true;
            mDrawerState.mScrollState = SCROLLING;
            if (mDrawerState.mActiveDrawer == NO_DRAWER)
                mDrawerState.mActiveDrawer = drawer;
            if (mListener != null)
                mListener.onBeginScroll(this, mDrawerState);
        }
    }

    private int preProcessScrollMotionEvent (MotionEvent ev) {
        final int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (DEBUG_TOUCH) Log.d(TAG, "DOWN: " + ev.getX(0) + "," + ev.getY(0));
                if (!mIsScrolling) {
                    mVelocityTracker.clear();
                    mDownEvent.mCoords.x = ev.getX(0);
                    mDownEvent.mCoords.y = ev.getY(0);
                    mDownEvent.mPointerId = ev.getPointerId(0);
                    if (DEBUG_TOUCH) Log.d(TAG, "SAVED: " + ev.getX(0) + "," + ev.getY(0));
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                final int ptrIdx = ev.findPointerIndex(mDownEvent.mPointerId);
                if (ptrIdx < 0 || ptrIdx >= ev.getPointerCount())
                    return -1;

                if (DEBUG_TOUCH) Log.d(TAG, "MOVE: " + ev.getX(ptrIdx) + "," + ev.getY(ptrIdx));
                if (mIsScrolling) {
                    return mDrawerState.mActiveDrawer;
                }

                // If the user has dragged her finger horizontally more than
                // the touch slop, start the scroll

                final float xDiff = calculateDistanceX(ev, mDownEvent);
                final float yDiff = calculateDistanceY(ev, mDownEvent);
                final float dxAbs = Math.abs(xDiff);
                final float dyAbs = Math.abs(yDiff);

                if (DEBUG_TOUCH) {
                    Log.d(TAG, "xDiff: " + xDiff + " -- " + ev.getX(ptrIdx) + " -- " + mDownEvent.mCoords.x);
                    Log.d(TAG, "xDiff/yDiff/dxAbs/dyAbs: " + xDiff + "/" + yDiff + "/" + dxAbs + "/" + dyAbs);
                    Log.d(TAG, "canScrollX/canScrollY: " + canScroll(MotionEvent.AXIS_X,
                            xDiff) + "/" + canScroll(MotionEvent.AXIS_Y, yDiff));
                    Log.d(TAG, "activeDrawer/scrollState: " + mDrawerState
                            .mActiveDrawer + "/" + mDrawerState.mScrollState);
                }

                if (dxAbs > dyAbs && dxAbs > mTouchSlop && canScroll(MotionEvent.AXIS_X, xDiff)) {
                    if (DEBUG_TOUCH) Log.d(TAG, "X axis branch");
                    int drawer;
                    if (mDrawerState.mActiveDrawer == NO_DRAWER)
                        drawer = xDiff > 0 ? LEFT_DRAWER : RIGHT_DRAWER;
                    else
                        drawer = mDrawerState.mActiveDrawer;
                    if (!isInitialPositionValidForDrawer(mDownEvent, drawer))
                        drawer = -1;
                    return drawer;
                }

                if (dyAbs > dxAbs && dyAbs > mTouchSlop && canScroll(MotionEvent.AXIS_Y, yDiff)) {
                    if (DEBUG_TOUCH) Log.d(TAG, "Y axis branch");
                    int drawer;
                    if (mDrawerState.mActiveDrawer == NO_DRAWER)
                        drawer = yDiff > 0 ? TOP_DRAWER : BOTTOM_DRAWER;
                    else
                        drawer = mDrawerState.mActiveDrawer;

                    if (!isInitialPositionValidForDrawer(mDownEvent, drawer))
                        drawer = -1;
                    return drawer;
                }

                break;
            }
        }
        return -1;
    }

    private boolean isInitialPositionValidForDrawer (HistoryEvent ev, int drawer) {
        boolean restrictToPeekArea = false;
        int peekSize = 0;
        Rect restrictToArea = null;

        if (DEBUG_TOUCH) Log.d(TAG, "drawer/peekSize/evX/evY: " + drawer + "/" + peekSize + "/" + ev.mCoords.x + "/" + ev.mCoords.y);

        switch (drawer) {
            case LEFT_DRAWER:
                restrictToPeekArea = mRestrictTouchesToPeekArea[0];
                peekSize = mPeekSize.left;
                restrictToArea = mRestrictTouchesToArea[0];
                break;
            case TOP_DRAWER:
                restrictToPeekArea = mRestrictTouchesToPeekArea[1];
                peekSize = mPeekSize.top;
                restrictToArea = mRestrictTouchesToArea[1];
                break;
            case RIGHT_DRAWER:
                restrictToPeekArea = mRestrictTouchesToPeekArea[2];
                peekSize = mPeekSize.right;
                restrictToArea = mRestrictTouchesToArea[2];
                break;
            case BOTTOM_DRAWER:
                restrictToPeekArea = mRestrictTouchesToPeekArea[3];
                peekSize = mPeekSize.bottom;
                restrictToArea = mRestrictTouchesToArea[3];
                break;
            case NO_DRAWER: return true;
            default: return false;
        }

        if (!restrictToPeekArea || peekSize == 0) {
            if (restrictToArea == null)
                return true;
                // restricted non-peek touch areas only apply when
                // all drawers are closed
            else if (mDrawerState.mActiveDrawer != NO_DRAWER) {
                return true;
            }
        }

        if (DEBUG_TOUCH) Log.d(TAG, "drawer/peekSize/evX/evY: " + drawer + "/" + peekSize + "/" + ev
                .mCoords.x + "/" + ev.mCoords.y);
        if (DEBUG_TOUCH && restrictToArea != null) {
            Log.d(TAG, "restrictArea: " + restrictToArea.left
                    + "/" + restrictToArea.top + "/" + restrictToArea.right + "/" + restrictToArea.bottom);
            Log.d(TAG, "left: " + (ev.mCoords.x > restrictToArea.left));
            Log.d(TAG, "right: " + (ev.mCoords.x < restrictToArea.right));
            Log.d(TAG, "top: " + (ev.mCoords.y > restrictToArea.top));
            Log.d(TAG, "bottom: " + (ev.mCoords.y < restrictToArea.bottom));
            Log.d(TAG, "restrictToPeek: " + restrictToPeekArea);
            Log.d(TAG, "peekSize: " + peekSize);
        }

        switch (drawer) {
            case LEFT_DRAWER:
                if (restrictToPeekArea && peekSize != 0) {
                    return ev.mCoords.x <= peekSize + mCurrentScrollX
                            && ev.mCoords.x >= mCurrentScrollX;
                } else {
                    return ev.mCoords.x >= restrictToArea.left
                            && ev.mCoords.x <= restrictToArea.right
                            && ev.mCoords.y >= restrictToArea.top
                            && ev.mCoords.y <= restrictToArea.bottom;
                }
            case TOP_DRAWER:
                if (restrictToPeekArea && peekSize != 0) {
                    return ev.mCoords.y <= peekSize + mCurrentScrollY
                            && ev.mCoords.y >= mCurrentScrollY;
                } else {
                    return ev.mCoords.x >= restrictToArea.left
                            && ev.mCoords.x <= restrictToArea.right
                            && ev.mCoords.y >= restrictToArea.top
                            && ev.mCoords.y <= restrictToArea.bottom;
                }
            case RIGHT_DRAWER:
                if (restrictToPeekArea && peekSize != 0) {
                    return ev.mCoords.x >= getWidth() - peekSize + mCurrentScrollX
                            && ev.mCoords.x <= getWidth() + mCurrentScrollX;
                } else {
                    return ev.mCoords.x >= getWidth() + restrictToArea.left
                            && ev.mCoords.x <= getWidth() + restrictToArea.right
                            && ev.mCoords.y >= restrictToArea.top
                            && ev.mCoords.y <= restrictToArea.bottom;
                }
            case BOTTOM_DRAWER:
                if (restrictToPeekArea && peekSize != 0) {
                    return ev.mCoords.y >= getHeight() - peekSize + mCurrentScrollY
                            && ev.mCoords.y <= getHeight() + mCurrentScrollY;
                } else {
                    return ev.mCoords.x >= restrictToArea.left
                            && ev.mCoords.x <= restrictToArea.right
                            && ev.mCoords.y >= getHeight() + restrictToArea.top
                            && ev.mCoords.y <= getHeight() + restrictToArea.bottom;
                }
        }

        return false;
    }

    private float calculateDistanceX (MotionEvent ev, HistoryEvent down) {
        float x = ev.getX() - down.mCoords.x;
        return x;
    }

    private float calculateDistanceY (MotionEvent ev, HistoryEvent down) {
        float y = ev.getY() - down.mCoords.y;
        return y;
    }

    public static class LayoutParams extends LinearLayout.LayoutParams {

        public LayoutParams(int w, int h) {
            super(w, h);
        }

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p.width, p.height);
    }

    protected Parcelable onSaveInstanceState () {
        Parcelable p = super.onSaveInstanceState();
        SavedState ss = new SavedState(p);
        ss.currentScrollFractionX = getScrollFractionX();
        ss.currentScrollFractionY = getScrollFractionY();
        ss.activeDrawer = mDrawerState.mActiveDrawer;
        ss.scrollState = mDrawerState.mScrollState;
        ss.draggingEnabled = mDrawerState.mDraggingEnabled;
        return ss;
    }

    protected void onRestoreInstanceState (Parcelable state) {
        if(!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState)state;
        super.onRestoreInstanceState(ss.getSuperState());

        // Avoid restoring state if the drawer was still being dragged
        // i.e. round the scroll states and set the right active drawer
        if (ss.scrollState != IDLE) {
            if (ss.currentScrollFractionX != Float.MIN_VALUE)
                ss.currentScrollFractionX = Math.round(ss.currentScrollFractionX);
            if (ss.currentScrollFractionY != Float.MIN_VALUE)
                ss.currentScrollFractionY = Math.round(ss.currentScrollFractionY);
            ss.scrollState = IDLE;
            // Need to set the right activeDrawer
            if (ss.currentScrollFractionX == 0 && ss.currentScrollFractionY == 0)
                ss.activeDrawer = NO_DRAWER;
        }

        if (ss.scrollState >= 0) mDrawerState.mScrollState = ss.scrollState;
        if (ss.activeDrawer >= 0) mDrawerState.mActiveDrawer = ss.activeDrawer;
        // Do not restore this, use setDraggingEnabled explicitly from activity/fragment code
        // mDrawerState.mDraggingEnabled = ss.draggingEnabled;

        boolean doNotRestore = (mDrawerState.mActiveDrawer == LEFT_DRAWER && mLeft == null)
                || (mDrawerState.mActiveDrawer == RIGHT_DRAWER && mRight == null)
                || (mDrawerState.mActiveDrawer == TOP_DRAWER && mTop == null)
                || (mDrawerState.mActiveDrawer == BOTTOM_DRAWER && mBottom == null);
        if (doNotRestore) {
            mDrawerState.mActiveDrawer = NO_DRAWER;
            mDrawerState.mScrollState = IDLE;
        }

        // Restoring the drawer offset will only occur in the next layout pass.
        // We need the actual drawer dimensions in order to properly set the
        // the offset.
        mPendingSavedState = new RestoreStateRunnable(ss);
    }

    private RestoreStateRunnable mPendingSavedState;

    private class RestoreStateRunnable implements Runnable {
        SavedState mSavedState;
        RestoreStateRunnable(SavedState ss) { mSavedState = ss; }
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "running Pending Saved State");

            SavedState ss = mSavedState;
            if (ss.currentScrollFractionX != Float.MIN_VALUE) {
                mCurrentScrollX = scrollFractionToSizeOffset(ss.activeDrawer, ss.currentScrollFractionX);
            }
            if (ss.currentScrollFractionY != Float.MIN_VALUE) {
                mCurrentScrollY = scrollFractionToSizeOffset(ss.activeDrawer, ss.currentScrollFractionY);
            }

            notifyOffset();
        }
    }

    static class SavedState extends BaseSavedState {
        float currentScrollFractionX = Float.MIN_VALUE;
        float currentScrollFractionY = Float.MIN_VALUE;
        int scrollState = -1;
        int activeDrawer = -1;
        boolean draggingEnabled = true;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            this.currentScrollFractionX = in.readFloat();
            this.currentScrollFractionY = in.readFloat();
            this.scrollState = in.readInt();
            this.activeDrawer = in.readInt();
            boolean[] bArray = new boolean[1];
            in.readBooleanArray(bArray);
            this.draggingEnabled = bArray[0];
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeFloat(this.currentScrollFractionX);
            out.writeFloat(this.currentScrollFractionY);
            out.writeInt(this.scrollState);
            out.writeInt(this.activeDrawer);
            out.writeBooleanArray(new boolean[] {this.draggingEnabled});
        }

        //required field that makes Parcelables from a Parcel
        public static final Creator<SavedState> CREATOR =
                new Creator<SavedState>() {
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}

package com.joe.jmlite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import java.util.function.BooleanSupplier;

/** Intercepts only in-page navigation; normal Activity exits stay with the system. */
public final class PredictiveBack implements AutoCloseable {
    private final Activity activity;
    private final View surface;
    private final BooleanSupplier canHandle;
    private final Runnable action;
    private Runnable unregister;
    private ValueAnimator settling;
    private boolean closed,gesturing,left;
    private float progress;
    private float originalScaleX,originalScaleY,originalX,originalY,originalAlpha;
    private boolean originalClip;
    private ViewOutlineProvider originalOutline;

    public PredictiveBack(Activity activity,View surface,BooleanSupplier canHandle,Runnable action){
        this.activity=activity;this.surface=surface;this.canHandle=canHandle;this.action=action;refresh();
    }
    public void refresh(){
        if(Build.VERSION.SDK_INT<33||closed)return;
        boolean enabled=canHandle.getAsBoolean();
        if(enabled&&unregister==null)unregister=Api33.register(this);
        else if(!enabled&&unregister!=null){unregister.run();unregister=null;reset();}
    }
    private void start(boolean fromLeft){
        reset();gesturing=true;left=fromLeft;progress=0;
        originalScaleX=surface.getScaleX();originalScaleY=surface.getScaleY();originalX=surface.getTranslationX();originalY=surface.getTranslationY();originalAlpha=surface.getAlpha();
        originalClip=surface.getClipToOutline();originalOutline=surface.getOutlineProvider();
        surface.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),view.getResources().getDisplayMetrics().density*24*progress);}});
        surface.setClipToOutline(true);
    }
    private void advance(float value){
        if(!gesturing)return;
        progress=Math.max(0f,Math.min(1f,value));
        surface.setScaleX(originalScaleX*previewScale(progress));surface.setScaleY(originalScaleY*previewScale(progress));
        surface.setTranslationX(originalX+previewShift(surface.getWidth(),progress,left));surface.setAlpha(originalAlpha*(1f-.04f*progress));surface.invalidateOutline();
    }
    private void invoke(){
        if(closed)return;
        if(!canHandle.getAsBoolean()){reset();refresh();return;}
        if(gesturing)settle(true);else{action.run();refresh();}
    }
    private void settle(boolean commit){
        if(!gesturing)return;
        if(settling!=null){settling.removeAllListeners();settling.cancel();}
        ValueAnimator animation=ValueAnimator.ofFloat(progress,commit?1f:0f);settling=animation;
        animation.setDuration(120);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animation.addUpdateListener(a->{if(settling==animation&&!closed)advance((float)a.getAnimatedValue());});
        animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
            if(settling!=animation||closed)return;settling=null;reset();
            if(commit&&!activity.isFinishing()&&!activity.isDestroyed()&&canHandle.getAsBoolean())action.run();refresh();
        }});animation.start();
    }
    private void reset(){
        if(settling!=null){ValueAnimator old=settling;settling=null;old.removeAllListeners();old.cancel();}
        if(!gesturing)return;
        gesturing=false;progress=0;surface.setScaleX(originalScaleX);surface.setScaleY(originalScaleY);surface.setTranslationX(originalX);surface.setTranslationY(originalY);surface.setAlpha(originalAlpha);surface.setOutlineProvider(originalOutline);surface.setClipToOutline(originalClip);
    }
    @Override public void close(){closed=true;if(unregister!=null){unregister.run();unregister=null;}reset();}
    static float previewScale(float progress){return 1f-.06f*Math.max(0f,Math.min(1f,progress));}
    static float previewShift(int width,float progress,boolean left){return Math.max(0,width)*.075f*Math.max(0f,Math.min(1f,progress))*(left?1f:-1f);}

    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        static Runnable register(PredictiveBack owner){
            android.window.OnBackInvokedDispatcher dispatcher=owner.activity.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback callback=Build.VERSION.SDK_INT>=34?Api34.callback(owner):owner::invoke;
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
            return ()->dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }
    @androidx.annotation.RequiresApi(34)
    private static final class Api34 {
        static android.window.OnBackAnimationCallback callback(PredictiveBack owner){return new android.window.OnBackAnimationCallback(){
            @Override public void onBackStarted(android.window.BackEvent event){if(!owner.closed)owner.start(event.getSwipeEdge()==android.window.BackEvent.EDGE_LEFT);}
            @Override public void onBackProgressed(android.window.BackEvent event){if(!owner.closed)owner.advance(event.getProgress());}
            @Override public void onBackCancelled(){if(!owner.closed)owner.settle(false);}
            @Override public void onBackInvoked(){owner.invoke();}
        };}
    }
}

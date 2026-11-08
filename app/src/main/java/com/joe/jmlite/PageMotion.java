package com.joe.jmlite;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.animation.AnimationUtils;
import eu.kanade.tachiyomi.R;

/** Reuse the folder entry motion while keeping the previous Activity opaque beneath it. */
public final class PageMotion {
    private static final java.util.WeakHashMap<Activity,Boolean> entering=new java.util.WeakHashMap<>();
    public static void enter(Activity activity){
        int background=new Ui(activity).bg;
        activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        if(Build.VERSION.SDK_INT>=34)Api34.configure(activity,background);
        else activity.overridePendingTransition(R.anim.shared_axis_x_push_enter,R.anim.page_hold);
        entering.put(activity,true);
        View decor=activity.getWindow().getDecorView();
        decor.post(()->{
            if(activity.isFinishing()||activity.isDestroyed()){entering.put(activity,false);return;}
            // A real ValueAnimator with the enter transition duration gives an actual lifecycle
            // end callback; unlike a DecorView Animation it cannot be swallowed by the window
            // transition and leave the page permanently marked as "entering".
            android.animation.ValueAnimator tracker=android.animation.ValueAnimator.ofFloat(0f,1f);
            tracker.setDuration(AnimationUtils.loadAnimation(activity,R.anim.shared_axis_x_push_enter).getDuration());
            tracker.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator a){entering.put(activity,false);}
                @Override public void onAnimationCancel(android.animation.Animator a){entering.put(activity,false);}
            });
            tracker.start();
        });
    }
    public static boolean isEntering(Activity activity){return Boolean.TRUE.equals(entering.get(activity));}
    public static void clearEntering(Activity activity){entering.put(activity,false);}
    /** API 34+ requires the close override before finish(); after super.finish() is too late. */
    public static void prepareExit(Activity activity,boolean gesture){
        if(Build.VERSION.SDK_INT>=34)activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,gesture?0:R.anim.shared_axis_x_pop_enter,gesture?0:R.anim.shared_axis_x_pop_exit);
    }
    /** Legacy pending transition belongs after finish(). */
    public static void exitLegacy(Activity activity,boolean gesture){
        if(Build.VERSION.SDK_INT<34)activity.overridePendingTransition(gesture?0:R.anim.shared_axis_x_pop_enter,gesture?0:R.anim.shared_axis_x_pop_exit);
    }
    /** Apply after finish(): enter belongs to the revealed page, exit to the departing page. */
    public static void exit(Activity activity,boolean gesture){
        int enter=gesture?0:R.anim.shared_axis_x_pop_enter;
        int exit=gesture?0:R.anim.shared_axis_x_pop_exit;
        if(Build.VERSION.SDK_INT>=34)activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,enter,exit);
        activity.overridePendingTransition(enter,exit);
    }
    static void folder(View content){content.clearAnimation();content.startAnimation(AnimationUtils.loadAnimation(content.getContext(),R.anim.shared_axis_x_push_enter));}
    /** Track the in-window folder push so an immediate edge swipe can use the direct branch. */
    static void folder(Activity activity,View content){
        entering.put(activity,true);
        android.view.animation.Animation animation=AnimationUtils.loadAnimation(content.getContext(),R.anim.shared_axis_x_push_enter);
        animation.setAnimationListener(new android.view.animation.Animation.AnimationListener(){
            @Override public void onAnimationStart(android.view.animation.Animation a){}
            @Override public void onAnimationRepeat(android.view.animation.Animation a){}
            @Override public void onAnimationEnd(android.view.animation.Animation a){entering.put(activity,false);}
        });
        content.startAnimation(animation);
    }
    /** Same exit as the toolbar back, but mirrored for a right-edge swipe. */
    static void exitSource(View outgoing,boolean left,Runnable finished){
        outgoing.clearAnimation();
        android.view.animation.Animation exit;
        try{exit=AnimationUtils.loadAnimation(outgoing.getContext(),left?R.anim.shared_axis_x_pop_exit:R.anim.shared_axis_x_push_exit);}catch(RuntimeException failed){finished.run();return;}
        exit.setFillAfter(true);
        exit.setAnimationListener(new android.view.animation.Animation.AnimationListener(){
            @Override public void onAnimationStart(android.view.animation.Animation a){}
            @Override public void onAnimationRepeat(android.view.animation.Animation a){}
            @Override public void onAnimationEnd(android.view.animation.Animation a){finished.run();}
        });
        outgoing.startAnimation(exit);
    }
    /** Only the source page moves out; the destination snapshot stays opaque and still. */
    static void backOutgoing(View outgoing,Runnable finished){
        outgoing.clearAnimation();
        android.view.animation.Animation exit;
        try{exit=AnimationUtils.loadAnimation(outgoing.getContext(),R.anim.shared_axis_x_pop_exit);}catch(RuntimeException failed){finished.run();return;}
        exit.setFillAfter(true);
        exit.setAnimationListener(new android.view.animation.Animation.AnimationListener(){
            @Override public void onAnimationStart(android.view.animation.Animation a){}
            @Override public void onAnimationRepeat(android.view.animation.Animation a){}
            @Override public void onAnimationEnd(android.view.animation.Animation a){finished.run();}
        });
        outgoing.startAnimation(exit);
    }
    /** The same pop motion for every internal back action, including the toolbar button. */
    static void back(View outgoing,View incoming){
        back(outgoing,incoming,null);
    }
    static void back(View outgoing,View incoming,Runnable finished){
        outgoing.clearAnimation();incoming.clearAnimation();
        android.view.animation.Animation exit=AnimationUtils.loadAnimation(outgoing.getContext(),R.anim.shared_axis_x_pop_exit);
        android.view.animation.Animation enter=AnimationUtils.loadAnimation(incoming.getContext(),R.anim.shared_axis_x_pop_enter);
        // Keep the outgoing page at its final offset/alpha until it is removed; otherwise View
        // animations snap back to alpha 1 for a frame and briefly flash the old page.
        exit.setFillAfter(true);enter.setFillAfter(true);
        if(finished!=null)enter.setAnimationListener(new android.view.animation.Animation.AnimationListener(){
            @Override public void onAnimationStart(android.view.animation.Animation animation){}
            @Override public void onAnimationRepeat(android.view.animation.Animation animation){}
            @Override public void onAnimationEnd(android.view.animation.Animation animation){finished.run();}
        });
        outgoing.startAnimation(exit);incoming.startAnimation(enter);
    }
    @androidx.annotation.RequiresApi(34) private static final class Api34 {
        static void configure(Activity activity,int background){activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,R.anim.shared_axis_x_push_enter,R.anim.page_hold,background);activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,R.anim.shared_axis_x_pop_enter,R.anim.shared_axis_x_pop_exit,background);}
    }
}

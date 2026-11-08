package com.joe.jmlite;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.animation.AnimationUtils;
import eu.kanade.tachiyomi.R;

/** Reuse the folder entry motion while keeping the previous Activity opaque beneath it. */
public final class PageMotion {
    public static void enter(Activity activity){
        int background=new Ui(activity).bg;
        activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        if(Build.VERSION.SDK_INT>=34)Api34.configure(activity,background);
        else activity.overridePendingTransition(R.anim.shared_axis_x_push_enter,R.anim.shared_axis_x_pop_exit);
    }
    static void folder(View content){content.clearAnimation();content.startAnimation(AnimationUtils.loadAnimation(content.getContext(),R.anim.shared_axis_x_push_enter));}
    /** The same pop motion for every internal back action, including the toolbar button. */
    static void back(View outgoing,View incoming){
        outgoing.clearAnimation();incoming.clearAnimation();
        android.view.animation.Animation exit=AnimationUtils.loadAnimation(outgoing.getContext(),R.anim.shared_axis_x_pop_exit);
        android.view.animation.Animation enter=AnimationUtils.loadAnimation(incoming.getContext(),R.anim.shared_axis_x_pop_enter);
        // Keep the outgoing page at its final offset/alpha until it is removed; otherwise View
        // animations snap back to alpha 1 for a frame and briefly flash the old page.
        exit.setFillAfter(true);enter.setFillAfter(true);
        outgoing.startAnimation(exit);incoming.startAnimation(enter);
    }
    @androidx.annotation.RequiresApi(34) private static final class Api34 {
        static void configure(Activity activity,int background){activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,R.anim.shared_axis_x_push_enter,R.anim.page_hold,background);activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,R.anim.shared_axis_x_pop_exit,R.anim.page_hold,background);}
    }
}

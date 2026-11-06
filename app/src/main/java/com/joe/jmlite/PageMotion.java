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
        else activity.overridePendingTransition(R.anim.shared_axis_x_push_enter,R.anim.page_hold);
    }
    static void folder(View content){content.clearAnimation();content.startAnimation(AnimationUtils.loadAnimation(content.getContext(),R.anim.shared_axis_x_push_enter));}
    @androidx.annotation.RequiresApi(34) private static final class Api34 {
        static void configure(Activity activity,int background){activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,R.anim.shared_axis_x_push_enter,R.anim.page_hold,background);}
    }
}

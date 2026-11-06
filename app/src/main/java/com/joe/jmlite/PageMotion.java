package com.joe.jmlite;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.animation.AnimationUtils;
import eu.kanade.tachiyomi.R;

/** Reuse the same shared-axis entry for a folder and its detail pages. */
public final class PageMotion {
    public static void detail(Activity activity){
        if(Build.VERSION.SDK_INT>=34)Api34.configure(activity);
        else activity.overridePendingTransition(R.anim.shared_axis_x_push_enter,R.anim.shared_axis_x_push_exit);
    }
    static void folder(View content){content.clearAnimation();content.startAnimation(AnimationUtils.loadAnimation(content.getContext(),R.anim.shared_axis_x_push_enter));}
    @androidx.annotation.RequiresApi(34) private static final class Api34 {
        static void configure(Activity activity){activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,R.anim.shared_axis_x_push_enter,R.anim.shared_axis_x_push_exit);}
    }
}

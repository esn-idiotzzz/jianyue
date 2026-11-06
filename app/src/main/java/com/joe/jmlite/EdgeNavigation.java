package com.joe.jmlite;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.*;
import android.widget.AbsListView;
import android.widget.ScrollView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.HashMap;
import java.util.Map;

/** Transparent, visible gesture navigation; foreground controls retain a safe bottom inset. */
public final class EdgeNavigation {
    public static void style(Activity activity,boolean dark){
        Window window=activity.getWindow();WindowCompat.setDecorFitsSystemWindows(window,false);
        window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=28)window.setNavigationBarDividerColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(window,window.getDecorView());
        controller.setAppearanceLightStatusBars(!dark);controller.setAppearanceLightNavigationBars(!dark);
        controller.show(WindowInsetsCompat.Type.navigationBars());
    }
    static void install(Activity activity,View root,boolean dark){
        style(activity,dark);Map<View,Integer> basePadding=new HashMap<>();
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int keyboard=insets.isVisible(WindowInsetsCompat.Type.ime())?insets.getInsets(WindowInsetsCompat.Type.ime()).bottom:0;
            view.setPadding(bars.left,bars.top,bars.right,keyboard);
            padScrollers(view,insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,basePadding);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
    private static void padScrollers(View view,int bottom,Map<View,Integer> base){
        if(view instanceof AbsListView||view instanceof ScrollView){
            int padding=base.computeIfAbsent(view,key->view.getPaddingBottom())+bottom;
            if(view.getPaddingBottom()!=padding)view.setPadding(view.getPaddingLeft(),view.getPaddingTop(),view.getPaddingRight(),padding);
            ((ViewGroup)view).setClipToPadding(false);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)padScrollers(group.getChildAt(n),bottom,base);}
    }
}

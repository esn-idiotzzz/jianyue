package com.joe.jmlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The manual is acknowledged only by its explicit confirmation button. */
public final class FirstRunGuide implements AutoCloseable {
    static final String ACKNOWLEDGED="first_run_guide_acknowledged";
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Runnable onAcknowledged;
    private AlertDialog dialog;
    private boolean acknowledged,closed;

    public FirstRunGuide(Activity activity,SharedPreferences preferences,Runnable onAcknowledged){
        this.activity=activity;this.preferences=preferences;this.onAcknowledged=onAcknowledged;
    }

    public boolean isAcknowledged(){return acknowledged||preferences.getBoolean(ACKNOWLEDGED,false);}
    public boolean isShowing(){return dialog!=null&&dialog.isShowing();}
    public boolean needsAcknowledgment(){return !closed&&!isAcknowledged()&&!activity.isFinishing()&&!activity.isDestroyed();}

    /** True means the guide owns the launch dialog slot, including an already shown guide. */
    public boolean showIfNeeded(){
        if(!needsAcknowledgment())return false;
        if(isShowing())return true;
        Ui ui=new Ui(activity);
        LinearLayout body=ui.column();body.setPadding(ui.dp(22),ui.dp(6),ui.dp(22),ui.dp(10));
        String[] sections={
            "1、支持 JM 车牌号下载和阅读。",
            "2、支持全网漫画搜索（建议使用魔法）。",
            "3、JM 漫画和全网漫画在线阅读后，可以在“历史”中查看。点击漫画进入详情后可进行收藏，收藏后保存在书架目录。",
            "4、JM 漫画支持“从头开始阅读”和“上次位置阅读”。",
            "5、全网漫画同样支持从头阅读、上次位置阅读，并提示具体上次阅读章节。",
            "6、⚠️注意周围环境~"
        };
        for(int n=0;n<sections.length;n++){
            TextView section=ui.text(sections[n],15,n==5?(ui.dark?0xffffc167:0xff9e6100):ui.ink,n==5);
            section.setLineSpacing(ui.dp(4),1f);section.setPadding(0,ui.dp(9),0,ui.dp(9));body.addView(section,new LinearLayout.LayoutParams(-1,-2));
        }
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        LinearLayout content=ui.column();int screen=activity.getResources().getDisplayMetrics().heightPixels;
        int height=Math.min(ui.dp(420),Math.min((int)(screen*.52f),Math.max(0,screen-ui.dp(170))));
        content.addView(scroll,new LinearLayout.LayoutParams(-1,height));
        final AlertDialog shown=PredictiveBack.dialog(activity).setTitle("首次使用 · 使用手册").setView(content)
                .setCancelable(false).setPositiveButton("我已知晓",(d,which)->acknowledge()).create();
        dialog=shown;shown.setCanceledOnTouchOutside(false);shown.setOnShowListener(d->shown.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.BLUE));shown.show();
        return true;
    }

    void acknowledge(){
        if(!needsAcknowledgment())return;
        acknowledged=true;preferences.edit().putBoolean(ACKNOWLEDGED,true).apply();
        AlertDialog shown=dialog;dialog=null;if(shown!=null)shown.dismiss();
        onAcknowledged.run();
    }

    /** Activity destruction must not count as having read the manual. */
    @Override public void close(){closed=true;AlertDialog shown=dialog;dialog=null;if(shown!=null)shown.dismiss();}
}

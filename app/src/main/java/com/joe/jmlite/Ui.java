package com.joe.jmlite;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BLUE = 0xff3478f6;
    final Context c;
    final boolean dark;
    final int bg, card, ink, muted, line, tint;
    Ui(Context c) {
        this.c = c;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xff101114 : 0xfff5f6fa;
        card = dark ? 0xff202126 : Color.WHITE;
        ink = dark ? 0xfff1f2f5 : 0xff18202c;
        muted = dark ? 0xff989da9 : 0xff818895;
        line = dark ? 0xff34363c : 0xffe9ecf1;
        tint = dark ? 0xff202f4a : 0xffeaf1ff;
    }
    int dp(float n) { return (int)(n * c.getResources().getDisplayMetrics().density + .5f); }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    void round(View v, int color, int radius) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x22ffffff : 0x11000000), shape(color,radius), null));
    }
    TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setIncludeFontPadding(false); return t;
    }
    TextView button(String s, boolean primary) {
        TextView t = text(s,16,primary?Color.WHITE:BLUE,true); t.setGravity(17); t.setMinHeight(dp(52));
        round(t, primary?BLUE:tint,18); return t;
    }
    LinearLayout column() { LinearLayout l = new LinearLayout(c); l.setOrientation(1); return l; }
    LinearLayout row() { LinearLayout l = new LinearLayout(c); l.setOrientation(0); l.setGravity(16); return l; }
    Icon icon(String kind, int color) { return new Icon(kind,color); }
    final class Icon extends View {
        String kind; int color; float glyphScale=1; final Paint pen=new Paint(3);
        Icon(String k,int col){super(c);kind=k;color=col;setMinimumWidth(dp(44));setMinimumHeight(dp(44));}
        void kind(String k){kind=k;invalidate();}
        void color(int col){color=col;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(dp(1)*glyphScale,dp(1)*glyphScale);
            pen.setColor(color);pen.setStrokeWidth(1.7f);pen.setStyle(Paint.Style.STROKE);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
            if("more".equals(kind)){pen.setStyle(Paint.Style.FILL);for(int x=-6;x<=6;x+=6)canvas.drawCircle(x,0,1.5f,pen);}
            else if("add".equals(kind)){canvas.drawLine(-7,0,7,0,pen);canvas.drawLine(0,-7,0,7,pen);}
            else if("back".equals(kind)){Path p=new Path();p.moveTo(3,-7);p.lineTo(-4,0);p.lineTo(3,7);canvas.drawPath(p,pen);}
            else if(kind.startsWith("eye")){Path p=new Path();p.moveTo(-10,0);p.cubicTo(-4,-9,4,-9,10,0);p.cubicTo(4,9,-4,9,-10,0);canvas.drawPath(p,pen);canvas.drawCircle(0,0,2.6f,pen);if("eye-off".equals(kind)){pen.setStrokeWidth(4);pen.setColor(bg);canvas.drawLine(-10,-10,10,10,pen);pen.setStrokeWidth(1.7f);pen.setColor(color);canvas.drawLine(-10,-10,10,10,pen);}}
            else if("download".equals(kind)){canvas.drawLine(0,-8,0,4,pen);Path p=new Path();p.moveTo(-4,0);p.lineTo(0,4);p.lineTo(4,0);canvas.drawPath(p,pen);p.reset();p.moveTo(-8,5);p.lineTo(-8,9);p.lineTo(8,9);p.lineTo(8,5);canvas.drawPath(p,pen);}
            else if("shelf".equals(kind)){canvas.drawRoundRect(new RectF(-8,-8,-2,7),1,1,pen);canvas.drawRoundRect(new RectF(1,-8,7,7),1,1,pen);canvas.drawLine(-10,10,10,10,pen);}
            else if("folder".equals(kind)){Path p=new Path();p.moveTo(-10,-5);p.lineTo(-10,8);p.lineTo(10,8);p.lineTo(10,-5);p.lineTo(0,-5);p.lineTo(-3,-8);p.lineTo(-10,-8);p.close();canvas.drawPath(p,pen);}
            else if("repository".equals(kind)){canvas.drawRoundRect(new RectF(-9,-7,9,9),2,2,pen);canvas.drawLine(-10,-3,10,-3,pen);canvas.drawLine(-3,2,3,2,pen);}
            else if("history".equals(kind)){canvas.drawCircle(0,0,9,pen);canvas.drawLine(0,-5,0,0,pen);canvas.drawLine(0,0,5,3,pen);}
            else if("search".equals(kind)){canvas.drawCircle(-2,-2,6.5f,pen);canvas.drawLine(3,3,9,9,pen);}
            else if("online".equals(kind)){canvas.drawCircle(0,0,9,pen);canvas.drawOval(new RectF(-4,-9,4,9),pen);canvas.drawLine(-9,0,9,0,pen);}
            else if("heart".equals(kind)||"heart-filled".equals(kind)){Path p=new Path();p.moveTo(0,9);p.cubicTo(-1.4f,7.6f,-10,2.4f,-10,-3.2f);p.cubicTo(-10,-7.8f,-6.4f,-10,-3.4f,-10);p.cubicTo(-1.4f,-10,-.2f,-8.9f,0,-7.7f);p.cubicTo(.2f,-8.9f,1.4f,-10,3.4f,-10);p.cubicTo(6.4f,-10,10,-7.8f,10,-3.2f);p.cubicTo(10,2.4f,1.4f,7.6f,0,9);p.close();if("heart-filled".equals(kind))pen.setStyle(Paint.Style.FILL);canvas.drawPath(p,pen);}
            canvas.restore();
        }
    }
    void space(LinearLayout l, int n) { View v = new View(c); l.addView(v,new LinearLayout.LayoutParams(1,dp(n))); }
    static void insets(Activity a, View root, boolean dark) {
        Window w = a.getWindow(); w.setStatusBarColor(Color.TRANSPARENT); w.setNavigationBarColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
        root.setOnApplyWindowInsetsListener((v,i)->{ v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom()); return i; });
    }
    static void readerBars(Window w, boolean fullscreen) {
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(fullscreen)flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        w.getDecorView().setSystemUiVisibility(flags);
        w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);
        if(android.os.Build.VERSION.SDK_INT>=28){android.view.WindowManager.LayoutParams p=w.getAttributes();p.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;w.setAttributes(p);}
        if(android.os.Build.VERSION.SDK_INT>=29)w.setNavigationBarContrastEnforced(false);
        if(android.os.Build.VERSION.SDK_INT>=30){android.view.WindowInsetsController c=w.getInsetsController();if(c!=null){c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(fullscreen)c.hide(android.view.WindowInsets.Type.systemBars());else c.show(android.view.WindowInsets.Type.systemBars());}}
    }
}

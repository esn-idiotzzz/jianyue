package com.joe.jmlite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Outline;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.function.BooleanSupplier;

/** One gesture path for app pages and cancelable dialogs; readers do not install it. */
public final class PredictiveBack implements AutoCloseable {
    private final Activity activity;
    private final View surface;
    private final Window window;
    private final BooleanSupplier canHandle;
    private final BooleanSupplier exits;
    private final BooleanSupplier preview;
    private final Runnable action;
    private Runnable unregister;private Runnable previewStart;
    private ValueAnimator settling;
    private boolean closed,stopped,gesturing,left,committed,commitExits,buttonLeaving,directionLocked;
    private int pointerEdge=-1;private boolean pointerEntering;
    private long pointerTime;
    private float gestureTouchX;
    private float progress;
    private float originalScaleX,originalScaleY,originalX,originalY;
    private int originalLayer,width;private float gestureScale=.92f,targetOffsetY,radius,maxRadius,density;private float originalPivotX,originalPivotY,originalAlpha;private boolean gestureLeaving,enterDirect;
    private boolean originalClip;
    private ViewOutlineProvider originalOutline;
    private final ViewTreeObserver.OnGlobalLayoutListener keyboardLayout=this::refresh;
    private final Runnable rearm=this::rearm;
    private void rearm(){if(!closed&&!stopped&&!gesturing&&!activity.isFinishing())committed=false;}

    public PredictiveBack(Activity activity,View surface,BooleanSupplier canHandle,Runnable action){
        this(activity,surface,canHandle,()->false,action);
    }
    public PredictiveBack(Activity activity,View surface,BooleanSupplier canHandle,BooleanSupplier exits,Runnable action){
        this(activity,activity.getWindow(),surface,canHandle,exits,action);
    }
    public PredictiveBack(Activity activity,View surface,BooleanSupplier canHandle,BooleanSupplier exits,BooleanSupplier preview,Runnable action){
        this(activity,activity.getWindow(),surface,canHandle,exits,preview,action);
    }
    private PredictiveBack(Activity activity,Window window,View surface,BooleanSupplier canHandle,BooleanSupplier exits,Runnable action){
        this(activity,window,surface,canHandle,exits,()->true,action);
    }
    private PredictiveBack(Activity activity,Window window,View surface,BooleanSupplier canHandle,BooleanSupplier exits,BooleanSupplier preview,Runnable action){
        this.activity=activity;this.window=window;this.surface=surface;this.canHandle=canHandle;this.exits=exits;this.preview=preview;this.action=action;
        if(Build.VERSION.SDK_INT>=33)surface.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayout);
        refresh();
    }
    /** Installs the blurred destination preview when a gesture begins. */
    public PredictiveBack setPreview(Runnable preview){previewStart=preview;return this;}
    /** True while the gesture is sliding out before the target page is built. */
    public boolean isLeavingByGesture(){return gestureLeaving;}
    /** Capture the physical starting edge before SystemUI takes over the touch stream. */
    public void recordTouch(MotionEvent event){
        if(closed||event.getActionMasked()!=MotionEvent.ACTION_DOWN)return;
        View decor=window.getDecorView();int[] location=new int[2];decor.getLocationOnScreen(location);
        int windowWidth=decor.getWidth();if(windowWidth<=0)windowWidth=surface.getWidth();
        float x=event.getRawX()-location[0],edge=Math.max(1f,surface.getResources().getDisplayMetrics().density)*48f;
        pointerEdge=x>=0f&&x<=edge?0:x>=windowWidth-edge&&x<=windowWidth?1:-1;
        pointerTime=event.getEventTime();
        pointerEntering=pointerEdge>=0&&PageMotion.isEntering(activity);
    }
    /** Animate a toolbar pop in the same window as its destination, then finish without a second transition. */
    public boolean animateButtonBack(Runnable finish){
        if(buttonLeaving)return true;
        if(closed||stopped||gestureLeaving||!surface.isAttachedToWindow()||!BackPreview.install(activity))return false;
        reset();buttonLeaving=true;
        BackPreview.buttonTarget(activity);
        PageMotion.backOutgoing(surface,()->{if(buttonLeaving&&!closed&&!stopped)finish.run();});
        return true;
    }
    /** Resume the owner without replacing a callback in the middle of a system gesture. */
    public void reinstall(){
        if(closed||buttonLeaving||activity.isFinishing())return;
        stopped=false;
        if(!gesturing)reset();
        refresh();
    }
    private void registerIfPossible(){
        if(unregister!=null||closed||stopped||!canHandle.getAsBoolean()||imeOwnsBack())return;
        try{unregister=Api33.register(this);}catch(RuntimeException ignored){}
    }
    private void unregisterSafely(){
        Runnable old=unregister;unregister=null;
        if(old!=null){try{old.run();}catch(RuntimeException ignored){}}
    }
    /** A focused editor can still accept text after the keyboard has been dismissed. */
    private boolean imeOwnsBack(){
        WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(surface);
        return insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime());
    }
    public void refresh(){
        if(Build.VERSION.SDK_INT<33||closed||stopped)return;
        if(gesturing||buttonLeaving)return;
        if(!canHandle.getAsBoolean()||imeOwnsBack()){unregisterSafely();reset();}
        else registerIfPossible();
    }
    private void start(boolean fromLeft,float touchY,boolean direct){
        if(closed||stopped||buttonLeaving||activity.isFinishing()||activity.isDestroyed())return;
        // A fast next gesture completes an in-place pop before previewing its new destination.
        // A finishing Activity keeps ownership until the system switches to the previous one.
        if(committed&&gesturing){if(commitExits||settling==null)return;settling.end();}
        reset();
        if(!canHandle.getAsBoolean()||!preview.getAsBoolean())return;
        gesturing=true;progress=0;left=fromLeft;enterDirect=direct;
        width=surface.getWidth();originalScaleX=surface.getScaleX();originalScaleY=surface.getScaleY();originalX=surface.getTranslationX();originalY=surface.getTranslationY();originalLayer=surface.getLayerType();originalPivotX=surface.getPivotX();originalPivotY=surface.getPivotY();originalAlpha=surface.getAlpha();gestureLeaving=false;
        density=surface.getResources().getDisplayMetrics().density;
        originalClip=surface.getClipToOutline();originalOutline=surface.getOutlineProvider();
        if(direct)return;
        if(previewStart!=null){try{previewStart.run();}catch(RuntimeException ignored){}}
        BackPreview.show();
        gestureScale=.92f;
        int surfaceHeight=surface.getHeight();
        originalClip=surface.getClipToOutline();originalOutline=surface.getOutlineProvider();
        float normalizedY=surfaceHeight>0?Math.max(-1f,Math.min(1f,(touchY-surfaceHeight/2f)/(surfaceHeight/2f))):0f;
        targetOffsetY=normalizedY*density*28f;
        maxRadius=density*32f;radius=0f;
        surface.setPivotX(width/2f);surface.setPivotY(surfaceHeight/2f);
        surface.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),radius);}});
        surface.setClipToOutline(true);
        if(surface.isHardwareAccelerated()&&originalLayer==View.LAYER_TYPE_NONE)surface.setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    private void advance(float value){
        if(!gesturing||enterDirect)return;
        progress=Math.max(0f,Math.min(1f,value));
        float scale=1f-(1f-gestureScale)*progress;
        surface.setScaleX(originalScaleX*scale);surface.setScaleY(originalScaleY*scale);
        surface.setTranslationX(originalX+previewShift(width,progress,left));
        surface.setTranslationY(originalY+targetOffsetY*progress);
        radius=maxRadius*progress;surface.invalidateOutline();
        BackPreview.setScrimAlpha(progress);
        BackPreview.setPreviewShift((left?-1f:1f)*density*12f*progress);
    }
    private void startEvent(boolean fromLeft,float touchX,float touchY){
        long age=SystemClock.uptimeMillis()-pointerTime;
        boolean physical=pointerEdge>=0&&age>=0&&age<=1500;
        boolean fromPhysical=pointerEdge==0;pointerEdge=-1;
        boolean entering=pointerEntering||PageMotion.isEntering(activity);
        pointerEntering=false;
        start(physical?fromPhysical:fromLeft,touchY,entering);
        if(gesturing&&!committed){gestureTouchX=touchX;directionLocked=physical;}
    }
    private void progressEvent(float value,float touchX){
        if(!gesturing||committed)return;
        // Some dispatchers reuse the previous swipeEdge on a fast opposite gesture. If the
        // initial DOWN was consumed by SystemUI, resolve once from actual finger movement.
        float delta=touchX-gestureTouchX;
        if(!directionLocked&&Math.abs(delta)>=Math.max(1f,density)*8f){left=delta>0f;directionLocked=true;}
        advance(value);
    }
    private void invoke(){
        if(closed||stopped||buttonLeaving||committed)return;
        if(activity.isFinishing()||activity.isDestroyed()){reset();return;}
        if(!canHandle.getAsBoolean()){reset();refresh();return;}
        if(!gesturing&&(pointerEntering||PageMotion.isEntering(activity))){pointerEntering=false;reset();return;}
        committed=true;commitExits=exits.getAsBoolean();
        if(!gesturing){pointerEdge=-1;directionLocked=false;action.run();surface.post(rearm);return;}
        gestureLeaving=true;
        if(!surface.isAttachedToWindow()){reset();committed=true;action.run();surface.post(rearm);return;}
        if(enterDirect){
            // An unfinished enter transition deliberately consumes the gesture: no shrink,
            // no preview, no navigation, so the page can never white-screen mid-entry.
            reset();committed=true;surface.post(rearm);return;
        }
        // without its finish callback must not suddenly start shrinking when the finger is lifted.
        float from=surface.getTranslationX(),to=originalX+(left?1f:-1f)*width*1.05f;
        BackPreview.fadeScrim(160);BackPreview.settlePreview(220);
        ValueAnimator animation=ValueAnimator.ofFloat(0f,1f);settling=animation;
        animation.setDuration(220);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animation.addUpdateListener(a->{if(settling==animation&&!closed)surface.setTranslationX(from+(to-from)*(float)a.getAnimatedValue());});
        animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
            if(settling!=animation||closed||stopped)return;
            settling=null;
            if(commitExits){
                // Keep gestureLeaving/committed alive until finishPage()/onStop(). The async
                // framework close must see this as the same gesture, never as a toolbar back.
                action.run();
                return;
            }
            BackPreview.hide();
            action.run();
            if(closed||activity.isFinishing()||activity.isDestroyed())return;
            reset();committed=true;
            surface.post(rearm);refresh();
        }});animation.start();
    }
    private void settle(){
        if(!gesturing)return;
        if(enterDirect){reset();refresh();return;}
        if(settling!=null){settling.removeAllListeners();settling.cancel();}
        ValueAnimator animation=ValueAnimator.ofFloat(progress,0f);settling=animation;
        animation.setDuration(220);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animation.addUpdateListener(a->{if(settling==animation&&!closed)advance((float)a.getAnimatedValue());});
        animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
            if(settling!=animation||closed)return;settling=null;reset();
            refresh();
        }});animation.start();
    }
    private void reset(){
        surface.removeCallbacks(rearm);
        if(settling!=null){ValueAnimator old=settling;settling=null;old.removeAllListeners();old.cancel();}
        committed=false;
        if(buttonLeaving){buttonLeaving=false;surface.clearAnimation();BackPreview.hide();}
        surface.animate().cancel();
        if(!gesturing)return;
        gesturing=false;progress=0;surface.setScaleX(originalScaleX);surface.setScaleY(originalScaleY);surface.setTranslationX(originalX);surface.setTranslationY(originalY);surface.setPivotX(originalPivotX);surface.setPivotY(originalPivotY);surface.setOutlineProvider(originalOutline);surface.setClipToOutline(originalClip);surface.setLayerType(originalLayer,null);surface.setAlpha(originalAlpha);radius=0;gestureLeaving=false;enterDirect=false;pointerEntering=false;BackPreview.setScrimAlpha(0f);BackPreview.setPreviewShift(0f);BackPreview.hide();
    }
    /** Release the temporary layer once a departing Activity is no longer visible. */
    public void onStop(){stopped=true;pointerEdge=-1;unregisterSafely();reset();}
    @Override public void close(){closed=true;if(Build.VERSION.SDK_INT>=33&&surface.getViewTreeObserver().isAlive())surface.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayout);unregisterSafely();reset();}
    static float previewScale(float progress){return 1f-.08f*Math.max(0f,Math.min(1f,progress));}
    static float previewShift(int width,float progress,boolean left){return Math.max(0,width)*.02f*Math.max(0f,Math.min(1f,progress))*(left?1f:-1f);}

    public static AlertDialog.Builder dialog(Activity activity){return dialog(activity,false);}
    /** Shelf menus fade their scrim in with the card so opening never flashes a dark frame. */
    public static AlertDialog.Builder dialog(Activity activity,final boolean fadeScrim){return new AlertDialog.Builder(activity){
        private boolean cancelable=true;
        @Override public AlertDialog.Builder setCancelable(boolean value){cancelable=value;return super.setCancelable(value);}
        @Override public AlertDialog create(){
            AlertDialog dialog=super.create();Window window=dialog.getWindow();Ui ui=new Ui(activity);
            android.util.TypedValue background=new android.util.TypedValue();int color=ui.card;
            if(activity.getTheme().resolveAttribute(android.R.attr.colorBackgroundFloating,background,true))color=background.resourceId!=0?activity.getColor(background.resourceId):background.data;
            window.setBackgroundDrawable(ui.shape(color,16));window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            if(Build.VERSION.SDK_INT>=28)window.setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
            if(Build.VERSION.SDK_INT>=29)window.setNavigationBarContrastEnforced(false);
            View decor=window.getDecorView();androidx.core.view.WindowCompat.getInsetsController(window,decor).setAppearanceLightNavigationBars(androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(),activity.getWindow().getDecorView()).isAppearanceLightNavigationBars());
            decor.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),ui.dp(16));}});decor.setClipToOutline(true);
            dialog.setOnShowListener(shown->{
                android.widget.ListView list=dialog.getListView();
                if(list==null)return;
                list.setDivider(null);list.setDividerHeight(ui.dp(5));
                list.post(()->{
                    int widest=ui.dp(220);
                    for(int i=0;i<list.getChildCount();i++){
                        View row=list.getChildAt(i);
                        android.graphics.drawable.GradientDrawable edge=ui.shape(android.graphics.Color.TRANSPARENT,10);
                        edge.setStroke(ui.dp(1),ui.dark?0x66ffffff:0x33000000);
                        row.setBackground(edge);row.setPadding(ui.dp(16),ui.dp(11),ui.dp(16),ui.dp(11));
                        if(row instanceof android.widget.TextView){
                            android.widget.TextView text=(android.widget.TextView)row;
                            widest=Math.max(widest,Math.round(text.getPaint().measureText(text.getText().toString()))+ui.dp(48));
                        }
                    }
                    int screen=activity.getResources().getDisplayMetrics().widthPixels;
                    dialog.getWindow().setLayout(Math.min(screen-ui.dp(40),widest),android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                });
            });
            if(!cancelable)return dialog;
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                private PredictiveBack back;private ValueAnimator scrim;private float scrimTarget;
                @Override public void onViewAttachedToWindow(View view){
                    if(fadeScrim){
                        scrimTarget=window.getAttributes().dimAmount;
                        if(scrimTarget>0f)window.setDimAmount(0f);
                    }
                    view.setAlpha(0);view.setScaleX(fadeScrim ? .96f : .97f);view.setScaleY(fadeScrim ? .96f : .97f);
                    view.post(()->{
                        if(!view.isAttachedToWindow())return;
                        if(scrimTarget>0f){
                            ValueAnimator animation=ValueAnimator.ofFloat(0f,scrimTarget);
                            scrim=animation;animation.setDuration(200);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
                            animation.addUpdateListener(a->{if(scrim==animation)window.setDimAmount((float)a.getAnimatedValue());});
                            animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(scrim==animation)window.setDimAmount(scrimTarget);}});
                            animation.start();
                        }
                        view.animate().alpha(1).scaleX(1).scaleY(1).setDuration(fadeScrim ? 180 : 150).setInterpolator(fadeScrim ? new android.view.animation.DecelerateInterpolator() : new android.view.animation.AccelerateDecelerateInterpolator()).withEndAction(()->{if(view.isAttachedToWindow()&&back==null)back=new PredictiveBack(activity,window,view,dialog::isShowing,()->true,dialog::cancel);}).start();
                    });
                }
                @Override public void onViewDetachedFromWindow(View view){if(scrim!=null){scrim.cancel();scrim=null;}if(back!=null){back.close();back=null;}}
            });return dialog;
        }
    };}

    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        static Runnable register(PredictiveBack owner){
            android.window.OnBackInvokedDispatcher dispatcher=owner.window.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback callback=Build.VERSION.SDK_INT>=34?Api34.callback(owner):owner::invoke;
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
            return ()->dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }
    @androidx.annotation.RequiresApi(34)
    private static final class Api34 {
        static android.window.OnBackAnimationCallback callback(PredictiveBack owner){return new android.window.OnBackAnimationCallback(){
            @Override public void onBackStarted(android.window.BackEvent event){if(!owner.closed)owner.startEvent(event.getSwipeEdge()==android.window.BackEvent.EDGE_LEFT,event.getTouchX(),event.getTouchY());}
            @Override public void onBackProgressed(android.window.BackEvent event){if(!owner.closed&&!owner.committed)owner.progressEvent(event.getProgress(),event.getTouchX());}
            @Override public void onBackCancelled(){if(!owner.closed&&!owner.committed)owner.settle();}
            @Override public void onBackInvoked(){owner.invoke();}
        };}
    }
}

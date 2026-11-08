package com.joe.jmlite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Outline;
import android.os.Build;
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
    private boolean closed,gesturing,left,committed;
    private float progress;
    private float originalScaleX,originalScaleY,originalX,originalY;
    private int originalLayer,width;private float gestureScale=.92f,targetOffsetY,radius,maxRadius;private float originalPivotX,originalPivotY;
    private boolean originalClip;
    private ViewOutlineProvider originalOutline;
    private final ViewTreeObserver.OnGlobalLayoutListener keyboardLayout=this::refresh;

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
    /** Re-register after the Activity returns to the foreground; some launchers drop the callback. */
    public void reinstall(){
        if(Build.VERSION.SDK_INT<33||closed||!canHandle.getAsBoolean())return;
        // Always replace the callback on resume: a launcher may have dropped it while keeping
        // our unregister token alive, and a stale token cannot tell us whether it still works.
        unregisterSafely();
        try{unregister=Api33.register(this);}catch(RuntimeException ignored){}
    }
    private void registerIfPossible(){
        if(unregister!=null||closed||!canHandle.getAsBoolean()||imeOwnsBack())return;
        try{unregister=Api33.register(this);}catch(RuntimeException ignored){}
    }
    private void unregisterSafely(){
        Runnable old=unregister;unregister=null;
        if(old!=null){try{old.run();}catch(RuntimeException ignored){}}
    }
    /** Only let the IME win while an editable field is actually focused; stale insets after resume are ignored. */
    private boolean imeOwnsBack(){
        WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(surface);
        if(insets==null||!insets.isVisible(WindowInsetsCompat.Type.ime()))return false;
        return surface.findFocus() instanceof android.widget.EditText;
    }
    public void refresh(){
        if(Build.VERSION.SDK_INT<33||closed)return;
        if(!canHandle.getAsBoolean()||imeOwnsBack()){unregisterSafely();reset();}
        else registerIfPossible();
    }
    private void start(boolean fromLeft,float touchY){
        if(committed||!canHandle.getAsBoolean()||!preview.getAsBoolean())return;
        if(previewStart!=null){try{previewStart.run();}catch(RuntimeException ignored){}}
        reset();gesturing=true;left=fromLeft;progress=0;
        width=surface.getWidth();originalScaleX=surface.getScaleX();originalScaleY=surface.getScaleY();originalX=surface.getTranslationX();originalY=surface.getTranslationY();originalLayer=surface.getLayerType();originalPivotX=surface.getPivotX();originalPivotY=surface.getPivotY();
        gestureScale=.92f;
        int surfaceHeight=surface.getHeight();
        originalClip=surface.getClipToOutline();originalOutline=surface.getOutlineProvider();
        float density=surface.getResources().getDisplayMetrics().density;
        float normalizedY=surfaceHeight>0?Math.max(-1f,Math.min(1f,(touchY-surfaceHeight/2f)/(surfaceHeight/2f))):0f;
        targetOffsetY=normalizedY*density*28f;
        maxRadius=density*32f;radius=0f;
        surface.setPivotX(width/2f);surface.setPivotY(surfaceHeight/2f);
        surface.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),radius);}});
        surface.setClipToOutline(true);
        if(surface.isHardwareAccelerated()&&originalLayer==View.LAYER_TYPE_NONE)surface.setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    private void advance(float value){
        if(!gesturing)return;
        progress=Math.max(0f,Math.min(1f,value));
        float scale=1f-(1f-gestureScale)*progress;
        surface.setScaleX(originalScaleX*scale);surface.setScaleY(originalScaleY*scale);
        surface.setTranslationX(originalX+previewShift(width,progress,left));
        surface.setTranslationY(originalY+targetOffsetY*progress);
        radius=maxRadius*progress;surface.invalidateOutline();
        BackPreview.setScrimAlpha(progress);
    }
    private void invoke(){
        if(closed||committed)return;
        if(activity.isFinishing()||activity.isDestroyed()){reset();return;}
        if(!canHandle.getAsBoolean()){reset();refresh();return;}
        if(!gesturing){committed=exits.getAsBoolean();action.run();refresh();return;}
        committed=true;
        boolean leaving=exits.getAsBoolean();
        if(leaving&&Build.VERSION.SDK_INT>=34&&window==activity.getWindow())Api34.suppressExitAnimation(activity);
        // Navigate at the current gesture position; never flash back to full size first.
        action.run();
        if(closed)return;
        if(!leaving)settle();
        refresh();
    }
    private void settle(){
        if(!gesturing)return;
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
        if(settling!=null){ValueAnimator old=settling;settling=null;old.removeAllListeners();old.cancel();}
        committed=false;
        if(!gesturing)return;
        gesturing=false;progress=0;surface.setScaleX(originalScaleX);surface.setScaleY(originalScaleY);surface.setTranslationX(originalX);surface.setTranslationY(originalY);surface.setPivotX(originalPivotX);surface.setPivotY(originalPivotY);surface.setOutlineProvider(originalOutline);surface.setClipToOutline(originalClip);surface.setLayerType(originalLayer,null);radius=0;BackPreview.setScrimAlpha(0f);
    }
    /** Release the temporary layer once a departing Activity is no longer visible. */
    public void onStop(){reset();}
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
        static void suppressExitAnimation(Activity activity){activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,0,0);}
        static android.window.OnBackAnimationCallback callback(PredictiveBack owner){return new android.window.OnBackAnimationCallback(){
            @Override public void onBackStarted(android.window.BackEvent event){if(owner.closed)return;float touchY=0f;try{touchY=event.getTouchY();}catch(RuntimeException ignored){}owner.start(event.getSwipeEdge()==android.window.BackEvent.EDGE_LEFT,touchY);}
            @Override public void onBackProgressed(android.window.BackEvent event){if(!owner.closed&&!owner.committed)owner.advance(event.getProgress());}
            @Override public void onBackCancelled(){if(!owner.closed&&!owner.committed)owner.settle();}
            @Override public void onBackInvoked(){owner.invoke();}
        };}
    }
}

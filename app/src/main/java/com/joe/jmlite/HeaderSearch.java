package com.joe.jmlite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** The same expanding title search used by source browsing. */
public final class HeaderSearch {
    private final Activity activity;
    private final Ui ui;
    private final TextView title;
    private final View companion;
    private final View searchSpace;
    private final FrameLayout box;
    private final LinearLayout editor;
    private final Ui.Icon icon;
    private final EditText input;
    private ValueAnimator animation;
    private boolean expanded,visible=true;
    private int transition;
    private Runnable expansionChanged;

    public HeaderSearch(Activity activity,LinearLayout header,TextView title,View companion,String hint,Consumer<String> onQuery){
        this.activity=activity;this.title=title;this.companion=companion;ui=new Ui(activity);
        int position=header.indexOfChild(title);LinearLayout.LayoutParams titleParams=(LinearLayout.LayoutParams)title.getLayoutParams();
        header.removeView(title);if(companion!=null)header.removeView(companion);
        // Keep the title row measured once; search expands by clipping its overlay.
        FrameLayout slot=new FrameLayout(activity);header.addView(slot,position,titleParams);
        LinearLayout resting=ui.row();slot.addView(resting,new FrameLayout.LayoutParams(-1,-1));resting.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        if(companion!=null)resting.addView(companion,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
        searchSpace=new View(activity);resting.addView(searchSpace,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
        box=new FrameLayout(activity);slot.addView(box,new FrameLayout.LayoutParams(-1,ui.dp(44),Gravity.CENTER_VERTICAL));
        icon=ui.icon("search",ui.ink);icon.setContentDescription(hint);box.addView(icon,new FrameLayout.LayoutParams(ui.dp(44),-1,Gravity.END));
        editor=ui.row();editor.setVisibility(View.GONE);
        input=new EditText(activity);input.setHint(hint);input.setSingleLine(true);input.setTextSize(15);input.setTextColor(ui.ink);input.setHintTextColor(ui.muted);input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(ui.dp(12),0,ui.dp(6),0);Ui.clearable(input);
        editor.addView(input,new LinearLayout.LayoutParams(0,-1,1));
        TextView close=ui.text("×",25,ui.muted,false);close.setGravity(Gravity.CENTER);close.setContentDescription("关闭搜索");editor.addView(close,new LinearLayout.LayoutParams(ui.dp(40),-1));
        box.addView(editor,new FrameLayout.LayoutParams(-1,-1));
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable s){}public void onTextChanged(CharSequence s,int start,int before,int count){onQuery.accept(s.toString());}});
        icon.setOnClickListener(v->toggle(true));close.setOnClickListener(v->collapse());
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){transition++;if(animation!=null){animation.cancel();animation=null;}}});
    }

    public boolean isExpanded(){return expanded||animation!=null;}
    public void setOnExpansionChanged(Runnable changed){expansionChanged=changed;}
    private void notifyExpansionChanged(){if(expansionChanged!=null)expansionChanged.run();}
    public void collapse(){if(expanded)toggle(false);}
    public void setVisible(boolean show){
        visible=show;
        if(!show){
            transition++;if(animation!=null){animation.cancel();animation=null;}expanded=false;
            input.setText("");input.clearFocus();((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
            title.setAlpha(1f);title.setVisibility(View.VISIBLE);editor.setVisibility(View.GONE);icon.setAlpha(1f);icon.setVisibility(View.VISIBLE);ui.round(box,Color.TRANSPARENT,20);box.setClipBounds(null);
        }else if(!expanded){
            // Restore the resting toolbar whenever the search is not open. A collapse that was
            // interrupted (page rebuild, tab switch) must not leave the magnifier at alpha 0.
            if(animation!=null){transition++;animation.cancel();animation=null;}
            title.setAlpha(1f);title.setVisibility(View.VISIBLE);editor.setAlpha(0f);editor.setVisibility(View.GONE);
            icon.setAlpha(1f);icon.setVisibility(View.VISIBLE);if(companion!=null)companion.setVisibility(View.VISIBLE);
            box.setClipBounds(null);
        }
        box.setVisibility(show?View.VISIBLE:View.GONE);
        searchSpace.setVisibility(show?View.VISIBLE:View.GONE);
        notifyExpansionChanged();
    }

    private void toggle(boolean expand){
        int width=box.getWidth();Rect clip=box.getClipBounds();int current=clip!=null?clip.width():expanded?width:ui.dp(44);
        expanded=expand;final int token=++transition;if(animation!=null)animation.cancel();
        title.setVisibility(View.VISIBLE);editor.setVisibility(View.VISIBLE);icon.setVisibility(View.VISIBLE);
        if(companion!=null)companion.setVisibility(View.INVISIBLE);
        int target=expand?width:ui.dp(44);
        animation=ValueAnimator.ofInt(current,target);animation.setDuration(220);animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(a->{int reveal=(int)a.getAnimatedValue();box.setClipBounds(new Rect(Math.max(0,box.getWidth()-reveal),0,box.getWidth(),box.getHeight()));float f=a.getAnimatedFraction(),progress=expand?f:1f-f;title.setAlpha(1f-progress);editor.setAlpha(progress);icon.setAlpha(1f-progress);});
        animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(token!=transition||!visible)return;animation=null;title.setAlpha(expand?0f:1f);editor.setAlpha(expand?1f:0f);icon.setAlpha(expand?0f:1f);title.setVisibility(expand?View.INVISIBLE:View.VISIBLE);editor.setVisibility(expand?View.VISIBLE:View.GONE);icon.setVisibility(expand?View.GONE:View.VISIBLE);box.setClipBounds(null);if(companion!=null&&!expand)companion.setVisibility(View.VISIBLE);if(expand&&box.isAttachedToWindow()&&!activity.isFinishing()&&!activity.isDestroyed()){input.requestFocus();((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);}notifyExpansionChanged();}});
        ui.round(box,expand?ui.tint:Color.TRANSPARENT,20);
        if(!expand){input.setText("");input.clearFocus();((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);}
        animation.start();
        notifyExpansionChanged();
    }
}

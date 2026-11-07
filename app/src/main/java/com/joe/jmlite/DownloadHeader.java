package com.joe.jmlite;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

/** ListView's deferred resize focus must return to the editor the user chose. */
final class DownloadHeader extends LinearLayout {
    private View editor;

    DownloadHeader(Context context){super(context);setOrientation(VERTICAL);setFocusableInTouchMode(true);}

    @Override public void requestChildFocus(View child,View focused){
        super.requestChildFocus(child,focused);
        if(focused instanceof EditText)editor=focused;
    }

    @Override public boolean requestFocus(int direction,Rect previouslyFocusedRect){
        if(editor!=null&&editor.isShown())return editor.requestFocus(direction,previouslyFocusedRect);
        return super.requestFocus(direction,previouslyFocusedRect);
    }
}

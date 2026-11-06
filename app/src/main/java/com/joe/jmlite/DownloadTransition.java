package com.joe.jmlite;

/** Shared geometry for the actual two-button merge and centered progress expansion. */
final class DownloadTransition {
    private DownloadTransition(){}
    private static float smooth(float value){float t=Math.max(0f,Math.min(1f,value));return t*t*(3f-2f*t);}
    static float merge(float fraction){return smooth(fraction/.42f);}
    static float expand(float fraction){return smooth((fraction-.38f)/.62f);}
    static float buttonsAlpha(float fraction){return 1f-smooth((fraction-.18f)/.24f);}
    static float panelAlpha(float fraction){return smooth((fraction-.34f)/.20f);}
    static float buttonScaleX(float fraction){return 1f-.88f*merge(fraction);}
    static float panelScaleX(float fraction){return .08f+.92f*expand(fraction);}
    static String pages(int done,int total){int count=Math.max(0,done);return total>0?Math.min(count,total)+"/"+total:count+"/…";}
}

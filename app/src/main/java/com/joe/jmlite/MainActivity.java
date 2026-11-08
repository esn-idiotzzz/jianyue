package com.joe.jmlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private Ui u;
    private Store store;
    private Library library;
    private CoverLoader covers;
    private SharedPreferences prefs;
    private final Handler handler=new Handler();
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final ArrayList<JSONObject> books=new ArrayList<>(),libraryBooks=new ArrayList<>(),bookFolders=new ArrayList<>(),rows=new ArrayList<>(),onlineBooks=new ArrayList<>();
    private final ArrayList<JSONObject> downloadRecords=new ArrayList<>();
    private LinearLayout content,taskCard;private CapsuleNav nav;
    private FrameLayout stage;
    private TextView shelfDropPill;
    private boolean shelfCapsuleHover;
    private FrameLayout actionSlot;
    private LinearLayout downloadActions;
    private TextView title,taskTitle,taskInfo,taskAction,start;
    private TextView taskCount;
    private float actionFraction;
    private boolean preparingDownload,downloadReceiverRegistered;
    private boolean forceDownloadRefresh;
    private Ui.Icon plus,back,eye;
    private HeaderSearch shelfSearch;
    private String shelfQuery="";
    private NavItem downloadNav,localNav,onlineNav,repositoryNav;
    private boolean jmHistory=false,historyLoaded=false;
    private TextView repoHint;
    private ProgressBar progress;
    private EditText input,searchInput;
    private ListView list;
    private GridView grid;
    private TextView emptyShelf;
    private BookAdapter adapter;
    private ShelfAdapter shelfAdapter;
    private OnlineAdapter onlineAdapter;
    private int tab=1,refreshGeneration=0,pendingShelfScroll=-1;
    private boolean resumed=false,reading=false,starting=false,operation=false,revealed=true,keyboardVisible;
    private String rowsSnapshot="",draft="",searchDraft="",taskId="",folderId="",highlightKey="";
    private boolean highlightScrollPending;
    private JSONObject pendingShare;
    private JSONObject draggingBook;
    private boolean shelfHoldActive;
    private final ArrayList<JSONObject> dragRows=new ArrayList<>();
    private JSONObject dragTarget;
    private int dragZone=ShelfOrder.NONE;
    private int dragGap=-1;
    private String dragHoverKey="";
    private final android.graphics.RectF dragHoverRect=new android.graphics.RectF();
    private Runnable pendingCapsuleLeave;
    private String dragPreviewSignature="";
    private Bitmap dragCover;
    private final HashMap<String,Integer> dragPreviewPositions=new HashMap<>();
    private float dragPointerX,dragPointerY;
    /** Constant-speed edge scrolling keeps the landing gap moving instead of jumping a row at a time. */
    private final Runnable shelfAutoScroll=new Runnable(){public void run(){
        if(draggingBook==null||grid==null||!resumed)return;
        int edge=u.dp(56),direction=dragPointerY<edge?-1:dragPointerY>grid.getHeight()-edge?1:0;
        if(direction==0||!grid.canScrollVertically(direction))return;
        grid.scrollBy(0,direction*u.dp(5));dragHoverKey="";dragHoverRect.setEmpty();previewShelfDrag(dragPointerX,dragPointerY);handler.postDelayed(this,16);
    }};
    private boolean taskShown;
    private Runnable pendingShelfMenu;
    private static final int SHELF_MENU_DELAY=90,SHELF_TILE_SETTLE=190;
    /** One timing and one scale for every drag hover so books and folders feel identical. */
    private static final int DRAG_TARGET_MS=170,DRAG_PREVIEW_MS=250,DRAG_NAME_MS=120,FOLDER_PREVIEW_DEPTH=3,DRAG_END_SETTLE_MS=520;
    /** A gentle fast-out curve makes the push and fill read as one calm movement. */
    private static final android.view.animation.PathInterpolator DRAG_PREVIEW_INTERPOLATOR=new android.view.animation.PathInterpolator(0.32f,0f,0.2f,1f);
    private final Runnable dragEndSettle=new Runnable(){public void run(){if(draggingBook==null)clearShelfPreview();}};
    /** A short edge dwell keeps a quick pass from pushing a book the user means to merge with. */
    private static final int SHELF_PUSH_DWELL=90;
    private String pendingPushKey="";private int pendingPushZone=ShelfOrder.NONE;private long pendingPushAt;
    private final Runnable shelfPushPreview=new Runnable(){public void run(){if(draggingBook!=null)previewShelfDrag(dragPointerX,dragPointerY);}};
    private static final float DRAG_TARGET_SCALE=.96f,DRAG_PREVIEW_SCALE=.93f;
    private ValueAnimator actionAnimator;private View tabOutgoing;private int tab1Scroll,tab2Scroll;private List<JSONObject> normalBooksCache,normalFoldersCache;private FolderCoverIndex normalCoversCache;
    private FirstRunGuide firstRunGuide;
    private boolean privateShelf;
    private Boolean navVisibleTarget;
    private int navigationInset;private boolean tabletDevice;private int folderPreviewDepth;
    private FolderCoverIndex folderCovers=FolderCoverIndex.from(java.util.Collections.emptyList());
    private PredictiveBack predictiveBack;
    private TextView localComicHint;
    private Runnable localComicHintDismiss;
    private final Runnable poll=new Runnable(){public void run(){refresh();if(resumed&&DownloadService.running)handler.postDelayed(this,1800);}};
    private final Runnable downloadRefresh=()->{boolean force=forceDownloadRefresh;forceDownloadRefresh=false;if(resumed)refresh(force);};
    private final BroadcastReceiver downloadChanges=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!resumed||!DownloadService.ACTION_CHANGED.equals(intent.getAction()))return;
            String state=intent.getStringExtra(DownloadService.EXTRA_STATUS);
            boolean terminal="complete".equals(state)||"paused".equals(state)||"error".equals(state)||!DownloadService.running;
            if(terminal&&taskId.equals(intent.getStringExtra(DownloadService.EXTRA_ID)))preparingDownload=false;
            forceDownloadRefresh|=terminal;handler.removeCallbacks(downloadRefresh);handler.postDelayed(downloadRefresh,forceDownloadRefresh?0:70);
        }
    };

    @Override public void onCreate(Bundle b){
        if(b==null&&isTaskRoot())PrivacyVault.resetSession();
        super.onCreate(b);tabletDevice=AdaptiveLayout.isTabletDevice(this);u=new Ui(this);store=new Store(this);library=new Library(this);covers=new CoverLoader(this);prefs=getSharedPreferences("settings",MODE_PRIVATE);revealed=prefs.getBoolean("history_revealed",true);if(prefs.contains("tablet_download_shrink_dp"))prefs.edit().remove("tablet_download_shrink_dp").apply();
        if(b!=null){tab=b.getInt("tab",1);draft=b.getString("draft","");searchDraft=b.getString("search","");folderId=b.getString("folder","");taskId=b.getString("task_id","");pendingShelfScroll=b.getInt("shelf_scroll",-1);}
        library.preserveFolder(folderId);
        LinearLayout root=u.column();root.setBackgroundColor(u.bg);Ui.insets(this,root,u.dark);
        LinearLayout heading=u.row();heading.setPadding(u.dp(16),u.dp(8),u.dp(12),u.dp(12));
        back=u.icon("back",u.ink);back.setContentDescription("返回书架");heading.addView(back,new LinearLayout.LayoutParams(u.dp(40),u.dp(44)));back.setOnClickListener(v->leaveFolder());
        title=u.text("简阅",23,u.ink,true);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(u.dp(4),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,u.dp(48),1));title.setGravity(Gravity.CENTER_VERTICAL);
        plus=u.icon("add",u.ink);plus.setContentDescription("导入漫画");heading.addView(plus,new LinearLayout.LayoutParams(u.dp(44),u.dp(44)));plus.setOnClickListener(v->importMenu());
        repoHint=u.text("<不行请尝试魔法~>",11,u.muted,false);repoHint.setContentDescription("关于简阅与开源许可");repoHint.setPadding(0,u.dp(12),0,u.dp(12));repoHint.setOnClickListener(v->license());heading.addView(repoHint);
        shelfSearch=new HeaderSearch(this,heading,title,plus,"搜索书架编号或名称",query->{shelfQuery=query;rowsSnapshot="";if(tab==1)render();});root.addView(heading);
        stage=new FrameLayout(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));content=u.column();stage.addView(content,new FrameLayout.LayoutParams(-1,-1));
        nav=new CapsuleNav();
        downloadNav=new NavItem(0,"download","下载");localNav=new NavItem(1,"shelf","书架");onlineNav=new NavItem(2,"history","历史");repositoryNav=new NavItem(3,"repository","仓库");
        nav.addView(localNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));nav.addView(downloadNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));nav.addView(onlineNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));nav.addView(repositoryNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));FrameLayout.LayoutParams capsule=new FrameLayout.LayoutParams(-2,u.dp(58),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);capsule.bottomMargin=u.dp(4);stage.addView(nav,capsule);nav.setOnDragListener((v,e)->shelfCapsuleDrag(e));setContentView(root);ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{Insets bars=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());boolean keyboard=insets.isVisible(WindowInsetsCompat.Type.ime());navigationInset=insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;int imeBottom=keyboard?insets.getInsets(WindowInsetsCompat.Type.ime()).bottom:0;v.setPadding(bars.left,bars.top,bars.right,imeBottom);FrameLayout.LayoutParams navParams=(FrameLayout.LayoutParams)nav.getLayoutParams();int margin=u.dp(4)+navigationInset;if(navParams.bottomMargin!=margin){navParams.bottomMargin=margin;nav.setLayoutParams(navParams);}if(shelfDropPill!=null){FrameLayout.LayoutParams pillParams=(FrameLayout.LayoutParams)shelfDropPill.getLayoutParams();if(pillParams.bottomMargin!=margin){pillParams.bottomMargin=margin;shelfDropPill.setLayoutParams(pillParams);}}if(grid!=null&&tab!=0){int padding=u.dp(privateShelf?20:88)+navigationInset;if(grid.getPaddingBottom()!=padding)grid.setPadding(grid.getPaddingLeft(),grid.getPaddingTop(),grid.getPaddingRight(),padding);}if(list!=null&&tab==0){int padding=u.dp(88)+navigationInset;if(list.getPaddingBottom()!=padding)list.setPadding(list.getPaddingLeft(),list.getPaddingTop(),list.getPaddingRight(),padding);}keyboardVisible=keyboard;updateNavVisibility();return insets;});
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{if(isFinishing()||isDestroyed())return;Rect visible=new Rect();root.getWindowVisibleDisplayFrame(visible);boolean keyboard=root.getRootView().getHeight()-visible.bottom>u.dp(120);boolean wasKeyboard=keyboardVisible;keyboardVisible=keyboard;if(wasKeyboard&&!keyboard)clearEditorCursor();updateNavVisibility();});
        buildTab();
        firstRunGuide=new FirstRunGuide(this,prefs,()->{if(predictiveBack!=null)predictiveBack.refresh();ForegroundUpdates.checkAfterGuide();});
        predictiveBack=new PredictiveBack(this,root,()->firstRunGuide==null||!firstRunGuide.isShowing(),()->!shelfSearch.isExpanded()&&(tab!=1||(!privateShelf&&folderId.isEmpty())),()->!shelfSearch.isExpanded()&&tab==1&&(privateShelf||!folderId.isEmpty()),this::onBackPressed);predictiveBack.setPreview(()->BackPreview.install(this));
        handleShelfJump(getIntent());
        shelfSearch.setOnExpansionChanged(()->{if(privateShelf)plus.setVisibility(View.GONE);if(predictiveBack!=null)predictiveBack.refresh();});
    }
    private final class NavItem extends LinearLayout {
        final Ui.Icon icon;final TextView label;final int index;
        NavItem(int index,String kind,String text){
            super(MainActivity.this);this.index=index;setOrientation(VERTICAL);setGravity(Gravity.CENTER);setPadding(0,u.dp(3),0,u.dp(3));
            icon=u.icon(kind,u.muted);icon.glyphScale=.8f;icon.setMinimumWidth(0);icon.setMinimumHeight(0);addView(icon,new LinearLayout.LayoutParams(u.dp(22),u.dp(22)));
            label=u.text(text,10,u.muted,false);label.setGravity(Gravity.CENTER);label.setIncludeFontPadding(false);label.setPadding(0,u.dp(2),0,0);addView(label,new LinearLayout.LayoutParams(-1,u.dp(14)));
            u.round(this,Color.TRANSPARENT,23);
            setOnTouchListener((v,e)->{int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN)animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)animate().scaleX(1f).scaleY(1f).setDuration(130).start();return false;});
            setContentDescription(text);setOnClickListener(v->switchTab(index));
        }
        void active(boolean on){icon.color(on?Ui.BLUE:u.muted);label.setTextColor(on?Ui.BLUE:u.muted);setSelected(on);if(on&&nav!=null)nav.select(index);}
    }
    /** Lightweight HyperOS-style capsule with a selected pill that glides between tabs. */
    private final class CapsuleNav extends LinearLayout {
        private final Paint pillPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private int activeIndex=-1;
        private float pillLeft,pillWidth;
        private ValueAnimator pillAnimator;
        CapsuleNav(){
            super(MainActivity.this);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setClickable(true);
            setPadding(u.dp(8),u.dp(6),u.dp(8),u.dp(6));
            GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{u.dark?0xF224252B:0xFAFFFFFF,u.dark?0xE61B1C22:0xF2FFFFFF});
            bg.setCornerRadius(u.dp(30));bg.setStroke(u.dp(1),u.dark?0x33FFFFFF:0x1A18202C);
            setBackground(bg);setElevation(u.dp(13));setClipToOutline(true);
            pillPaint.setColor(u.dark?0x402F6FEA:0x263478F6);
        }
        void select(int index){
            if(index<0||index==activeIndex)return;
            activeIndex=index;View child=childFor(index);
            if(child==null||getWidth()==0){post(this::snapPill);return;}
            float targetLeft=child.getLeft(),targetWidth=child.getWidth();
            if(pillWidth<=0){pillLeft=targetLeft;pillWidth=targetWidth;invalidate();return;}
            if(pillAnimator!=null)pillAnimator.cancel();
            final float startLeft=pillLeft,startWidth=pillWidth;
            ValueAnimator animation=ValueAnimator.ofFloat(0f,1f);pillAnimator=animation;
            animation.setDuration(260);animation.setInterpolator(new DecelerateInterpolator());
            animation.addUpdateListener(a->{float f=(float)a.getAnimatedValue();pillLeft=startLeft+(targetLeft-startLeft)*f;pillWidth=startWidth+(targetWidth-startWidth)*f;invalidate();});
            animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(pillAnimator==animation)pillAnimator=null;}});
            animation.start();
        }
        private View childFor(int index){for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child instanceof NavItem&&((NavItem)child).index==index)return child;}return null;}
        private void snapPill(){
            if(activeIndex<0)return;View child=childFor(activeIndex);if(child==null)return;
            pillLeft=child.getLeft();pillWidth=child.getWidth();invalidate();
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            super.onLayout(changed,left,top,right,bottom);
            if(pillAnimator==null||!pillAnimator.isRunning())snapPill();
        }
        @Override protected void dispatchDraw(Canvas canvas){
            if(pillWidth>0){
                View child=childFor(activeIndex);
                float top=child!=null?child.getTop()+u.dp(3):u.dp(3);
                float bottom=child!=null?child.getBottom()-u.dp(3):getHeight()-u.dp(3);
                canvas.drawRoundRect(pillLeft,top,pillLeft+pillWidth,bottom,u.dp(23),u.dp(23),pillPaint);
            }
            super.dispatchDraw(canvas);
        }
    }    private void switchTab(int next){
        if(tab==next&&!privateShelf)return;
        final int from=tab;
        finishTabTransition();
        final String leaving=folderId;final Library leavingLibrary=library;
        while(folderPreviewDepth>0){folderPreviewDepth--;BackPreview.pop();}
        privateShelf=false;library=new Library(this);library.preserveFolder("");
        if(input!=null)draft=input.getText().toString();if(searchInput!=null)searchDraft=searchInput.getText().toString();
        if(tab==1&&grid!=null)tab1Scroll=grid.getFirstVisiblePosition();
        if(tab==2&&grid!=null)tab2Scroll=grid.getFirstVisiblePosition();
        shelfSearch.collapse();tab=next;folderId="";if(tab==2){onlineBooks.clear();rows.clear();}
        final int direction=tabVisualOrder(next)>tabVisualOrder(from)?1:-1;
        transitionContent(direction,this::buildTab,true);
        final int restoreScroll=next==1?tab1Scroll:next==2?tab2Scroll:0;
        if(restoreScroll>0&&grid!=null){
            grid.post(()->{if(grid!=null&&restoreScroll<grid.getCount())grid.setSelectionFromTop(restoreScroll,0);});
        }
        releaseFolderInBackground(leavingLibrary,leaving,false);
    }
    private int tabVisualOrder(int value){return value==1?0:value==0?1:value==2?2:3;}
    /** Shared page change: old page leaves, new page arrives; direction < 0 uses the back pop. */
    private void transitionContent(int direction,Runnable build){transitionContent(direction,build,false);}
    /** targetOnTop is used by tab switches; in-window back keeps the real target as stable bottom. */
    private void transitionContent(int direction,Runnable build,boolean targetOnTop){
        PageMotion.clearEntering(this);
        finishTabTransition();
        // The incoming view is the real destination; never leave its frozen snapshot behind it.
        BackPreview.hide();
        final LinearLayout previous=content;
        final LinearLayout incoming=u.column();
        final FrameLayout.LayoutParams match=new FrameLayout.LayoutParams(-1,-1);
        if(!targetOnTop&&direction<0){
            // Back within the same window: real target stays as the stable bottom layer.
            stage.addView(incoming,Math.max(0,stage.indexOfChild(previous)),match);
        }else{
            stage.addView(incoming,Math.max(0,stage.indexOfChild(nav)),match);
        }
        content=incoming;
        build.run();
        tabOutgoing=previous;
        if(!targetOnTop&&direction<0){
            incoming.setAlpha(1f);incoming.setTranslationX(0f);
            PageMotion.backOutgoing(previous,()->previous.post(()->{if(tabOutgoing==previous){stage.removeView(previous);tabOutgoing=null;}}));
        }else{
            final int distance=u.dp(24);
            incoming.setAlpha(0f);incoming.setTranslationX(direction*distance);
            previous.animate().alpha(0f).translationX(-direction*distance).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
            incoming.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(new DecelerateInterpolator()).withEndAction(()->{
                if(tabOutgoing==previous){stage.removeView(previous);tabOutgoing=null;}
            }).start();
        }
    }
    private void finishTabTransition(){
        if(tabOutgoing!=null){stage.removeView(tabOutgoing);tabOutgoing=null;}
        if(content!=null){content.clearAnimation();content.animate().cancel();content.setAlpha(1f);content.setTranslationX(0f);}
    }    private void buildTab(){
        finishTabTransition();
        if(tab==1&&!folderId.isEmpty())shelfSearch.collapse();
        cancelShelfMenu();shelfHoldActive=false;hideShelfDropPill();
        if(actionAnimator!=null){actionAnimator.cancel();actionAnimator=null;}
        content.removeAllViews();input=null;searchInput=null;taskCard=null;actionSlot=null;downloadActions=null;taskAction=null;taskShown=false;list=null;grid=null;adapter=null;shelfAdapter=null;onlineAdapter=null;rowsSnapshot="";
        shelfSearch.setVisible(tab==1);title.setText(tab==0?"下载":tab==1?folderTitle():tab==3?"仓库":"历史");plus.setVisibility(tab==1&&!privateShelf&&!shelfSearch.isExpanded()?View.VISIBLE:View.GONE);back.setVisibility(tab==1&&(privateShelf||!folderId.isEmpty())?View.VISIBLE:View.GONE);
        repoHint.setVisibility(tab==3?View.VISIBLE:View.GONE);repositoryNav.active(tab==3);downloadNav.active(tab==0);localNav.active(tab==1);onlineNav.active(tab==2);
        if(tab==0)buildDownload();else if(tab==1)buildShelf();else if(tab==3)content.addView(RepositoryPanel.create(this),new LinearLayout.LayoutParams(-1,-1));else buildOnline();render();
        if(tab==2&&resumed)refresh(true);
        if(predictiveBack!=null)predictiveBack.refresh();
        updateNavVisibility();
    }
    private boolean navAllowed(){return !keyboardVisible&&!privateShelf&&!(tab==1&&!folderId.isEmpty());}
    private void clearEditorCursor(){
        View focus=getCurrentFocus();
        if(focus instanceof EditText)((EditText)focus).clearFocus();
    }
    private void updateNavVisibility(){
        if(nav==null)return;
        // The stage is already inset above the keyboard; hide before the first keyboard frame
        // instead of animating the capsule up over the IME and then fading it out.
        if(keyboardVisible){navVisibleTarget=false;nav.animate().cancel();nav.animate().withEndAction(null);nav.setVisibility(View.GONE);return;}
        boolean show=navAllowed();
        if(navVisibleTarget!=null&&navVisibleTarget==show)return;
        boolean initial=navVisibleTarget==null;navVisibleTarget=show;
        nav.animate().cancel();nav.animate().withEndAction(null);
        if(initial){nav.setAlpha(show?1f:0f);nav.setTranslationY(show?0f:u.dp(10));nav.setVisibility(show?View.VISIBLE:View.GONE);return;}
        if(show){
            if(nav.getVisibility()!=View.VISIBLE){nav.setAlpha(0f);nav.setTranslationY(u.dp(10));nav.setVisibility(View.VISIBLE);}
            nav.animate().alpha(1f).translationY(0f).setDuration(190).setInterpolator(new DecelerateInterpolator()).start();
        }else if(nav.getVisibility()==View.VISIBLE){
            nav.animate().alpha(0f).translationY(u.dp(10)).setDuration(170).setInterpolator(new DecelerateInterpolator()).withEndAction(()->{if(!navAllowed())nav.setVisibility(View.GONE);}).start();
        }
    }
    private void buildDownload(){
        list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);list.setPadding(u.dp(20),0,u.dp(20),u.dp(88)+navigationInset);
        list.setItemsCanFocus(true);
        AdaptiveLayout.capContentWidth(list,AdaptiveLayout.DOWNLOAD_LIST_PADDING_DP,AdaptiveLayout.DOWNLOAD_LIST_MAX_WIDTH_DP);
        LinearLayout header=new DownloadHeader(this),card=u.column();card.setPadding(u.dp(18),u.dp(20),u.dp(18),u.dp(18));u.round(card,u.card,22);card.addView(u.text("JM Comic",18,u.ink,true));u.space(card,16);
        input=new EditText(this);input.setId(1001);input.setSingleLine(true);input.setTextSize(15);input.setTextColor(u.ink);input.setHintTextColor(u.muted);input.setHint("输入 JM 编号或链接");input.setText(draft);input.setPadding(u.dp(15),0,u.dp(15),0);input.setSelectAllOnFocus(true);u.round(input,u.bg,14);card.addView(input,new LinearLayout.LayoutParams(-1,u.dp(52)));u.space(card,14);
        actionSlot=new FrameLayout(this);downloadActions=u.row();start=u.button("开始下载",true);start.setTextSize(14);start.setMinHeight(u.dp(48));start.setId(1002);start.setOnClickListener(v->download());LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(0,u.dp(48),1);d.setMargins(0,0,u.dp(10),0);downloadActions.addView(start,d);TextView online=u.button("在线阅读",false);online.setTextSize(14);online.setMinHeight(u.dp(48));online.setId(1003);online.setOnClickListener(v->online());downloadActions.addView(online,new LinearLayout.LayoutParams(0,u.dp(48),1));actionSlot.addView(downloadActions,new FrameLayout.LayoutParams(-1,u.dp(48)));card.addView(actionSlot,new LinearLayout.LayoutParams(-1,-2));header.addView(card);u.space(header,12);
        Ui.clearable(input);
        input.setOnClickListener(v->v.requestFocus());
        LinearLayout searchCard=u.row();searchCard.setPadding(u.dp(10),0,u.dp(8),0);u.round(searchCard,u.card,18);
        Ui.Icon find=u.icon("search",u.muted);find.glyphScale=.8f;find.setMinimumWidth(0);find.setMinimumHeight(0);searchCard.addView(find,new LinearLayout.LayoutParams(u.dp(30),u.dp(54)));
        searchInput=new EditText(this);searchInput.setId(1006);searchInput.setSingleLine(true);searchInput.setTextSize(15);searchInput.setTextColor(u.ink);searchInput.setHintTextColor(u.muted);searchInput.setHint("搜索全网漫画");searchInput.setText(searchDraft);searchInput.setBackgroundColor(Color.TRANSPARENT);searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);searchInput.setPadding(u.dp(6),0,u.dp(6),0);
        Ui.magicWandHint(searchInput);Ui.clearable(searchInput);
        searchInput.setOnClickListener(v->v.requestFocus());
        searchInput.setOnEditorActionListener((v,actionId,event)->{if(actionId==EditorInfo.IME_ACTION_SEARCH){searchOnline();return true;}return false;});
        if(tabletDevice){View.OnFocusChangeListener keepJmInputVisible=(v,hasFocus)->{if(!hasFocus||list==null)return;list.post(()->{if(list!=null)list.setSelectionFromTop(0,0);});list.postDelayed(()->{if(list!=null&&v.hasFocus())list.setSelectionFromTop(0,0);},180);};input.setOnFocusChangeListener(keepJmInputVisible);}
        searchCard.addView(searchInput,new LinearLayout.LayoutParams(0,u.dp(54),1));
        TextView searchGo=u.text("搜索",13,Color.WHITE,true);searchGo.setGravity(Gravity.CENTER);u.round(searchGo,Ui.BLUE,14);searchGo.setPadding(u.dp(14),0,u.dp(14),0);searchGo.setOnClickListener(v->searchOnline());
        LinearLayout.LayoutParams searchGoParams=new LinearLayout.LayoutParams(u.dp(56),u.dp(36));searchGoParams.setMargins(0,0,u.dp(2),0);searchCard.addView(searchGo,searchGoParams);
        header.addView(searchCard,new LinearLayout.LayoutParams(-1,u.dp(56)));u.space(header,12);
        taskCard=u.column();taskCard.setPadding(u.dp(16),u.dp(13),u.dp(16),u.dp(14));
        GradientDrawable panel=u.shape(u.dark?0xff182b47:0xffedf5ff,18);panel.setStroke(u.dp(1),u.dark?0x553478f6:0x243478f6);taskCard.setBackground(panel);
        LinearLayout status=u.row();taskTitle=u.text("正在准备下载",14,u.ink,true);status.addView(taskTitle,new LinearLayout.LayoutParams(0,-2,1));
        taskCount=u.text("0/…",18,Ui.BLUE,true);taskCount.setFontFeatureSettings("tnum");taskCount.setContentDescription("已下载页数/总页数");
        LinearLayout.LayoutParams countParams=new LinearLayout.LayoutParams(-2,-2);countParams.rightMargin=u.dp(12);status.addView(taskCount,countParams);
        taskAction=u.text("暂停",12,Ui.BLUE,true);taskAction.setGravity(Gravity.CENTER);taskAction.setPadding(u.dp(11),u.dp(7),u.dp(11),u.dp(7));u.round(taskAction,u.dark?0xff243e63:Color.WHITE,13);taskAction.setOnClickListener(v->onTaskAction());status.addView(taskAction);taskCard.addView(status);
        u.space(taskCard,7);taskInfo=u.text("正在获取章节与页数",11,u.muted,false);taskInfo.setMaxLines(2);taskInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);taskCard.addView(taskInfo);
        u.space(taskCard,10);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        GradientDrawable track=u.shape(u.dark?0xff2b4568:0xffd6e6fb,3);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Ui.BLUE,0xff65a8ff});fill.setCornerRadius(u.dp(3));
        android.graphics.drawable.LayerDrawable bar=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{track,new android.graphics.drawable.ClipDrawable(fill,Gravity.LEFT,android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        bar.setId(0,android.R.id.background);bar.setId(1,android.R.id.progress);progress.setProgressDrawable(bar);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        taskCard.addView(progress,new LinearLayout.LayoutParams(-1,u.dp(6)));taskCard.setVisibility(View.GONE);taskCard.setAlpha(0f);actionFraction=0f;actionSlot.addView(taskCard,new FrameLayout.LayoutParams(-1,-2));
        LinearLayout caption=u.row();caption.setPadding(u.dp(2),u.dp(12),0,u.dp(6));TextView recent=u.text("最近下载  ›",16,u.ink,true);recent.setContentDescription("查看全部下载记录");recent.setPadding(0,u.dp(10),0,u.dp(10));recent.setOnClickListener(v->{BackPreview.capture(getWindow().getDecorView());startActivity(new Intent(this,AllDownloadsActivity.class));});caption.addView(recent,new LinearLayout.LayoutParams(0,-2,1));eye=u.icon(revealed?"eye":"eye-off",u.muted);eye.setId(1004);eye.setContentDescription(revealed?"隐藏封面和书名":"显示封面和书名");caption.addView(eye,new LinearLayout.LayoutParams(u.dp(40),u.dp(40)));eye.setOnClickListener(v->togglePrivacy());header.addView(caption);
        header.requestFocus();list.addHeaderView(header,null,false);adapter=new BookAdapter();list.setAdapter(adapter);list.setOnItemClickListener((p,v,pos,id)->{int n=pos-list.getHeaderViewsCount();if(n>=0&&n<rows.size())open(rows.get(n));});list.setOnItemLongClickListener((p,v,pos,id)->{int n=pos-list.getHeaderViewsCount();if(n<0||n>=rows.size()||rows.get(n).optBoolean("empty"))return false;manage(rows.get(n));return true;});content.addView(list,new LinearLayout.LayoutParams(-1,-1));
    }
    private void buildShelf(){
        PrivacyPullHost frame=new PrivacyPullHost(this);final int windowDp=AdaptiveLayout.windowWidthDp(this);grid=new AdaptiveGridView(this,(contentDp,widthDp)->AdaptiveLayout.shelfColumns(widthDp,contentDp,tabletDevice));grid.setId(1005);grid.setNumColumns(AdaptiveLayout.shelfColumns(windowDp,windowDp-AdaptiveLayout.SHELF_PAGE_PADDING_DP*2,tabletDevice));grid.setHorizontalSpacing(u.dp(AdaptiveLayout.SHELF_SPACING_DP));grid.setVerticalSpacing(u.dp(AdaptiveLayout.SHELF_VERTICAL_SPACING_DP));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setPadding(u.dp(AdaptiveLayout.SHELF_PAGE_PADDING_DP),u.dp(4),u.dp(AdaptiveLayout.SHELF_PAGE_PADDING_DP),u.dp(privateShelf?20:88)+navigationInset);grid.setClipToPadding(false);grid.setSelector(android.R.color.transparent);shelfAdapter=new ShelfAdapter();grid.setAdapter(shelfAdapter);frame.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        emptyShelf=u.text(tab==2?"暂无浏览记录":"书架还空着",15,u.muted,false);emptyShelf.setVisibility(tab==2?View.GONE:View.VISIBLE);emptyShelf.setGravity(Gravity.CENTER);frame.addView(emptyShelf,new FrameLayout.LayoutParams(-1,-1));emptyShelf.setOnClickListener(null);emptyShelf.setClickable(false);if(tab==1&&!privateShelf)attachPrivacyPull(frame);content.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        grid.setClipChildren(false);
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){
            @Override public void onScrollStateChanged(AbsListView view,int scrollState){
                if(scrollState==AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL||scrollState==AbsListView.OnScrollListener.SCROLL_STATE_FLING)clearShelfHighlight();
            }
            @Override public void onScroll(AbsListView view,int firstVisible,int visible,int total){}
        });
        grid.setOnDragListener((v,e)->{
            if(draggingBook==null)return false;
            switch(e.getAction()){
                case DragEvent.ACTION_DRAG_LOCATION:
                    dragPointerX=e.getX();dragPointerY=e.getY();previewShelfDrag(dragPointerX,dragPointerY);handler.removeCallbacks(shelfAutoScroll);handler.post(shelfAutoScroll);return true;
                case DragEvent.ACTION_DRAG_EXITED:handler.removeCallbacks(shelfAutoScroll);clearShelfPreview();return true;
                case DragEvent.ACTION_DROP:
                    previewShelfDrag(e.getX(),e.getY(),true);
                    if(dragTarget==null||dragZone==ShelfOrder.NONE||operation)return false;
                    drop(dragTarget,draggingBook,dragZone);return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    handler.removeCallbacks(shelfAutoScroll);
                    hideShelfDropPill();
                    JSONObject item=draggingBook;
                    boolean placed=e.getResult();
                    draggingBook=null;dragRows.clear();dragCover=null;dragPreviewPositions.clear();dragPreviewSignature="";dragGap=-1;dragHoverKey="";dragHoverRect.setEmpty();clearPendingPush();
                    if(placed){
                        // The mutation owns the refreshed layout; keep the landing preview a moment so the
                        // already animated tiles never slide back to their old slots before the write lands.
                        dragTarget=null;dragZone=ShelfOrder.NONE;rowsSnapshot="";
                        handler.removeCallbacks(dragEndSettle);handler.postDelayed(dragEndSettle,DRAG_END_SETTLE_MS);
                    }else{
                        handler.removeCallbacks(dragEndSettle);clearShelfPreview();restoreShelfTile(item);
                    }
                    Runnable capsuleLeave=pendingCapsuleLeave;pendingCapsuleLeave=null;
                    if(capsuleLeave!=null){handler.removeCallbacks(capsuleLeave);capsuleLeave.run();}
                    return true;
                default:return true;
            }
        });
    }
    private String folderTitle(){for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id")))return f.optString("title","书籍夹");return privateShelf?"隐私书架":"书架";}
    private void leaveFolder(){
        PageMotion.clearEntering(this);
        boolean gesture=predictiveBack!=null&&predictiveBack.isLeavingByGesture();
        if(folderId.isEmpty()&&privateShelf){if(gesture)setPrivateShelf(false);else transitionContent(-1,()->setPrivateShelf(false));return;}
        String leaving=folderId;String parent="";for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id")))parent=f.optString("parent");
        folderId=parent;library.preserveFolder(parent);
        boolean optimistic=collapseFolderInMemory(leaving);
        if(gesture)buildTab();else transitionContent(-1,this::buildTab);releaseFolderInBackground(library,leaving,optimistic);if(folderPreviewDepth>0){folderPreviewDepth--;BackPreview.pop();}
    }
    /** Apply the pending collapse before the parent shelf is drawn, so the folder never flashes. */
    private boolean collapseFolderInMemory(String folderId){
        HashSet<String> ids=shelfFolderTree(folderId);if(ids==null)return false;
        JSONObject root=null;for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id"))){root=f;break;}
        if(root==null||Library.isProtectedFolder(root)||shelfFolderTreeProtected(ids))return false;
        ArrayList<JSONObject> books=new ArrayList<>();for(JSONObject b:libraryBooks)if(ids.contains(b.optString("folder")))books.add(b);
        if(books.size()>1)return false;
        String parent=root.optString("parent");
        if(books.size()==1){JSONObject b=books.get(0);try{b.put("folder",parent);}catch(Exception ignored){}libraryBooks.remove(b);libraryBooks.add(b);}
        bookFolders.removeIf(f->ids.contains(f.optString("id")));
        return true;
    }
    private void releaseFolderInBackground(Library lib,String folderId,boolean optimisticChanged){
        if(folderId==null||folderId.isEmpty())return;
        disk.execute(()->{boolean changed=false;try{changed=lib.releasePreservedFolder(folderId);}catch(Exception ignored){}
            if(changed||optimisticChanged)runOnUiThread(()->{if(resumed&&!isDestroyed())refresh(true);});});
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleShelfJump(intent);}
    private void handleShelfJump(Intent intent){String key=intent.getStringExtra("shelf_jump");if(key==null)return;intent.removeExtra("shelf_jump");String highlight=intent.getStringExtra("shelf_highlight");intent.removeExtra("shelf_highlight");if(highlight==null||highlight.isEmpty())clearShelfHighlight();Runnable jump=()->disk.execute(()->{try{JSONObject book=new Library(this).find(key);runOnUiThread(()->{if(book==null||isDestroyed())return;Runnable navigate=()->{tab=1;shelfSearch.collapse();shelfQuery="";setPrivateShelf(book.optBoolean("privacy"));folderId=book.optString("folder");library.preserveFolder(folderId);highlightKey=highlight==null?"":highlight;highlightScrollPending=!highlightKey.isEmpty();buildTab();refresh(true);};if(book.optBoolean("privacy"))PrivacyVault.enter(this,navigate);else navigate.run();});}catch(Exception e){runOnUiThread(()->toast("书籍已不存在"));}});if(intent.getBooleanExtra("shelf_private",false))PrivacyVault.enter(this,jump);else jump.run();}
    private void setPrivateShelf(boolean hidden){
        while(folderPreviewDepth>0){folderPreviewDepth--;BackPreview.pop();}
        if(hidden&&!privateShelf){
            // Keep the real normal shelf as the return target; no empty rebuild while the disk read runs.
            normalBooksCache=new ArrayList<>(libraryBooks);
            normalFoldersCache=new ArrayList<>(bookFolders);
            normalCoversCache=folderCovers;
            BackPreview.capture(getWindow().getDecorView(),nav);
            folderPreviewDepth++;
        }
        privateShelf=hidden;library=new Library(this,hidden);library.preserveFolder("");folderId="";shelfSearch.collapse();
        if(hidden){
            libraryBooks.clear();folderCovers=FolderCoverIndex.from(java.util.Collections.emptyList());bookFolders.clear();
        }else if(normalBooksCache!=null){
            libraryBooks.clear();libraryBooks.addAll(normalBooksCache);
            bookFolders.clear();bookFolders.addAll(normalFoldersCache);
            if(normalCoversCache!=null)folderCovers=normalCoversCache;
        }else{
            // Cold-start jump into privacy: load the normal shelf before the first frame.
            try{
                Library normal=new Library(this,false);
                List<JSONObject> books=normal.books(),folders=normal.folders();
                libraryBooks.clear();libraryBooks.addAll(books);
                bookFolders.clear();bookFolders.addAll(folders);
                folderCovers=FolderCoverIndex.from(books,folders);
            }catch(Exception ignored){libraryBooks.clear();bookFolders.clear();folderCovers=FolderCoverIndex.from(java.util.Collections.emptyList());}
        }
        rowsSnapshot="";buildTab();refresh(true);
    }
    private void transferPrivacy(JSONObject book,boolean folder){Runnable move=()->mutate(()->library.transfer(folder?book.optString("id"):Library.key(book),folder,!privateShelf));if(privateShelf)move.run();else PrivacyVault.beforeFirstUse(this,move);}
    private void attachPrivacyPull(PrivacyPullHost frame){
        final GridView pullGrid=grid;final TextView pullEmpty=emptyShelf;
        final float[] start={-1},distance={0},startX={0};final boolean[] pulling={false};final float threshold=getResources().getDisplayMetrics().heightPixels/3f;
        PrivacyPull ring=new PrivacyPull(this);frame.addView(ring,new FrameLayout.LayoutParams(-1,(int)threshold,Gravity.TOP));
        View.OnTouchListener pullListener=(v,event)->{if(draggingBook!=null||shelfHoldActive||!folderId.isEmpty()||shelfSearch.isExpanded())return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){start[0]=event.getRawY();startX[0]=event.getRawX();pulling[0]=false;return false;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&start[0]>=0){float delta=event.getRawY()-start[0];boolean top=pullGrid.getFirstVisiblePosition()==0&&(pullGrid.getChildCount()==0||pullGrid.getChildAt(0).getTop()>=pullGrid.getPaddingTop());if(!pulling[0]&&!top){start[0]=event.getRawY();startX[0]=event.getRawX();return false;}if(!pulling[0]&&Math.abs(event.getRawX()-startX[0])>Math.abs(delta))return false;if(!pulling[0]&&top&&delta>u.dp(12)){pulling[0]=true;}if(pulling[0]){distance[0]=Math.max(0,Math.min(threshold,delta));ring.progress=distance[0]/threshold;ring.invalidate();pullGrid.setTranslationY(distance[0]);pullEmpty.setTranslationY(distance[0]);return true;}}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){boolean complete=event.getActionMasked()==MotionEvent.ACTION_UP&&distance[0]>=threshold;start[0]=-1;if(pulling[0]){pulling[0]=false;if(complete){ring.progress=0f;ring.invalidate();pullGrid.setTranslationY(0f);pullEmpty.setTranslationY(0f);distance[0]=0;PrivacyVault.enterFromShelf(this,()->setPrivateShelf(true));return true;}ValueAnimator a=ValueAnimator.ofFloat(distance[0],0);a.setDuration(180);a.addUpdateListener(anim->{float value=(float)anim.getAnimatedValue();ring.progress=value/threshold;ring.invalidate();pullGrid.setTranslationY(value);pullEmpty.setTranslationY(value);});a.start();distance[0]=0;return true;}}return false;};frame.pull=pullListener;
    }
    /** Observe touches before children so a cover cannot consume a top-edge pull. */
    private static final class PrivacyPullHost extends FrameLayout {
        View.OnTouchListener pull;boolean claimed;
        PrivacyPullHost(android.content.Context context){super(context);}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)claimed=false;
            if(claimed)return true;
            if(pull!=null&&pull.onTouch(this,event)){claimed=true;return true;}
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event){return pull!=null&&(pull.onTouch(this,event)||claimed)||super.onTouchEvent(event);}
        @Override public void requestDisallowInterceptTouchEvent(boolean disallow){if(pull==null||!disallow)super.requestDisallowInterceptTouchEvent(disallow);}
    }
    private static final class PrivacyPull extends View {
        float progress;final android.graphics.Paint pen=new android.graphics.Paint(3);final android.graphics.RectF arc=new android.graphics.RectF();
        PrivacyPull(android.content.Context c){super(c);}
        protected void onDraw(Canvas canvas){
            if(progress<=0)return;canvas.clipRect(0,0,getWidth(),getHeight()*progress);
            float density=getResources().getDisplayMetrics().density,radius=15*density,x=getWidth()/2f,gap=getHeight()*progress,y=(gap-24*density)/2;
            pen.setStyle(android.graphics.Paint.Style.STROKE);pen.setStrokeWidth(3*density);pen.setStrokeCap(android.graphics.Paint.Cap.ROUND);pen.setColor(0x223478f6);canvas.drawCircle(x,y,radius,pen);
            pen.setColor(Ui.BLUE);arc.set(x-radius,y-radius,x+radius,y+radius);canvas.drawArc(arc,-90,360*progress,false,pen);
            pen.setStyle(android.graphics.Paint.Style.FILL);pen.setTextAlign(android.graphics.Paint.Align.CENTER);pen.setTextSize(13*density);canvas.drawText("继续下滑进入隐私书架",x,y+radius+23*density,pen);
        }
    }

    private void togglePrivacy(){revealed=!revealed;prefs.edit().putBoolean("history_revealed",revealed).apply();eye.kind(revealed?"eye":"eye-off");eye.setContentDescription(revealed?"隐藏封面和书名":"显示封面和书名");rowsSnapshot="";render();}
    private void download() {
        if(starting)return;
        final String id;
        try{id=JmApi.parseId(input.getText().toString());}catch(Exception e){input.setError("请输入有效编号或 JM 链接");return;}
        draft=input.getText().toString();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},42);
        starting=true;
        withLocalDownload(id,()->{if(DownloadService.running){toast("请先暂停当前任务");return;}beginDownload(id,"已开始下载");});
    }
    private void online(){
        if(starting)return;
        final String id;try{id=JmApi.parseId(input.getText().toString());}catch(IllegalArgumentException e){input.setError("请输入有效编号或 JM 链接");return;}
        draft=input.getText().toString();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        starting=true;
        withLocalDownload(id,()->{BackPreview.capture(getWindow().getDecorView());startActivity(new Intent().setClassName(this,"com.joe.jmlite.MihonEntryActivity").putExtra("source_jm_id",id).putExtra("title","JM"+id+" · 在线阅读"));});
    }
    /** Both the download and online buttons share the same existing-shelf prompt. */
    private void withLocalDownload(String id,Runnable proceed){
        disk.execute(()->{try{JSONObject b=new Library(this).findLocalDownload(id);runOnUiThread(()->{starting=false;if(isFinishing()||isDestroyed()||!resumed)return;if(b!=null){DownloadCollision.show(this,b);return;}proceed.run();});}catch(Exception e){runOnUiThread(()->{starting=false;if(!isDestroyed())toast("无法读取下载记录");});}});
    }
    private void searchOnline(){
        if(searchInput==null)return;
        String query=searchInput.getText().toString().trim();
        if(query.isEmpty()){searchInput.setError("请输入漫画名");return;}
        searchDraft=query;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchInput.getWindowToken(),0);
        BackPreview.capture(getWindow().getDecorView());startActivity(new Intent(this,OnlineSearchActivity.class).putExtra("query",query));
    }
    private void buildOnline(){
        historyLoaded=false;
        LinearLayout heading=u.row();heading.setPadding(u.dp(20),0,u.dp(20),u.dp(10));heading.addView(u.text("在线漫画",16,u.ink,true),new LinearLayout.LayoutParams(0,u.dp(42),1));
        TextView jm=u.button("JM漫画",jmHistory),web=u.button("全网漫画",!jmHistory);jm.setTextSize(12);web.setTextSize(12);
        heading.addView(jm,new LinearLayout.LayoutParams(u.dp(78),u.dp(36)));heading.addView(web,new LinearLayout.LayoutParams(u.dp(88),u.dp(36)));
        jm.setOnClickListener(v->{if(!jmHistory){jmHistory=true;onlineBooks.clear();rows.clear();buildTab();}});web.setOnClickListener(v->{if(jmHistory){jmHistory=false;onlineBooks.clear();rows.clear();buildTab();}});content.addView(heading);buildShelf();
    }
    /** All online cover taps land on a details screen before reading. */
    private void openOnline(JSONObject o){BackPreview.capture(getWindow().getDecorView());
        if(OnlineHistory.isJm(o)){
            startActivity(new Intent(this,BookInfoActivity.class).putExtra("book",o.toString()).putExtra("prefer_local",true));
            return;
        }
        startActivity(new Intent(this,OnlineMangaActivity.class)
                .putExtra("sourceId",o.optLong("sourceId"))
                .putExtra("url",o.optString("url"))
                .putExtra("title",o.optString("title"))
                .putExtra("thumbnail",o.optString("thumbnail"))
                .putExtra("author",o.optString("author"))
                .putExtra("description",o.optString("description"))
                .putExtra("sourceName",o.optString("sourceName")));
    }
    private void confirmRemoveOnline(JSONObject o){
        PredictiveBack.dialog(this).setTitle("移出在线记录？").setMessage(o.optString("title"))
                .setNegativeButton("取消",null)
                .setPositiveButton("移出",(d,n)->disk.execute(()->{try{new OnlineHistory(this,OnlineHistory.isJm(o)).remove(o.optString("sourceId"),o.optString("url"));}catch(Exception ignored){}runOnUiThread(this::refresh);}))
                .show();
    }

    private void refresh(){refresh(false);}
    private void refresh(boolean force){
        if(draggingBook!=null||disk.isShutdown()||isDestroyed())return;
        // A forced refresh (tab switch) must still queue while a mutation is finishing; the
        // single disk executor keeps it ordered after the mutation.
        if((reading||operation)&&!force)return;
        final boolean jm=jmHistory;
        final int generation=++refreshGeneration;
        reading=true;
        disk.execute(()->{
            try{
                List<JSONObject> result=store.list(),local=library.books(result),folders=library.folders(),online=new OnlineHistory(this,jm).list(),downloads=new DownloadHistory(this).list(3);
                FolderCoverIndex coverIndex=FolderCoverIndex.from(local,folders);
                runOnUiThread(()->{
                    if(isDestroyed()||generation!=refreshGeneration)return;
                    // This generation owns the latch even if the JM/全网 tab changed while it ran.
                    reading=false;
                    if(draggingBook!=null)return;
                    // A slower JM/全网 read must never replace the list of the tab now on screen.
                    if(jm!=jmHistory||!resumed)return;
                    books.clear();books.addAll(result);libraryBooks.clear();libraryBooks.addAll(local);folderCovers=coverIndex;bookFolders.clear();bookFolders.addAll(folders);onlineBooks.clear();onlineBooks.addAll(online);downloadRecords.clear();downloadRecords.addAll(downloads);historyLoaded=true;render();
                });
            }catch(Exception e){
                runOnUiThread(()->{if(generation==refreshGeneration)reading=false;});
            }
        });
    }
    private void render(){
        if(tab==3)return;
        if(tab==2){rows.clear();rows.addAll(onlineBooks);String snap=shelfSnapshot();if(!snap.equals(rowsSnapshot)){rowsSnapshot=snap;if(shelfAdapter!=null)shelfAdapter.notifyDataSetChanged();}paintHistoryEmpty();return;}
        rows.clear();if(tab==0){rows.addAll(downloadRecords.subList(0,Math.min(3,downloadRecords.size())));if(rows.isEmpty())rows.add(empty());}
        else{if(shelfQuery.trim().isEmpty())for(JSONObject f:bookFolders)if(folderId.equals(f.optString("parent"))){try{JSONObject row=new JSONObject(f.toString());row.put("book_folder",true);rows.add(row);}catch(Exception ignored){}}rows.addAll(BookSearch.shelf(libraryBooks,folderId,shelfQuery));title.setText(folderTitle());if(emptyShelf!=null){emptyShelf.setText(shelfQuery.trim().isEmpty()?"书架还空着":"没有匹配的书籍");emptyShelf.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);}}
        String snapshot=shelfSnapshot()+revealed;if(!snapshot.equals(rowsSnapshot)){rowsSnapshot=snapshot;if(adapter!=null)adapter.notifyDataSetChanged();if(shelfAdapter!=null)shelfAdapter.notifyDataSetChanged();}updateTask();restoreShelfScroll();scrollToShelfHighlight();
    }
    /** Cheap per-row fingerprint: one StringBuilder instead of a JSONArray and JSONObject per row/refresh. */
    private String shelfSnapshot(){
        StringBuilder out=new StringBuilder(rows.size()*48);
        for(JSONObject b:rows){
            out.append(b.optString("key")).append('\u0001').append(b.optString("id")).append('\u0001')
                    .append(b.optString("title")).append('\u0001').append(b.optString("folder")).append('\u0001')
                    .append(b.optInt("done")).append('\u0001').append(b.optString("uri")).append('\u0001')
                    .append(b.optBoolean("empty")).append('\u0001').append(b.optBoolean("book_folder")).append('\u0001')
                    .append(b.optLong("completedAt")).append('\u0001').append(b.optInt("downloadedChapters")).append('\u0001')
                    .append(b.optString("thumbnail")).append('\u0001').append(b.optLong("sourceId")).append('\u0001')
                    .append(b.optString("url")).append('\u0001').append(b.optString("lastChapterName")).append('\u0001')
                    .append(b.optInt("chapterCount")).append('\u0001').append(b.optString("sourceName")).append('\u0001');
            if(b.optBoolean("book_folder"))out.append(folderCovers.signature(b.optString("id")));
            out.append('\u0002');
        }
        return out.toString();
    }
    /** Rotation or a window resize rebuilds the shelf; keep the user at the same row. */
    private void restoreShelfScroll(){
        if(pendingShelfScroll<0||grid==null||rows.isEmpty())return;
        final int position=Math.min(pendingShelfScroll,rows.size()-1);pendingShelfScroll=-1;
        grid.post(()->{if(grid!=null&&position<grid.getCount())grid.setSelection(position);});
    }
    /** Bring the freshly jumped-to book into view once, without dismissing the outline. */
    private void scrollToShelfHighlight(){
        if(!highlightScrollPending||highlightKey.isEmpty()||grid==null)return;
        int position=-1;for(int n=0;n<rows.size();n++)if(shelfKey(rows.get(n)).equals(highlightKey)){position=n;break;}
        if(position<0)return;
        highlightScrollPending=false;final int target=position;
        grid.post(()->{if(grid!=null&&!highlightKey.isEmpty())grid.setSelection(target);});
    }
    private void clearShelfHighlight(){
        if(highlightKey.isEmpty())return;
        highlightKey="";highlightScrollPending=false;
        if(grid==null)return;
        for(int n=0;n<grid.getChildCount();n++){View child=grid.getChildAt(n);if(child instanceof ShelfTile)((ShelfTile)child).applyHighlight(false);}
    }
    private JSONObject empty(){JSONObject b=new JSONObject();try{b.put("empty",true);}catch(Exception ignored){}return b;}
    private void paintHistoryEmpty(){if(emptyShelf!=null)emptyShelf.setVisibility(historyLoaded&&onlineBooks.isEmpty()?View.VISIBLE:View.GONE);}
    private void updateTask(){
        if(taskCard==null)return;boolean running=DownloadService.running;if(running){taskId=DownloadService.activeId;preparingDownload=false;}
        text(start,running?"任务下载中":"开始下载");JSONObject current=null;for(JSONObject b:books){if(!taskId.isEmpty()&&b.optString("id").equals(taskId)){current=b;break;}}
        if(current==null||preparingDownload){if(preparingDownload||running){paintPreparingTask();setTaskVisible(true);}else setTaskVisible(false);return;}
        boolean active=running&&taskId.equals(DownloadService.activeId);String status=current.optString("status");
        int done=current.optInt("done"),total=current.optInt("total");boolean waiting=active&&total==0;if(progress.isIndeterminate()!=waiting)progress.setIndeterminate(waiting);if(progress.getMax()!=Math.max(total,1))progress.setMax(Math.max(total,1));if(progress.getProgress()!=done)progress.setProgress(done);
        text(taskCount,DownloadTransition.pages(done,total));taskCount.setContentDescription("已下载 "+done+" 页，总共 "+(total>0?total:"未知")+" 页");
        String label="JM"+current.optString("id"),name=current.optString("title");if(!name.isEmpty()&&!name.equals(label))label+=" · "+name;
        String msg=current.optString("message");text(taskInfo,label+("error".equals(status)?"\n"+msg:""));
        if(!active&&"complete".equals(status)){text(taskTitle,"下载完成");try{if(input!=null&&taskId.equals(JmApi.parseId(input.getText().toString()))){input.setText("");draft="";}}catch(IllegalArgumentException ignored){}taskId="";setTaskVisible(false);return;}
        text(taskTitle,active?(waiting?"正在获取章节":"正在下载"):"error".equals(status)?"下载未完成":"下载已暂停");
        taskAction.setVisibility(View.VISIBLE);taskAction.setEnabled(true);text(taskAction,active?"暂停":("error".equals(status)?"重试":"继续"));
        setTaskVisible(true);
    }
    private void paintPreparingTask(){text(taskTitle,"正在准备下载");text(taskCount,"0/…");taskCount.setContentDescription("正在获取总页数");text(taskInfo,"JM"+taskId+" · 正在获取章节与页数");progress.setIndeterminate(true);taskAction.setEnabled(false);text(taskAction,"准备中");}
    private void beginDownload(String id,String message){
        taskId=id;preparingDownload=true;paintPreparingTask();setTaskVisible(true);
        try{startForegroundService(new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_START).putExtra("id",id));handler.removeCallbacks(poll);handler.post(poll);toast(message);}
        catch(RuntimeException e){preparingDownload=false;taskId="";setTaskVisible(false);toast("无法启动下载，请重试");}
    }
    private void onTaskAction(){
        if(DownloadService.running){startService(new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_CANCEL));return;}
        if(taskId.isEmpty())return;
        beginDownload(taskId,"继续下载");
    }
    private void setTaskVisible(boolean show){
        if(taskCard==null||actionSlot==null||downloadActions==null)return;
        if(show==taskShown)return;
        taskShown=show;
        ValueAnimator previous=actionAnimator;actionAnimator=null;if(previous!=null){previous.removeAllListeners();previous.cancel();}
        final FrameLayout slot=actionSlot;final LinearLayout buttons=downloadActions,card=taskCard;
        final int width=Math.max(1,slot.getWidth()>0?slot.getWidth():content.getWidth()-u.dp(36));
        final int targetHeight=measureActionHeight(card,width);final float target=show?1f:0f;
        buttons.setVisibility(View.VISIBLE);card.setVisibility(View.VISIBLE);start.setEnabled(!show);buttons.getChildAt(1).setEnabled(!show);
        if(slot.getWidth()<=0){applyDownloadTransition(target,slot,buttons,card,width,targetHeight);finishDownloadTransition(show,slot,buttons,card);return;}
        final ValueAnimator animator=ValueAnimator.ofFloat(actionFraction,target);actionAnimator=animator;
        animator.setDuration(Math.max(120,(int)(480*Math.abs(target-actionFraction))));animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(a->{if(actionAnimator==animator&&actionSlot==slot)applyDownloadTransition((float)a.getAnimatedValue(),slot,buttons,card,width,targetHeight);});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation){cancelled=true;}
            @Override public void onAnimationEnd(android.animation.Animator animation){
                if(cancelled||actionAnimator!=animator||actionSlot!=slot)return;
                actionAnimator=null;finishDownloadTransition(show,slot,buttons,card);
            }
        });animator.start();
    }
    private void applyDownloadTransition(float fraction,FrameLayout slot,LinearLayout buttons,LinearLayout card,int width,int height){
        actionFraction=fraction;float merge=DownloadTransition.merge(fraction),expand=DownloadTransition.expand(fraction);
        for(int n=0;n<buttons.getChildCount();n++){View button=buttons.getChildAt(n);button.setTranslationX((width/2f-button.getLeft()-button.getWidth()/2f)*merge);button.setScaleX(DownloadTransition.buttonScaleX(fraction));button.setScaleY(1f-.18f*merge);}
        buttons.setAlpha(DownloadTransition.buttonsAlpha(fraction));card.setPivotX(width/2f);card.setPivotY(u.dp(24));card.setScaleX(DownloadTransition.panelScaleX(fraction));card.setScaleY(.82f+.18f*expand);card.setAlpha(DownloadTransition.panelAlpha(fraction));
        ViewGroup.LayoutParams lp=slot.getLayoutParams();lp.height=Math.round(u.dp(48)+(height-u.dp(48))*expand);slot.setLayoutParams(lp);
    }
    private void finishDownloadTransition(boolean show,FrameLayout slot,LinearLayout buttons,LinearLayout card){
        buttons.setVisibility(show?View.GONE:View.VISIBLE);card.setVisibility(show?View.VISIBLE:View.GONE);
        ViewGroup.LayoutParams lp=slot.getLayoutParams();lp.height=show?-2:u.dp(48);slot.setLayoutParams(lp);
    }
    private int measureActionHeight(View view,int width){
        view.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        return Math.max(view.getMeasuredHeight(),u.dp(48));
    }
    private void text(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}

    private void open(JSONObject b){
        if(b.optBoolean("empty"))return;if(b.optBoolean("download_history")){DownloadRecordActions.open(this,b,disk);return;}if(b.has("sourceId")){openOnline(b);return;}if(b.optBoolean("book_folder")){BackPreview.capture(getWindow().getDecorView(),nav);folderPreviewDepth++;folderId=b.optString("id");library.preserveFolder(folderId);buildTab();PageMotion.folder(this,content);return;}
        BackPreview.capture(getWindow().getDecorView());startActivity(new Intent(this,BookInfoActivity.class).putExtra("book",b.toString()));
    }
    private void manage(JSONObject b){
        if(b.optBoolean("empty"))return;if(tab==1){if(b.optBoolean("book_folder"))manageFolder(b);else manageBook(b);return;}
        if(b.optBoolean("download_history")){DownloadRecordActions.confirmRemove(this,b,disk,()->refresh(true));return;}
        String id=b.optString("id");PredictiveBack.dialog(this).setTitle(revealed?b.optString("title"):id).setItems(new String[]{"查看车牌号","删除下载内容"},(d,n)->{if(n==0)PredictiveBack.dialog(this).setTitle("车牌号").setMessage(id).setPositiveButton("知道了",null).show();else confirmDelete(b,true);}).show();
    }
    private void manageBook(JSONObject b){
        if(b.has("sourceId")){manageOnlineBook(b);return;}
        boolean favorite=isFavorite(b);
        LinearLayout head=u.column();head.setPadding(u.dp(24),u.dp(18),u.dp(24),u.dp(2));
        TextView heading=u.text(b.optString("title","未命名"),18,u.ink,true);heading.setMaxLines(2);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);head.addView(heading);
        u.space(head,8);head.addView(u.text("车牌号："+plate(b),13,u.muted,false));u.space(head,12);
        LinearLayout fav=u.row();fav.setGravity(Gravity.CENTER_VERTICAL);fav.setPadding(u.dp(13),0,u.dp(18),0);
        Ui.Icon heart=u.icon("heart",u.muted);heart.setMinimumWidth(0);heart.setMinimumHeight(0);fav.addView(heart,new LinearLayout.LayoutParams(u.dp(22),u.dp(22)));
        TextView flabel=u.text(favorite?"已收藏":"收藏",13,u.muted,true);flabel.setPadding(u.dp(7),0,0,0);fav.addView(flabel);
        FavoriteStyle.paint(u,heart,fav,flabel,favorite);
        head.addView(fav,new LinearLayout.LayoutParams(-2,u.dp(38)));
        AlertDialog dlg=PredictiveBack.dialog(this,true).setCustomTitle(head).setItems(new String[]{"重命名书籍","书籍分类","删除","分享",privateShelf?"从隐私书架移除":"添加到隐私书架"},(d,n)->{if(n==0)nameDialog("重命名书籍",b.optString("title"),name->mutate(()->library.rename(b.optString("key"),name)));else if(n==1)classify(b);else if(n==2)confirmDelete(b,false);else if(n==3)share(b);else transferPrivacy(b,false);}).create();
        fav.setOnClickListener(v->{dlg.dismiss();setFavorite(b,!favorite);});
        dlg.show();
    }
    private void manageOnlineBook(JSONObject b){
        boolean fav=false;for(JSONObject saved:libraryBooks)if(Library.key(saved).equals(b.optString("key")))fav=saved.optBoolean("favorite",true);final boolean favorite=fav;
        String caption=b.optString("title")+(OnlineHistory.isJm(b)?"\n编号："+b.optString("url"):"");
        PredictiveBack.dialog(this,true).setTitle(caption).setItems(tab==1?new String[]{favorite?"♥ 取消收藏":"♡ 收藏", "重命名书籍","书籍分类","删除",privateShelf?"从隐私书架移除":"添加到隐私书架"}:new String[]{favorite?"♥ 取消收藏":"♡ 收藏", "重命名书籍","书籍分类","删除"},(d,n)->{
            if(n==0)mutate(()->library.favoriteOnline(b,!favorite));
            else if(n==1)nameDialog("重命名书籍",b.optString("title"),name->mutate(()->{b.put("title",name).put("customTitle",name);new OnlineHistory(this,OnlineHistory.isJm(b)).save(b);if(favorite)library.rename(b.optString("key"),name);}));
            else if(n==2){if(tab==1||favorite)classify(b);else {operation=true;disk.execute(()->{try{library.favoriteOnline(b,true);runOnUiThread(()->{operation=false;refresh();classify(b);});}catch(Exception e){runOnUiThread(()->{operation=false;toast("分类失败");});}});}}
            else if(n==4&&tab==1)transferPrivacy(b,false);
            else if(n==3)PredictiveBack.dialog(this).setTitle("移除这本漫画？").setMessage("移出历史和在线书架，已下载图片保留。").setNegativeButton("取消",null).setPositiveButton("移除",(x,y)->mutate(()->{new OnlineHistory(this,OnlineHistory.isJm(b)).remove(b.optString("sourceId"),b.optString("url"));library.delete(b.optString("key"));})).show();
        }).show();
    }
    private String plate(JSONObject b){String type=b.optString("type");if("import".equals(type))return "本地导入";if("tree".equals(type))return "本地文件夹";return b.optString("id","—");}
    private boolean isFavorite(JSONObject b){
        if(b.optBoolean("favorite"))return true;
        // Books keep their favorite state anywhere under 我的收藏, including nested folders.
        HashSet<String> seen=new HashSet<>();String id=b.optString("folder");
        while(!id.isEmpty()&&seen.add(id)){
            JSONObject folder=null;for(JSONObject f:bookFolders)if(id.equals(f.optString("id"))){folder=f;break;}
            if(folder==null)return false;
            if("favorites".equals(folder.optString("system"))||Library.FAVORITES.equals(folder.optString("title")))return true;
            id=folder.optString("parent");
        }
        return false;
    }
    private void setFavorite(JSONObject b,boolean favorite){
        if(operation){toast("请等待当前操作完成");return;}operation=true;
        disk.execute(()->{String error=null;try{library.favorite(b.optString("key"),favorite);}catch(Exception e){error=e.getMessage();}
            String message=error;runOnUiThread(()->{operation=false;if(isDestroyed())return;if(message!=null){toast(message.isEmpty()?"操作失败":message);return;}toast(favorite?"已加入我的收藏":"已取消收藏");refresh(true);});});
    }
    private void manageFolder(JSONObject f){
        String parent=f.optString("parent");int index=-1,count=0;
        for(JSONObject item:bookFolders)if(parent.equals(item.optString("parent"))){if(f.optString("id").equals(item.optString("id")))index=count;count++;}
        boolean canUp=index>0,canDown=index>=0&&index<count-1;
        ArrayList<String> items=new ArrayList<>();boolean system=Library.isProtectedFolder(f);if(!system)items.add("重命名书籍夹");if(canUp)items.add("上移");if(canDown)items.add("下移");if(!system)items.add("删除书籍夹");items.add(privateShelf?"从隐私书架移除":"添加到隐私书架");
        PredictiveBack.dialog(this,true).setTitle(f.optString("title")).setItems(items.toArray(new String[0]),(d,n)->{
            String action=items.get(n);
            if("重命名书籍夹".equals(action))nameDialog("重命名书籍夹",f.optString("title"),name->mutate(()->library.renameFolder(f.optString("id"),name)));
            else if("上移".equals(action))mutate(()->library.moveFolder(f.optString("id"),-1));
            else if("下移".equals(action))mutate(()->library.moveFolder(f.optString("id"),1));
            else if(action.equals("添加到隐私书架")||action.equals("从隐私书架移除"))transferPrivacy(f,true);
            else{
                boolean nested=!f.optString("parent").isEmpty();String where=nested?"上一级书籍夹":"书架";
                int books=0,children=0;for(JSONObject member:folderCovers.members(f.optString("id"))){if(member.optBoolean("book_folder"))children++;else books++;}
                String message=children>0?"夹内书籍和子书籍夹中的书籍都会移到"+where+"，子书籍夹会被一并拆除。":books==0?"该书籍夹没有书籍，拆除后仅删除书籍夹。":books==1?"夹内 1 本书籍会回到"+where+"。":"夹内 "+books+" 本书籍会回到"+where+"。";
                PredictiveBack.dialog(this).setTitle("删除书籍夹？").setMessage(message).setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->mutate(()->library.deleteFolder(f.optString("id")))).show();
            }
        }).show();
    }
    private interface Named {void run(String name);}
    private interface DiskAction {void run()throws Exception;}
    private void nameDialog(String caption,String value,Named done){EditText edit=new EditText(this);edit.setSingleLine(true);edit.setTextSize(16);edit.setText(value);edit.setSelectAllOnFocus(true);Ui.clearable(edit);LinearLayout box=u.column();box.setPadding(u.dp(24),u.dp(8),u.dp(24),0);box.addView(edit);AlertDialog d=PredictiveBack.dialog(this).setTitle(caption).setView(box).setNegativeButton("取消",null).setPositiveButton("确定",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String name=edit.getText().toString().trim();if(name.isEmpty()){edit.setError("请输入名称");return;}if(name.length()>100){edit.setError("名称最多 100 个字");return;}d.dismiss();done.run(name);}));d.show();edit.requestFocus();}
    private void classify(JSONObject b){ArrayList<String> names=new ArrayList<>(),ids=new ArrayList<>();for(JSONObject f:bookFolders){names.add(f.optString("title"));ids.add(f.optString("id"));}names.add("＋ 创建书籍夹");if(!b.optString("folder").isEmpty())names.add(privateShelf?"移回隐私书架":"移回书架");PredictiveBack.dialog(this).setTitle("书籍分类").setItems(names.toArray(new String[0]),(d,n)->{if(n<ids.size())mutate(()->library.move(b.optString("key"),ids.get(n)));else if(n==ids.size())nameDialog("创建书籍夹","",name->mutate(()->library.moveToNewFolder(b.optString("key"),name,folderId)));else mutate(()->library.move(b.optString("key"),""));}).show();}
    private void confirmDelete(JSONObject b,boolean history){boolean tree="tree".equals(b.optString("type"));PredictiveBack.dialog(this).setTitle(history?"删除已下载图片？":tree?"移除这本书？":"删除这本书？").setMessage(tree?"原始文件夹和图片会保留。":"将删除本书及内置目录中的图片。").setNegativeButton("取消",null).setPositiveButton("确定",(d,n)->mutate(()->{if(DownloadService.running&&b.optString("id").equals(DownloadService.activeId))throw new IllegalStateException("请先暂停下载");library.delete(b.optString("key","download:"+b.optString("id")));})).show();}
    private void mutate(DiskAction action){if(operation){toast("请等待当前操作完成");return;}operation=true;disk.execute(()->{String error=null;boolean localComic=false;try{action.run();}catch(Library.LocalComicException e){localComic=true;}catch(Exception e){error=e.getMessage();}String msg=error;boolean rejectedLocal=localComic;runOnUiThread(()->{operation=false;if(isDestroyed())return;if(rejectedLocal)showLocalComicHint();else if(msg!=null)toast(msg.isEmpty()?"操作失败":msg);refresh(true);});});}
    private void share(JSONObject b){if(b.has("sourceId")){toast("在线漫画不提供分享");return;}if(Build.VERSION.SDK_INT<29&&checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")!=PackageManager.PERMISSION_GRANTED){pendingShare=b;requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"},44);return;}if(operation){toast("请等待当前操作完成");return;}toast("正在打包 CBZ…");operation=true;disk.execute(()->{try{Uri uri=library.exportCbz(b);runOnUiThread(()->{operation=false;if(isDestroyed())return;toast("已保存到 Download/jmshare");Intent send=new Intent(Intent.ACTION_SEND).setType("application/vnd.comicbook+zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("CBZ",uri));try{startActivity(Intent.createChooser(send,"分享漫画"));}catch(Exception e){toast("CBZ 已保存到 Download/jmshare");}refresh();});}catch(Exception e){runOnUiThread(()->{operation=false;if(!isDestroyed())toast("分享失败："+e.getMessage());});}});}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==44){JSONObject b=pendingShare;pendingShare=null;if(b!=null&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)share(b);else toast("保存分享文件需要存储权限");}}
    private void importMenu(){PredictiveBack.dialog(this).setTitle("添加到书架").setItems(new String[]{"导入漫画压缩包","导入图片文件夹","创建书籍夹"},(d,n)->{if(n==0){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,43);}else if(n==1){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,41);}else nameDialog("创建书籍夹","",name->mutate(()->library.createFolder(name,folderId)));}).show();}
    private JSONArray folders(){try{return new JSONArray(prefs.getString("folders","[]"));}catch(Exception e){return new JSONArray();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==43){toast("正在导入漫画…");mutate(()->library.importComic(uri));return;}if(request!=41)return;try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);JSONArray all=folders();for(int n=0;n<all.length();n++)if(all.getJSONObject(n).optString("uri").equals(uri.toString())){toast("已添加此文件夹");return;}String name=android.provider.DocumentsContract.getTreeDocumentId(uri);int slash=name.lastIndexOf('/');if(slash>=0)name=name.substring(slash+1);all.put(new JSONObject().put("uri",uri.toString()).put("title",name).put("local",true));prefs.edit().putString("folders",all.toString()).apply();refresh();}catch(Exception e){toast("未获得文件夹读取权限，请重新选择");}}
    private static String shelfKey(JSONObject book){return book.optBoolean("book_folder")?"folder:"+book.optString("id"):Library.key(book);}
    private void previewShelfDrag(float x,float y){previewShelfDrag(x,y,false);}
    private void previewShelfDrag(float x,float y,boolean force){
        if(draggingBook==null||grid==null||rows.isEmpty())return;
        String sourceKey=shelfKey(draggingBook);
        boolean sourceFolder=draggingBook.optBoolean("book_folder");
        ArrayList<String> order=new ArrayList<>();int folders=0;
        for(JSONObject book:dragRows){order.add(shelfKey(book));if(book.optBoolean("book_folder"))folders++;}
        int compactFolders=Math.max(0,folders-(sourceFolder?1:0));
        List<String> compact=ShelfOrder.without(order,sourceKey);
        ShelfTile origin=null;
        for(int n=0;n<grid.getChildCount();n++){
            View child=grid.getChildAt(n);
            if(child instanceof ShelfTile&&((ShelfTile)child).book!=null&&sourceKey.equals(shelfKey(((ShelfTile)child).book))){origin=(ShelfTile)child;break;}
        }
        // While the finger still covers the lifted slot the row stays filled: no landing hole.
        boolean atOrigin=origin!=null&&x>=origin.getLeft()&&x<origin.getRight()&&y>=origin.getTop()&&y<origin.getBottom();
        if(atOrigin){dragHoverKey="";dragHoverRect.setEmpty();}
        ShelfTile hovered=null;float hitLeft=0,hitTop=0;
        if(!atOrigin){
            // A chosen merge/into target stays sticky until the finger leaves its original bounds;
            // the auto-fill shift must never make the preview jump to the next book by itself.
            if(!dragHoverKey.isEmpty()&&dragHoverRect.contains(x,y)){
                for(int n=0;n<grid.getChildCount();n++){
                    View child=grid.getChildAt(n);
                    if(child instanceof ShelfTile&&((ShelfTile)child).book!=null&&dragHoverKey.equals(shelfKey(((ShelfTile)child).book))){hovered=(ShelfTile)child;hitLeft=dragHoverRect.left;hitTop=dragHoverRect.top;break;}
                }
            }
            if(hovered==null){
                dragHoverKey="";
                for(int n=0;n<grid.getChildCount()&&hovered==null;n++){
                    View child=grid.getChildAt(n);
                    if(!(child instanceof ShelfTile))continue;
                    ShelfTile tile=(ShelfTile)child;
                    if(tile.book==null||sourceKey.equals(shelfKey(tile.book)))continue;
                    Integer slot=dragPreviewPositions.get(shelfKey(tile.book));
                    float left,top;
                    if(slot!=null){Point point=shelfSlot(slot);left=point.x;top=point.y;}
                    else{left=child.getLeft()+child.getTranslationX();top=child.getTop()+child.getTranslationY();}
                    if(x>=left&&x<left+child.getWidth()&&y>=top&&y<top+child.getHeight()){hovered=tile;hitLeft=left;hitTop=top;break;}
                }
            }
        }
        JSONObject target=hovered==null?null:hovered.book;
        boolean targetFolder=target!=null&&target.optBoolean("book_folder");
        int zone=ShelfOrder.NONE;
        if(hovered!=null&&!sourceKey.equals(shelfKey(target))){
            float width=Math.max(1,hovered.getWidth());
            if(sourceFolder)zone=ShelfOrder.zone(Math.max(0,Math.min(width,x-hitLeft)),width,true,targetFolder);
            else if(targetFolder)zone=ShelfOrder.INTO;
            else zone=ShelfOrder.zone(Math.max(0,Math.min(width,x-hitLeft)),width,false,false);
        }
        boolean edgePush=!sourceFolder&&hovered!=null&&!targetFolder&&(zone==ShelfOrder.BEFORE||zone==ShelfOrder.AFTER);
        if(edgePush&&!force){
            long now=android.os.SystemClock.uptimeMillis();String key=shelfKey(target);
            if(!key.equals(pendingPushKey)||zone!=pendingPushZone||pendingPushAt==0){
                pendingPushKey=key;pendingPushZone=zone;pendingPushAt=now;
                handler.removeCallbacks(shelfPushPreview);handler.postDelayed(shelfPushPreview,SHELF_PUSH_DWELL);return;
            }
            if(now-pendingPushAt<SHELF_PUSH_DWELL)return;
        }else clearPendingPush();
        List<String> next;JSONObject dropTarget=null;int dropZone=ShelfOrder.NONE;int gap=-1;
        if(atOrigin){
            // The lifted card is still over its own slot: keep that slot as the landing gap.
            gap=order.indexOf(sourceKey);next=ShelfOrder.landing(order,sourceKey,gap);
        }else if(zone==ShelfOrder.MERGE||zone==ShelfOrder.INTO){
            // Keep the target card exactly where the finger is: compacting would slide it a slot away.
            next=order.indexOf(shelfKey(target))>order.indexOf(sourceKey)?order:compact;
            dropTarget=target;dropZone=zone;
            if(!dragHoverKey.equals(shelfKey(target))){
                dragHoverKey=shelfKey(target);
                dragHoverRect.set(hitLeft,hitTop,hitLeft+hovered.getWidth(),hitTop+hovered.getHeight());
            }
        }else{
            dragHoverKey="";
            int boundary;
            if(hovered!=null){
                int index=compact.indexOf(shelfKey(target));
                boundary=index<0?ShelfOrder.compactIndex(order,sourceKey,shelfInsertionBoundary(x,y)):index+(zone==ShelfOrder.AFTER?1:0);
            }else boundary=ShelfOrder.compactIndex(order,sourceKey,shelfInsertionBoundary(x,y));
            gap=ShelfOrder.clampGap(boundary,compact.size(),compactFolders,sourceFolder);
            next=ShelfOrder.landing(order,sourceKey,gap);
            int targetIndex=ShelfOrder.dropTargetIndex(compact,gap,sourceFolder,compactFolders);
            if(targetIndex>=0){
                String targetKey=compact.get(targetIndex);
                for(JSONObject book:dragRows)if(shelfKey(book).equals(targetKey)){dropTarget=book;break;}
                if(dropTarget!=null)dropZone=ShelfOrder.dropAfter(gap,compact.size(),compactFolders,sourceFolder)?ShelfOrder.AFTER:ShelfOrder.BEFORE;
            }
        }
        String signature=(gap<0?"compact":Integer.toString(gap))+'|'+zone+'|'+(dropTarget==null?"":shelfKey(dropTarget))+'|'+dropZone;
        if(signature.equals(dragPreviewSignature))return;
        dragPreviewSignature=signature;
        dragTarget=dropTarget;dragZone=dropZone;dragGap=gap;
        String keep=(zone==ShelfOrder.MERGE||zone==ShelfOrder.INTO)&&target!=null?shelfKey(target):null;
        for(int n=0;n<grid.getChildCount();n++){
            View child=grid.getChildAt(n);
            if(!(child instanceof ShelfTile))continue;
            ShelfTile tile=(ShelfTile)child;
            if(keep!=null&&tile.book!=null&&keep.equals(shelfKey(tile.book)))continue;
            tile.clearFolderPreview();tile.clearDropTarget();
        }
        if(zone==ShelfOrder.MERGE&&hovered!=null)hovered.showFolderPreview();
        else if(zone==ShelfOrder.INTO&&hovered!=null)hovered.showDropTarget();
        applyShelfPreview(next);
    }
    /** Adapter boundary nearest to the pointer when no tile sits directly under it. */
    private int shelfInsertionBoundary(float x,float y){
        View sample=grid.getChildAt(0);
        if(sample==null)return rows.size();
        int columns=Math.max(1,grid.getNumColumns());
        int cellWidth=Math.max(1,sample.getWidth()+grid.getHorizontalSpacing());
        int cellHeight=Math.max(1,sample.getHeight()+grid.getVerticalSpacing());
        int first=grid.getFirstVisiblePosition();
        int row=(int)Math.floor((y-sample.getTop())/(double)cellHeight);
        if(row<0)return first;
        int column=Math.max(0,Math.min(columns-1,(int)Math.floor((x-grid.getPaddingLeft())/(double)cellWidth)));
        int slot=first+row*columns+column;
        if(slot>=rows.size())return rows.size();
        int childIndex=slot-first;
        View cell=childIndex>=0&&childIndex<grid.getChildCount()?grid.getChildAt(childIndex):null;
        float left=cell==null?grid.getPaddingLeft()+column*cellWidth:cell.getLeft();
        float width=cell==null?sample.getWidth():cell.getWidth();
        return slot+(x>=left+width/2f?1:0);
    }
    /** Pin every visible tile to the landing order so a vacated slot fills in the same animation. */
    private void applyShelfPreview(List<String> next){
        dragPreviewPositions.clear();
        for(int n=0;n<next.size();n++)dragPreviewPositions.put(next.get(n),n);
        if(grid==null)return;
        for(int n=0;n<grid.getChildCount();n++){View child=grid.getChildAt(n);if(child instanceof ShelfTile)applyShelfTilePreview((ShelfTile)child);}
    }
    private void applyShelfTilePreview(ShelfTile tile){
        if(tile.book==null||draggingBook==null)return;
        Integer position=dragPreviewPositions.get(shelfKey(tile.book));
        if(position==null){tile.slideTo(0,0);return;}
        Point slot=shelfSlot(position);
        tile.slideTo(slot.x-tile.getLeft(),slot.y-tile.getTop());
    }
    private Point shelfSlot(int position){
        int columns=Math.max(1,grid.getNumColumns());
        int first=grid.getFirstVisiblePosition();
        int childIndex=position-first;
        if(childIndex>=0&&childIndex<grid.getChildCount()){View child=grid.getChildAt(childIndex);return new Point(child.getLeft(),child.getTop());}
        View firstChild=grid.getChildAt(0);
        if(firstChild==null)return new Point(grid.getPaddingLeft(),grid.getPaddingTop());
        int rowHeight=Math.max(1,firstChild.getHeight()+grid.getVerticalSpacing());
        int firstRow=first/columns,row=position/columns,column=position%columns;
        return new Point(grid.getPaddingLeft()+column*(firstChild.getWidth()+grid.getHorizontalSpacing()),firstChild.getTop()+(row-firstRow)*rowHeight);
    }
    private GradientDrawable dragOutline(){GradientDrawable border=u.shape(Color.TRANSPARENT,16);border.setStroke(u.dp(2),Ui.BLUE);return border;}
    private void clearPendingPush(){handler.removeCallbacks(shelfPushPreview);pendingPushKey="";pendingPushZone=ShelfOrder.NONE;pendingPushAt=0;}
    private void clearShelfPreview(){
        clearPendingPush();
        dragTarget=null;dragZone=ShelfOrder.NONE;dragGap=-1;dragHoverKey="";dragHoverRect.setEmpty();dragPreviewSignature="";dragPreviewPositions.clear();
        if(grid==null)return;
        for(int n=0;n<grid.getChildCount();n++){
            View child=grid.getChildAt(n);
            if(!(child instanceof ShelfTile)){child.animate().cancel();child.setTranslationX(0);child.setTranslationY(0);continue;}
            ShelfTile tile=(ShelfTile)child;
            tile.clearFolderPreview();tile.clearDropTarget();tile.slideTo(0,0);
        }
    }
    /** The bottom capsule only becomes a drop target while dragging something that lives in a folder. */
    private boolean shelfCapsuleActive(){
        return tab==1&&!folderId.isEmpty()&&draggingBook!=null&&(shelfSearch==null||!shelfSearch.isExpanded())&&!(draggingBook.optBoolean("book_folder")&&Library.isProtectedFolder(draggingBook));
    }
    private boolean shelfCapsuleDrag(DragEvent e){
        if(draggingBook==null)return false;
        if(e.getAction()==DragEvent.ACTION_DRAG_STARTED)return true;
        if(!shelfCapsuleActive())return false;
        switch(e.getAction()){
            case DragEvent.ACTION_DRAG_ENTERED:
                handler.removeCallbacks(shelfAutoScroll);
                clearShelfPreview();
                setShelfCapsuleHover(true);
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                setShelfCapsuleHover(true);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                setShelfCapsuleHover(false);
                return true;
            case DragEvent.ACTION_DROP:
                setShelfCapsuleHover(false);
                dropOnShelfCapsule();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                setShelfCapsuleHover(false);
                return true;
            default:
                return false;
        }
    }
    private void dropOnShelfCapsule(){
        JSONObject item=draggingBook;
        if(item==null||folderId.isEmpty()||operation)return;
        if(item.optBoolean("book_folder")){
            if(Library.isProtectedFolder(item)){toast("系统书籍夹不能拆除");return;}
            // Dismantle: its books and every nested folder's books return to the list it is dragged onto.
            mutate(()->library.deleteFolder(item.optString("id")));
            return;
        }
        String parent="";JSONObject current=null;
        for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id"))){parent=f.optString("parent");current=f;}
        HashSet<String> ids=shelfFolderTree(folderId);
        if(current!=null&&ids!=null&&!Library.isProtectedFolder(current)&&!shelfFolderTreeProtected(ids)&&shelfFolderTreeBooks(ids)==1){
            moveLastBookOut(item,parent,folderId);
            return;
        }
        final String target=parent;
        mutate(()->library.move(Library.key(item),target));

    }
    /** The dragged book is the last one in this folder: move it out, then leave and remove the folder. */
    private void moveLastBookOut(JSONObject item,String parent,String leaving){
        if(operation){toast("请等待当前操作完成");return;}
        operation=true;final String key=Library.key(item);
        disk.execute(()->{String error=null;boolean local=false;
            try{library.move(key,parent);}catch(Library.LocalComicException e){local=true;}catch(Exception e){error=e.getMessage();}
            String msg=error;boolean rejected=local;
            runOnUiThread(()->{operation=false;if(isDestroyed())return;
                if(rejected)showLocalComicHint();else if(msg!=null)toast(msg.isEmpty()?"操作失败":msg);
                if(rejected||msg!=null){refresh(true);return;}
                leaveAfterCapsule(key,leaving,parent);
            });
        });
    }
    /** Return to the parent with the already-emptied folder removed, so no deleted folder flashes. */
    private void leaveAfterCapsule(String key,String leaving,String parent){
        final Runnable[] holder=new Runnable[1];
        Runnable action=()->{
            if(isDestroyed()||isFinishing())return;
            if(draggingBook!=null){pendingCapsuleLeave=holder[0];handler.postDelayed(holder[0],60);return;}
            pendingCapsuleLeave=null;
            for(JSONObject b:libraryBooks)if(key.equals(b.optString("key"))){try{b.put("folder",parent);}catch(Exception ignored){}break;}
            HashSet<String> ids=shelfFolderTree(leaving);if(ids!=null)bookFolders.removeIf(f->ids.contains(f.optString("id")));
            folderId=parent;library.preserveFolder(parent);
            buildTab();releaseFolderInBackground(library,leaving,false);
        };
        holder[0]=action;
        if(draggingBook!=null){pendingCapsuleLeave=action;handler.postDelayed(action,120);}else action.run();
    }
    /** Folder ids of a folder and every folder nested below it; null when it is not on screen. */
    private HashSet<String> shelfFolderTree(String folderId){
        if(folderId==null||folderId.isEmpty())return null;
        boolean found=false;for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id"))){found=true;break;}
        if(!found)return null;
        HashSet<String> ids=new HashSet<>();ids.add(folderId);boolean grew=true;
        while(grew){grew=false;for(JSONObject f:bookFolders){if(ids.contains(f.optString("parent"))&&ids.add(f.optString("id")))grew=true;}}
        return ids;
    }
    private int shelfFolderTreeBooks(HashSet<String> ids){int books=0;for(JSONObject b:libraryBooks)if(ids.contains(b.optString("folder")))books++;return books;}
    private boolean shelfFolderTreeProtected(HashSet<String> ids){
        for(JSONObject f:bookFolders)if(ids.contains(f.optString("id"))&&Library.isProtectedFolder(f))return true;
        return false;
    }
    private void setShelfCapsuleHover(boolean hover){
        if(shelfCapsuleHover==hover)return;
        shelfCapsuleHover=hover;
        if(shelfDropPill==null)return;
        shelfDropPill.animate().cancel();
        shelfDropPill.animate().scaleX(hover?1.06f:1f).scaleY(hover?1.06f:1f).setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
    }
    private void showShelfDropPill(String label){
        if(stage==null)return;
        shelfCapsuleHover=false;
        if(shelfDropPill==null){
            shelfDropPill=u.text(label,13,Ui.BLUE,true);
            shelfDropPill.setGravity(Gravity.CENTER);
            shelfDropPill.setPadding(u.dp(24),0,u.dp(24),0);
            GradientDrawable background=u.shape(u.dark?0xff223554:0xffe9f1ff,28);
            background.setStroke(u.dp(1),u.dark?0x663478f6:0x4d3478f6);
            shelfDropPill.setBackground(background);
            shelfDropPill.setElevation(u.dp(10));
            shelfDropPill.setOnDragListener((v,e)->shelfCapsuleDrag(e));
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,u.dp(56),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            params.bottomMargin=u.dp(4)+navigationInset;
            stage.addView(shelfDropPill,params);
        }
        shelfDropPill.setText(label);
        shelfDropPill.animate().cancel();
        shelfDropPill.setVisibility(View.VISIBLE);
        shelfDropPill.setAlpha(0f);shelfDropPill.setScaleX(.9f);shelfDropPill.setScaleY(.9f);
        shelfDropPill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
        if(nav!=null){nav.animate().cancel();nav.animate().alpha(0f).setDuration(120).start();}
    }
    private void hideShelfDropPill(){
        shelfCapsuleHover=false;
        if(shelfDropPill!=null){
            shelfDropPill.animate().cancel();
            shelfDropPill.animate().alpha(0f).scaleX(.9f).scaleY(.9f).setDuration(120).withEndAction(()->{if(shelfDropPill!=null)shelfDropPill.setVisibility(View.GONE);}).start();
        }
        if(nav!=null){nav.animate().cancel();nav.animate().alpha(1f).setDuration(160).start();}
    }
    /** Bring the lifted card back at its slot while the menu is about to open. */
    private void restoreShelfTile(JSONObject item){
        if(grid==null)return;
        String key=item==null?null:shelfKey(item);
        for(int n=0;n<grid.getChildCount();n++){
            View child=grid.getChildAt(n);
            if(!(child instanceof ShelfTile)||key==null||((ShelfTile)child).book==null||!key.equals(shelfKey(((ShelfTile)child).book))){child.setAlpha(1f);continue;}
            ShelfTile tile=(ShelfTile)child;
            tile.animate().cancel();
            tile.setAlpha(0f);tile.setScaleX(.97f);tile.setScaleY(.97f);
            tile.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SHELF_TILE_SETTLE).setInterpolator(new DecelerateInterpolator()).start();
        }
    }
    /** Let the card start settling first, then let the menu grow in over it. */
    private void queueShelfMenu(JSONObject item){
        if(item==null)return;
        cancelShelfMenu();
        Runnable show=()->{pendingShelfMenu=null;if(isDestroyed()||!resumed||grid==null||(tab!=1&&tab!=2))return;if(operation){toast("请等待当前操作完成");return;}if(item.optBoolean("book_folder"))manageFolder(item);else manageBook(item);};
        pendingShelfMenu=show;handler.postDelayed(show,SHELF_MENU_DELAY);
    }
    private void cancelShelfMenu(){
        if(pendingShelfMenu==null)return;
        handler.removeCallbacks(pendingShelfMenu);pendingShelfMenu=null;
    }
    private Bitmap snapshotFace(View view){int width=Math.max(1,view.getWidth()),height=Math.max(1,view.getHeight());Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);android.graphics.Path clip=new android.graphics.Path();clip.addRoundRect(new android.graphics.RectF(0,0,width,height),u.dp(12),u.dp(12),android.graphics.Path.Direction.CW);canvas.clipPath(clip);view.draw(canvas);return image;}
    private void drop(JSONObject target,JSONObject source,int zone){
        if(source==null||target==null||Library.key(source).equals(Library.key(target)))return;
        if(source.optBoolean("book_folder")){
            if(target.optBoolean("book_folder"))mutate(()->library.reorderFolder(source.optString("id"),target.optString("id"),zone==ShelfOrder.AFTER));
            return;
        }
        if(zone==ShelfOrder.INTO){mutate(()->library.move(Library.key(source),target.optString("id")));return;}
        if(zone==ShelfOrder.MERGE){String parent=folderId;mutate(()->library.mergeBooks(Library.key(source),Library.key(target),parent));return;}
        mutate(()->library.reorderBook(Library.key(source),Library.key(target),zone==ShelfOrder.AFTER));
    }
    private void license(){PredictiveBack.dialog(this).setTitle("JM 简阅 1.3.0").setItems(new String[]{"Mihon 阅读器 · Apache 2.0","内置中文图源 · Keiyoushi","JMComic · MIT","压缩格式支持库","源码与修改说明","GitHub 项目："+AppUpdate.PROJECT_URL,"隐私密码设置"},(d,n)->{if(n==6){PrivacyVault.settings(this);return;}if(n==5){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdate.PROJECT_URL)));}catch(android.content.ActivityNotFoundException e){toast("未找到可打开链接的浏览器");}return;}String[] files={"LICENSE-Mihon.txt","LICENSES-extensions.txt","LICENSE-JMComic.txt","LICENSE-archives.txt","NOTICE-JM.txt"};showLicense(files[n]);}).setPositiveButton("完成",null).show();}
    private void showLicense(String file){try(java.io.InputStream in=getAssets().open(file);java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream()){byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;)b.write(buf,0,n);PredictiveBack.dialog(this).setTitle("开源许可").setMessage(b.toString("UTF-8")).setPositiveButton("知道了",null).show();}catch(Exception e){toast("无法读取许可");}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void showLocalComicHint(){
        clearLocalComicHint();if(isFinishing()||isDestroyed())return;
        FrameLayout host=findViewById(android.R.id.content);final TextView hint=u.text("本地漫画",14,Color.WHITE,true);hint.setGravity(Gravity.CENTER);hint.setPadding(u.dp(24),u.dp(12),u.dp(24),u.dp(12));hint.setBackground(u.shape(0xf02a3445,28));hint.setElevation(u.dp(8));
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(host);params.bottomMargin=u.dp(90)+(insets==null?0:insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);host.addView(hint,params);localComicHint=hint;
        hint.setAlpha(0);hint.setTranslationY(u.dp(8));hint.animate().alpha(1).translationY(0).setDuration(160).start();
        localComicHintDismiss=()->hint.animate().alpha(0).translationY(u.dp(8)).setDuration(160).withEndAction(()->{if(localComicHint==hint)clearLocalComicHint();}).start();handler.postDelayed(localComicHintDismiss,3000-160);
    }
    private void clearLocalComicHint(){if(localComicHintDismiss!=null){handler.removeCallbacks(localComicHintDismiss);localComicHintDismiss=null;}if(localComicHint!=null){TextView hint=localComicHint;localComicHint=null;hint.animate().cancel();if(hint.getParent() instanceof ViewGroup)((ViewGroup)hint.getParent()).removeView(hint);}}
    @Override public boolean dispatchTouchEvent(MotionEvent ev){
        if(predictiveBack!=null)predictiveBack.recordTouch(ev);
        int action=ev.getActionMasked();
        if((action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE)&&!highlightKey.isEmpty())clearShelfHighlight();
        return super.dispatchTouchEvent(ev);
    }
    @Override protected void onResume(){super.onResume();preparingDownload=false;if(!downloadReceiverRegistered){androidx.core.content.ContextCompat.registerReceiver(this,downloadChanges,new IntentFilter(DownloadService.ACTION_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);downloadReceiverRegistered=true;}resumed=true;handler.post(poll);if(firstRunGuide!=null)firstRunGuide.showIfNeeded();if(predictiveBack!=null)predictiveBack.reinstall();}
    @Override protected void onStart(){super.onStart();if(predictiveBack!=null)predictiveBack.reinstall();}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus&&predictiveBack!=null)predictiveBack.reinstall();}
    @Override protected void onPause(){cancelShelfMenu();hideShelfDropPill();super.onPause();resumed=false;if(downloadReceiverRegistered){unregisterReceiver(downloadChanges);downloadReceiverRegistered=false;}handler.removeCallbacks(poll);handler.removeCallbacks(downloadRefresh);}
    @Override protected void onStop(){PageMotion.clearEntering(this);if(predictiveBack!=null)predictiveBack.onStop();super.onStop();}
    @Override protected void onDestroy(){if(isFinishing()&&!isChangingConfigurations())SourceVisits.reset(this);resumed=false;clearLocalComicHint();if(firstRunGuide!=null)firstRunGuide.close();if(predictiveBack!=null)predictiveBack.close();handler.removeCallbacksAndMessages(null);covers.close();disk.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(firstRunGuide!=null&&firstRunGuide.isShowing())return;if(shelfSearch!=null&&shelfSearch.isExpanded())shelfSearch.collapse();else if(tab==1&&(privateShelf||!folderId.isEmpty()))leaveFolder();else moveTaskToBack(true);}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);b.putString("draft",input==null?draft:input.getText().toString());b.putString("search",searchInput==null?searchDraft:searchInput.getText().toString());b.putString("folder",privateShelf?"":folderId);b.putString("task_id",taskId);b.putInt("shelf_scroll",grid==null?-1:grid.getFirstVisiblePosition());}
    private final class OnlineAdapter extends BaseAdapter {
        public int getCount(){return onlineBooks.size();}public Object getItem(int p){return onlineBooks.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){OnlineRow row=old instanceof OnlineRow?(OnlineRow)old:new OnlineRow();row.bind(onlineBooks.get(p));return row;}
    }
    /** Recycled like the shelf rows so scrolling history does not inflate a row per movement. */
    private final class OnlineRow extends LinearLayout {
        final ImageView cover;final TextView name,sub;
        OnlineRow(){
            super(MainActivity.this);setOrientation(VERTICAL);setPadding(0,0,0,u.dp(10));
            LinearLayout body=u.row();body.setPadding(u.dp(14),u.dp(12),u.dp(14),u.dp(12));u.round(body,u.card,20);addView(body,new LinearLayout.LayoutParams(-1,-2));
            FrameLayout coverFrame=new FrameLayout(MainActivity.this);coverFrame.setBackground(u.shape(u.tint,12));coverFrame.setClipToOutline(true);
            cover=new ImageView(MainActivity.this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);coverFrame.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            body.addView(coverFrame,new LinearLayout.LayoutParams(u.dp(52),u.dp(72)));
            LinearLayout details=u.column();LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,-2,1);dp.setMargins(u.dp(14),0,u.dp(8),0);body.addView(details,dp);
            name=u.text("",14,u.ink,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(name);
            u.space(details,7);
            sub=u.text("",11,u.muted,false);sub.setMaxLines(1);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(sub);
        }
        void bind(JSONObject o){
            RemoteCovers.load(cover,o.optString("thumbnail"),o.optLong("sourceId"));
            text(name,o.optString("title","未命名"));
            String source=o.optString("sourceName","在线图源"),last=o.optString("lastChapterName","");
            text(sub,last.isEmpty()?source+" · 共"+o.optInt("chapterCount")+"章":source+" · "+last);
        }
    }
    private final class BookAdapter extends BaseAdapter {
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public int getViewTypeCount(){return 2;}
        public int getItemViewType(int p){return rows.get(p).optBoolean("empty")?1:0;}
        public View getView(int p,View old,ViewGroup parent){
            JSONObject book=rows.get(p);
            if(book.optBoolean("empty")){
                if(old!=null)return old;
                LinearLayout outer=u.column();outer.setPadding(0,0,0,u.dp(10));LinearLayout body=u.row();body.setPadding(u.dp(14),u.dp(12),u.dp(14),u.dp(12));u.round(body,u.card,20);outer.addView(body,new LinearLayout.LayoutParams(-1,-2));TextView empty=u.text("从一个编号开始",14,u.muted,false);empty.setGravity(Gravity.CENTER);empty.setPadding(0,u.dp(30),0,u.dp(30));body.addView(empty,new LinearLayout.LayoutParams(-1,-2));return outer;
            }
            BookRow row=old instanceof BookRow?(BookRow)old:new BookRow();
            text(row.name,DownloadRecordActions.displayTitle(book,revealed));text(row.info,DownloadRecordActions.summary(book));row.cover.bind(book,revealed);
            return row;
        }
    }
    private final class BookRow extends LinearLayout {
        final TextView name,info;final BookCover cover;
        BookRow(){
            super(MainActivity.this);setOrientation(VERTICAL);setPadding(0,0,0,u.dp(10));LinearLayout body=u.row();body.setPadding(u.dp(14),u.dp(12),u.dp(14),u.dp(12));u.round(body,u.card,20);addView(body,new LinearLayout.LayoutParams(-1,-2));
            cover=new BookCover();body.addView(cover,new LinearLayout.LayoutParams(u.dp(52),u.dp(72)));LinearLayout details=u.column();LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,-2,1);dp.setMargins(u.dp(14),0,u.dp(8),0);body.addView(details,dp);
            name=u.text("",14,u.ink,true);name.setMaxLines(2);name.setMinLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(name);u.space(details,7);info=u.text("",11,u.muted,false);info.setSingleLine(true);info.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(info);
        }
    }
    private final class BookCover extends RoundedCover {
        final ImageView image;private boolean remote;
        BookCover(){
            super(MainActivity.this,12);setBackground(u.shape(u.tint,12));setClipToOutline(true);TextView mark=u.text("JM",17,Ui.BLUE,true);mark.setGravity(Gravity.CENTER);addView(mark,new FrameLayout.LayoutParams(-1,-1));
            image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);addView(image,new FrameLayout.LayoutParams(-1,-1));
        }
        void bind(JSONObject book,boolean show){
            image.setTag(book);image.setVisibility(show?View.VISIBLE:View.INVISIBLE);
            boolean next=show&&book.has("sourceId")&&!OnlineHistory.isJm(book)&&(!book.optBoolean("download_history")||book.optString("thumbnail").startsWith("https://"));
            if(remote&&!next)RemoteCovers.load(image,"",0L);
            if(!remote&&next)covers.clear(image);
            remote=next;
            if(!show){covers.clear(image);return;}
            if(remote)RemoteCovers.load(image,book.optString("thumbnail"),book.optLong("sourceId"));else covers.load(image,book);
        }
    }
    private FrameLayout cover(JSONObject book,boolean show){BookCover cover=new BookCover();cover.bind(book,show);return cover;}
    private String onlineDetail(JSONObject b){
        if(!b.has("sourceId")||b.optBoolean("book_folder"))return "";
        if(OnlineHistory.isJm(b)){
            String id=b.optString("url");
            return "编号 JM"+id+" · "+OnlineHistory.readingLabel(b);
        }
        String source=b.optString("sourceName","在线图源");
        String last=b.optString("lastChapterName","");
        if(!last.isEmpty())return source+" · "+last;
        int count=b.optInt("chapterCount");
        return count>0?source+" · 共"+count+"章":source;
    }
    private final class ShelfAdapter extends BaseAdapter {
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){ShelfTile tile=old instanceof ShelfTile?(ShelfTile)old:new ShelfTile();tile.bind(rows.get(p));return tile;}
    }
    private final class ShelfTile extends LinearLayout {
        private static final int SHELF_HOLD_MILLIS=300;
        private boolean holdReady;
        private final Runnable shelfLongPress=()->{
            if(tab!=1||!isPressed())return;
            // A long hold only arms the tile. Dragging starts after the finger actually moves,
            // so releasing without movement can open the menu without cancelling a drag first.
            MotionEvent cancel=MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,0,0,0);
            super.onTouchEvent(cancel);cancel.recycle();
            holdReady=true;
            shelfHoldActive=true;
            cancelShelfMenu();
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if(shelfDragAllowed()){ViewParent parent=getParent();if(parent!=null)parent.requestDisallowInterceptTouchEvent(true);}
        };
        private boolean shelfDragAllowed(){return tab==1&&!operation&&(shelfSearch==null||!shelfSearch.isExpanded());}
        private void endShelfHold(){
            holdReady=false;
            shelfHoldActive=false;
            ViewParent parent=getParent();
            if(parent!=null)parent.requestDisallowInterceptTouchEvent(false);
        }
        private boolean beginShelfDrag(){
            if(book==null||operation||draggingBook!=null||(shelfSearch!=null&&shelfSearch.isExpanded()))return false;
            cancelShelfMenu();
            draggingBook=book;dragRows.clear();dragRows.addAll(rows);
            dragTarget=null;dragZone=ShelfOrder.NONE;dragGap=-1;dragHoverKey="";dragHoverRect.setEmpty();dragPreviewSignature="";clearPendingPush();dragCover=snapshotFace(face);
            holdReady=false;handler.removeCallbacks(dragEndSettle);
            boolean started=startDragAndDrop(ClipData.newPlainText("book",Library.key(book)),new TileShadow(this,touchX,touchY),book,0);
            if(!started){draggingBook=null;dragRows.clear();dragTarget=null;dragZone=ShelfOrder.NONE;dragGap=-1;dragPreviewSignature="";dragCover=null;return false;}
            holdReady=false;shelfHoldActive=false;
            setAlpha(0f);
            if(shelfCapsuleActive())showShelfDropPill(book.optBoolean("book_folder")?"拖动到此拆除书籍夹":"拖动到此移出书籍夹");
            return true;
        }
        private JSONObject book;private final FrameLayout face;private final GridView ownerGrid;private final TextView name,info;private String faceKey="";private int width=1,height=1;private final boolean tabletTile=AdaptiveLayout.isTabletDevice(MainActivity.this);private int touchX,touchY;private View folderPreview;private BookCover bookCover;private boolean dropTarget;private float slideTargetX,slideTargetY;private final Runnable pressFeedback=()->{if(isPressed())animate().scaleX(.97f).scaleY(.97f).setDuration(110).start();};
        ShelfTile(){super(MainActivity.this);ownerGrid=grid;setOrientation(VERTICAL);face=new FrameLayout(MainActivity.this);addView(face,new LinearLayout.LayoutParams(-1,height));u.space(this,6);name=u.text("",12,u.ink,true);name.setMaxLines(2);name.setMinLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(u.dp(2),0,u.dp(2),0);addView(name,new LinearLayout.LayoutParams(-1,-2));info=u.text("",10,u.muted,false);info.setMaxLines(1);info.setMinLines(1);info.setEllipsize(android.text.TextUtils.TruncateAt.END);info.setPadding(u.dp(2),u.dp(2),u.dp(2),0);info.setVisibility(View.INVISIBLE);addView(info,new LinearLayout.LayoutParams(-1,-2));setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){if(holdReady)endShelfHold();removeCallbacks(shelfLongPress);cancelShelfMenu();touchX=(int)e.getX();touchY=(int)e.getY();removeCallbacks(pressFeedback);postDelayed(pressFeedback,Math.max(0,(tab==1?Math.min(SHELF_HOLD_MILLIS,ViewConfiguration.getLongPressTimeout()):ViewConfiguration.getLongPressTimeout())-110));}else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){removeCallbacks(pressFeedback);animate().cancel();if(getScaleX()!=1f||getScaleY()!=1f)animate().scaleX(1).scaleY(1).setDuration(150).start();}return false;});setOnClickListener(v->open(book));setOnLongClickListener(v->{
            if(holdReady)return true;
            if(book==null)return false;
            if(operation){toast("请等待当前操作完成");return true;}
            v.animate().cancel();v.animate().scaleX(1f).scaleY(1f).setDuration(SHELF_TILE_SETTLE).setInterpolator(new DecelerateInterpolator()).start();
            queueShelfMenu(book);return true;
        });
        }
        /**
         * Phones keep the original cover-width formula verbatim, so their shelf stays
         * pixel-identical at every window size. Tablet tiles follow the real column width and keep
         * the same 1.40 height/width ratio at every column count.
         */
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int coverWidth;
            if(tabletTile){
                int measured=MeasureSpec.getSize(widthSpec);
                int columns=Math.max(1,ownerGrid==null?3:ownerGrid.getNumColumns());
                int content=ownerGrid==null?0:ownerGrid.getWidth()-ownerGrid.getPaddingLeft()-ownerGrid.getPaddingRight();
                int expected;
                if(content>0)expected=(content-u.dp(10)*(columns-1))/columns;
                else{
                    int window=AdaptiveLayout.windowWidthPx(MainActivity.this)-u.dp(36);
                    expected=window>0?(window-u.dp(10)*(columns-1))/columns:u.dp(120);
                }
                // GridView may pre-measure a tile at the full content width; use the expected
                // column width so the row height is right even before the real layout pass.
                if(expected>0&&measured>expected)measured=expected;
                if(measured<=0)measured=u.dp(120);
                coverWidth=measured;
            }else{
                int columns=Math.max(1,ownerGrid==null?3:ownerGrid.getNumColumns());
                coverWidth=(getResources().getDisplayMetrics().widthPixels-u.dp(36)-u.dp(10)*(columns-1))/columns;
            }
            int coverHeight=AdaptiveLayout.shelfCoverHeightPx(coverWidth);
            if(coverHeight!=height){
                height=coverHeight;
                ViewGroup.LayoutParams params=face.getLayoutParams();
                if(params!=null)params.height=height;
            }
            width=coverWidth;
            super.onMeasure(widthSpec,heightSpec);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(tab==1){
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    holdReady=false;
                    postDelayed(shelfLongPress,Math.min(SHELF_HOLD_MILLIS,ViewConfiguration.getLongPressTimeout()));
                }else if(action==MotionEvent.ACTION_MOVE){
                    float distance=(float)Math.hypot(event.getX()-touchX,event.getY()-touchY);
                    int slop=Math.max(ViewConfiguration.get(getContext()).getScaledTouchSlop(),u.dp(10));
                    if(holdReady){
                        if(distance>slop){
                            if(shelfDragAllowed()&&draggingBook==null){
                                if(!beginShelfDrag())endShelfHold();
                            }else endShelfHold();
                        }
                    }else if(distance>slop)removeCallbacks(shelfLongPress);
                }else if(action==MotionEvent.ACTION_UP){
                    removeCallbacks(shelfLongPress);
                    boolean menu=holdReady&&draggingBook==null&&book!=null;
                    endShelfHold();
                    if(menu){queueShelfMenu(book);return true;}
                }else if(action==MotionEvent.ACTION_CANCEL){
                    removeCallbacks(shelfLongPress);
                    endShelfHold();
                }
            }
            return super.onTouchEvent(event);
        }
        @Override protected void onDetachedFromWindow(){removeCallbacks(shelfLongPress);removeCallbacks(pressFeedback);if(holdReady)endShelfHold();animate().cancel();setScaleX(1);setScaleY(1);setTranslationX(0);setTranslationY(0);slideTargetX=0;slideTargetY=0;super.onDetachedFromWindow();}
        void bind(JSONObject b){
            if(holdReady)endShelfHold();removeCallbacks(shelfLongPress);removeCallbacks(pressFeedback);
            boolean rebound=book==null||!shelfKey(book).equals(shelfKey(b));
            if(draggingBook==null||rebound){resetSlide();setScaleX(1);setScaleY(1);setForeground(null);clearFolderPreview();clearDropTarget();name.setAlpha(1f);if(bookCover!=null)bookCover.setAlpha(1f);}
            book=b;
            setAlpha(draggingBook!=null&&shelfKey(draggingBook).equals(shelfKey(b))?0f:1f);
            text(name,b.optString("title","未命名"));setContentDescription(b.optString("title"));
            String detail=onlineDetail(b);text(info,detail);
            int wanted=detail.isEmpty()?View.INVISIBLE:View.VISIBLE;
            if(info.getVisibility()!=wanted)info.setVisibility(wanted);
            String identity=b.optBoolean("book_folder")?"folder:"+b.optString("id"):b.optString("key");
            if(b.optBoolean("book_folder")){identity+="/"+folderCovers.signature(b.optString("id"));}else if(b.has("sourceId"))identity+="|"+b.optString("thumbnail");
            if(b.optBoolean("book_folder")){if(!identity.equals(faceKey)){face.removeAllViews();face.addView(folderCover(b,width,height),new FrameLayout.LayoutParams(-1,-1));}else retryMissingCovers(face);}
            else{if(bookCover==null)bookCover=new BookCover();if(bookCover.getParent()!=face){face.removeAllViews();face.addView(bookCover,new FrameLayout.LayoutParams(-1,-1));}bookCover.bind(b,true);}
            faceKey=identity;
            if(rebound&&draggingBook!=null)post(()->{if(draggingBook!=null&&book==b)applyShelfTilePreview(this);});
            applyHighlight(!highlightKey.isEmpty()&&highlightKey.equals(shelfKey(b)));
        }
        /** One retargetable animation per tile: repeated drag locations never restart unchanged tiles. */
        void slideTo(float x,float y){
            if(Math.abs(slideTargetX-x)<.5f&&Math.abs(slideTargetY-y)<.5f)return;
            slideTargetX=x;slideTargetY=y;
            animate().cancel();
            animate().translationX(x).translationY(y).setDuration(DRAG_PREVIEW_MS).setInterpolator(DRAG_PREVIEW_INTERPOLATOR).start();
        }
        private void resetSlide(){animate().cancel();slideTargetX=0;slideTargetY=0;setTranslationX(0);setTranslationY(0);}
        void showDropTarget(){
            if(dropTarget)return;
            dropTarget=true;
            face.animate().cancel();
            face.setForeground(dragOutline());
            face.animate().scaleX(DRAG_TARGET_SCALE).scaleY(DRAG_TARGET_SCALE).setDuration(DRAG_TARGET_MS).setInterpolator(new DecelerateInterpolator()).start();
        }
        void clearDropTarget(){
            if(!dropTarget)return;
            dropTarget=false;
            face.animate().cancel();
            face.setForeground(null);
            face.animate().scaleX(1f).scaleY(1f).setDuration(DRAG_TARGET_MS).setInterpolator(new DecelerateInterpolator()).start();
        }
        void applyHighlight(boolean on){
            if(on)face.setForeground(dragOutline());
            else if(!dropTarget)face.setForeground(null);
        }
        void clearFolderPreview(){
            if(folderPreview==null)return;
            View preview=folderPreview;folderPreview=null;
            preview.animate().cancel();
            preview.animate().alpha(0f).scaleX(DRAG_PREVIEW_SCALE).scaleY(DRAG_PREVIEW_SCALE).setDuration(DRAG_PREVIEW_MS).setInterpolator(new DecelerateInterpolator()).withEndAction(()->face.removeView(preview)).start();
            if(bookCover!=null){bookCover.animate().cancel();bookCover.animate().alpha(1f).setDuration(DRAG_PREVIEW_MS).setInterpolator(new DecelerateInterpolator()).start();}
            if(book!=null){name.animate().cancel();text(name,book.optString("title","未命名"));name.setAlpha(0f);name.animate().alpha(1f).setDuration(DRAG_NAME_MS).start();}
        }
        /** A hovered book turns into the same folder face a real folder uses, then opens on release. */
        void showFolderPreview(){
            if(folderPreview!=null||book==null||book.optBoolean("book_folder"))return;
            Bitmap target=snapshotFace(face);
            FrameLayout preview=folderFace(0,2,index->index==0?bitmapCell(target):index==1?bitmapCell(dragCover):emptyFolderCell(6));
            preview.setAlpha(0f);preview.setScaleX(DRAG_PREVIEW_SCALE);preview.setScaleY(DRAG_PREVIEW_SCALE);
            folderPreview=preview;face.addView(preview,new FrameLayout.LayoutParams(-1,-1));
            showDropTarget();
            if(bookCover!=null){bookCover.animate().cancel();bookCover.animate().alpha(0f).setDuration(DRAG_PREVIEW_MS).setInterpolator(new DecelerateInterpolator()).start();}
            preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(DRAG_PREVIEW_MS).setInterpolator(new DecelerateInterpolator()).start();
            name.animate().cancel();name.animate().alpha(0f).setDuration(DRAG_NAME_MS).setInterpolator(new DecelerateInterpolator()).withEndAction(()->{if(folderPreview!=preview)return;name.setText("新建书夹");name.animate().alpha(1f).setDuration(DRAG_NAME_MS).start();}).start();
        }
    }
    private void retryMissingCovers(View view){if(view instanceof ImageView){ImageView image=(ImageView)view;if(image.getDrawable()==null&&image.getTag() instanceof JSONObject){JSONObject b=(JSONObject)image.getTag();if(!b.has("sourceId")||(OnlineHistory.isJm(b)&&!b.optString("thumbnail").isEmpty()))covers.load(image,b);}}else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)retryMissingCovers(group.getChildAt(n));}}
    /** Canvas snapshots do not apply child outline clips; keep the same clip in software. */
    private static class RoundedCover extends FrameLayout {
        private final android.graphics.Path clip=new android.graphics.Path();private final float radius;
        RoundedCover(android.content.Context context,int dp){super(context);radius=dp*context.getResources().getDisplayMetrics().density;}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);clip.reset();clip.addRoundRect(new android.graphics.RectF(0,0,w,h),radius,radius,android.graphics.Path.Direction.CW);}
        @Override protected void dispatchDraw(Canvas canvas){if(canvas.isHardwareAccelerated()){super.dispatchDraw(canvas);return;}int save=canvas.save();canvas.clipPath(clip);super.dispatchDraw(canvas);canvas.restoreToCount(save);}
    }
    private static final class TileShadow extends View.DragShadowBuilder {
        private final Bitmap snapshot;private final Point touch=new Point();
        TileShadow(View view,int x,int y){super(view);int w=Math.max(1,view.getWidth()),h=Math.max(1,view.getHeight());snapshot=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(snapshot);float radius=12f*view.getResources().getDisplayMetrics().density;int coverHeight=view instanceof ShelfTile?((ShelfTile)view).face.getHeight():h;coverHeight=Math.max(1,Math.min(h,coverHeight));android.graphics.Path clip=new android.graphics.Path();clip.addRoundRect(new android.graphics.RectF(0,0,w,coverHeight),radius,radius,android.graphics.Path.Direction.CW);if(coverHeight<h)clip.addRect(0,coverHeight,w,h,android.graphics.Path.Direction.CW);canvas.clipPath(clip);view.draw(canvas);touch.set(Math.max(0,Math.min(w,x)),Math.max(0,Math.min(h,y)));}
        @Override public void onProvideShadowMetrics(Point size,Point touchPoint){size.set(snapshot.getWidth(),snapshot.getHeight());touchPoint.set(touch.x,touch.y);}
        @Override public void onDrawShadow(Canvas canvas){int save=canvas.save();canvas.scale(.97f,.97f,snapshot.getWidth()/2f,snapshot.getHeight()/2f);canvas.drawBitmap(snapshot,0,0,null);canvas.restoreToCount(save);}
    }
    private interface FolderCellFactory {View create(int index);}
    /**
     * One collage for real folders and for the drag preview, so a book that becomes a folder keeps
     * exactly the same face during the transition.
     */
    private FrameLayout folderFace(int depth,int count,FolderCellFactory factory){
        FrameLayout face=new RoundedCover(this,16);
        face.setBackground(u.shape(u.dark?0xff252b39:0xffe8edf8,16));
        face.setClipToOutline(true);
        LinearLayout collage=u.column();
        int padding=Math.max(1,8>>depth);
        collage.setPadding(u.dp(padding),u.dp(padding),u.dp(padding),u.dp(padding));
        int margin=Math.max(1,2>>depth);
        for(int row=0;row<2;row++){
            LinearLayout line=u.row();
            for(int col=0;col<2;col++){
                View cell=factory.create(row*2+col);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-1,1);
                cp.setMargins(u.dp(margin),u.dp(margin),u.dp(margin),u.dp(margin));
                line.addView(cell,cp);
            }
            collage.addView(line,new LinearLayout.LayoutParams(-1,0,1));
        }
        face.addView(collage,new FrameLayout.LayoutParams(-1,-1));
        if(depth==0){
            TextView countText=u.text(String.valueOf(count),10,Color.WHITE,true);
            countText.setGravity(17);countText.setBackground(u.shape(0xb923304c,8));
            FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(u.dp(24),u.dp(20),Gravity.RIGHT|Gravity.BOTTOM);
            badge.setMargins(0,0,u.dp(8),u.dp(8));face.addView(countText,badge);
        }
        return face;
    }
    private View emptyFolderCell(int radius){View cell=new View(this);cell.setBackground(u.shape(u.dark?0xff30394b:0xffd9e1f1,radius));return cell;}
    private View bitmapCell(Bitmap bitmap){
        RoundedCover holder=new RoundedCover(this,12);
        holder.setBackground(u.shape(u.tint,12));holder.setClipToOutline(true);
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if(bitmap!=null)image.setImageBitmap(bitmap);
        holder.addView(image,new FrameLayout.LayoutParams(-1,-1));
        return holder;
    }
    /** Nested folders are rendered recursively so the outer preview shows 书籍夹中夹 like books. */
    private View folderMember(JSONObject item,int depth){
        if(!item.optBoolean("book_folder"))return cover(item,true);
        if(depth>=FOLDER_PREVIEW_DEPTH)return flatFolderCell(item.optString("id"),2);
        return folderCover(item,depth+1);
    }
    private View flatFolderCell(String folderId,int guard){
        List<JSONObject> nested=folderCovers.members(folderId);
        RoundedCover holder=new RoundedCover(this,8);
        holder.setBackground(u.shape(u.dark?0xff30394b:0xffd9e1f1,8));holder.setClipToOutline(true);
        JSONObject first=nested.isEmpty()?null:nested.get(0);
        View inner=null;
        if(first!=null){if(!first.optBoolean("book_folder"))inner=cover(first,true);else if(guard>0)inner=flatFolderCell(first.optString("id"),guard-1);}
        if(inner!=null){FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);int inset=u.dp(2);lp.setMargins(inset,inset,inset,inset);holder.addView(inner,lp);}
        return holder;
    }
    private FrameLayout folderCover(JSONObject f,int width,int height){return folderCover(f,0);}
    private FrameLayout folderCover(JSONObject f,int depth){
        List<JSONObject> members=folderCovers.members(f.optString("id"));
        return folderFace(depth,members.size(),index->index>=members.size()?emptyFolderCell(depth==0?6:4):folderMember(members.get(index),depth));
    }
}

package com.joe.jmlite;

import android.app.Activity;
import android.app.AlertDialog;
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
    private LinearLayout content,taskCard,nav;
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
    private int tab=1,refreshGeneration=0;
    private boolean resumed=false,reading=false,starting=false,operation=false,revealed=true;
    private String rowsSnapshot="",draft="",searchDraft="",taskId="",folderId="";
    private JSONObject pendingShare;
    private JSONObject draggingBook;
    private final ArrayList<JSONObject> dragRows=new ArrayList<>();
    private JSONObject dragTarget;
    private int dragZone=ShelfOrder.NONE;
    private Bitmap dragCover;
    private final HashMap<String,Integer> dragPreviewPositions=new HashMap<>();
    private float dragPointerX,dragPointerY;
    private final Runnable shelfAutoScroll=new Runnable(){public void run(){
        if(draggingBook==null||grid==null||!resumed)return;
        int edge=u.dp(56),direction=dragPointerY<edge?-1:dragPointerY>grid.getHeight()-edge?1:0;
        if(direction==0||!grid.canScrollVertically(direction))return;
        grid.smoothScrollBy(direction*u.dp(18),50);previewShelfDrag(dragPointerX,dragPointerY);handler.postDelayed(this,50);
    }};
    private boolean dragMoved,taskShown;
    private float dragX,dragY;
    private ValueAnimator actionAnimator;
    private FirstRunGuide firstRunGuide;
    private boolean privateShelf;
    private int navigationInset;
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
        super.onCreate(b);u=new Ui(this);store=new Store(this);library=new Library(this);covers=new CoverLoader(this);prefs=getSharedPreferences("settings",MODE_PRIVATE);revealed=prefs.getBoolean("history_revealed",true);
        if(b!=null){tab=b.getInt("tab",1);draft=b.getString("draft","");searchDraft=b.getString("search","");folderId=b.getString("folder","");taskId=b.getString("task_id","");}
        LinearLayout root=u.column();root.setBackgroundColor(u.bg);Ui.insets(this,root,u.dark);
        LinearLayout heading=u.row();heading.setPadding(u.dp(16),u.dp(8),u.dp(12),u.dp(12));
        back=u.icon("back",u.ink);back.setContentDescription("返回书架");heading.addView(back,new LinearLayout.LayoutParams(u.dp(40),u.dp(44)));back.setOnClickListener(v->leaveFolder());
        title=u.text("简阅",23,u.ink,true);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(u.dp(4),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,u.dp(48),1));title.setGravity(Gravity.CENTER_VERTICAL);
        plus=u.icon("add",u.ink);plus.setContentDescription("导入漫画");heading.addView(plus,new LinearLayout.LayoutParams(u.dp(44),u.dp(44)));plus.setOnClickListener(v->importMenu());
        repoHint=u.text("<不行请尝试魔法~>",11,u.muted,false);repoHint.setContentDescription("关于简阅与开源许可");repoHint.setPadding(0,u.dp(12),0,u.dp(12));repoHint.setOnClickListener(v->license());heading.addView(repoHint);
        shelfSearch=new HeaderSearch(this,heading,title,plus,"搜索书架编号或名称",query->{shelfQuery=query;rowsSnapshot="";if(tab==1)render();});root.addView(heading);
        FrameLayout stage=new FrameLayout(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));content=u.column();stage.addView(content,new FrameLayout.LayoutParams(-1,-1));
        nav=u.row();nav.setClickable(true);nav.setGravity(Gravity.CENTER);nav.setPadding(u.dp(6),u.dp(5),u.dp(6),u.dp(5));GradientDrawable navBg=u.shape(u.card,28);navBg.setStroke(u.dp(1),u.dark?0x1affffff:0x0d000000);nav.setBackground(navBg);nav.setElevation(u.dp(10));nav.setClipToOutline(true);
        downloadNav=new NavItem(0,"download","下载");localNav=new NavItem(1,"shelf","书架");onlineNav=new NavItem(2,"history","历史");repositoryNav=new NavItem(3,"repository","仓库");
        nav.addView(localNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));nav.addView(downloadNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));nav.addView(onlineNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));nav.addView(repositoryNav,new LinearLayout.LayoutParams(u.dp(60),u.dp(46)));FrameLayout.LayoutParams capsule=new FrameLayout.LayoutParams(-2,u.dp(56),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);capsule.bottomMargin=u.dp(4);stage.addView(nav,capsule);setContentView(root);ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());boolean keyboard=insets.isVisible(WindowInsetsCompat.Type.ime());navigationInset=insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;v.setPadding(bars.left,bars.top,bars.right,keyboard?insets.getInsets(WindowInsetsCompat.Type.ime()).bottom:0);FrameLayout.LayoutParams navParams=(FrameLayout.LayoutParams)nav.getLayoutParams();int margin=u.dp(4)+navigationInset;if(navParams.bottomMargin!=margin){navParams.bottomMargin=margin;nav.setLayoutParams(navParams);}if(grid!=null&&tab!=0){int padding=u.dp(privateShelf?20:88)+navigationInset;if(grid.getPaddingBottom()!=padding)grid.setPadding(grid.getPaddingLeft(),grid.getPaddingTop(),grid.getPaddingRight(),padding);}if(list!=null&&tab==0){int padding=u.dp(88)+navigationInset;if(list.getPaddingBottom()!=padding)list.setPadding(list.getPaddingLeft(),list.getPaddingTop(),list.getPaddingRight(),padding);}nav.setVisibility(keyboard||privateShelf?View.GONE:View.VISIBLE);return insets;});
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{if(isFinishing()||isDestroyed())return;Rect visible=new Rect();root.getWindowVisibleDisplayFrame(visible);boolean keyboard=root.getRootView().getHeight()-visible.bottom>u.dp(120);nav.setVisibility(keyboard||privateShelf?View.GONE:View.VISIBLE);});
        buildTab();
        firstRunGuide=new FirstRunGuide(this,prefs,()->{if(predictiveBack!=null)predictiveBack.refresh();ForegroundUpdates.checkAfterGuide();});
        predictiveBack=new PredictiveBack(this,root,()->firstRunGuide==null||!firstRunGuide.isShowing(),()->!shelfSearch.isExpanded()&&(tab!=1||(!privateShelf&&folderId.isEmpty())),()->tab==1&&(privateShelf||!folderId.isEmpty()),this::onBackPressed);
        handleShelfJump(getIntent());
        shelfSearch.setOnExpansionChanged(()->{if(privateShelf)plus.setVisibility(View.GONE);if(predictiveBack!=null)predictiveBack.refresh();});
    }
    private final class NavItem extends LinearLayout {
        final Ui.Icon icon;final TextView label;final int index;
        NavItem(int index,String kind,String text){
            super(MainActivity.this);this.index=index;setOrientation(VERTICAL);setGravity(Gravity.CENTER);setPadding(0,u.dp(3),0,u.dp(3));
            icon=u.icon(kind,u.muted);icon.glyphScale=.8f;icon.setMinimumWidth(0);icon.setMinimumHeight(0);addView(icon,new LinearLayout.LayoutParams(u.dp(22),u.dp(22)));
            label=u.text(text,10,u.muted,false);label.setGravity(Gravity.CENTER);label.setIncludeFontPadding(false);label.setPadding(0,u.dp(2),0,0);addView(label,new LinearLayout.LayoutParams(-1,u.dp(14)));
            setContentDescription(text);setOnClickListener(v->switchTab(index));
        }
        void active(boolean on){icon.color(on?Ui.BLUE:u.muted);label.setTextColor(on?Ui.BLUE:u.muted);u.round(this,on?u.tint:Color.TRANSPARENT,23);setSelected(on);}
    }
    private void switchTab(int next){if(tab==next&&!privateShelf)return;privateShelf=false;library=new Library(this);if(input!=null)draft=input.getText().toString();if(searchInput!=null)searchDraft=searchInput.getText().toString();shelfSearch.collapse();tab=next;folderId="";if(tab==2){onlineBooks.clear();rows.clear();}buildTab();}
    private void buildTab(){
        if(actionAnimator!=null){actionAnimator.cancel();actionAnimator=null;}
        content.removeAllViews();input=null;searchInput=null;taskCard=null;actionSlot=null;downloadActions=null;taskAction=null;taskShown=false;list=null;grid=null;adapter=null;shelfAdapter=null;onlineAdapter=null;rowsSnapshot="";
        shelfSearch.setVisible(tab==1);title.setText(tab==0?"下载":tab==1?folderTitle():tab==3?"仓库":"历史");plus.setVisibility(tab==1&&!privateShelf&&!shelfSearch.isExpanded()?View.VISIBLE:View.GONE);back.setVisibility(tab==1&&(privateShelf||!folderId.isEmpty())?View.VISIBLE:View.GONE);
        repoHint.setVisibility(tab==3?View.VISIBLE:View.GONE);repositoryNav.active(tab==3);downloadNav.active(tab==0);localNav.active(tab==1);onlineNav.active(tab==2);
        if(tab==0)buildDownload();else if(tab==1)buildShelf();else if(tab==3)content.addView(RepositoryPanel.create(this),new LinearLayout.LayoutParams(-1,-1));else buildOnline();render();
        if(tab==2&&resumed)refresh(true);
        if(predictiveBack!=null)predictiveBack.refresh();
    }
    private void buildDownload(){
        list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);list.setPadding(u.dp(20),0,u.dp(20),u.dp(88)+navigationInset);
        list.setItemsCanFocus(true);
        LinearLayout header=u.column(),card=u.column();card.setPadding(u.dp(18),u.dp(20),u.dp(18),u.dp(18));u.round(card,u.card,22);card.addView(u.text("JM Comic",18,u.ink,true));u.space(card,16);
        header.setFocusableInTouchMode(true);
        input=new EditText(this);input.setId(1001);input.setSingleLine(true);input.setTextSize(15);input.setTextColor(u.ink);input.setHintTextColor(u.muted);input.setHint("输入 JM 编号或链接");input.setText(draft);input.setPadding(u.dp(15),0,u.dp(15),0);input.setSelectAllOnFocus(true);u.round(input,u.bg,14);card.addView(input,new LinearLayout.LayoutParams(-1,u.dp(52)));u.space(card,14);
        actionSlot=new FrameLayout(this);downloadActions=u.row();start=u.button("开始下载",true);start.setTextSize(14);start.setMinHeight(u.dp(48));start.setId(1002);start.setOnClickListener(v->download());LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(0,u.dp(48),1);d.setMargins(0,0,u.dp(10),0);downloadActions.addView(start,d);TextView online=u.button("在线阅读",false);online.setTextSize(14);online.setMinHeight(u.dp(48));online.setId(1003);online.setOnClickListener(v->online());downloadActions.addView(online,new LinearLayout.LayoutParams(0,u.dp(48),1));actionSlot.addView(downloadActions,new FrameLayout.LayoutParams(-1,u.dp(48)));card.addView(actionSlot,new LinearLayout.LayoutParams(-1,-2));header.addView(card);u.space(header,12);
        Ui.clearable(input);
        LinearLayout searchCard=u.row();searchCard.setPadding(u.dp(10),0,u.dp(8),0);u.round(searchCard,u.card,18);
        Ui.Icon find=u.icon("search",u.muted);find.glyphScale=.8f;find.setMinimumWidth(0);find.setMinimumHeight(0);searchCard.addView(find,new LinearLayout.LayoutParams(u.dp(30),u.dp(54)));
        searchInput=new EditText(this);searchInput.setId(1006);searchInput.setSingleLine(true);searchInput.setTextSize(15);searchInput.setTextColor(u.ink);searchInput.setHintTextColor(u.muted);searchInput.setHint("搜索全网漫画");searchInput.setText(searchDraft);searchInput.setBackgroundColor(Color.TRANSPARENT);searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);searchInput.setPadding(u.dp(6),0,u.dp(6),0);
        Ui.magicWandHint(searchInput);Ui.clearable(searchInput);
        searchInput.setOnEditorActionListener((v,actionId,event)->{if(actionId==EditorInfo.IME_ACTION_SEARCH){searchOnline();return true;}return false;});
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
        LinearLayout caption=u.row();caption.setPadding(u.dp(2),u.dp(12),0,u.dp(6));TextView recent=u.text("最近下载  ›",16,u.ink,true);recent.setContentDescription("查看全部下载记录");recent.setPadding(0,u.dp(10),0,u.dp(10));recent.setOnClickListener(v->startActivity(new Intent(this,AllDownloadsActivity.class)));caption.addView(recent,new LinearLayout.LayoutParams(0,-2,1));eye=u.icon(revealed?"eye":"eye-off",u.muted);eye.setId(1004);eye.setContentDescription(revealed?"隐藏封面和书名":"显示封面和书名");caption.addView(eye,new LinearLayout.LayoutParams(u.dp(40),u.dp(40)));eye.setOnClickListener(v->togglePrivacy());header.addView(caption);
        header.requestFocus();list.addHeaderView(header,null,false);adapter=new BookAdapter();list.setAdapter(adapter);list.setOnItemClickListener((p,v,pos,id)->{int n=pos-list.getHeaderViewsCount();if(n>=0&&n<rows.size())open(rows.get(n));});list.setOnItemLongClickListener((p,v,pos,id)->{int n=pos-list.getHeaderViewsCount();if(n<0||n>=rows.size()||rows.get(n).optBoolean("empty"))return false;manage(rows.get(n));return true;});content.addView(list,new LinearLayout.LayoutParams(-1,-1));
    }
    private void buildShelf(){
        PrivacyPullHost frame=new PrivacyPullHost(this);grid=new GridView(this);grid.setId(1005);grid.setNumColumns(Math.max(3,Math.min(6,getResources().getConfiguration().screenWidthDp/120)));grid.setHorizontalSpacing(u.dp(12));grid.setVerticalSpacing(u.dp(18));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setPadding(u.dp(20),u.dp(6),u.dp(20),u.dp(privateShelf?20:88)+navigationInset);grid.setClipToPadding(false);grid.setSelector(android.R.color.transparent);shelfAdapter=new ShelfAdapter();grid.setAdapter(shelfAdapter);frame.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        emptyShelf=u.text(tab==2?"暂无浏览记录":"书架还空着",15,u.muted,false);emptyShelf.setVisibility(tab==2?View.GONE:View.VISIBLE);emptyShelf.setGravity(Gravity.CENTER);frame.addView(emptyShelf,new FrameLayout.LayoutParams(-1,-1));emptyShelf.setOnClickListener(v->{if(tab==1&&!privateShelf)importMenu();});if(tab==1&&!privateShelf)attachPrivacyPull(frame);content.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        grid.setClipChildren(false);
        grid.setOnDragListener((v,e)->{
            if(draggingBook==null)return false;
            switch(e.getAction()){
                case DragEvent.ACTION_DRAG_LOCATION:
                    if(Math.hypot(e.getX()-dragX,e.getY()-dragY)>u.dp(12))dragMoved=true;
                    dragPointerX=e.getX();dragPointerY=e.getY();previewShelfDrag(dragPointerX,dragPointerY);handler.removeCallbacks(shelfAutoScroll);handler.post(shelfAutoScroll);return true;
                case DragEvent.ACTION_DRAG_EXITED:handler.removeCallbacks(shelfAutoScroll);clearShelfPreview();return true;
                case DragEvent.ACTION_DROP:
                    previewShelfDrag(e.getX(),e.getY());
                    if(dragTarget==null||dragZone==ShelfOrder.NONE||operation)return false;
                    dragMoved=true;drop(dragTarget,draggingBook,dragZone);return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    handler.removeCallbacks(shelfAutoScroll);
                    JSONObject item=draggingBook;boolean menu=!e.getResult()&&!dragMoved;
                    if(!e.getResult()){clearShelfPreview();for(int n=0;n<grid.getChildCount();n++)grid.getChildAt(n).setAlpha(1f);}
                    else{dragTarget=null;dragZone=ShelfOrder.NONE;rowsSnapshot="";}
                    draggingBook=null;dragRows.clear();dragCover=null;dragPreviewPositions.clear();
                    refresh(true);
                    if(menu&&item!=null&&resumed&&!isDestroyed()){if(item.optBoolean("book_folder"))manageFolder(item);else manageBook(item);}
                    return true;
                default:return true;
            }
        });
    }
    private String folderTitle(){for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id")))return f.optString("title","书籍夹");return privateShelf?"隐私书架":"书架";}
    private void leaveFolder(){if(folderId.isEmpty()&&privateShelf){setPrivateShelf(false);return;}String parent="";for(JSONObject f:bookFolders)if(folderId.equals(f.optString("id")))parent=f.optString("parent");folderId=parent;buildTab();}

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleShelfJump(intent);}
    private void handleShelfJump(Intent intent){String key=intent.getStringExtra("shelf_jump");if(key==null)return;intent.removeExtra("shelf_jump");Runnable jump=()->disk.execute(()->{try{JSONObject book=new Library(this).find(key);runOnUiThread(()->{if(book==null||isDestroyed())return;Runnable navigate=()->{tab=1;setPrivateShelf(book.optBoolean("privacy"));folderId=book.optString("folder");buildTab();refresh(true);};if(book.optBoolean("privacy"))PrivacyVault.enter(this,navigate);else navigate.run();});}catch(Exception e){runOnUiThread(()->toast("书籍已不存在"));}});if(intent.getBooleanExtra("shelf_private",false))PrivacyVault.enter(this,jump);else jump.run();}
    private void setPrivateShelf(boolean hidden){privateShelf=hidden;library=new Library(this,hidden);folderId="";shelfSearch.collapse();libraryBooks.clear();folderCovers=FolderCoverIndex.from(java.util.Collections.emptyList());bookFolders.clear();rowsSnapshot="";buildTab();refresh(true);}
    private void transferPrivacy(JSONObject book,boolean folder){Runnable move=()->mutate(()->library.transfer(folder?book.optString("id"):Library.key(book),folder,!privateShelf));if(privateShelf)move.run();else PrivacyVault.beforeFirstUse(this,move);}
    private void attachPrivacyPull(PrivacyPullHost frame){
        final GridView pullGrid=grid;final TextView pullEmpty=emptyShelf;
        final float[] start={-1},distance={0},startX={0};final boolean[] pulling={false};final float threshold=getResources().getDisplayMetrics().heightPixels/3f;
        PrivacyPull ring=new PrivacyPull(this);frame.addView(ring,new FrameLayout.LayoutParams(-1,(int)threshold,Gravity.TOP));
        View.OnTouchListener pullListener=(v,event)->{if(draggingBook!=null||!folderId.isEmpty()||shelfSearch.isExpanded())return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){start[0]=event.getRawY();startX[0]=event.getRawX();pulling[0]=false;return false;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&start[0]>=0){float delta=event.getRawY()-start[0];boolean top=pullGrid.getFirstVisiblePosition()==0&&(pullGrid.getChildCount()==0||pullGrid.getChildAt(0).getTop()>=pullGrid.getPaddingTop());if(!pulling[0]&&!top){start[0]=event.getRawY();startX[0]=event.getRawX();return false;}if(!pulling[0]&&Math.abs(event.getRawX()-startX[0])>Math.abs(delta))return false;if(!pulling[0]&&top&&delta>u.dp(12)){pulling[0]=true;}if(pulling[0]){distance[0]=Math.max(0,Math.min(threshold,delta));ring.progress=distance[0]/threshold;ring.invalidate();pullGrid.setTranslationY(distance[0]);pullEmpty.setTranslationY(distance[0]);return true;}}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){boolean complete=event.getActionMasked()==MotionEvent.ACTION_UP&&distance[0]>=threshold;start[0]=-1;if(pulling[0]){pulling[0]=false;ValueAnimator a=ValueAnimator.ofFloat(distance[0],0);a.setDuration(180);a.addUpdateListener(anim->{float value=(float)anim.getAnimatedValue();ring.progress=value/threshold;ring.invalidate();pullGrid.setTranslationY(value);pullEmpty.setTranslationY(value);});a.start();distance[0]=0;if(complete)PrivacyVault.enterFromShelf(this,()->setPrivateShelf(true));return true;}}return false;};frame.pull=pullListener;
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
        if(DownloadService.running){toast("请先暂停当前任务");return;}
        final String id;
        try{id=JmApi.parseId(input.getText().toString());}catch(Exception e){input.setError("请输入有效编号或 JM 链接");return;}
        draft=input.getText().toString();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},42);
        starting=true;
        disk.execute(()->{try{JSONObject b=new Library(this).findLocalDownload(id);runOnUiThread(()->{starting=false;if(isFinishing()||isDestroyed()||!resumed)return;if(b!=null){DownloadCollision.show(this,b);return;}beginDownload(id,"已开始下载");});}catch(Exception e){runOnUiThread(()->{starting=false;if(!isDestroyed())toast("无法读取下载记录");});}});
    }
    private void online(){
        final String id;try{id=JmApi.parseId(input.getText().toString());}catch(IllegalArgumentException e){input.setError("请输入有效编号或 JM 链接");return;}
        draft=input.getText().toString();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        startActivity(new Intent().setClassName(this,"com.joe.jmlite.MihonEntryActivity").putExtra("online_id",id).putExtra("title","JM"+id+" · 在线阅读"));
    }
    private void searchOnline(){
        if(searchInput==null)return;
        String query=searchInput.getText().toString().trim();
        if(query.isEmpty()){searchInput.setError("请输入漫画名");return;}
        searchDraft=query;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchInput.getWindowToken(),0);
        startActivity(new Intent(this,OnlineSearchActivity.class).putExtra("query",query));
    }
    private void buildOnline(){
        historyLoaded=false;
        LinearLayout heading=u.row();heading.setPadding(u.dp(20),0,u.dp(20),u.dp(10));heading.addView(u.text("在线漫画",16,u.ink,true),new LinearLayout.LayoutParams(0,u.dp(42),1));
        TextView jm=u.button("JM漫画",jmHistory),web=u.button("全网漫画",!jmHistory);jm.setTextSize(12);web.setTextSize(12);
        heading.addView(jm,new LinearLayout.LayoutParams(u.dp(78),u.dp(36)));heading.addView(web,new LinearLayout.LayoutParams(u.dp(88),u.dp(36)));
        jm.setOnClickListener(v->{if(!jmHistory){jmHistory=true;onlineBooks.clear();rows.clear();buildTab();}});web.setOnClickListener(v->{if(jmHistory){jmHistory=false;onlineBooks.clear();rows.clear();buildTab();}});content.addView(heading);buildShelf();
    }
    /** All online cover taps land on a details screen before reading. */
    private void openOnline(JSONObject o){
        if(OnlineHistory.isJm(o)){
            startActivity(new Intent(this,BookInfoActivity.class).putExtra("book",o.toString()));
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
                FolderCoverIndex coverIndex=FolderCoverIndex.from(local);
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
        if(tab==2){rows.clear();rows.addAll(onlineBooks);String snap=new JSONArray(rows).toString();if(!snap.equals(rowsSnapshot)){rowsSnapshot=snap;if(shelfAdapter!=null)shelfAdapter.notifyDataSetChanged();}paintHistoryEmpty();return;}
        rows.clear();if(tab==0){rows.addAll(downloadRecords.subList(0,Math.min(3,downloadRecords.size())));if(rows.isEmpty())rows.add(empty());}
        else{if(shelfQuery.trim().isEmpty())for(JSONObject f:bookFolders)if(folderId.equals(f.optString("parent"))){try{JSONObject row=new JSONObject(f.toString());row.put("book_folder",true);rows.add(row);}catch(Exception ignored){}}rows.addAll(BookSearch.shelf(libraryBooks,folderId,shelfQuery));title.setText(folderTitle());if(emptyShelf!=null){emptyShelf.setText(shelfQuery.trim().isEmpty()?"书架还空着":"没有匹配的书籍");emptyShelf.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);}}
        JSONArray visible=new JSONArray();for(JSONObject b:rows)visible.put(new JSONArray().put(b.optString("key")).put(b.optString("id")).put(b.optString("title")).put(b.optString("folder")).put(b.optInt("done")).put(b.optString("uri")).put(b.optBoolean("empty")).put(b.optBoolean("book_folder")).put(b.optLong("completedAt")).put(b.optInt("downloadedChapters")).put(b.optString("thumbnail")).put(b.optBoolean("book_folder")?folderCovers.signature(b.optString("id")):""));
        String snapshot=visible.toString()+revealed;if(!snapshot.equals(rowsSnapshot)){rowsSnapshot=snapshot;if(adapter!=null)adapter.notifyDataSetChanged();if(shelfAdapter!=null)shelfAdapter.notifyDataSetChanged();}updateTask();
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
        if(b.optBoolean("empty"))return;if(b.optBoolean("download_history")){DownloadRecordActions.open(this,b,disk);return;}if(b.has("sourceId")){openOnline(b);return;}if(b.optBoolean("book_folder")){folderId=b.optString("id");buildTab();PageMotion.folder(content);return;}
        startActivity(new Intent(this,BookInfoActivity.class).putExtra("book",b.toString()));
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
        AlertDialog dlg=PredictiveBack.dialog(this).setCustomTitle(head).setItems(new String[]{"重命名书籍","书籍分类","删除","分享",privateShelf?"从隐私书架移除":"添加到隐私书架"},(d,n)->{if(n==0)nameDialog("重命名书籍",b.optString("title"),name->mutate(()->library.rename(b.optString("key"),name)));else if(n==1)classify(b);else if(n==2)confirmDelete(b,false);else if(n==3)share(b);else transferPrivacy(b,false);}).create();
        fav.setOnClickListener(v->{dlg.dismiss();setFavorite(b,!favorite);});
        dlg.show();
    }
    private void manageOnlineBook(JSONObject b){
        boolean fav=false;for(JSONObject saved:libraryBooks)if(Library.key(saved).equals(b.optString("key")))fav=saved.optBoolean("favorite",true);final boolean favorite=fav;
        String caption=b.optString("title")+(OnlineHistory.isJm(b)?"\n编号："+b.optString("url"):"");
        PredictiveBack.dialog(this).setTitle(caption).setItems(tab==1?new String[]{favorite?"♥ 取消收藏":"♡ 收藏", "重命名书籍","书籍分类","删除",privateShelf?"从隐私书架移除":"添加到隐私书架"}:new String[]{favorite?"♥ 取消收藏":"♡ 收藏", "重命名书籍","书籍分类","删除"},(d,n)->{
            if(n==0)mutate(()->library.favoriteOnline(b,!favorite));
            else if(n==1)nameDialog("重命名书籍",b.optString("title"),name->mutate(()->{b.put("title",name).put("customTitle",name);new OnlineHistory(this,OnlineHistory.isJm(b)).save(b);if(favorite)library.rename(b.optString("key"),name);}));
            else if(n==2){if(tab==1||favorite)classify(b);else {operation=true;disk.execute(()->{try{library.favoriteOnline(b,true);runOnUiThread(()->{operation=false;refresh();classify(b);});}catch(Exception e){runOnUiThread(()->{operation=false;toast("分类失败");});}});}}
            else if(n==4&&tab==1)transferPrivacy(b,false);
            else if(n==3)PredictiveBack.dialog(this).setTitle("移除这本漫画？").setMessage("移出历史和在线书架，已下载图片保留。").setNegativeButton("取消",null).setPositiveButton("移除",(x,y)->mutate(()->{new OnlineHistory(this,OnlineHistory.isJm(b)).remove(b.optString("sourceId"),b.optString("url"));library.delete(b.optString("key"));})).show();
        }).show();
    }
    private String plate(JSONObject b){String type=b.optString("type");if("import".equals(type))return "本地导入";if("tree".equals(type))return "本地文件夹";return b.optString("id","—");}
    private boolean isFavorite(JSONObject b){if(b.optBoolean("favorite"))return true;for(JSONObject f:bookFolders)if("favorites".equals(f.optString("system"))||Library.FAVORITES.equals(f.optString("title")))return f.optString("id").equals(b.optString("folder"));return false;}
    private void setFavorite(JSONObject b,boolean favorite){
        if(operation){toast("请等待当前操作完成");return;}operation=true;
        disk.execute(()->{String error=null;try{library.favorite(b.optString("key"),favorite);}catch(Exception e){error=e.getMessage();}
            String message=error;runOnUiThread(()->{operation=false;if(isDestroyed())return;if(message!=null){toast(message.isEmpty()?"操作失败":message);return;}toast(favorite?"已加入我的收藏":"已取消收藏");refresh();});});
    }
    private void manageFolder(JSONObject f){
        String parent=f.optString("parent");int index=-1,count=0;
        for(JSONObject item:bookFolders)if(parent.equals(item.optString("parent"))){if(f.optString("id").equals(item.optString("id")))index=count;count++;}
        boolean canUp=index>0,canDown=index>=0&&index<count-1;
        ArrayList<String> items=new ArrayList<>();if(!Library.isProtectedFolder(f))items.add("重命名书籍夹");if(canUp)items.add("上移");if(canDown)items.add("下移");items.add("删除书籍夹");items.add(privateShelf?"从隐私书架移除":"添加到隐私书架");
        PredictiveBack.dialog(this).setTitle(f.optString("title")).setItems(items.toArray(new String[0]),(d,n)->{
            String action=items.get(n);
            if("重命名书籍夹".equals(action))nameDialog("重命名书籍夹",f.optString("title"),name->mutate(()->library.renameFolder(f.optString("id"),name)));
            else if("上移".equals(action))mutate(()->library.moveFolder(f.optString("id"),-1));
            else if("下移".equals(action))mutate(()->library.moveFolder(f.optString("id"),1));
            else if(action.equals("添加到隐私书架")||action.equals("从隐私书架移除"))transferPrivacy(f,true);
            else PredictiveBack.dialog(this).setTitle("删除书籍夹？").setMessage("夹内书籍会回到书架。").setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->mutate(()->library.deleteFolder(f.optString("id")))).show();
        }).show();
    }
    private interface Named {void run(String name);}
    private interface DiskAction {void run()throws Exception;}
    private void nameDialog(String caption,String value,Named done){EditText edit=new EditText(this);edit.setSingleLine(true);edit.setTextSize(16);edit.setText(value);edit.setSelectAllOnFocus(true);Ui.clearable(edit);LinearLayout box=u.column();box.setPadding(u.dp(24),u.dp(8),u.dp(24),0);box.addView(edit);AlertDialog d=PredictiveBack.dialog(this).setTitle(caption).setView(box).setNegativeButton("取消",null).setPositiveButton("确定",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String name=edit.getText().toString().trim();if(name.isEmpty()){edit.setError("请输入名称");return;}if(name.length()>100){edit.setError("名称最多 100 个字");return;}d.dismiss();done.run(name);}));d.show();edit.requestFocus();}
    private void classify(JSONObject b){ArrayList<String> names=new ArrayList<>(),ids=new ArrayList<>();for(JSONObject f:bookFolders){names.add(f.optString("title"));ids.add(f.optString("id"));}names.add("＋ 创建书籍夹");if(!b.optString("folder").isEmpty())names.add(privateShelf?"移回隐私书架":"移回书架");PredictiveBack.dialog(this).setTitle("书籍分类").setItems(names.toArray(new String[0]),(d,n)->{if(n<ids.size())mutate(()->library.move(b.optString("key"),ids.get(n)));else if(n==ids.size())nameDialog("创建书籍夹","",name->mutate(()->library.moveToNewFolder(b.optString("key"),name,folderId)));else mutate(()->library.move(b.optString("key"),""));}).show();}
    private void confirmDelete(JSONObject b,boolean history){boolean tree="tree".equals(b.optString("type"));PredictiveBack.dialog(this).setTitle(history?"删除已下载图片？":tree?"移除这本书？":"删除这本书？").setMessage(tree?"原始文件夹和图片会保留。":"将删除本书及内置目录中的图片。").setNegativeButton("取消",null).setPositiveButton("确定",(d,n)->mutate(()->{if(DownloadService.running&&b.optString("id").equals(DownloadService.activeId))throw new IllegalStateException("请先暂停下载");library.delete(b.optString("key","download:"+b.optString("id")));})).show();}
    private void mutate(DiskAction action){if(operation){toast("请等待当前操作完成");return;}operation=true;disk.execute(()->{String error=null;boolean localComic=false;try{action.run();}catch(Library.LocalComicException e){localComic=true;}catch(Exception e){error=e.getMessage();}String msg=error;boolean rejectedLocal=localComic;runOnUiThread(()->{operation=false;if(isDestroyed())return;if(rejectedLocal)showLocalComicHint();else if(msg!=null)toast(msg.isEmpty()?"操作失败":msg);refresh();});});}
    private void share(JSONObject b){if(b.has("sourceId")){toast("在线漫画不提供分享");return;}if(Build.VERSION.SDK_INT<29&&checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")!=PackageManager.PERMISSION_GRANTED){pendingShare=b;requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"},44);return;}if(operation){toast("请等待当前操作完成");return;}toast("正在打包 CBZ…");operation=true;disk.execute(()->{try{Uri uri=library.exportCbz(b);runOnUiThread(()->{operation=false;if(isDestroyed())return;toast("已保存到 Download/jmshare");Intent send=new Intent(Intent.ACTION_SEND).setType("application/vnd.comicbook+zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("CBZ",uri));try{startActivity(Intent.createChooser(send,"分享漫画"));}catch(Exception e){toast("CBZ 已保存到 Download/jmshare");}refresh();});}catch(Exception e){runOnUiThread(()->{operation=false;if(!isDestroyed())toast("分享失败："+e.getMessage());});}});}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==44){JSONObject b=pendingShare;pendingShare=null;if(b!=null&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)share(b);else toast("保存分享文件需要存储权限");}}
    private void importMenu(){PredictiveBack.dialog(this).setTitle("添加到书架").setItems(new String[]{"导入漫画压缩包","导入图片文件夹","创建书籍夹"},(d,n)->{if(n==0){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,43);}else if(n==1){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,41);}else nameDialog("创建书籍夹","",name->mutate(()->library.createFolder(name,folderId)));}).show();}
    private JSONArray folders(){try{return new JSONArray(prefs.getString("folders","[]"));}catch(Exception e){return new JSONArray();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==43){toast("正在导入漫画…");mutate(()->library.importComic(uri));return;}if(request!=41)return;try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);JSONArray all=folders();for(int n=0;n<all.length();n++)if(all.getJSONObject(n).optString("uri").equals(uri.toString())){toast("已添加此文件夹");return;}String name=android.provider.DocumentsContract.getTreeDocumentId(uri);int slash=name.lastIndexOf('/');if(slash>=0)name=name.substring(slash+1);all.put(new JSONObject().put("uri",uri.toString()).put("title",name).put("local",true));prefs.edit().putString("folders",all.toString()).apply();refresh();}catch(Exception e){toast("未获得文件夹读取权限，请重新选择");}}
    private static String shelfKey(JSONObject book){return book.optBoolean("book_folder")?"folder:"+book.optString("id"):Library.key(book);}
    private void previewShelfDrag(float x,float y){
        ShelfTile hovered=null;
        // Deliberately ignore translations: insertion animations must not change hit testing.
        for(int n=0;n<grid.getChildCount();n++){
            View child=grid.getChildAt(n);
            if(child instanceof ShelfTile&&x>=child.getLeft()-grid.getHorizontalSpacing()/2f&&x<=child.getRight()+grid.getHorizontalSpacing()/2f&&y>=child.getTop()-grid.getVerticalSpacing()/2f&&y<=child.getBottom()+grid.getVerticalSpacing()/2f){hovered=(ShelfTile)child;break;}
        }
        JSONObject target=hovered==null?null:hovered.book;
        int zone=target==null||shelfKey(target).equals(shelfKey(draggingBook))?ShelfOrder.NONE:ShelfOrder.zone(Math.max(0,Math.min(hovered.getWidth(),x-hovered.getLeft())),hovered.getWidth(),draggingBook.optBoolean("book_folder"),target.optBoolean("book_folder"));
        if(zone==ShelfOrder.NONE)target=null;
        if(zone==dragZone&&(target==null?dragTarget==null:dragTarget!=null&&shelfKey(target).equals(shelfKey(dragTarget))))return;
        clearShelfPreview();
        if(zone==ShelfOrder.NONE)return;
        dragTarget=target;dragZone=zone;dragMoved=true;
        if(zone==ShelfOrder.BEFORE||zone==ShelfOrder.AFTER){
            ArrayList<String> order=new ArrayList<>();for(JSONObject book:dragRows)order.add(shelfKey(book));
            List<String> next=ShelfOrder.insert(order,shelfKey(draggingBook),shelfKey(target),zone==ShelfOrder.AFTER);
            for(int n=0;n<next.size();n++)dragPreviewPositions.put(next.get(n),n);
            for(int n=0;n<grid.getChildCount();n++){
                View child=grid.getChildAt(n);if(!(child instanceof ShelfTile))continue;
                Integer position=dragPreviewPositions.get(shelfKey(((ShelfTile)child).book));if(position==null)continue;
                Point slot=shelfSlot(position);child.animate().cancel();child.animate().translationX(slot.x-child.getLeft()).translationY(slot.y-child.getTop()).scaleX(1).scaleY(1).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            }
        }else if(zone==ShelfOrder.MERGE)hovered.showFolderPreview();
        else{hovered.setForeground(dragOutline());hovered.animate().scaleX(.96f).scaleY(.96f).setDuration(160).start();}
    }
    private Point shelfSlot(int position){
        int childIndex=position-grid.getFirstVisiblePosition();
        if(childIndex>=0&&childIndex<grid.getChildCount()){View child=grid.getChildAt(childIndex);return new Point(child.getLeft(),child.getTop());}
        View first=grid.getChildAt(0);int columns=grid.getNumColumns(),rowHeight=first.getHeight()+grid.getVerticalSpacing();
        if(grid.getChildCount()>columns)rowHeight=grid.getChildAt(columns).getTop()-first.getTop();
        return new Point(grid.getPaddingLeft()+(position%columns)*(first.getWidth()+grid.getHorizontalSpacing()),first.getTop()+(position/columns-grid.getFirstVisiblePosition()/columns)*rowHeight);
    }
    private GradientDrawable dragOutline(){GradientDrawable border=u.shape(Color.TRANSPARENT,16);border.setStroke(u.dp(2),Ui.BLUE);return border;}
    private void clearShelfPreview(){
        dragTarget=null;dragZone=ShelfOrder.NONE;dragPreviewPositions.clear();
        if(grid==null)return;
        for(int n=0;n<grid.getChildCount();n++){
            View child=grid.getChildAt(n);child.animate().cancel();child.setForeground(null);
            if(child instanceof ShelfTile)((ShelfTile)child).clearFolderPreview();
            child.animate().translationX(0).translationY(0).scaleX(1).scaleY(1).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
        }
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
    private void license(){PredictiveBack.dialog(this).setTitle("JM 简阅 4.0.0").setItems(new String[]{"Mihon 阅读器 · Apache 2.0","内置中文图源 · Keiyoushi","JMComic · MIT","压缩格式支持库","源码与修改说明","GitHub 项目："+AppUpdate.PROJECT_URL,"隐私密码设置"},(d,n)->{if(n==6){PrivacyVault.settings(this);return;}if(n==5){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdate.PROJECT_URL)));}catch(android.content.ActivityNotFoundException e){toast("未找到可打开链接的浏览器");}return;}String[] files={"LICENSE-Mihon.txt","LICENSES-extensions.txt","LICENSE-JMComic.txt","LICENSE-archives.txt","NOTICE-JM.txt"};showLicense(files[n]);}).setPositiveButton("完成",null).show();}
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
    @Override protected void onResume(){super.onResume();preparingDownload=false;if(!downloadReceiverRegistered){androidx.core.content.ContextCompat.registerReceiver(this,downloadChanges,new IntentFilter(DownloadService.ACTION_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);downloadReceiverRegistered=true;}resumed=true;handler.post(poll);if(firstRunGuide!=null)firstRunGuide.showIfNeeded();if(predictiveBack!=null)predictiveBack.refresh();}
    @Override protected void onPause(){super.onPause();resumed=false;if(downloadReceiverRegistered){unregisterReceiver(downloadChanges);downloadReceiverRegistered=false;}handler.removeCallbacks(poll);handler.removeCallbacks(downloadRefresh);}
    @Override protected void onStop(){if(predictiveBack!=null)predictiveBack.onStop();super.onStop();}
    @Override protected void onDestroy(){resumed=false;clearLocalComicHint();if(firstRunGuide!=null)firstRunGuide.close();if(predictiveBack!=null)predictiveBack.close();handler.removeCallbacksAndMessages(null);covers.close();disk.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(firstRunGuide!=null&&firstRunGuide.isShowing())return;if(shelfSearch!=null&&shelfSearch.isExpanded())shelfSearch.collapse();else if(tab==1&&(privateShelf||!folderId.isEmpty()))leaveFolder();else moveTaskToBack(true);}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);b.putString("draft",input==null?draft:input.getText().toString());b.putString("search",searchInput==null?searchDraft:searchInput.getText().toString());b.putString("folder",privateShelf?"":folderId);b.putString("task_id",taskId);}
    private final class OnlineAdapter extends BaseAdapter {
        public int getCount(){return onlineBooks.size();}public Object getItem(int p){return onlineBooks.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){
            JSONObject o=onlineBooks.get(p);
            LinearLayout outer=u.column();outer.setPadding(0,0,0,u.dp(10));
            LinearLayout r=u.row();r.setPadding(u.dp(14),u.dp(12),u.dp(14),u.dp(12));u.round(r,u.card,20);outer.addView(r,new LinearLayout.LayoutParams(-1,-2));
            FrameLayout coverFrame=new FrameLayout(MainActivity.this);coverFrame.setBackground(u.shape(u.tint,12));coverFrame.setClipToOutline(true);
            ImageView cover=new ImageView(MainActivity.this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);coverFrame.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            RemoteCovers.load(cover,o.optString("thumbnail"),o.optLong("sourceId"));r.addView(coverFrame,new LinearLayout.LayoutParams(u.dp(52),u.dp(72)));
            LinearLayout details=u.column();LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,-2,1);dp.setMargins(u.dp(14),0,u.dp(8),0);r.addView(details,dp);
            TextView name=u.text(o.optString("title","未命名"),14,u.ink,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(name);
            u.space(details,7);
            String source=o.optString("sourceName","在线图源");
            String last=o.optString("lastChapterName","");
            String info=last.isEmpty()?source+" · 共"+o.optInt("chapterCount")+"章":source+" · "+last;
            TextView sub=u.text(info,11,u.muted,false);sub.setMaxLines(1);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(sub);
            return outer;
        }
    }
    private final class BookAdapter extends BaseAdapter {
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){JSONObject b=rows.get(p);LinearLayout outer=u.column();outer.setPadding(0,0,0,u.dp(10));LinearLayout r=u.row();r.setPadding(u.dp(14),u.dp(12),u.dp(14),u.dp(12));u.round(r,u.card,20);outer.addView(r,new LinearLayout.LayoutParams(-1,-2));if(b.optBoolean("empty")){TextView empty=u.text("从一个编号开始",14,u.muted,false);empty.setGravity(17);empty.setPadding(0,u.dp(30),0,u.dp(30));r.addView(empty,new LinearLayout.LayoutParams(-1,-2));return outer;}
            FrameLayout cover=cover(b,revealed);r.addView(cover,new LinearLayout.LayoutParams(u.dp(52),u.dp(72)));LinearLayout details=u.column();LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,-2,1);dp.setMargins(u.dp(14),0,u.dp(8),0);r.addView(details,dp);TextView name=u.text(DownloadRecordActions.displayTitle(b,revealed),14,u.ink,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(name);u.space(details,7);details.addView(u.text(DownloadRecordActions.summary(b),11,u.muted,false));return outer;}
    }
    private FrameLayout cover(JSONObject b,boolean show){FrameLayout frame=new RoundedCover(this,12);frame.setBackground(u.shape(u.tint,12));frame.setClipToOutline(true);TextView mark=u.text("JM",17,Ui.BLUE,true);mark.setGravity(17);frame.addView(mark,new FrameLayout.LayoutParams(-1,-1));if(show){ImageView image=new ImageView(this);image.setTag(b);image.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));if(b.has("sourceId")&&!OnlineHistory.isJm(b)&&(!b.optBoolean("download_history")||b.optString("thumbnail").startsWith("https://")))RemoteCovers.load(image,b.optString("thumbnail"),b.optLong("sourceId"));else covers.load(image,b);}return frame;}
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
        private JSONObject book;private final FrameLayout face;private final TextView name,info;private String faceKey="";private final int width,height;private int touchX,touchY;private View folderPreview;private final Runnable pressFeedback=()->{if(isPressed())animate().scaleX(.97f).scaleY(.97f).setDuration(110).start();};
        ShelfTile(){super(MainActivity.this);setOrientation(VERTICAL);int columns=grid.getNumColumns();width=(getResources().getDisplayMetrics().widthPixels-u.dp(40)-u.dp(12)*(columns-1))/columns;height=(int)(width*1.40f);face=new FrameLayout(MainActivity.this);addView(face,new LinearLayout.LayoutParams(-1,height));u.space(this,9);name=u.text("",13,u.ink,true);name.setMaxLines(2);name.setMinLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setLineSpacing(u.dp(2),1);name.setPadding(u.dp(1),0,u.dp(1),0);addView(name,new LinearLayout.LayoutParams(-1,Math.max(u.dp(38),name.getLineHeight()*2+u.dp(2))));info=u.text("",10,u.muted,false);info.setMaxLines(2);info.setMinLines(2);info.setEllipsize(android.text.TextUtils.TruncateAt.END);info.setLineSpacing(u.dp(1),1);info.setPadding(u.dp(1),u.dp(4),u.dp(1),0);info.setVisibility(View.INVISIBLE);addView(info,new LinearLayout.LayoutParams(-1,-2));setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){touchX=(int)e.getX();touchY=(int)e.getY();removeCallbacks(pressFeedback);postDelayed(pressFeedback,Math.max(0,ViewConfiguration.getLongPressTimeout()-110));}else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){removeCallbacks(pressFeedback);animate().scaleX(1).scaleY(1).setDuration(150).start();}return false;});setOnClickListener(v->open(book));setOnLongClickListener(v->{if(tab==2||(shelfSearch!=null&&shelfSearch.isExpanded())){if(book.optBoolean("book_folder"))manageFolder(book);else manageBook(book);return true;}if(operation){toast("请等待当前操作完成");return true;}draggingBook=book;dragRows.clear();dragRows.addAll(rows);dragTarget=null;dragZone=ShelfOrder.NONE;dragCover=snapshotFace(face);dragMoved=false;dragX=v.getLeft()+touchX;dragY=v.getTop()+touchY;v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);boolean started=v.startDragAndDrop(ClipData.newPlainText("book",Library.key(book)),new TileShadow(v,touchX,touchY),book,0);if(!started){draggingBook=null;dragRows.clear();dragCover=null;if(book.optBoolean("book_folder"))manageFolder(book);else manageBook(book);}else v.setAlpha(0f);return true;});
        }
        @Override protected void onDetachedFromWindow(){removeCallbacks(pressFeedback);animate().cancel();setScaleX(1);setScaleY(1);super.onDetachedFromWindow();}
        void bind(JSONObject b){removeCallbacks(pressFeedback);boolean rebound=book==null||!shelfKey(book).equals(shelfKey(b));if(draggingBook==null||rebound){animate().cancel();setTranslationX(0);setTranslationY(0);setScaleX(1);setScaleY(1);setForeground(null);clearFolderPreview();}book=b;setAlpha(draggingBook!=null&&Library.key(draggingBook).equals(Library.key(b))?0f:1f);name.setText(b.optString("title","未命名"));setContentDescription(b.optString("title"));String detail=onlineDetail(b);info.setText(detail);info.setVisibility(detail.isEmpty()?View.INVISIBLE:View.VISIBLE);String identity=b.optBoolean("book_folder")?"folder:"+b.optString("id"):b.optString("key");if(b.optBoolean("book_folder")){identity+="/"+folderCovers.signature(b.optString("id"));}else if(b.has("sourceId"))identity+="|"+b.optString("thumbnail");if(!identity.equals(faceKey)){faceKey=identity;face.removeAllViews();face.addView(b.optBoolean("book_folder")?folderCover(b,width,height):cover(b,true),new FrameLayout.LayoutParams(-1,-1));}else retryMissingCovers(face);if(rebound&&draggingBook!=null){String bound=shelfKey(b);post(()->{Integer position=dragPreviewPositions.get(bound);if(draggingBook!=null&&bound.equals(shelfKey(book))&&position!=null){Point slot=shelfSlot(position);animate().cancel();setTranslationX(slot.x-getLeft());setTranslationY(slot.y-getTop());}});}}
        void clearFolderPreview(){if(folderPreview!=null){face.removeView(folderPreview);folderPreview=null;if(book!=null)name.setText(book.optString("title","未命名"));}}
        void showFolderPreview(){
            Bitmap target=snapshotFace(face);FrameLayout preview=new FrameLayout(MainActivity.this);preview.setBackground(u.shape(u.dark?0xff252b39:0xffe8edf8,16));preview.setClipToOutline(true);
            LinearLayout collage=u.column();collage.setPadding(u.dp(8),u.dp(8),u.dp(8),u.dp(8));
            for(int row=0;row<2;row++){LinearLayout line=u.row();for(int col=0;col<2;col++){View cell;if(row==0){ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(col==0?target:dragCover);cell=image;}else{cell=new View(MainActivity.this);cell.setBackground(u.shape(u.dark?0xff30394b:0xffd9e1f1,6));}LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-1,1);cp.setMargins(u.dp(2),u.dp(2),u.dp(2),u.dp(2));line.addView(cell,cp);}collage.addView(line,new LinearLayout.LayoutParams(-1,0,1));}
            preview.addView(collage,new FrameLayout.LayoutParams(-1,-1));preview.setForeground(dragOutline());TextView count=u.text("2",10,Color.WHITE,true);count.setGravity(Gravity.CENTER);count.setBackground(u.shape(Ui.BLUE,8));FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(u.dp(24),u.dp(20),Gravity.RIGHT|Gravity.BOTTOM);badge.setMargins(0,0,u.dp(8),u.dp(8));preview.addView(count,badge);folderPreview=preview;face.addView(preview,new FrameLayout.LayoutParams(-1,-1));name.setText("新建书夹");
        }
    }
    private void retryMissingCovers(View view){if(view instanceof ImageView){ImageView image=(ImageView)view;if(image.getDrawable()==null&&image.getTag() instanceof JSONObject){JSONObject b=(JSONObject)image.getTag();if(!b.has("sourceId")||(OnlineHistory.isJm(b)&&!b.optString("thumbnail").isEmpty()))covers.load(image,b);}}else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)retryMissingCovers(group.getChildAt(n));}}
    /** Canvas snapshots do not apply child outline clips; keep the same clip in software. */
    private static final class RoundedCover extends FrameLayout {
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
    private FrameLayout folderCover(JSONObject f,int width,int height){FrameLayout face=new RoundedCover(this,16);face.setBackground(u.shape(u.dark?0xff252b39:0xffe8edf8,16));face.setClipToOutline(true);List<JSONObject> members=folderCovers.members(f.optString("id"));LinearLayout collage=u.column();collage.setPadding(u.dp(8),u.dp(8),u.dp(8),u.dp(8));for(int row=0;row<2;row++){LinearLayout r=u.row();for(int col=0;col<2;col++){int n=row*2+col;FrameLayout c=n<members.size()?cover(members.get(n),true):new FrameLayout(this);if(n>=members.size())c.setBackground(u.shape(u.dark?0xff30394b:0xffd9e1f1,6));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-1,1);cp.setMargins(u.dp(2),u.dp(2),u.dp(2),u.dp(2));r.addView(c,cp);}collage.addView(r,new LinearLayout.LayoutParams(-1,0,1));}face.addView(collage,new FrameLayout.LayoutParams(-1,-1));TextView count=u.text(String.valueOf(members.size()),10,Color.WHITE,true);count.setGravity(17);count.setBackground(u.shape(0xb923304c,8));FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(u.dp(24),u.dp(20),Gravity.RIGHT|Gravity.BOTTOM);badge.setMargins(0,0,u.dp(8),u.dp(8));face.addView(count,badge);return face;}
}

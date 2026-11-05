package com.joe.jmlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 本地书籍与 JM 在线记录的漫画详情页：封面、名称、编号、阅读页数、收藏和续读入口。 */
public final class BookInfoActivity extends Activity {
    private static final int RED=0xffe5484d;
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private Ui u;
    private Library library;
    private CoverLoader covers;
    private JSONObject book;
    private String key;
    private boolean favorite,onlineJm;
    private Ui.Icon heart;
    private TextView nameView,heartLabel,pages,downloadButton;
    private ImageView coverImage;
    private FrameLayout heartButton;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        u=new Ui(this);library=new Library(this);covers=new CoverLoader(this);
        String raw=getIntent().getStringExtra("book");
        try{book=raw==null?null:new JSONObject(raw);}catch(Exception e){book=null;}
        if(book==null){finish();return;}
        onlineJm=OnlineHistory.isJm(book);
        key=Library.key(book);
        LinearLayout root=u.column();root.setBackgroundColor(u.bg);
        FrameLayout header=new FrameLayout(this);
        Ui.Icon back=u.icon("back",u.ink);back.setContentDescription("返回");back.setOnClickListener(v->finish());u.round(back,Color.TRANSPARENT,22);
        FrameLayout.LayoutParams backParams=new FrameLayout.LayoutParams(u.dp(44),u.dp(44),Gravity.START|Gravity.CENTER_VERTICAL);backParams.leftMargin=u.dp(8);header.addView(back,backParams);
        TextView title=u.text(onlineJm?"漫画详情":"书籍信息",17,u.ink,true);title.setGravity(Gravity.CENTER);header.addView(title,new FrameLayout.LayoutParams(-1,u.dp(52),Gravity.CENTER));
        root.addView(header,new LinearLayout.LayoutParams(-1,u.dp(52)));

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=u.column();content.setGravity(Gravity.CENTER_HORIZONTAL);content.setPadding(u.dp(24),u.dp(20),u.dp(24),u.dp(30));
        FrameLayout cover=new FrameLayout(this);cover.setBackground(u.shape(u.tint,18));cover.setClipToOutline(true);cover.setElevation(u.dp(6));
        TextView mark=u.text("JM",22,Ui.BLUE,true);mark.setGravity(Gravity.CENTER);cover.addView(mark,new FrameLayout.LayoutParams(-1,-1));
        coverImage=new ImageView(this);coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);coverImage.setTag(book);cover.addView(coverImage,new FrameLayout.LayoutParams(-1,-1));
        covers.load(coverImage,book);
        LinearLayout.LayoutParams coverParams=new LinearLayout.LayoutParams(u.dp(120),u.dp(168));coverParams.bottomMargin=u.dp(18);content.addView(cover,coverParams);

        nameView=u.text(book.optString("title","未命名"),20,u.ink,true);nameView.setGravity(Gravity.CENTER);nameView.setMaxLines(2);nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);content.addView(nameView,new LinearLayout.LayoutParams(-1,-2));
        u.space(content,9);
        TextView plate=u.text((onlineJm?"编号 · ":"车牌号 · ")+(onlineJm?onlineId(book):plate(book)),12,u.muted,false);plate.setGravity(Gravity.CENTER);content.addView(plate);
        u.space(content,5);
        pages=u.text(onlineJm?OnlineHistory.readingLabel(book):"共 "+Math.max(0,book.optInt("done"))+" 页",12,u.muted,false);pages.setGravity(Gravity.CENTER);content.addView(pages);

        u.space(content,20);
        heartButton=new FrameLayout(this);heartButton.setClickable(true);heartButton.setContentDescription("收藏");
        heart=u.icon("heart",u.muted);heart.setMinimumWidth(0);heart.setMinimumHeight(0);FrameLayout.LayoutParams heartParams=new FrameLayout.LayoutParams(u.dp(30),u.dp(30),Gravity.CENTER);heartButton.addView(heart,heartParams);
        content.addView(heartButton,new LinearLayout.LayoutParams(u.dp(60),u.dp(60)));
        u.space(content,8);
        heartLabel=u.text(onlineJm?"加入在线书架":"收藏",12,u.muted,true);heartLabel.setGravity(Gravity.CENTER);content.addView(heartLabel);

        u.space(content,22);
        TextView resume=u.button("上次位置阅读",true);
        LinearLayout.LayoutParams resumeParams=new LinearLayout.LayoutParams(-1,u.dp(52));content.addView(resume,resumeParams);
        u.space(content,12);
        TextView restart=u.button("从头开始阅读",false);
        content.addView(restart,new LinearLayout.LayoutParams(-1,u.dp(52)));
        resume.setOnClickListener(v->read(false));
        restart.setOnClickListener(v->read(true));
        if(onlineJm){
            u.space(content,12);
            downloadButton=u.button("下载到书架",false);downloadButton.setEnabled(false);
            content.addView(downloadButton,new LinearLayout.LayoutParams(-1,u.dp(52)));
            downloadButton.setOnClickListener(v->downloadToShelf());
        }

        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Ui.insets(this,root,u.dark);
        setContentView(root);
        heartButton.setOnClickListener(v->toggleFavorite());
        if(onlineJm)refreshOnlineRecord();else loadPageCount();
        refreshFavorite();
    }

    @Override protected void onResume(){super.onResume();refreshFavorite();if(onlineJm){refreshOnlineRecord();refreshDownloadButton();}}
    @Override protected void onDestroy(){disk.shutdownNow();covers.close();super.onDestroy();}

    private static String plate(JSONObject b){
        String type=b.optString("type");
        if("import".equals(type))return "本地导入";
        if("tree".equals(type))return "本地文件夹";
        return b.optString("id","—");
    }

    private static String onlineId(JSONObject b){
        String id=b.optString("url");
        return id.isEmpty()?b.optString("id","—"):id;
    }

    /** Re-read the JM history record so the page count and title update after reading. */
    private void refreshOnlineRecord(){
        if(!onlineJm)return;
        final String id=onlineId(book);
        disk.execute(()->{
            JSONObject latest=null;
            try{latest=new OnlineHistory(this,true).find("-1",id);}catch(Exception ignored){}
            final JSONObject record=latest;
            runOnUiThread(()->{
                if(isDestroyed())return;
                if(record!=null){
                    try{for(Iterator<String> keys=record.keys();keys.hasNext();){String field=keys.next();book.put(field,record.get(field));}}catch(Exception ignored){}
                    if(!book.optString("title").isEmpty())nameView.setText(book.optString("title"));
                    if(!book.optString("thumbnail").isEmpty())covers.load(coverImage,book);
                }
                pages.setText(OnlineHistory.readingLabel(book));
            });
        });
    }

    private void loadPageCount(){
        if(book.optInt("done")>0)return;
        disk.execute(()->{
            int count=0;
            try{List<LocalImages.Page> list=library.pages(book);count=list==null?0:list.size();}catch(Exception ignored){}
            final int total=count;
            runOnUiThread(()->{if(!isDestroyed())pages.setText("共 "+total+" 页");});
        });
    }

    private void refreshFavorite(){
        disk.execute(()->{
            boolean value=false;
            try{
                if(onlineJm)value=library.hasOnline(key);
                else{
                    JSONObject latest=library.find(key);
                    value=latest!=null&&library.isFavorite(latest);
                }
            }catch(Exception ignored){}
            final boolean favored=value;
            runOnUiThread(()->{if(isDestroyed())return;favorite=favored;paintFavorite();});
        });
    }

    private void paintFavorite(){
        heartLabel.setText(onlineJm?(favorite?"已加入在线书架":"加入在线书架"):(favorite?"已收藏":"收藏"));
        FavoriteStyle.paint(u,heart,heartButton,heartLabel,favorite);
    }

    private void toggleFavorite(){
        final boolean next=!favorite;
        heartButton.setEnabled(false);
        disk.execute(()->{
            String error=null;
            try{
                if(onlineJm)library.favoriteOnline(book,next);
                else library.favorite(key,next);
            }catch(Exception e){error=e.getMessage();}
            final String message=error;
            runOnUiThread(()->{
                heartButton.setEnabled(true);
                if(isDestroyed())return;
                if(message!=null){Toast.makeText(this,message.isEmpty()?"操作失败":message,Toast.LENGTH_SHORT).show();return;}
                favorite=next;paintFavorite();
                Toast.makeText(this,onlineJm?(next?"已加入书架 · 在线JM漫画":"已移出在线书架"):(next?"已加入我的收藏":"已取消收藏"),Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void paintDownloadButton(boolean exists){
        downloadButton.setText(exists?"已存在于书架":"下载到书架");
        downloadButton.setEnabled(!exists);downloadButton.setAlpha(exists?.55f:1f);
    }

    private boolean localDownloadExists(String id) throws Exception {
        JSONObject local=new Store(this).read(id);
        // Library.books() only exposes completed JM downloads, independently of their folder.
        return local!=null&&"complete".equals(local.optString("status"));
    }

    private void refreshDownloadButton(){
        if(downloadButton==null||disk.isShutdown())return;
        downloadButton.setEnabled(false);
        disk.execute(()->{
            try{
                boolean exists=localDownloadExists(JmApi.parseId(onlineId(book)));
                runOnUiThread(()->{if(!isDestroyed())paintDownloadButton(exists);});
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){paintDownloadButton(false);Toast.makeText(this,"无法读取书架，请重试",Toast.LENGTH_SHORT).show();}});}
        });
    }

    private void downloadToShelf(){
        if(DownloadService.running){Toast.makeText(this,"请先暂停当前任务",Toast.LENGTH_SHORT).show();return;}
        final String id;
        try{id=JmApi.parseId(onlineId(book));}catch(Exception e){Toast.makeText(this,"编号无效",Toast.LENGTH_SHORT).show();return;}
        downloadButton.setEnabled(false);
        disk.execute(()->{
            try{
                boolean exists=localDownloadExists(id);
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    paintDownloadButton(exists);if(exists)return;
                    if(DownloadService.running){Toast.makeText(this,"请先暂停当前任务",Toast.LENGTH_SHORT).show();return;}
                    try{
                        startForegroundService(new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_START).putExtra("id",id));
                        Toast.makeText(this,"已开始下载到书架",Toast.LENGTH_SHORT).show();
                    }catch(RuntimeException e){Toast.makeText(this,"无法启动下载，请重试",Toast.LENGTH_SHORT).show();}
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){paintDownloadButton(false);Toast.makeText(this,"无法读取书架，请重试",Toast.LENGTH_SHORT).show();}});}
        });
    }

    private void read(boolean fromStart){
        Intent intent=new Intent().setClassName(this,"com.joe.jmlite.MihonEntryActivity").putExtra("title",book.optString("title","未命名"));
        if(onlineJm){
            intent.putExtra("online_id",onlineId(book));
        }else if(book.has("key"))intent.putExtra("library_key",book.optString("key"));
        else if(book.has("uri"))intent.putExtra("tree",book.optString("uri"));
        else intent.putExtra("id",book.optString("id"));
        if(fromStart)intent.putExtra("start_over",true);
        startActivity(intent);
    }
}

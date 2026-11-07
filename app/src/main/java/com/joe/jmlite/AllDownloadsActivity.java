package com.joe.jmlite;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Download records remain available after their local books are removed. */
public final class AllDownloadsActivity extends Activity {
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final ArrayList<JSONObject> records=new ArrayList<>(),shown=new ArrayList<>();
    private Ui ui;
    private CoverLoader covers;
    private HeaderSearch search;
    private PredictiveBack predictiveBack;
    private TextView empty;
    private RecordsAdapter adapter;
    private String query="";
    private int generation;
    private boolean receiverRegistered,active;
    private final BroadcastReceiver changes=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){String status=intent.getStringExtra(DownloadService.EXTRA_STATUS);if("complete".equals(status)||"removed".equals(status))reload();}};

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ui=new Ui(this);covers=new CoverLoader(this);
        LinearLayout root=ui.column();root.setBackgroundColor(ui.bg);Ui.insets(this,root,ui.dark);
        LinearLayout header=ui.row();header.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(12));
        Ui.Icon back=ui.icon("back",ui.ink);back.setContentDescription("返回下载");back.setOnClickListener(v->{if(search.isExpanded())search.collapse();else finish();});header.addView(back,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
        TextView title=ui.text("全部下载记录",21,ui.ink,true);title.setGravity(Gravity.CENTER_VERTICAL);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,ui.dp(44),1));
        search=new HeaderSearch(this,header,title,null,"搜索下载编号或名称",value->{query=value;filter();});root.addView(header);
        predictiveBack=new PredictiveBack(this,root,()->true,()->!search.isExpanded(),this::onBackPressed);search.setOnExpansionChanged(predictiveBack::refresh);
        FrameLayout frame=new FrameLayout(this);ListView list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);list.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(20));AdaptiveLayout.capContentWidth(list,20,AdaptiveLayout.RECORDS_LIST_MAX_WIDTH_DP);adapter=new RecordsAdapter();list.setAdapter(adapter);frame.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=ui.text("正在读取下载记录…",15,ui.muted,false);empty.setGravity(Gravity.CENTER);frame.addView(empty,new FrameLayout.LayoutParams(-1,-1));root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        list.setOnItemClickListener((p,v,index,id)->DownloadRecordActions.open(this,shown.get(index),disk));
        list.setOnItemLongClickListener((p,v,index,id)->{DownloadRecordActions.confirmRemove(this,shown.get(index),disk,this::reload);return true;});
    }
    private void reload(){
        if(!active||disk.isShutdown())return;
        final int request=++generation;disk.execute(()->{try{List<JSONObject> loaded=new DownloadHistory(this).list();runOnUiThread(()->{if(!active||isDestroyed()||request!=generation)return;records.clear();records.addAll(loaded);filter();});}catch(Exception e){runOnUiThread(()->{if(active&&!isDestroyed()&&request==generation){empty.setText("无法读取下载记录，请重新打开重试");empty.setVisibility(View.VISIBLE);}});}});
    }
    private void filter(){if(adapter==null)return;shown.clear();for(JSONObject record:records)if(BookSearch.matches(record,query))shown.add(record);adapter.notifyDataSetChanged();empty.setText(query.trim().isEmpty()?"暂无下载记录":"没有匹配的下载记录");empty.setVisibility(shown.isEmpty()?View.VISIBLE:View.GONE);}
    @Override protected void onResume(){super.onResume();active=true;IntentFilter filter=new IntentFilter(DownloadService.ACTION_CHANGED);if(Build.VERSION.SDK_INT>=33)registerReceiver(changes,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(changes,filter);receiverRegistered=true;reload();}
    @Override protected void onPause(){active=false;generation++;if(receiverRegistered){unregisterReceiver(changes);receiverRegistered=false;}super.onPause();}
    @Override protected void onStop(){if(predictiveBack!=null)predictiveBack.onStop();super.onStop();}
    @Override protected void onDestroy(){active=false;generation++;if(predictiveBack!=null)predictiveBack.close();covers.close();disk.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(search.isExpanded())search.collapse();else super.onBackPressed();}
    private final class RecordsAdapter extends BaseAdapter {
        public int getCount(){return shown.size();}public Object getItem(int p){return shown.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){
            JSONObject book=shown.get(p);Row row=old!=null?(Row)old.getTag():null;
            if(row==null){LinearLayout outer=ui.column();outer.setPadding(0,0,0,ui.dp(10));LinearLayout body=ui.row();body.setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));ui.round(body,ui.card,20);outer.addView(body);
                FrameLayout face=new FrameLayout(AllDownloadsActivity.this);face.setBackground(ui.shape(ui.tint,12));face.setClipToOutline(true);TextView mark=ui.text("漫画",14,Ui.BLUE,true);mark.setGravity(Gravity.CENTER);face.addView(mark,new FrameLayout.LayoutParams(-1,-1));ImageView image=new ImageView(AllDownloadsActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);face.addView(image,new FrameLayout.LayoutParams(-1,-1));body.addView(face,new LinearLayout.LayoutParams(ui.dp(52),ui.dp(72)));
                LinearLayout text=ui.column();TextView name=ui.text("",14,ui.ink,true);name.setMaxLines(2);name.setMinLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(name);ui.space(text,7);TextView info=ui.text("",11,ui.muted,false);info.setSingleLine(true);info.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(info);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(ui.dp(14),0,0,0);body.addView(text,lp);row=new Row(outer,image,name,info);outer.setTag(row);
            }
            row.name.setText(book.optString("title","未命名"));row.info.setText((book.has("sourceId")&&!OnlineHistory.isJm(book)?book.optString("sourceName","全网漫画"):"JM"+book.optString("id"))+" · "+DownloadRecordActions.summary(book));
            boolean remote=book.has("sourceId")&&!OnlineHistory.isJm(book);
            if(row.remote&&!remote)RemoteCovers.load(row.image,"",0L);
            if(!row.remote&&remote)covers.clear(row.image);
            row.remote=remote;
            if(remote)RemoteCovers.load(row.image,book.optString("thumbnail"),book.optLong("sourceId"));else covers.load(row.image,book);
            return row.root;
        }
    }
    private static final class Row {final View root;final ImageView image;final TextView name,info;boolean remote;Row(View root,ImageView image,TextView name,TextView info){this.root=root;this.image=image;this.name=name;this.info=info;}}
}

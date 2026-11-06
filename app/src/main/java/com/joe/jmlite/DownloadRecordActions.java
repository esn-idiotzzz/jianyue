package com.joe.jmlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;

/** Opening or removing a download record does not remove its book. */
final class DownloadRecordActions {
    private DownloadRecordActions(){}
    static String displayTitle(JSONObject book,boolean revealed){
        if(revealed)return book.optString("title","JM"+book.optString("id"));
        if(book.has("sourceId")&&!OnlineHistory.isJm(book)){String source=book.optString("sourceName");return source.trim().isEmpty()?"全网漫画":source;}
        return book.optString("id");
    }
    static String summary(JSONObject book){
        if(book.has("sourceId")&&!OnlineHistory.isJm(book)){int chapters=book.optInt("downloadedChapters");return chapters>0?"已下载 "+chapters+" 章":"全网漫画";}
        return book.optInt("done")+" 页";
    }
    static void open(Activity activity,JSONObject record,ExecutorService disk){
        if(record.has("sourceId")&&!OnlineHistory.isJm(record)){
            activity.startActivity(new Intent(activity,OnlineMangaActivity.class).putExtra("sourceId",record.optLong("sourceId")).putExtra("url",record.optString("url")).putExtra("title",record.optString("title")).putExtra("thumbnail",record.optString("thumbnail")).putExtra("author",record.optString("author")).putExtra("description",record.optString("description")).putExtra("sourceName",record.optString("sourceName")));return;
        }
        disk.execute(()->{try{
            String id=record.optString("id");JSONObject local=new Store(activity).read(id),book;
            if(local!=null&&"complete".equals(local.optString("status")))book=local.put("key","download:"+id).put("type","download");
            else book=new JSONObject(record.toString()).put("jm",true).put("sourceId",-1).put("url",id).put("key","-1|"+id).put("type","online");
            activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())activity.startActivity(new Intent(activity,BookInfoActivity.class).putExtra("book",book.toString()));});
        }catch(Exception e){activity.runOnUiThread(()->{if(!activity.isDestroyed())Toast.makeText(activity,"无法打开下载记录",Toast.LENGTH_SHORT).show();});}});
    }
    static void confirmRemove(Activity activity,JSONObject record,ExecutorService disk,Runnable changed){
        new AlertDialog.Builder(activity).setTitle("删除下载记录？").setMessage("仅删除此记录，书架中的漫画和已下载内容保留。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,n)->disk.execute(()->{try{
                new DownloadHistory(activity).remove(Library.key(record));
                activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())changed.run();});
            }catch(Exception e){activity.runOnUiThread(()->{if(!activity.isDestroyed())Toast.makeText(activity,"无法删除下载记录",Toast.LENGTH_SHORT).show();});}})).show();
    }
}

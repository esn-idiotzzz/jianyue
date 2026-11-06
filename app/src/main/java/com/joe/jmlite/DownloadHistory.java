package com.joe.jmlite;

import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Completed-download records survive removal of the downloaded book and its images. */
public final class DownloadHistory {
    private static final Object LOCK=new Object();
    private final Context context;
    private final AtomicFile file;
    private final File covers;

    public DownloadHistory(Context context){this.context=context.getApplicationContext();file=new AtomicFile(new File(this.context.getFilesDir(),"download_history.json"));covers=new File(this.context.getFilesDir(),"download_history_covers");}

    public List<JSONObject> list() throws Exception {return list(0);}
    /** A non-positive limit returns all records; recent downloads use limit=3. */
    public List<JSONObject> list(int limit) throws Exception {synchronized(LOCK){return sorted(ready(),limit);}}

    public static void notifyChanged(Context context,String idOrKey,String status,int done,int total){
        context.sendBroadcast(new Intent(DownloadService.ACTION_CHANGED).setPackage(context.getPackageName())
                .putExtra(DownloadService.EXTRA_ID,idOrKey).putExtra(DownloadService.EXTRA_STATUS,status)
                .putExtra(DownloadService.EXTRA_DONE,done).putExtra(DownloadService.EXTRA_TOTAL,total));
    }

    public void record(JSONObject meta) throws Exception {synchronized(LOCK){
        if(!"complete".equals(meta.optString("status")))return;
        JSONObject state=ready(),record=jmRecord(meta);preserveCover(record);upsert(state,record,true);write(state);
    }}

    /** Called only after a real source chapter has been committed to the download cache. */
    public void recordOnline(JSONObject download) throws Exception {synchronized(LOCK){
        JSONObject state=ready();mergeOnline(state,download);write(state);
    }}

    static void mergeOnline(JSONObject state,JSONObject download) throws Exception {
        JSONObject record=onlineRecord(download);
        JSONObject previous=find(state,record.getString("key"));
        JSONArray chapters=new JSONArray();
        if(previous!=null){JSONArray old=previous.optJSONArray("chapterIds");if(old!=null)for(int n=0;n<old.length();n++)addUnique(chapters,old.optString(n));}
        addUnique(chapters,download.optString("chapterId"));
        record.put("chapterIds",chapters).put("downloadedChapters",Math.max(chapters.length(),Math.max(download.optInt("downloadedChapters"),previous==null?0:previous.optInt("downloadedChapters"))));
        upsert(state,record,true);
    }

    /** Both a bare JM number and its download:number key are accepted. */
    public void remove(String idOrKey) throws Exception {synchronized(LOCK){
        JSONObject state=ready();removeRecord(state,key(idOrKey));write(state);
        if(idOrKey.matches("(?:download:)?[0-9]{1,12}")){String id=idOrKey.replaceFirst("^download:","");File cover=new File(covers,id+".jpg");if(cover.exists())cover.delete();}
        notifyChanged(context,key(idOrKey),"removed",0,0);
    }}

    /** Must finish successfully before Store is allowed to delete local content. */
    public void preserveBeforeDelete(JSONObject meta) throws Exception {synchronized(LOCK){
        JSONObject state=ready();
        if(meta==null||!"complete".equals(meta.optString("status")))return;
        JSONObject record=jmRecord(meta);String key=record.getString("key");
        if(wasRemoved(state,key))return;
        JSONObject previous=find(state,key);
        if(previous!=null)return;
        preserveCover(record);upsert(state,record,false);write(state);
    }}

    private JSONObject ready() throws Exception {
        JSONObject state=read();
        if(!state.optBoolean("migrated")&&migrate(state,new Store(context).list())){
            JSONArray items=array(state,"items");for(int n=0;n<items.length();n++){JSONObject record=items.getJSONObject(n);if("jm".equals(record.optString("kind")))preserveCover(record);}
            write(state);
        }
        return state;
    }

    private JSONObject read() throws Exception {
        if(!file.getBaseFile().isFile()&&!new File(file.getBaseFile()+".bak").isFile())return new JSONObject();
        return new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
    }
    private void write(JSONObject state) throws Exception {
        FileOutputStream out=null;
        try{out=file.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private void preserveCover(JSONObject record) throws Exception {
        String id=record.getString("id");
        if(!id.matches("[0-9]{1,12}"))throw new IOException("编号无效");
        File target=new File(covers,id+".jpg");
        if(!target.isFile()||target.length()==0){
            List<File> pages=new Store(context).pages(id);if(pages.isEmpty())return;
            if(!covers.isDirectory()&&!covers.mkdirs())throw new IOException("无法保存下载记录封面");
            AtomicFile cover=new AtomicFile(target);FileOutputStream out=null;
            try(FileInputStream in=new FileInputStream(pages.get(0))){out=cover.startWrite();byte[] bytes=new byte[16384];for(int n;(n=in.read(bytes))!=-1;)out.write(bytes,0,n);cover.finishWrite(out);}
            catch(Exception e){if(out!=null)cover.failWrite(out);throw e;}
        }
        record.put("thumbnail",target.toURI().toString());
    }

    static JSONObject jmRecord(JSONObject meta) throws Exception {
        String id=meta.optString("id");if(!id.matches("[0-9]{1,12}"))throw new IOException("编号无效");
        long time=meta.optLong("completedAt",meta.optLong("updated",System.currentTimeMillis()));
        return new JSONObject().put("id",id).put("key","download:"+id).put("kind","jm").put("type","download").put("download_history",true)
                .put("title",meta.optString("title","JM"+id)).put("done",meta.optInt("done")).put("total",meta.optInt("total"))
                .put("status","complete").put("completedAt",time).put("updated",time).put("thumbnail",meta.optString("thumbnail"));
    }
    static JSONObject onlineRecord(JSONObject download) throws Exception {
        String source=download.optString("sourceId"),url=download.optString("url");
        if(source.isEmpty()||url.isEmpty())throw new IOException("图源记录无效");
        JSONObject record=new JSONObject(download.toString());long time=download.optLong("completedAt",System.currentTimeMillis());
        return record.put("key",source+"|"+url).put("kind","web").put("type","online").put("download_history",true).put("status","complete").put("completedAt",time).put("updated",time);
    }
    static boolean migrate(JSONObject state,List<JSONObject> legacy) throws Exception {
        if(state.optBoolean("migrated"))return false;
        for(JSONObject meta:legacy)if("complete".equals(meta.optString("status")))upsert(state,jmRecord(meta),false);
        state.put("migrated",true);return true;
    }
    static void upsert(JSONObject state,JSONObject record,boolean newCompletion) throws Exception {
        String key=record.getString("key");if(!newCompletion&&wasRemoved(state,key))return;
        JSONArray next=new JSONArray(),items=array(state,"items");
        for(int n=0;n<items.length();n++){JSONObject item=items.getJSONObject(n);if(!key.equals(item.optString("key")))next.put(item);}
        next.put(new JSONObject(record.toString()));state.put("items",next);
        if(newCompletion){JSONArray removed=array(state,"removed"),keep=new JSONArray();for(int n=0;n<removed.length();n++)if(!key.equals(removed.optString(n)))keep.put(removed.optString(n));state.put("removed",keep);}
    }
    static void removeRecord(JSONObject state,String idOrKey) throws Exception {
        String key=key(idOrKey);JSONArray next=new JSONArray(),items=array(state,"items");
        for(int n=0;n<items.length();n++)if(!key.equals(items.getJSONObject(n).optString("key")))next.put(items.getJSONObject(n));
        state.put("items",next);addUnique(array(state,"removed"),key);
    }
    static List<JSONObject> sorted(JSONObject state,int limit) throws Exception {
        ArrayList<JSONObject> result=new ArrayList<>();JSONArray items=array(state,"items");
        for(int n=0;n<items.length();n++)result.add(new JSONObject(items.getJSONObject(n).toString()));
        result.sort((a,b)->{int time=Long.compare(b.optLong("completedAt",b.optLong("updated")),a.optLong("completedAt",a.optLong("updated")));return time==0?a.optString("key").compareTo(b.optString("key")):time;});
        return limit>0&&result.size()>limit?new ArrayList<>(result.subList(0,limit)):result;
    }
    private static String key(String idOrKey){return idOrKey.matches("[0-9]{1,12}")?"download:"+idOrKey:idOrKey;}
    private static JSONObject find(JSONObject state,String key) throws Exception {JSONArray items=array(state,"items");for(int n=0;n<items.length();n++)if(key.equals(items.getJSONObject(n).optString("key")))return items.getJSONObject(n);return null;}
    private static boolean wasRemoved(JSONObject state,String key) throws Exception {JSONArray removed=array(state,"removed");for(int n=0;n<removed.length();n++)if(key.equals(removed.optString(n)))return true;return false;}
    private static void addUnique(JSONArray values,String value){if(value.isEmpty())return;for(int n=0;n<values.length();n++)if(value.equals(values.optString(n)))return;values.put(value);}
    private static JSONArray array(JSONObject state,String key) throws Exception {JSONArray result=state.optJSONArray(key);if(result==null){result=new JSONArray();state.put(key,result);}return result;}
}

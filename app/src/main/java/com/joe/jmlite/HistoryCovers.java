package com.joe.jmlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent covers owned by history records, isolated from shelf/private-shelf covers. */
final class HistoryCovers {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService io=Executors.newFixedThreadPool(2,r->new Thread(r,"history-cover"));
    private static final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(32*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private static CoverDiskCache disk;
    private static synchronized CoverDiskCache disk(Context context){
        if(disk==null)disk=new CoverDiskCache(new File(context.getApplicationContext().getFilesDir(),"history-covers"),128L*1024*1024);
        return disk;
    }
    static String key(JSONObject book){
        String key=book.optString("key");
        if(key.isEmpty())key=OnlineHistory.key(book.optString("sourceId"),book.optString("url"));
        return key;
    }
    static void load(ImageView view,Context context,JSONObject book){
        String thumb=book.optString("thumbnail");
        if(!thumb.startsWith("http"))return;
        final String key=key(book);
        Bitmap cached; synchronized(memory){cached=memory.get(key);}
        if(cached!=null){view.setImageBitmap(cached);return;}
        final Context app=context.getApplicationContext();
        io.execute(()->{
            byte[] data=disk(app).read(key);
            if(data!=null){
                Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
                if(bitmap!=null){synchronized(memory){memory.put(key,bitmap);}main.post(()->{if(view.getTag()==book)view.setImageBitmap(bitmap);});return;}
            }
            main.post(()->{if(view.getTag()==book)RemoteCovers.load(view,thumb,OnlineHistory.isJm(book)?0L:book.optLong("sourceId"),()->save(app,key,view));});
        });
    }
    private static void save(Context context,String key,ImageView view){
        Drawable drawable=view.getDrawable();
        if(!(drawable instanceof BitmapDrawable))return;
        Bitmap bitmap=((BitmapDrawable)drawable).getBitmap();
        if(bitmap==null||bitmap.isRecycled())return;
        io.execute(()->{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG,84,out);
            disk(context).write(key,out.toByteArray());
        });
    }
    static void delete(Context context,JSONObject book){
        String key=key(book);synchronized(memory){memory.remove(key);}disk(context).remove(key);
    }
    static void clear(Context context){
        synchronized(memory){memory.evictAll();}
        disk=null;
        deleteTree(new File(context.getApplicationContext().getFilesDir(),"history-covers"));
    }
    private static void deleteTree(File file){if(file==null||!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();}
}
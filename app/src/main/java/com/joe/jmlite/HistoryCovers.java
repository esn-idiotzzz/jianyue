package com.joe.jmlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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
        if(disk==null)disk=new CoverDiskCache(new File(context.getApplicationContext().getFilesDir(),"history-covers"),Long.MAX_VALUE);
        return disk;
    }
    static String key(JSONObject book){
        String key=book.optString("key");
        if(key.isEmpty())key=OnlineHistory.key(book.optString("sourceId"),book.optString("url"));
        return key;
    }

    /** JM direct-reading covers are stored next to app files, never under cacheDir. */
    static File jmCoverFile(Context context,String id){
        return new File(new File(context.getApplicationContext().getFilesDir(),"jm-history-covers"),id+".jpg");
    }

    static void load(ImageView view,Context context,JSONObject book){
        String thumb=book.optString("thumbnail");
        if(thumb.isEmpty())return;
        final String key=key(book);
        view.setTag(book);
        Bitmap cached; synchronized(memory){cached=memory.get(key);}
        if(cached!=null){view.setImageBitmap(cached);return;}
        final Context app=context.getApplicationContext();
        if(!thumb.startsWith("http")){loadLocal(view,app,book,key,thumb);return;}
        io.execute(()->{
            byte[] data=disk(app).read(key);
            if(data!=null){
                Bitmap bitmap=decode(data);
                if(bitmap!=null){synchronized(memory){memory.put(key,bitmap);}main.post(()->{if(view.getTag()==book)view.setImageBitmap(bitmap);});return;}
                disk(app).remove(key);
            }
            main.post(()->{if(view.getTag()==book)RemoteCovers.load(view,thumb,OnlineHistory.isJm(book)?0L:book.optLong("sourceId"),()->save(app,key,view));});
        });
    }

    private static void loadLocal(ImageView view,Context app,JSONObject book,String key,String thumb){
        io.execute(()->{
            byte[] data=disk(app).read(key);
            if(data==null){
                try(InputStream input=app.getContentResolver().openInputStream(Uri.parse(thumb))){
                    if(input==null)return;
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    byte[] buffer=new byte[16*1024];int total=0;
                    for(int count;(count=input.read(buffer))!=-1;){
                        total+=count;
                        if(total>8*1024*1024)return;
                        out.write(buffer,0,count);
                    }
                    data=out.toByteArray();
                }catch(Exception ignored){return;}
                if(data==null||data.length==0)return;
                disk(app).write(key,data);
            }
            Bitmap bitmap=decode(data);
            if(bitmap==null)return;
            synchronized(memory){memory.put(key,bitmap);}
            main.post(()->{if(view.getTag()==book)view.setImageBitmap(bitmap);});
        });
    }

    private static Bitmap decode(byte[] data){
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        int sample=1;
        while(bounds.outWidth/sample>480||bounds.outHeight/sample>720)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data,0,data.length,options);
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
        String jmId=OnlineHistory.isJm(book)?book.optString("url"):"";
        if(!jmId.isEmpty()){
            File file=jmCoverFile(context,jmId);
            if(file.isFile())file.delete();
        }
        String thumb=book.optString("thumbnail");
        if(!thumb.startsWith("http")&&!thumb.isEmpty()){
            try{
                File file=new File(Uri.parse(thumb).getPath());
                File root=new File(context.getApplicationContext().getFilesDir(),"jm-history-covers").getCanonicalFile();
                String path=file.getCanonicalPath();
                if(path.startsWith(root.getPath()+File.separator)&&file.isFile())file.delete();
            }catch(Exception ignored){}
        }
    }

    static void clear(Context context){
        synchronized(memory){memory.evictAll();}
        disk=null;
        File files=context.getApplicationContext().getFilesDir();
        deleteTree(new File(files,"history-covers"));
        deleteTree(new File(files,"jm-history-covers"));
    }

    private static void deleteTree(File file){if(file==null||!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();}
}

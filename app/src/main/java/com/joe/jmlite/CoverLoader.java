package com.joe.jmlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, bounded cover cache owned by a single screen. */
final class CoverLoader implements AutoCloseable {
    private static final int COVER_TAG=0x7f0a01c0;
    private final Context context;
    private final Library library;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>(24),new ThreadPoolExecutor.AbortPolicy());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(6*1024*1024){
        @Override protected int sizeOf(String key,Bitmap image){return image.getAllocationByteCount();}
    };
    /** A missing or slow cover must not be re-queued on every recycling bind during a scroll. */
    private final HashMap<String,Long> attempts=new HashMap<>();
    private volatile boolean closed;

    CoverLoader(Context context){this.context=context.getApplicationContext();library=new Library(this.context);}

    void clear(ImageView view){view.setTag(COVER_TAG,null);view.setImageDrawable(null);}

    void load(ImageView view,JSONObject book){
        String key=cacheKey(book);
        if(key.equals(view.getTag(COVER_TAG))&&view.getDrawable()!=null)return;
        view.setTag(COVER_TAG,key);view.setImageDrawable(null);
        if(closed||key.isEmpty())return;
        Bitmap ready=cache.get(key);
        if(ready!=null){view.setImageBitmap(ready);return;}
        long now=SystemClock.uptimeMillis();
        Long last=attempts.get(key);
        if(last!=null&&now-last<600)return;
        if(attempts.size()>256)attempts.clear();
        attempts.put(key,now);
        enqueue(new WeakReference<>(view),book,key);
    }

    /** The reader-set cover changes both the file and its version, so stale bitmaps are bypassed. */
    private static String cacheKey(JSONObject book){
        String key=Library.key(book);
        String cover=book.optString("cover");
        if(cover.isEmpty())return key;
        return key+"|"+book.optLong("coverUpdated")+"|"+cover;
    }

    private void enqueue(WeakReference<ImageView> target,JSONObject book,String key){
        if(closed)return;
        try{workers.execute(()->{
            if(closed||Thread.currentThread().isInterrupted()||!matches(target,key))return;
            try{
                Bitmap image=cache.get(key);
                if(image==null){
                    String source=book.optString("cover");
                    if(source.isEmpty()){
                        List<LocalImages.Page> pages=(book.optBoolean("download_history")||OnlineHistory.isJm(book))&&!book.optString("thumbnail").isEmpty()?java.util.Collections.singletonList(new LocalImages.Page(book.optString("thumbnail"),"cover.jpg")):library.pages(book);
                        if(pages.isEmpty())return;
                        source=pages.get(0).uri;
                    }
                    if(source.isEmpty()||closed||Thread.currentThread().isInterrupted())return;
                    image=decode(source);
                    if(image==null)return;
                    synchronized(cache){
                        if(closed){image.recycle();return;}
                        Bitmap raced=cache.get(key);
                        if(raced==null)cache.put(key,image);else{image.recycle();image=raced;}
                    }
                }
                final Bitmap done=image;
                synchronized(main){
                    if(closed)return;
                    main.post(()->{
                        ImageView visible=target.get();
                        if(!closed&&visible!=null&&key.equals(visible.getTag(COVER_TAG)))visible.setImageBitmap(done);
                    });
                }
            }catch(Exception|OutOfMemoryError ignored){/* The screen's placeholder remains visible. */}
        });}catch(RejectedExecutionException ignored){
            synchronized(main){
                if(closed)return;
                main.postDelayed(()->{
                    ImageView visible=target.get();
                    if(closed||visible==null||!key.equals(visible.getTag(COVER_TAG))||
                            !visible.isAttachedToWindow()||!visible.isShown()||!visible.getGlobalVisibleRect(new Rect()))return;
                    enqueue(target,book,key);
                },80);
            }
        }
    }

    private boolean matches(WeakReference<ImageView> target,String key){ImageView view=target.get();return view!=null&&key.equals(view.getTag(COVER_TAG));}

    private Bitmap decode(String path)throws Exception{
        Uri uri=Uri.parse(path);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<=0||bounds.outHeight<=0||closed||Thread.currentThread().isInterrupted())return null;
        int sample=1;
        while(bounds.outWidth/sample>480||bounds.outHeight/sample>720||
                (long)(bounds.outWidth/sample)*(bounds.outHeight/sample)>240*360*2)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,options);}
    }

    @Override public void close(){
        synchronized(main){closed=true;main.removeCallbacksAndMessages(null);}
        workers.shutdownNow();
        synchronized(cache){cache.evictAll();}
        attempts.clear();
    }
}

package com.joe.jmlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import eu.kanade.tachiyomi.source.online.HttpSource;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Small bounded loader for remote covers used by online search, info and history. */
final class RemoteCovers {
    private static final int TAG = 0x7f0a01c1;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            3, 3, 20, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(48),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private RemoteCovers() {}

    static void load(final ImageView view, final String url) {
        load(view, url, 0L);
    }

    static void load(final ImageView view, final String url, final long sourceId) {
        final String key = sourceId + "|" + url;
        view.setTag(TAG, key);
        view.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap ready = cache.get(key);
        if (ready != null) { view.setImageBitmap(ready); return; }
        final Context context = view.getContext().getApplicationContext();
        try {
            workers.execute(() -> {
                if (!key.equals(view.getTag(TAG))) return;
                try {
                    HttpSource source = sourceId == 0L ? null : CoverSourceAccess.find(context, sourceId);
                    if (!key.equals(view.getTag(TAG))) return;
                    OkHttpClient coverClient = source == null ? client : source.getClient();
                    Request.Builder builder = new Request.Builder().url(url);
                    if (source == null) {
                        builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
                    } else {
                        builder.headers(source.getHeaders());
                    }
                    Request request = builder.build();
                    Bitmap bitmap;
                    try (Response response = coverClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) return;
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        try(InputStream in=response.body().byteStream()) {
                            byte[] buffer=new byte[8192];int total=0;
                            for(int n;(n=in.read(buffer))!=-1;){total+=n;if(total>8*1024*1024)return;bytes.write(buffer,0,n);}
                        }
                        byte[] data=bytes.toByteArray();
                        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                        if(bounds.outWidth<=0||bounds.outHeight<=0)return;
                        int sample=1;while(bounds.outWidth/sample>480||bounds.outHeight/sample>720)sample*=2;
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
                        bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);
                    }
                    if (bitmap == null) return;
                    cache.put(key, bitmap);
                    final Bitmap done = bitmap;
                    main.post(() -> {
                        if (key.equals(view.getTag(TAG))) view.setImageBitmap(done);
                    });
                } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}

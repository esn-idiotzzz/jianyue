package com.joe.jmlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import eu.kanade.tachiyomi.source.online.HttpSource;
import java.io.InputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded parallel cover loading shared by online search, info and history. */
final class RemoteCovers {
    private static final int TAG = 0x7f0a01c1;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CoverRequests<Bitmap> requests = new CoverRequests<>(6, 96);
    private static CoverDiskCache diskCache;
    private static synchronized CoverDiskCache disk(Context context) {
        if (diskCache == null) diskCache = new CoverDiskCache(new File(context.getCacheDir(), "remote-covers"), 128L * 1024 * 1024);
        return diskCache;
    }
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private RemoteCovers() {}

    static void load(final ImageView view, final String url) { load(view, url, 0L); }

    /** Warm a nearby tile without binding it; the same memory/disk cache is shared with load(). */
    static void prefetch(final Context context, final String url, final long sourceId) {
        if (context == null || url == null || url.isEmpty()) return;
        final String key = sourceId + "|" + url;
        if (cacheGet(key) != null) return;
        requests.load(key, cancellation -> download(context, key, url, sourceId, cancellation), bitmap -> {
            if (bitmap != null) cachePut(key, bitmap);
        });
    }
    static void load(final ImageView view, final String url, final long sourceId) { load(view,url,sourceId,null); }
    static void load(final ImageView view, final String url, final long sourceId, final Runnable onReady) {
        Object old = view.getTag(TAG);
        String key = sourceId + "|" + url;
        if (old instanceof Target) {
            Target target = (Target) old;
            if (key.equals(target.key)) { if (view.isAttachedToWindow()) target.request(); return; }
            target.cancel();
            view.removeOnAttachStateChangeListener(target);
        }
        view.setImageDrawable(null);
        view.setTag(TAG, null);
        if (url == null || url.isEmpty()) return;
        Target target = new Target(view, key, url, sourceId, onReady);
        view.setTag(TAG, target);
        view.addOnAttachStateChangeListener(target);
        Bitmap ready = cacheGet(key);
        if (ready != null) view.setImageBitmap(ready);
        else if (view.isAttachedToWindow()) target.request();
    }

    private static Bitmap cacheGet(String key){synchronized(cache){return cache.get(key);}}
    private static void cachePut(String key,Bitmap value){synchronized(cache){cache.put(key,value);}}
    private static final class Target implements View.OnAttachStateChangeListener {
        final WeakReference<ImageView> image;
        final Context context;
        final String key, url;
        final long sourceId;
        CoverRequests.Ticket ticket;
        long generation;
        final Runnable ready;
        Target(ImageView view, String key, String url, long sourceId, Runnable ready) {
            image = new WeakReference<>(view);
            context = view.getContext().getApplicationContext();
            this.key = key; this.url = url; this.sourceId = sourceId; this.ready = ready;
        }
        void request() {
            ImageView view = image.get();
            if (view == null || view.getTag(TAG) != this || !view.isAttachedToWindow() || ticket != null) return;
            if (view.getDrawable() != null) return;
            Bitmap cached = cacheGet(key);
            if (cached != null) { view.setImageBitmap(cached); if (ready != null) ready.run(); return; }
            long token = ++generation;
            ticket = requests.load(key, cancellation -> download(context, key, url, sourceId, cancellation), bitmap -> main.post(() -> {
                if (token != generation) return;
                ticket = null;
                ImageView current = image.get();
                if (bitmap != null && current != null && current.getTag(TAG) == this && current.isAttachedToWindow()) { current.setImageBitmap(bitmap); if (ready != null) ready.run(); }
            }));
        }
        void cancel() { generation++; if (ticket != null) { ticket.cancel(); ticket = null; } }
        @Override public void onViewAttachedToWindow(View view) { request(); }
        @Override public void onViewDetachedFromWindow(View view) {
            generation++;
            CoverRequests.Ticket old = ticket;
            ticket = null;
            // A result re-sort may attach another view for this cover in the same frame.
            if (old != null) main.postDelayed(old::cancel, 32);
        }
    }

    private static Bitmap download(Context context, String key, String url, long sourceId,
            CoverRequests.Cancellation cancellation) throws Exception {
        if (cancellation.isCancelled()) return null;
        Bitmap ready = cacheGet(key);
        if (ready != null) return ready;
        CoverDiskCache stored = disk(context);
        byte[] saved = stored.read(key);
        if (saved != null) {
            Bitmap bitmap = decode(saved);
            if (bitmap != null) { cachePut(key, bitmap); return bitmap; }
            stored.remove(key);
        }
        if (cancellation.isCancelled()) return null;
        HttpSource source = sourceId == 0L ? null : CoverSourceAccess.find(context, sourceId);
        if (cancellation.isCancelled()) return null;
        OkHttpClient coverClient = source == null ? client : source.getClient();
        Request.Builder builder = new Request.Builder().url(url);
        if (source == null) builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
        else builder.headers(source.getHeaders());
        Call call = coverClient.newCall(builder.build());
        call.timeout().timeout(15, TimeUnit.SECONDS);
        cancellation.onCancel(call::cancel);
        Bitmap bitmap;
        byte[] data;
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || cancellation.isCancelled()) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (InputStream in = response.body().byteStream()) {
                byte[] buffer = new byte[8192]; int total = 0;
                for (int n; (n = in.read(buffer)) != -1;) {
                    total += n;
                    if (total > 8 * 1024 * 1024 || cancellation.isCancelled()) return null;
                    bytes.write(buffer, 0, n);
                }
            }
            data = bytes.toByteArray();
            bitmap = decode(data);
        }
        if (bitmap != null && !cancellation.isCancelled()) { cachePut(key, bitmap); stored.write(key, data); }
        return bitmap;
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1; while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 720) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }
}

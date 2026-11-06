package com.joe.jmlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class DownloadService extends Service {
    public static final String ACTION_START = "com.joe.jmlite.START";
    public static final String ACTION_CANCEL = "com.joe.jmlite.CANCEL";
    public static final String ACTION_CHANGED = "com.joe.jmlite.DOWNLOAD_CHANGED";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_STATUS = "status", EXTRA_DONE = "done", EXTRA_TOTAL = "total";
    public static volatile boolean running;
    public static volatile String activeId = "";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION = 1;
    private static final int DOWNLOAD_THREADS = 6;
    private volatile boolean cancelled;
    private volatile String pauseReason = "已暂停，可继续下载";
    private volatile JmApi api;
    private final List<JmApi> imageApis = new ArrayList<>();
    private Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastProgressNotice;

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelDownload();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        String id = intent.getStringExtra(EXTRA_ID);
        if (id == null || !id.matches("[0-9]{1,12}")) { stopSelf(); return START_NOT_STICKY; }
        cancelled = false;
        pauseReason = "已暂停，可继续下载";
        running = true;
        activeId = id;
        startForeground(NOTIFICATION, notification("正在准备下载", 0, 0));
        DownloadHistory.notifyChanged(this,id,"starting",0,0);
        new Thread(() -> download(id), "jm-download").start();
        return START_NOT_STICKY;
    }

    private void download(String id) {
        JSONObject meta = null;
        try {
            meta = store.read(id);
            if (meta == null) meta = new JSONObject().put("id", id).put("title", "JM" + id);
            meta.put("status", "downloading").put("message", "正在获取章节");
            store.write(id, meta);
            changed(meta,true);
            api = new JmApi(getSharedPreferences("settings", MODE_PRIVATE).getString("api_host", ""));
            checkCancelled();
            if (!meta.optBoolean("plan_complete")) plan(id, meta);
            downloadImages(id, meta);
            checkCancelled();
            meta.put("done", savedPages(id, meta)).put("status", "complete").put("message", "下载完成").put("completedAt",System.currentTimeMillis());
            store.write(id, meta);
            try{new DownloadHistory(this).record(meta);}catch(Exception historyError){android.util.Log.w("JMDownload","Completed book retained; unable to save download history",historyError);}
        } catch (Exception e) {
            try {
                if (meta == null) meta = new JSONObject().put("id", id).put("title", "JM" + id);
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = "下载失败，请重试";
                meta.put("done", savedPages(id, meta)).put("status", cancelled ? "paused" : "error").put("message", cancelled ? pauseReason : message);
                store.write(id, meta);
            } catch (Exception ignored) { }
        } finally {
            api = null;
            final String status=meta==null?"error":meta.optString("status","error");
            final int done=meta==null?0:meta.optInt("done"),total=meta==null?0:meta.optInt("total");
            main.post(() -> {
                running = false;
                activeId = "";
                DownloadHistory.notifyChanged(this,id,status,done,total);
                stopForeground(true);
                stopSelf();
            });
        }
    }

    private void downloadImages(String id, JSONObject meta) throws Exception {
        JSONArray chapters = meta.getJSONArray("chapters");
        File book = store.dir(id);
        int total = 0;
        for (int i = 0; i < chapters.length(); i++) {
            total += chapters.getJSONObject(i).getJSONArray("images").length();
        }
        if (total == 0) throw new IOException("没有可下载的图片");
        int existing = savedPages(id, meta);
        meta.put("total", total).put("done", existing);
        store.write(id, meta);
        if (existing == total) return;
        final int totalPages = total;
        final int[] position = {0, 0}, done = {existing};
        final Exception[] failure = {null};
        CountDownLatch finished = new CountDownLatch(DOWNLOAD_THREADS);
        List<JmApi> clients = new ArrayList<>();
        try {
            for (int i = 0; i < DOWNLOAD_THREADS; i++) {
                JmApi child = api.fork();
                synchronized (imageApis) { imageApis.add(child); }
                checkCancelled();
                clients.add(child);
            }
            for (int i = 0; i < clients.size(); i++) {
                final JmApi client = clients.get(i);
                new Thread(() -> {
                    try {
                        while (true) {
                            Page page;
                            synchronized (meta) {
                                if (cancelled || failure[0] != null) return;
                                page = nextPage(chapters, book, position);
                            }
                            if (page == null) return;
                            client.image(page.chapterId, page.filename, page.scrambleId, page.target);
                            synchronized (meta) {
                                done[0]++;
                                meta.put("done", done[0]).put("message", "多线程下载 · " + done[0] + "/" + totalPages + " 页");
                                store.write(id, meta);
                                changed(meta,false);
                                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(meta.optString("title"), done[0], totalPages));
                            }
                        }
                    } catch (Exception e) {
                        synchronized (meta) { if (failure[0] == null) failure[0] = e; }
                        cancelImageApis();
                    } finally { finished.countDown(); }
                }, "jm-image-" + (i + 1)).start();
            }
            boolean interrupted = false;
            while (true) {
                try { finished.await(); break; }
                catch (InterruptedException e) { interrupted = true; cancelImageApis(); }
            }
            if (interrupted) { Thread.currentThread().interrupt(); throw new IOException("下载已中断，请重试"); }
            checkCancelled();
            if (failure[0] != null) throw failure[0];
        } finally {
            synchronized (imageApis) { imageApis.clear(); }
        }
    }

    private int savedPages(String id, JSONObject meta) throws Exception {
        int count = 0;
        JSONArray chapters = meta.optJSONArray("chapters");
        if (chapters == null) return count;
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject chapter = chapters.getJSONObject(i);
            JSONArray images = chapter.getJSONArray("images");
            File folder = new File(store.dir(id), chapter.getString("directory"));
            for (int page = 0; page < images.length(); page++) {
                File file = new File(folder, String.format(Locale.ROOT, "%06d.jpg", page + 1));
                if (file.isFile() && file.length() > 0) count++;
            }
        }
        return count;
    }

    private Page nextPage(JSONArray chapters, File book, int[] position) throws Exception {
        while (position[0] < chapters.length()) {
            JSONObject chapter = chapters.getJSONObject(position[0]);
            JSONArray images = chapter.getJSONArray("images");
            if (position[1] >= images.length()) { position[0]++; position[1] = 0; continue; }
            int page = position[1]++;
            File folder = new File(book, chapter.getString("directory"));
            File target = new File(folder, String.format(Locale.ROOT, "%06d.jpg", page + 1));
            if (target.isFile() && target.length() > 0) continue;
            return new Page(chapter.getString("id"), images.getString(page), chapter.getInt("scramble_id"), target);
        }
        return null;
    }

    private static final class Page {
        final String chapterId, filename;
        final int scrambleId;
        final File target;
        Page(String chapterId, String filename, int scrambleId, File target) {
            this.chapterId = chapterId; this.filename = filename; this.scrambleId = scrambleId; this.target = target;
        }
    }

    private void plan(String id, JSONObject meta) throws Exception {
        JSONObject album = api.album(id);
        String albumId = album.optString("id", id);
        for (int redirects = 0; redirects < 3; redirects++) {
            String parent = album.optString("series_id", "0");
            if (parent.isEmpty() || "0".equals(parent) || parent.equals(albumId)) break;
            checkCancelled();
            albumId = parent;
            album = api.album(parent);
        }
        meta.put("title", album.optString("name", "JM" + id));
        JSONArray series = album.optJSONArray("series");
        List<JSONObject> ordered = new ArrayList<>();
        if (series != null) for (int i = 0; i < series.length(); i++) ordered.add(series.getJSONObject(i));
        ordered.sort((a, b) -> Integer.compare(a.optInt("sort", 0), b.optInt("sort", 0)));
        if (ordered.isEmpty()) ordered.add(new JSONObject().put("id", album.optString("id", albumId)).put("name", meta.getString("title")));
        JSONArray chapters = new JSONArray();
        meta.put("chapters", chapters).put("plan_complete", false);
        store.write(id, meta);
        for (int i = 0; i < ordered.size(); i++) {
            checkCancelled();
            String chapterId = ordered.get(i).getString("id");
            if (!chapterId.matches("[0-9]{1,12}")) throw new IOException("章节编号无效");
            JSONObject detail = api.chapter(chapterId);
            chapters.put(new JSONObject().put("id", chapterId)
                    .put("directory", String.format(Locale.ROOT, "%04d-%s", i + 1, chapterId))
                    .put("images", detail.getJSONArray("images"))
                    .put("scramble_id", detail.getInt("scramble_id")));
            meta.put("message", "正在获取章节 " + (i + 1) + "/" + ordered.size());
            store.write(id, meta);
            changed(meta,false);
        }
        meta.put("plan_complete", true);
        store.write(id, meta);
        changed(meta,true);
    }

    private synchronized void changed(JSONObject meta,boolean force){
        long now=System.nanoTime()/1_000_000L;
        if(force||now-lastProgressNotice>=250){lastProgressNotice=now;DownloadHistory.notifyChanged(this,meta.optString("id"),meta.optString("status"),meta.optInt("done"),meta.optInt("total"));}
    }

    private void checkCancelled() throws IOException {
        if (cancelled) throw new IOException("已暂停");
    }

    private void cancelDownload() {
        cancelled = true;
        JmApi current = api;
        if (current != null) current.cancel();
        cancelImageApis();
    }

    private void cancelImageApis() {
        synchronized (imageApis) { for (JmApi child : imageApis) child.cancel(); }
    }

    private Notification notification(String text, int done, int total) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, DownloadService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("JM Lite · 下载中").setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setProgress(total, done, total <= 0)
                .addAction(new Notification.Action.Builder(null, "暂停", cancel).build()).build();
    }

    @Override public void onDestroy() {
        cancelDownload();
        super.onDestroy();
    }

    // Android 15 calls this overload; the local API 34 build stubs lack its declaration.
    public void onTimeout(int startId, int foregroundServiceType) {
        pauseReason = "系统限制已暂停，请回到应用继续";
        cancelDownload();
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

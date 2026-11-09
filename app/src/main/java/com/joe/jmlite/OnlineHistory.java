package com.joe.jmlite;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Online reading history for extension sources. Stored separately from the local shelf. */
public final class OnlineHistory {
    private static final Object LOCK = new Object();
    private static final int LIMIT = 200;
    private final AtomicFile file;

    public OnlineHistory(Context context) { this(context, false); }
    public OnlineHistory(Context context, boolean jm) {
        file = new AtomicFile(new File(context.getApplicationContext().getFilesDir(), jm ? "jm_online_history.json" : "online_history.json"));
    }

    public static String key(String sourceId, String url) {
        return sourceId + "|" + url;
    }

    /** Older records may carry only sourceId=-1; treat both forms as JM. */
    public static boolean isJm(JSONObject record) {
        return record != null && (record.optBoolean("jm") || "-1".equals(record.optString("sourceId"))
                || record.optInt("sourceId", 0) == -1 || record.optString("key").startsWith("-1|"));
    }

    /** Total pages for JM, falling back to the saved per-chapter progress. */
    public static int totalPages(JSONObject record) {
        if (record == null) return 0;
        int total = record.optInt("totalPages", 0);
        if (total > 0) return total;
        JSONObject progress = record.optJSONObject("chapterProgress");
        if (progress != null) {
            String last = record.optString("lastChapterUrl");
            JSONObject current = last.isEmpty() ? null : progress.optJSONObject(last);
            if (current != null && current.optInt("total", 0) > 0) return current.optInt("total");
            int best = 0;
            for (Iterator<String> keys = progress.keys(); keys.hasNext(); ) {
                JSONObject item = progress.optJSONObject(keys.next());
                if (item != null) best = Math.max(best, item.optInt("total", 0));
            }
            return best;
        }
        return 0;
    }

    /** Number of pages already read, clamped to the known total. */
    public static int readPages(JSONObject record) {
        if (record == null) return 0;
        int total = totalPages(record);
        boolean started = record.has("progress") || total > 0;
        if (!started) return 0;
        int progress = Math.max(0, record.optInt("progress", 0));
        boolean read = record.optBoolean("read");
        int pages = read ? Math.max(progress + 1, total) : progress + 1;
        if (total > 0) pages = Math.min(pages, total);
        return Math.max(0, pages);
    }

    /** Compact reading position used by JM history rows and the JM detail page. */
    public static String readingLabel(JSONObject record) {
        int total = totalPages(record);
        int read = readPages(record);
        if (total <= 0 && read <= 0) return record != null && record.optBoolean("read") ? "已读完" : "尚未阅读";
        if (total > 0) return "已读 " + read + "/" + total + " 页";
        if (record != null && record.optBoolean("read")) return "已读完 · " + read + " 页";
        return "已读 " + read + " 页";
    }

    public List<JSONObject> list() throws Exception {
        synchronized (LOCK) {
            ArrayList<JSONObject> result = new ArrayList<>();
            JSONArray items = state().optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) result.add(item);
            }
            // The stored order is the user-visible order. Reading a record or saving its
            // progress must not move it; only promote() does that.
            return result;
        }
    }

    public JSONObject find(String sourceId, String url) throws Exception {
        synchronized (LOCK) {
            String key = key(sourceId, url);
            JSONArray items = state().optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && key.equals(item.optString("key"))) return item;
            }
            return null;
        }
    }

    public void save(JSONObject record) throws Exception {
        save(record, false);
    }

    /**
     * Replaces an existing record in place (or puts a new record at the top). Opening a history
     * item must not change the list order, so this method never moves an existing record.
     */
    private void save(JSONObject record, boolean promote) throws Exception {
        synchronized (LOCK) {
            JSONObject root = state();
            JSONArray items = root.optJSONArray("items");
            if (items == null) { items = new JSONArray(); root.put("items", items); }
            String key = record.optString("key");
            if (key.isEmpty()) key = key(record.optString("sourceId"), record.optString("url"));
            record.put("key", key).put("updated", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            boolean found = false;
            if (promote) next.put(record);
            for (int i = 0; i < items.length(); i++) {
                JSONObject old = items.optJSONObject(i);
                if (old == null) continue;
                if (key.equals(old.optString("key"))) {
                    found = true;
                    if (!promote) next.put(record);
                    continue;
                }
                next.put(old);
            }
            if (!found && !promote) {
                // A brand-new history entry naturally belongs at the top.
                JSONArray shifted = new JSONArray();
                shifted.put(record);
                for (int i = 0; i < next.length(); i++) shifted.put(next.getJSONObject(i));
                next = shifted;
            }
            if (next.length() > LIMIT) {
                JSONArray trimmed = new JSONArray();
                for (int i = 0; i < LIMIT; i++) trimmed.put(next.getJSONObject(i));
                next = trimmed;
            }
            root.put("items", next);
            write(root);
        }
    }

    /** Moves [record] to the front. Used only by search/warehouse entry points. */
    public void promote(JSONObject record) throws Exception {
        save(record, true);
    }

    public void clear() throws Exception {
        synchronized (LOCK) {
            JSONObject root=state();root.put("items",new JSONArray());write(root);
        }
    }
    public void remove(String sourceId, String url) throws Exception {
        synchronized (LOCK) {
            JSONObject root = state();
            JSONArray items = root.optJSONArray("items");
            if (items == null) return;
            String key = key(sourceId, url);
            JSONArray next = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && !key.equals(item.optString("key"))) next.put(item);
            }
            root.put("items", next);
            write(root);
        }
    }

    private JSONObject state() throws Exception {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base + ".bak").exists()) return new JSONObject().put("orderVersion", 1);
        JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (root.optInt("orderVersion", 0) < 1) {
            // Older builds stored oldest-first and sorted by timestamp for display. Convert once
            // to an explicit newest-first order that is no longer affected by progress saves.
            JSONArray items = root.optJSONArray("items");
            JSONArray ordered = new JSONArray();
            if (items != null) {
                for (int i = items.length() - 1; i >= 0; i--) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) ordered.put(item);
                }
            }
            root.put("items", ordered).put("orderVersion", 1);
            write(root);
        }
        return root;
    }

    private void write(JSONObject root) throws Exception {
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }
}

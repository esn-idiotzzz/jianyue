package com.joe.jmlite;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class Store {
    private static final Object LOCK = new Object();
    private final Context context;
    private final File root;

    public Store(Context context) {
        this.context = context;
        root = new File(context.getFilesDir(), "books");
        root.mkdirs();
    }

    public File root() { return root; }

    public File dir(String id) {
        if (id == null || !id.matches("[0-9]{1,12}")) throw new IllegalArgumentException("编号无效");
        return new File(root, id);
    }

    public static String id(JSONObject meta) { return meta.optString("id", ""); }

    public JSONObject read(String id) throws Exception {
        synchronized (LOCK) {
            AtomicFile file = new AtomicFile(new File(dir(id), "meta.json"));
            if (!file.getBaseFile().isFile() && !new File(file.getBaseFile() + ".bak").isFile()) return null;
            return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        }
    }

    public void write(String id, JSONObject meta) throws Exception {
        synchronized (LOCK) {
            File folder = dir(id);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("无法创建保存目录");
            meta.put("id", id);
            meta.put("updated", System.currentTimeMillis());
            AtomicFile file = new AtomicFile(new File(folder, "meta.json"));
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                out.write(meta.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
            } catch (Exception e) {
                if (out != null) file.failWrite(out);
                throw e;
            }
        }
    }

    public List<JSONObject> list() throws Exception {
        synchronized (LOCK) {
            List<JSONObject> result = new ArrayList<>();
            File[] folders = root.listFiles();
            if (folders != null) for (File folder : folders) {
                if (folder.isDirectory() && folder.getName().matches("[0-9]{1,12}")) {
                    JSONObject meta = read(folder.getName());
                    if (meta != null) result.add(meta);
                }
            }
            result.sort((a, b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
            return result;
        }
    }

    public List<File> pages(String id) throws Exception {
        synchronized (LOCK) {
            List<File> result = new ArrayList<>();
            File[] chapters = dir(id).listFiles();
            if (chapters == null) return result;
            Arrays.sort(chapters, Comparator.comparing(File::getName));
            for (File chapter : chapters) {
                if (!chapter.isDirectory() || !chapter.getName().matches("[0-9]{4,}-[0-9]+")) continue;
                File[] pages = chapter.listFiles(file -> file.isFile() && file.getName().matches("[0-9]{6}\\.jpg") && file.length() > 0);
                if (pages != null) {
                    Arrays.sort(pages, Comparator.comparing(File::getName));
                    result.addAll(Arrays.asList(pages));
                }
            }
            return result;
        }
    }

    public void delete(String id) throws Exception {
        // History may read Store while migrating; never take its lock while holding LOCK.
        new DownloadHistory(context).preserveBeforeDelete(read(id));
        synchronized (LOCK) {
            File folder = dir(id);
            String expected = root.getCanonicalPath() + File.separator + id;
            if (!folder.getCanonicalPath().equals(expected)) throw new java.io.IOException("保存路径无效");
            deleteTree(folder, expected);
        }
    }

    private void deleteTree(File file, String base) throws Exception {
        String path = file.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) throw new java.io.IOException("保存路径无效");
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child, base);
        if (file.exists() && !file.delete()) throw new java.io.IOException("无法删除本地文件");
    }
}

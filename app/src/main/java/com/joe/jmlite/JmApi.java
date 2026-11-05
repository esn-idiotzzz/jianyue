package com.joe.jmlite;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Small Android port of the MIT-licensed jmcomic 2.7.11 API and image algorithm. */
public final class JmApi {
    public static final String VERSION = "jmcomic 2.7.11";
    private static final String SECRET = "185Hcomic3PAPP7R";
    private static final String DOMAIN_SECRET = "diosfjckwpqpdfjkvnqQjsik";
    private static final String UA = "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36";
    private static final String[] DEFAULT_API = {"www.cdnhjk.net", "www.cdngwc.cc", "www.cdngwc.net", "www.cdngwc.club"};
    private static final String[] IMAGE_HOSTS = {"cdn-msp.jmapiproxy1.cc", "cdn-msp.jmapiproxy2.cc", "cdn-msp2.jmapiproxy2.cc", "cdn-msp3.jmapiproxy2.cc", "cdn-msp.jmapinodeudzn.net", "cdn-msp3.jmapinodeudzn.net"};
    private static final String[] DOMAIN_URLS = {
            "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt"};
    private static final Pattern ID = Pattern.compile("(?i)(?:JM)?([0-9]{1,12})");
    private static final Pattern ID_PATH = Pattern.compile("/(?:album|photo)/([0-9]{1,12})/?");
    private static final Object IMAGE_DECODE_LOCK = new Object();
    private final List<String> hosts = new ArrayList<>();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final boolean custom;
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;
    private boolean ready;
    private String appVersion = "2.1.12";

    public JmApi(String customHost) {
        custom = customHost != null && !customHost.trim().isEmpty();
        if (custom) hosts.add(host(customHost.trim()));
        else hosts.addAll(Arrays.asList(DEFAULT_API));
    }

    JmApi fork() throws Exception {
        ensureReady();
        checkCancelled();
        JmApi copy = new JmApi("");
        copy.hosts.clear();
        copy.hosts.addAll(hosts);
        copy.cookies.putAll(cookies);
        copy.appVersion = appVersion;
        copy.ready = true;
        return copy;
    }

    public JSONObject album(String id) throws Exception {
        String requested = parseId(id);
        ensureReady();
        JSONObject result = api("/album?id=" + requested);
        requireDetail(result);
        if (!requested.equals(result.optString("id"))) throw new IOException("接口返回了其他作品，请更换 API 域名");
        String parent = result.optString("series_id", "0");
        if (!parent.equals("0") && !parent.isEmpty() && !parent.equals(result.optString("id"))) {
            result = api("/album?id=" + parseId(parent));
            if (!parent.equals(result.optString("id"))) throw new IOException("接口返回了其他作品，请更换 API 域名");
        }
        requireDetail(result);
        return result;
    }

    public JSONObject chapter(String id) throws Exception {
        String chapterId = parseId(id);
        ensureReady();
        JSONObject result = api("/chapter?id=" + chapterId);
        requireDetail(result);
        if (!chapterId.equals(result.optString("id"))) throw new IOException("接口返回了其他章节，请更换 API 域名");
        String path = "/chapter_view_template?id=" + chapterId
                + "&mode=vertical&page=0&app_img_shunt=1&express=off&v=" + timestamp();
        String template = apiText(path, "18comicAPPContent");
        Matcher match = Pattern.compile("var\\s+scramble_id\\s*=\\s*([0-9]+)").matcher(template);
        // Same established fallback as upstream when the template omits this variable.
        result.put("scramble_id", match.find() ? Integer.parseInt(match.group(1)) : 220980);
        return result;
    }

    public void image(String chapterId, String filename, int scrambleId, File target) throws Exception {
        checkCancelled();
        String id = parseId(chapterId);
        requireImageFilename(filename);
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建图片目录");
        File temporary = new File(parent, target.getName() + ".part");
        Exception failure = null;
        for (String imageHost : IMAGE_HOSTS) {
            checkCancelled();
            try {
                byte[] bytes = request("https://" + imageHost + "/media/photos/" + id + "/" + filename
                        + "?v=" + timestamp(), null, true, 32 * 1024 * 1024, 8000);
                saveImage(bytes, id, filename, scrambleId, target, temporary);
                return;
            } catch (OutOfMemoryError e) {
                throw new IOException("手机内存不足，无法还原这张图片", e);
            } catch (Exception e) {
                checkCancelled();
                failure = e;
            } finally {
                temporary.delete();
            }
        }
        throw new IOException("图片下载失败：" + message(failure), failure);
    }

    public Bitmap imageBitmap(String chapterId, String filename, int scrambleId, int maxWidth) throws Exception {
        checkCancelled();
        String id = parseId(chapterId);
        requireImageFilename(filename);
        if (maxWidth < 1) throw new IllegalArgumentException("图片显示宽度无效");
        ensureReady();
        Exception failure = null;
        for (String imageHost : IMAGE_HOSTS) {
            checkCancelled();
            try {
                byte[] bytes = request("https://" + imageHost + "/media/photos/" + id + "/" + filename
                        + "?v=" + timestamp(), null, true, 32 * 1024 * 1024, 8000);
                synchronized (IMAGE_DECODE_LOCK) {
                    Bitmap decoded = decodeImage(bytes, id, filename, scrambleId);
                    try {
                        int width = decoded.getWidth(), height = decoded.getHeight();
                        double scale = Math.min(1d, Math.min((double) maxWidth / width, 8192d / height));
                        scale = Math.min(scale, Math.sqrt(8_000_000d / ((long) width * height)));
                        if (scale < 1d) {
                            Bitmap scaled = Bitmap.createScaledBitmap(decoded, Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), true);
                            if (scaled != decoded) decoded.recycle();
                            decoded = scaled;
                        }
                        if (decoded.getConfig() != Bitmap.Config.RGB_565) {
                            Bitmap opaque = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.RGB_565);
                            Canvas canvas = new Canvas(opaque);canvas.drawColor(Color.WHITE);canvas.drawBitmap(decoded, 0, 0, null);
                            decoded.recycle();decoded = opaque;
                        }
                        checkCancelled();
                        return decoded;
                    } catch (Exception | Error e) {
                        decoded.recycle();
                        throw e;
                    }
                }
            } catch (OutOfMemoryError e) {
                throw new IOException("手机内存不足，无法还原这张图片", e);
            } catch (Exception e) {
                checkCancelled();
                failure = e;
            }
        }
        throw new IOException("图片加载失败：" + message(failure), failure);
    }

    private static void requireImageFilename(String filename) throws IOException {
        if (filename == null || !filename.matches("[A-Za-z0-9_-]+\\.(?i:jpg|jpeg|png|webp|gif)")) {
            throw new IOException("接口返回了无效的图片文件名");
        }
    }

    private void saveImage(byte[] bytes, String id, String filename, int scrambleId, File target, File temporary) throws Exception {
        synchronized (IMAGE_DECODE_LOCK) {
            Bitmap decoded = decodeImage(bytes, id, filename, scrambleId);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    boolean png = target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png");
                    if (!decoded.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, output)) {
                        throw new IOException("图片保存失败");
                    }
                    output.getFD().sync();
                }
                checkCancelled();
                android.system.Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            } finally {
                decoded.recycle();
            }
        }
    }

    private Bitmap decodeImage(byte[] bytes, String id, String filename, int scrambleId) throws Exception {
        checkCancelled();
        Bitmap source = null, decoded = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            long pixels = (long) options.outWidth * options.outHeight;
            Runtime runtime = Runtime.getRuntime();
            long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
            if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("图片响应无法解码");
            if (pixels > 32_000_000L || pixels * 8L + 16 * 1024 * 1024L > available) {
                throw new IOException("图片尺寸过大，当前手机内存不足以安全还原");
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (source == null) throw new IOException("图片响应无法解码");
            checkCancelled();
            String basename = filename.substring(0, filename.lastIndexOf('.'));
            int count = filename.toLowerCase(java.util.Locale.ROOT).endsWith(".gif") ? 0
                    : segments(scrambleId, Long.parseLong(id), basename);
            decoded = restore(source, count);
            checkCancelled();
            return decoded;
        } catch (Exception | Error e) {
            if (decoded != null) decoded.recycle();
            throw e;
        } finally {
            if (source != null && source != decoded) source.recycle();
        }
    }

    static Bitmap restore(Bitmap source, int count) {
        if (count == 0) return source;
        int width = source.getWidth(), height = source.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        int remainder = height % count, strip = height / count;
        for (int i = 0; i < count; i++) {
            int sourceY = height - strip * (i + 1) - remainder;
            int targetY = strip * i + (i == 0 ? 0 : remainder);
            int size = strip + (i == 0 ? remainder : 0);
            if (size > 0) canvas.drawBitmap(source, new Rect(0, sourceY, width, sourceY + size),
                    new Rect(0, targetY, width, targetY + size), null);
        }
        return result;
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    private void ensureReady() throws Exception {
        checkCancelled();
        if (ready) return;
        if (!custom) {
            for (String url : DOMAIN_URLS) {
                try {
                    String encoded = new String(request(url, null, false, 1024 * 1024, 5000), StandardCharsets.UTF_8);
                    encoded = encoded.replaceFirst("^[^\\x00-\\x7F]+", "").trim();
                    JSONArray servers = new JSONObject(decrypt(encoded, "", DOMAIN_SECRET)).getJSONArray("Server");
                    List<String> updated = new ArrayList<>();
                    for (int i = 0; i < servers.length() && i < 12; i++) {
                        String value = host(servers.getString(i));
                        if (!updated.contains(value)) updated.add(value);
                    }
                    if (!updated.isEmpty()) {
                        for (String old : hosts) if (!updated.contains(old)) updated.add(old);
                        hosts.clear();
                        hosts.addAll(updated);
                        break;
                    }
                } catch (Exception ignored) {
                    checkCancelled();
                }
            }
        }
        JSONObject setting = api("/setting");
        String version = setting.optString("jm3_version");
        if (version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) appVersion = version;
        ready = true;
    }

    private JSONObject api(String path) throws Exception {
        Exception failure = null;
        for (String apiHost : new ArrayList<>(hosts)) {
            checkCancelled();
            try {
                String ts = timestamp();
                byte[] bytes = request("https://" + apiHost + path, ts, false, 8 * 1024 * 1024, 8000);
                JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (envelope.optInt("code") != 200) throw new IOException(envelope.optString("errorMsg", envelope.optString("msg", "接口请求失败")));
                Object data = envelope.opt("data");
                JSONObject result = data instanceof JSONObject ? (JSONObject) data
                        : new JSONObject(decrypt(envelope.getString("data"), ts, SECRET));
                if (path.equals("/setting") && cookies.isEmpty()) throw new IOException("接口未返回 Cookie，请更换 API 域名");
                hosts.remove(apiHost);
                hosts.add(0, apiHost);
                return result;
            } catch (Exception e) {
                checkCancelled();
                failure = e;
            }
        }
        throw new IOException("无法连接 API：" + message(failure), failure);
    }

    private String apiText(String path, String tokenSecret) throws Exception {
        Exception failure = null;
        for (String apiHost : new ArrayList<>(hosts)) {
            checkCancelled();
            try {
                return new String(request("https://" + apiHost + path, timestamp(), false,
                        8 * 1024 * 1024, 8000, tokenSecret), StandardCharsets.UTF_8);
            } catch (Exception e) {
                checkCancelled();
                failure = e;
            }
        }
        throw new IOException("章节解码信息获取失败：" + message(failure), failure);
    }

    private byte[] request(String url, String ts, boolean image, int limit, int timeout) throws Exception {
        return request(url, ts, image, limit, timeout, SECRET);
    }

    private byte[] request(String url, String ts, boolean image, int limit, int timeout, String tokenSecret) throws Exception {
        long deadline = System.nanoTime() + 25_000_000_000L;
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkCancelled();
            if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException("网络请求超时");
            URL address = new URL(url);
            if (!address.getProtocol().equals("https")) throw new IOException("接口必须使用 HTTPS");
            HttpURLConnection connection = (HttpURLConnection) address.openConnection();
            active = connection;
            try {
                checkCancelled();
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", UA);
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (ts != null) {
                    connection.setRequestProperty("token", md5(ts + tokenSecret));
                    connection.setRequestProperty("tokenparam", ts + "," + appVersion);
                }
                if (image) {
                    connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                    connection.setRequestProperty("X-Requested-With", "com.JMComic3.app");
                    connection.setRequestProperty("Referer", "https://" + hosts.get(0) + "/");
                }
                if ((ts != null || image) && !cookies.isEmpty()) {
                    StringBuilder header = new StringBuilder();
                    for (Map.Entry<String, String> entry : cookies.entrySet()) {
                        if (header.length() > 0) header.append("; ");
                        header.append(entry.getKey()).append('=').append(entry.getValue());
                    }
                    connection.setRequestProperty("Cookie", header.toString());
                }
                checkCancelled();
                int code = connection.getResponseCode();
                if (ts != null) {
                    for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                        if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                            for (String value : entry.getValue()) for (HttpCookie cookie : HttpCookie.parse(value)) {
                                cookies.put(cookie.getName(), cookie.getValue());
                            }
                        }
                    }
                }
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("接口重定向无效");
                    url = new URL(address, location).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                if (connection.getContentLengthLong() > limit) throw new IOException("响应文件过大");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled();
                        if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException("网络请求超时");
                        if (output.size() + read > limit) throw new IOException("响应文件过大");
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                }
            } finally {
                connection.disconnect();
                if (active == connection) active = null;
            }
        }
        throw new IOException("接口重定向次数过多");
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消下载");
    }

    private static void requireDetail(JSONObject result) throws IOException {
        if (result.optString("name").isEmpty() || result.optString("id").isEmpty()) {
            throw new IOException("该编号不存在，或接口返回的数据不完整");
        }
    }

    private static String timestamp() { return Long.toString(System.currentTimeMillis() / 1000); }
    private static String message(Exception e) { return e == null || e.getMessage() == null ? "网络请求失败" : e.getMessage(); }

    private static String host(String value) {
        try {
            URI uri = new URI(value.contains("://") ? value : "https://" + value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                    || uri.getRawQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (Exception e) {
            throw new IllegalArgumentException("请输入 API 域名，例如 www.example.com");
        }
    }

    public static String parseId(String input) throws IllegalArgumentException {
        String value = input == null ? "" : input.trim();
        Matcher match = ID.matcher(value);
        String number = null;
        if (match.matches()) number = match.group(1);
        else {
            try {
                URI uri = new URI(value);
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) {
                    match = ID_PATH.matcher(uri.getPath());
                    if (match.matches()) number = match.group(1);
                }
            } catch (Exception ignored) { }
        }
        if (number != null && Long.parseLong(number) > 0) return Long.toString(Long.parseLong(number));
        throw new IllegalArgumentException("请输入有效编号、JM 编号或作品链接");
    }

    public static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte b : digest) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String decrypt(String data, String timestamp, String secret) throws Exception {
        byte[] key = md5(timestamp + secret).getBytes(StandardCharsets.US_ASCII);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new String(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), StandardCharsets.UTF_8);
    }

    public static int segments(long scramble, long chapter, String basename) {
        if (chapter < scramble) return 0;
        if (chapter < 268850) return 10;
        String digest = md5(Long.toString(chapter) + basename);
        return ((int) digest.charAt(digest.length() - 1) % (chapter < 421926 ? 10 : 8)) * 2 + 2;
    }
}

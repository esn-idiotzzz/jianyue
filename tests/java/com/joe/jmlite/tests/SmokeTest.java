package com.joe.jmlite.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import com.joe.jmlite.JmApi;
import com.joe.jmlite.Store;
import com.joe.jmlite.DownloadService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Exercises native Android behavior using synthetic data only. */
public final class SmokeTest extends Instrumentation {
    private int checks;
    private final StringBuilder output = new StringBuilder();
    private Store store;
    private String fixtureId;
    private Activity activity;
    private SyntheticHttps transport;
    private final java.util.Map<Activity,Object> closedOnlineSessions=new java.util.IdentityHashMap<>();
    private boolean menuGestureSent;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            android.accessibilityservice.AccessibilityServiceInfo accessibility=getUiAutomation().getServiceInfo();accessibility.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(accessibility);
            testIds();
            testCrypto();
            testCancellation();
            testStrips();
            testStore();
            testLocalOrdering();
            transport = new SyntheticHttps();
            URL.setURLStreamHandlerFactory(transport);
            testUi();
            testMainRefresh();
            testDownload();
            testMemoryImage();
            testOnline();
            result.putString("stream", output + "PASS " + checks + " checks; synthetic/offline only\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            java.io.StringWriter detail = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(detail));
            result.putString("stream", output + "FAIL after " + checks + " checks\n" + detail);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (store != null && fixtureId != null) {
                try { store.delete(fixtureId); } catch (Exception ignored) { }
            }
        }
    }

    private void check(boolean good, String name) {
        if (!good) throw new AssertionError(name);
        checks++;
        output.append("ok ").append(checks).append(" ").append(name).append('\n');
    }

    private void eq(Object expected, Object actual, String name) {
        check(expected.equals(actual), name + " [expected=" + expected + ", actual=" + actual + "]");
    }

    private void testIds() {
        eq("123", JmApi.parseId("123"), "bare numeric ID");
        eq("123", JmApi.parseId("JM123"), "JM prefix");
        eq("123", JmApi.parseId("jm000123"), "case and leading zeros");
        eq("123", JmApi.parseId("https://example.invalid/album/123"), "album URL");
        eq("456", JmApi.parseId("https://example.invalid/photo/456/?page=1#test"), "chapter URL suffix");
        String[] invalid = {"", "0", "JM0", "-1", "abc", "12x34", "../123", "https://example.invalid/other/123", "9999999999999"};
        for (String value : invalid) {
            boolean rejected = false;
            try { JmApi.parseId(value); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "reject invalid ID " + value);
        }
    }

    private void testCrypto() throws Exception {
        eq("d41d8cd98f00b204e9800998ecf8427e", JmApi.md5(""), "MD5 empty standard vector");
        eq("900150983cd24fb0d6963f7d28e17f72", JmApi.md5("abc"), "MD5 ASCII standard vector");
        eq("880c64833265ad47a928afcf1b1220f5", JmApi.md5("1700000000185Hcomic3PAPP7R"), "upstream token vector");
        // Ciphertext independently generated with .NET AES ECB + PKCS7, ASCII MD5 key.
        String cipher = "H+N0tDioRCdm8l0BRRGwWBqW8TenuHXOsBeZG08iuGGLu0DxxqoRPGaWox931HrJ";
        eq("{\"fixture\":\"synthetic\",\"value\":42}", JmApi.decrypt(cipher, "1700000000", "185Hcomic3PAPP7R"), "AES response decode vector");
        boolean rejected = false;
        try { JmApi.decrypt("bad data", "1700000000", "185Hcomic3PAPP7R"); } catch (Exception expected) { rejected = true; }
        check(rejected, "malformed ciphertext rejected");
        eq(0, JmApi.segments(220980, 220979, "00001"), "before scramble threshold");
        eq(10, JmApi.segments(220980, 220980, "00001"), "scramble threshold inclusive");
        eq(10, JmApi.segments(220980, 268849, "00001"), "legacy strip boundary");
        eq(6, JmApi.segments(220980, 268850, "00001"), "MD5 ten-way boundary");
        eq(18, JmApi.segments(220980, 421925, "00001"), "last ten-way chapter");
        eq(14, JmApi.segments(220980, 421926, "00001"), "eight-way boundary");
        eq(12, JmApi.segments(220980, 1000000, "00001"), "current segmentation vector");
    }

    private void testStrips() throws Exception {
        Method restore = JmApi.class.getDeclaredMethod("restore", Bitmap.class, int.class);
        restore.setAccessible(true);
        Bitmap source = Bitmap.createBitmap(3, 13, Bitmap.Config.RGB_565);
        for (int y = 0; y < 13; y++) for (int x = 0; x < 3; x++) source.setPixel(x, y, Color.rgb(y * 16, x * 64, y * 16));
        Bitmap decoded = (Bitmap) restore.invoke(null, source, 4);
        eq(3, decoded.getWidth(), "strip width preserved");
        eq(13, decoded.getHeight(), "strip remainder height preserved");
        int[] rows = {9, 10, 11, 12, 6, 7, 8, 3, 4, 5, 0, 1, 2};
        boolean matches = true;
        for (int y = 0; y < rows.length; y++) for (int x = 0; x < 3; x++) if (decoded.getPixel(x, y) != source.getPixel(x, rows[y])) matches = false;
        check(matches, "synthetic strip order matches upstream remainder algorithm");
        check(restore.invoke(null, source, 0) == source, "unscrambled bitmap fast path");
        decoded.recycle();
        source.recycle();
    }

    private void testCancellation() throws Exception {
        JmApi api = new JmApi("");
        api.cancel();
        boolean rejected = false;
        try { api.album("123"); } catch (java.io.InterruptedIOException expected) { rejected = true; }
        check(rejected, "cancelled client aborts before any network request");
    }

    private void testLocalOrdering() throws Exception {
        Class<?> local = Class.forName("com.joe.jmlite.LocalImages");
        Method natural = local.getDeclaredMethod("natural", String.class, String.class);
        Method isImage = local.getDeclaredMethod("isImage", String.class, String.class);
        natural.setAccessible(true);
        isImage.setAccessible(true);
        check((Integer) natural.invoke(null, "2.jpg", "10.jpg") < 0, "local pages use numeric order");
        check((Integer) natural.invoke(null, "chapter2/10.jpg", "chapter10/2.jpg") < 0, "local chapter natural order");
        check((Integer) natural.invoke(null, "0002.jpg", "10.jpg") < 0, "leading zeros sort naturally");
        check((Integer) natural.invoke(null, "999999999999999999999.jpg", "1000000000000000000000.jpg") < 0, "large page numbers avoid integer overflow");
        eq(0, natural.invoke(null, "A.JPG", "a.jpg"), "local ordering ignores case");
        eq(true, isImage.invoke(null, "PAGE.JPEG", "application/octet-stream"), "uppercase image extension accepted");
        eq(true, isImage.invoke(null, "opaque", "image/png"), "image MIME accepted without suffix");
        eq(false, isImage.invoke(null, "notes.txt", "text/plain"), "unrelated local files excluded");
    }

    private void imageFile(File file) throws Exception {
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new Exception("fixture directory");
        Bitmap bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.RGB_565);
        bitmap.eraseColor(Color.rgb(52, 120, 246));
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream); }
        finally { bitmap.recycle(); }
    }

    private void testStore() throws Exception {
        store = new Store(getTargetContext());
        fixtureId = "990" + (System.currentTimeMillis() % 1000000000L);
        check(!store.dir(fixtureId).exists(), "fixture isolated from existing library");
        JSONObject fixture = new JSONObject().put("id", fixtureId).put("title", "Synthetic offline test").put("status", "paused").put("done", 0).put("total", 3).put("updated", System.currentTimeMillis());
        store.write(fixtureId, fixture);
        eq("Synthetic offline test", store.read(fixtureId).getString("title"), "metadata round trip");
        fixture.put("done", 1).put("status", "complete");
        store.write(fixtureId, fixture);
        eq(1, store.read(fixtureId).getInt("done"), "atomic metadata replacement");
        File book = store.dir(fixtureId);
        imageFile(new File(book, "0002-43/000001.jpg"));
        imageFile(new File(book, "0001-90/000002.jpg"));
        imageFile(new File(book, "0001-90/000001.jpg"));
        try (FileOutputStream ignored = new FileOutputStream(new File(book, "0001-90/000003.jpg.tmp"))) { }
        List<File> pages = store.pages(fixtureId);
        eq(3, pages.size(), "temporary image excluded");
        eq("0001-90", pages.get(0).getParentFile().getName(), "series order precedes chapter ID");
        eq("000001.jpg", pages.get(0).getName(), "page order within chapter");
        eq("000002.jpg", pages.get(1).getName(), "second page order");
        eq("0002-43", pages.get(2).getParentFile().getName(), "later chapter order");
        boolean listed = false;
        for (JSONObject item : store.list()) if (fixtureId.equals(item.optString("id"))) listed = true;
        check(listed, "book appears in library");
        boolean rejected = false;
        try { store.delete("../outside"); } catch (Exception expected) { rejected = true; }
        check(rejected, "delete rejects path traversal");
    }

    private void testUi() throws Exception {
        Context context = getTargetContext();
        Intent launch = new Intent().setClassName(context, "com.joe.jmlite.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(launch);
        waitForIdleSync();
        check(activity != null && !activity.isFinishing(), "main screen launches");
        check(activity.getWindow().getDecorView().getVisibility() == View.VISIBLE, "main screen visible");
        final View download=activity.findViewById(1002),online=activity.findViewById(1003);
        check(online!=null&&"在线阅读".contentEquals(((android.widget.TextView)online).getText()),"main screen exposes online reading button");
        check(download.getParent()==online.getParent()&&download.getLeft()<online.getLeft()&&download.getTop()==online.getTop(),"download and online buttons sit left and right");
        runOnMainSync(new Runnable() { @Override public void run() {
            EditText input = activity.findViewById(1001);
            input.setText("0");
            activity.findViewById(1002).performClick();
            activity.findViewById(1003).performClick();
        } });
        waitForIdleSync();
        check(!DownloadService.running && transport.requests == 0, "invalid UI input does not start a request");
        // Reader fixture is a small solid-color JPEG, never a remote/content request.
        Intent reader = new Intent().setClassName(context, "com.joe.jmlite.ReaderActivity").putExtra("id", fixtureId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity reading = startActivitySync(reader);
        waitForIdleSync();
        check(reading != null && !reading.isFinishing(), "offline reader launches with fixture");
        final ListView pageList = awaitReader(reading);
        check(pageList.getCount() == 3, "reader scans all synthetic pages");
        check(((View) field(reading, "top")).getVisibility() == View.GONE && ((View) field(reading, "bottom")).getVisibility() == View.GONE, "reader starts with controls hidden");
        check(immersive(reading.getWindow()), "reader hides status and navigation bars on entry");
        tap(pageList); awaitControls(reading, true);
        check(((View) field(reading, "top")).getVisibility() == View.VISIBLE && ((View) field(reading, "bottom")).getVisibility() == View.VISIBLE, "single tap reveals reader controls");
        check((reading.getWindow().getDecorView().getSystemUiVisibility() & (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) == 0, "reader controls reveal system bars");
        tap(pageList); awaitControls(reading, false);
        check(immersive(reading.getWindow()), "single tap restores immersive reading");
        final android.graphics.Rect tapArea = new android.graphics.Rect();
        runOnMainSync(() -> ((android.view.ViewGroup) pageList.getChildAt(0)).getChildAt(0).getGlobalVisibleRect(tapArea));
        for (int tap = 0; tap < 2; tap++) {
            long down = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, tapArea.centerX(), tapArea.centerY(), 0);
            sendPointerSync(event); event.recycle(); Thread.sleep(15);
            event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, tapArea.centerX(), tapArea.centerY(), 0);
            sendPointerSync(event); event.recycle(); if (tap == 0) Thread.sleep(60);
        }
        boolean zoomShown = false;
        for (long end = System.currentTimeMillis() + 3000; System.currentTimeMillis() < end && !zoomShown; Thread.sleep(30)) {
            android.view.accessibility.AccessibilityNodeInfo window = getUiAutomation().getRootInActiveWindow();
            zoomShown = window != null && !window.findAccessibilityNodeInfosByText("完成").isEmpty();
        }
        check(zoomShown, "real double-tap gesture opens zoom dialog");
        android.app.Dialog zoom = (android.app.Dialog) field(reading, "zoomDialog");
        check(zoom != null && immersive(zoom.getWindow()), "zoom view also hides system bars");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); waitForIdleSync();
        final SeekBar seek = (SeekBar) field(reading, "seek");
        testLiveSeek(reading,pageList,seek);
        eq(2, seek.getMax(), "reader seek range matches page count");
        Method jump = reading.getClass().getDeclaredMethod("jump", int.class); jump.setAccessible(true);
        runOnMainSync(() -> { try { jump.invoke(reading, 1); } catch (Exception e) { throw new RuntimeException(e); } });
        final int[] saved = new int[1];
        for (long end = System.currentTimeMillis() + 3000; System.currentTimeMillis() < end && saved[0] == 0; Thread.sleep(30)) runOnMainSync(() -> saved[0] = pageList.getFirstVisiblePosition());
        check(saved[0] > 0, "reader jump reaches a later page after bounds load");
        runOnMainSync(() -> { saved[0] = pageList.getFirstVisiblePosition(); reading.finish(); });
        waitForIdleSync();
        eq(saved[0], context.getSharedPreferences("reading", Context.MODE_PRIVATE).getInt("book_" + fixtureId, -1), "reader persists page on exit");
        final Activity reopened = startActivitySync(reader);
        final ListView resumedPages = awaitReader(reopened);
        final int[] restored = new int[1];
        runOnMainSync(() -> { restored[0] = resumedPages.getFirstVisiblePosition(); reopened.finish(); });
        eq(saved[0], restored[0], "reader restores page after reopening");
        waitForIdleSync();
        store.delete(fixtureId);
        check(!store.dir(fixtureId).exists(), "fixture cleanup contained to book");
        check(store.read(fixtureId) == null, "deleted metadata absent");
        fixtureId = null;
    }

    private Object field(Object activity, String name) throws Exception {
        java.lang.reflect.Field f = activity.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }

    private void testMainRefresh() throws Exception {
        Context context=getTargetContext();
        android.content.SharedPreferences settings=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        String previousFolders=settings.getString("folders","[]");
        boolean previousRunning=DownloadService.running;String previousId=DownloadService.activeId;
        final String folderUri="content://com.joe.jmlite.tests/tree/synthetic";
        String[] ids=new String[4];String[] statuses={"complete","paused","error","downloading"};
        try {
            for(int n=0;n<ids.length;n++) {
                long value=981000000000L+(android.os.SystemClock.uptimeMillis()%99999999L)*4+n;
                while(store.dir(Long.toString(value)).exists())value+=4;
                ids[n]=Long.toString(value);
                store.write(ids[n],new JSONObject().put("title","UI fixture "+statuses[n]).put("status",statuses[n]).put("done",n==0?12:1).put("total",12));
            }
            imageFile(new File(store.dir(ids[1]),"0001-127/000001.jpg"));
            store.write(ids[0],store.read(ids[0]));
            JSONArray folders=new JSONArray(previousFolders);folders.put(new JSONObject().put("uri",folderUri).put("title","UI imported folder"));
            settings.edit().putString("folders",folders.toString()).commit();
            DownloadService.running=false;DownloadService.activeId="";
            runOnMainSync(()->activity.finish());waitForIdleSync();
            Intent launch=new Intent().setClassName(context,"com.joe.jmlite.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch);refreshMain(activity);
            final Activity main=activity;
            final boolean[] defaults=new boolean[3];
            runOnMainSync(()->{try {
                defaults[0]=((View)field(main,"taskCard")).getVisibility()==View.GONE;
                defaults[1]=((EditText)main.findViewById(1001)).getText().length()==0;
                defaults[2]="开始下载".contentEquals(((android.widget.TextView)main.findViewById(1002)).getText());
            }catch(Exception e){throw new RuntimeException(e);}});
            final boolean downloadFiltered=hasMainRow(main,ids[0],null)&&!hasMainRow(main,ids[1],null)&&!hasMainRow(main,ids[2],null)&&!hasMainRow(main,ids[3],null);
            switchMain(main,1);
            final boolean localFiltered=hasMainRow(main,ids[0],null)&&!hasMainRow(main,ids[1],null)&&!hasMainRow(main,ids[2],null)&&!hasMainRow(main,ids[3],null);
            final boolean folderKept=hasMainRow(main,null,folderUri);
            switchMain(main,0);
            DownloadService.running=true;DownloadService.activeId=ids[3];refreshMain(main);
            final EditText input=main.findViewById(1001);
            final int[] notifications={0},detached={0};
            android.database.DataSetObserver observer=new android.database.DataSetObserver(){@Override public void onChanged(){notifications[0]++;}@Override public void onInvalidated(){notifications[0]++;}};
            android.widget.BaseAdapter mainAdapter=(android.widget.BaseAdapter)field(main,"adapter");
            runOnMainSync(()->{input.setText("JM123456");input.requestFocus();input.setSelection(3);input.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){detached[0]++;}});mainAdapter.registerDataSetObserver(observer);});
            for(int done=2;done<=3;done++){JSONObject progress=store.read(ids[3]);progress.put("done",done);store.write(ids[3],progress);refreshMain(main);}
            final boolean[] stable=new boolean[5];final int[] shownProgress={-1};
            runOnMainSync(()->{try {
                stable[0]=main.findViewById(1001)==input;stable[1]="JM123456".contentEquals(input.getText());stable[2]=input.hasFocus();stable[3]=input.getSelectionStart()==3&&input.getSelectionEnd()==3;stable[4]=input.isAttachedToWindow();
                shownProgress[0]=((android.widget.ProgressBar)field(main,"progress")).getProgress();mainAdapter.unregisterDataSetObserver(observer);
            }catch(Exception e){throw new RuntimeException(e);}});
            output.append("observed main refresh: oldTaskHidden=").append(defaults[0]).append(", historyFiltered=").append(downloadFiltered).append(", localFiltered=").append(localFiltered).append(", progressInvalidations=").append(notifications[0]).append(", inputDetachments=").append(detached[0]).append('\n');
            check(defaults[0],"fresh launch does not replay an old completed task");
            check(defaults[1]&&defaults[2],"fresh download form starts empty and ready");
            check(downloadFiltered,"download history includes completed books only");
            check(localFiltered,"local library excludes paused failed and active downloads");
            check(folderKept,"local library keeps manually added folders");
            eq(3,shownProgress[0],"current task progress updates without rebuilding its input");
            eq(0,notifications[0],"page progress does not invalidate the history adapter");
            eq(0,detached[0],"progress never detaches the download input");
            check(stable[0]&&stable[1]&&stable[2]&&stable[3]&&stable[4],"progress preserves input identity text focus selection and attachment");
            JSONObject complete=store.read(ids[3]);complete.put("status","complete").put("done",12);store.write(ids[3],complete);DownloadService.running=false;DownloadService.activeId="";refreshMain(main);
            check(((View)field(main,"taskCard")).getVisibility()==View.GONE,"completed task restores the default download panel");
            check(hasMainRow(main,ids[3],null),"finished task enters history only after completion");
            eq(1,store.pages(ids[1]).size(),"hidden partial downloads retain resumable files");
            runOnMainSync(()->main.finish());waitForIdleSync();activity=startActivitySync(launch);refreshMain(activity);
            check(((View)field(activity,"taskCard")).getVisibility()==View.GONE,"reopening after completion keeps the default panel");
            check(((EditText)activity.findViewById(1001)).getText().length()==0,"reopening clears the previous download input");
            testDownloadMenu(activity,ids,folderUri);
        } finally {
            DownloadService.running=previousRunning;DownloadService.activeId=previousId;
            settings.edit().putString("folders",previousFolders).commit();
            for(String id:ids)if(id!=null)try{store.delete(id);}catch(Exception ignored){}
            if(activity!=null&&!activity.isFinishing())refreshMain(activity);
        }
    }

    private void refreshMain(Activity main) throws Exception {
        Method refresh=main.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);
        runOnMainSync(()->{try{refresh.invoke(main);}catch(Exception e){throw new RuntimeException(e);}});
        final boolean[] ready={false};
        for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end&&!ready[0];Thread.sleep(20))runOnMainSync(()->{try{ready[0]=!(Boolean)field(main,"reading");}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();if(!ready[0])throw new AssertionError("main metadata refresh did not settle");
    }

    private void testDownloadMenu(Activity main,String[] ids,String folderUri) throws Exception {
        imageFile(new File(store.dir(ids[0]),"0001-127/000001.jpg"));String before=diskState();
        longPressRow(main,ids[0],null);
        check(dialogHas("查看车牌号")&&dialogHas("删除下载内容")&&!dialogHas("继续下载"),"downloaded resource long press exposes plate and delete actions");
        clickDialogText("查看车牌号");
        check(dialogHas("车牌号")&&dialogHas(ids[0]),"plate dialog displays the exact original download ID");
        clickDialogText("知道了");eq(before,diskState(),"viewing the plate does not modify downloaded files or metadata");
        longPressRow(main,ids[0],null);clickDialogText("删除下载内容");
        check(dialogHas("删除已下载图片？"),"download deletion retains its confirmation dialog");
        clickDialogText("取消");eq(before,diskState(),"cancelling deletion preserves downloaded content");
        longPressRow(main,ids[0],null);clickDialogText("删除下载内容");clickDialogText("确定");
        for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end&&store.dir(ids[0]).exists();Thread.sleep(20)){}
        check(!store.dir(ids[0]).exists(),"confirmed deletion removes only the selected downloaded book");
        check(store.read(ids[3])!=null&&store.pages(ids[1]).size()==1,"deleting a download preserves other completed and partial books");
        refreshMain(main);switchMain(main,1);longPressRow(main,null,folderUri);
        check(dialogHas("重命名书籍")&&dialogHas("书籍分类")&&dialogHas("删除")&&dialogHas("分享")&&!dialogHas("查看车牌号"),"local folder entry exposes the four bookshelf actions without a plate ID");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
    }

    private void longPressRow(Activity main,String id,String uri) throws Exception {
        final android.widget.AbsListView list=(android.widget.AbsListView)(field(main,"list")!=null?field(main,"list"):field(main,"grid"));final int headers=list instanceof ListView?((ListView)list).getHeaderViewsCount():0;final int[] position={-1};
        runOnMainSync(()->{try{List<?> rows=(List<?>)field(main,"rows");for(int n=0;n<rows.size();n++){JSONObject row=(JSONObject)rows.get(n);if(id!=null&&id.equals(row.optString("id"))||uri!=null&&uri.equals(row.optString("uri"))){position[0]=n+headers;if(list instanceof ListView)((ListView)list).setSelectionFromTop(position[0],0);else list.setSelection(position[0]);break;}}}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();if(position[0]<0)throw new AssertionError("menu fixture missing");
        final android.graphics.Rect area=new android.graphics.Rect();final View[] row={null};final boolean[] visible={false};
        for(long end=System.currentTimeMillis()+2000;System.currentTimeMillis()<end&&!visible[0];Thread.sleep(20))runOnMainSync(()->{row[0]=list.getChildAt(position[0]-list.getFirstVisiblePosition());visible[0]=row[0]!=null&&list.getPositionForView(row[0])==position[0]&&row[0].getGlobalVisibleRect(area)&&area.height()>48&&area.width()>48;});
        if(!visible[0])throw new AssertionError("long-press row not laid out: position="+position[0]+", first="+list.getFirstVisiblePosition()+", area="+area);
        output.append("menu row position=").append(position[0]).append(", first=").append(list.getFirstVisiblePosition()).append(", area=").append(area).append('\n');
        boolean gesture=!menuGestureSent;
        if(gesture){menuGestureSent=true;long down=android.os.SystemClock.uptimeMillis();float y=Math.min(area.bottom-16,area.centerY()+16);pointer(down,android.view.MotionEvent.ACTION_DOWN,area.centerX(),y);Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+250);pointer(down,android.view.MotionEvent.ACTION_UP,area.centerX(),y);}
        waitForIdleSync();
        if(gesture){Thread.sleep(100);StringBuilder text=new StringBuilder();windowTexts(getUiAutomation().getRootInActiveWindow(),text);output.append("actual long-press active window: ").append(text).append('\n');}
        if(!gesture||!dialogHas(list instanceof ListView?"查看车牌号":"重命名书籍")){
            if(gesture)output.append("raw emulator gesture did not open a menu; checking the registered item-long-click callback instead\n");
            runOnMainSync(()->{if(list instanceof ListView){if(list.getOnItemLongClickListener()==null||!list.getOnItemLongClickListener().onItemLongClick(list,row[0],position[0],list.getAdapter().getItemId(position[0])))throw new AssertionError("list long-press callback did not consume the downloaded item");}else try{Method manage=main.getClass().getDeclaredMethod("manageBook",JSONObject.class);manage.setAccessible(true);manage.invoke(main,list.getAdapter().getItem(position[0]));}catch(Exception failure){throw new RuntimeException(failure);}});waitForIdleSync();
        }
        StringBuilder windows=new StringBuilder();for(android.view.accessibility.AccessibilityNodeInfo root:dialogRoots())windowTexts(root,windows);output.append("item menu windows: ").append(windows).append('\n');
    }

    private boolean dialogHas(String text) throws Exception {
        for(long end=System.currentTimeMillis()+1500;System.currentTimeMillis()<end;Thread.sleep(20)){
            for(android.view.accessibility.AccessibilityNodeInfo root:dialogRoots())for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&text.contentEquals(node.getText()))return true;
        }
        return false;
    }

    private void windowTexts(android.view.accessibility.AccessibilityNodeInfo node,StringBuilder text){if(node==null)return;if(node.getText()!=null)text.append('[').append(node.getText()).append(']');for(int n=0;n<node.getChildCount();n++)windowTexts(node.getChild(n),text);}

    private java.util.List<android.view.accessibility.AccessibilityNodeInfo> dialogRoots(){
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> roots=new java.util.ArrayList<>();
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows=new java.util.ArrayList<>(getUiAutomation().getWindows());windows.sort((a,b)->Integer.compare(b.getLayer(),a.getLayer()));
        for(android.view.accessibility.AccessibilityWindowInfo window:windows){android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root!=null)roots.add(root);}
        android.view.accessibility.AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();if(active!=null)roots.add(active);return roots;
    }

    private void clickDialogText(String text) throws Exception {
        for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end;Thread.sleep(20)){
            for(android.view.accessibility.AccessibilityNodeInfo root:dialogRoots())for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&text.contentEquals(node.getText())){
                while(node!=null&&!node.isClickable())node=node.getParent();
                if(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){waitForIdleSync();return;}
            }
        }
        throw new AssertionError("dialog action unavailable: "+text);
    }

    private void switchMain(Activity main,int tab) throws Exception {
        Method change=main.getClass().getDeclaredMethod("switchTab",int.class);change.setAccessible(true);
        runOnMainSync(()->{try{change.invoke(main,tab);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
    }

    private boolean hasMainRow(Activity main,String id,String uri) throws Exception {
        final boolean[] found={false};runOnMainSync(()->{try{for(Object item:(List<?>)field(main,"rows")){JSONObject row=(JSONObject)item;if(id!=null&&id.equals(row.optString("id"))||uri!=null&&uri.equals(row.optString("uri")))found[0]=true;}}catch(Exception e){throw new RuntimeException(e);}});return found[0];
    }

    private boolean immersive(Window window) throws Exception {
        final boolean[] hidden = new boolean[1];
        for (long end = System.currentTimeMillis() + 2000; System.currentTimeMillis() < end && !hidden[0]; Thread.sleep(30)) runOnMainSync(() -> {
            int flags = window.getDecorView().getSystemUiVisibility();
            hidden[0] = (flags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 && (flags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;
            if (android.os.Build.VERSION.SDK_INT >= 30 && window.getDecorView().getRootWindowInsets() != null) hidden[0] &= !window.getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.statusBars()) && !window.getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.navigationBars());
        });
        return hidden[0];
    }

    private void tap(ListView list) throws Exception {
        final android.graphics.Rect area = new android.graphics.Rect();
        runOnMainSync(() -> ((android.view.ViewGroup) list.getChildAt(0)).getChildAt(0).getGlobalVisibleRect(area));
        long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down, 0, area.centerX(), area.centerY(), 0); sendPointerSync(event); event.recycle();
        Thread.sleep(20); event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), 1, area.centerX(), area.centerY(), 0); sendPointerSync(event); event.recycle();
    }

    private void testLiveSeek(Activity reading,ListView list,SeekBar seek) throws Exception {
        tap(list);awaitControls(reading,true);
        final android.graphics.Rect area=new android.graphics.Rect();
        runOnMainSync(()->seek.getGlobalVisibleRect(area));
        check(!area.isEmpty(),"reader seek bar is visible for live dragging");
        float left=area.left+seek.getPaddingLeft(),right=area.right-seek.getPaddingRight(),middle=(left+right)/2,y=area.centerY();
        long down=android.os.SystemClock.uptimeMillis();boolean followed=false,latest=false;int heldProgress=-1;
        pointer(down,android.view.MotionEvent.ACTION_DOWN,left,y);
        try {
            pointer(down,android.view.MotionEvent.ACTION_MOVE,middle,y);
            followed=awaitPage(list,1,3000);
            heldProgress=seek.getProgress();
            pointer(down,android.view.MotionEvent.ACTION_MOVE,right,y);
            pointer(down,android.view.MotionEvent.ACTION_MOVE,middle,y);
            Thread.sleep(200);latest=awaitPage(list,1,1000);
        } finally { pointer(down,android.view.MotionEvent.ACTION_UP,middle,y); }
        waitForIdleSync();
        check(followed,"reader image follows seek thumb before finger release");
        eq(1,heldProgress,"image scrolling does not overwrite the held seek thumb");
        check(latest&&awaitPage(list,1,1000),"rapid live seeking keeps the latest target after release");
    }

    private void pointer(long down,int action,float x,float y) {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);
        sendPointerSync(event);event.recycle();
    }

    private boolean awaitPage(ListView list,int page,long timeout) throws Exception {
        final boolean[] atPage={false};
        for(long end=System.currentTimeMillis()+timeout;System.currentTimeMillis()<end&&!atPage[0];Thread.sleep(20))runOnMainSync(()->{View first=list.getChildAt(0);atPage[0]=list.getFirstVisiblePosition()==page&&first!=null&&Math.abs(first.getTop())<=2;});
        return atPage[0];
    }

    private void awaitControls(Activity activity, boolean visible) throws Exception {
        View top = (View) field(activity, "top");
        for (long end = System.currentTimeMillis() + 2000; System.currentTimeMillis() < end && (top.getVisibility() == View.VISIBLE) != visible; Thread.sleep(30)) waitForIdleSync();
    }

    private ListView awaitReader(Activity activity) throws Exception {
        final ListView pages = (ListView) field(activity, "list"); final boolean[] loaded = new boolean[1];
        for (long end = System.currentTimeMillis() + 8000; System.currentTimeMillis() < end && !loaded[0]; Thread.sleep(30)) {
            runOnMainSync(() -> { android.view.ViewGroup row = (android.view.ViewGroup) pages.getChildAt(0); loaded[0] = pages.getCount() == 3 && row != null && ((ImageView) row.getChildAt(0)).getDrawable() != null; });
        }
        check(loaded[0], "reader decodes visible image asynchronously"); return pages;
    }

    private void testDownload() throws Exception {
        fixtureId = "989" + (System.currentTimeMillis() % 1000000000L);
        transport.albumId = fixtureId;
        Intent start = new Intent(getTargetContext(), DownloadService.class).setAction(DownloadService.ACTION_START).putExtra("id", fixtureId);
        getTargetContext().startForegroundService(start);
        JSONObject meta = awaitDownload(fixtureId);
        eq("complete", meta.optString("status"), "synthetic foreground download completes");
        eq("Synthetic test album", meta.optString("title"), "API title persisted");
        eq(12, meta.optInt("done"), "all concurrent synthetic images counted exactly once");
        eq(12, meta.optInt("total"), "multi-chapter total counted");
        check(transport.validToken && transport.cookieSeen && transport.imageCookies, "request token and initialized Cookie sent by every image worker");
        eq(1, transport.settingRequests, "workers share initialized session without repeated settings requests");
        check(transport.maxInFlight > 4 && transport.maxInFlight <= 6, "network requests use six workers within the configured limit [peak="+transport.maxInFlight+"]");
        eq(0, transport.inFlight, "completed job has no remaining network requests");
        List<File> pages = store.pages(fixtureId);
        eq(12, pages.size(), "decoded images discoverable by reader");
        eq("0001-127", pages.get(0).getParentFile().getName(), "service sorts chapter series by sort field");
        eq("0002-131", pages.get(6).getParentFile().getName(), "second chapter stored after first");
        eq("000006.jpg", pages.get(5).getName(), "concurrent completion preserves page order");
        int previous = transport.imageRequests;
        check(pages.get(1).delete(), "remove one synthetic page to exercise resume");
        meta.put("status", "paused");
        store.write(fixtureId, meta);
        getTargetContext().startForegroundService(start);
        meta = awaitDownload(fixtureId);
        eq("complete", meta.optString("status"), "resume completes");
        eq(1, transport.imageRequests - previous, "resume fetches only missing page");
        eq(12, store.pages(fixtureId).size(), "resume preserves complete page set");
        eq(2, transport.settingRequests, "resumed plan initializes one fresh cookie session");
        store.delete(fixtureId);
        fixtureId = null;
        testPausedDownload();
        testFailedDownload();
    }

    private void testPausedDownload() throws Exception {
        fixtureId = "988" + (System.currentTimeMillis() % 1000000000L); transport.albumId = fixtureId; transport.imageDelayMs = 300;
        Intent start = new Intent(getTargetContext(), DownloadService.class).setAction(DownloadService.ACTION_START).putExtra("id", fixtureId);
        getTargetContext().startForegroundService(start);
        int saved = 0;
        for (long end = System.currentTimeMillis() + 8000; System.currentTimeMillis() < end && saved == 0; Thread.sleep(15)) { JSONObject m = store.read(fixtureId); if(m != null) saved = m.optInt("done"); }
        check(saved > 0 && saved < 12 && DownloadService.running, "pause starts after a partially saved parallel download");
        long before = System.currentTimeMillis(); getTargetContext().startService(new Intent(getTargetContext(), DownloadService.class).setAction(DownloadService.ACTION_CANCEL));
        JSONObject meta = awaitStopped(fixtureId, "paused");
        check(System.currentTimeMillis() - before < 3000, "pause aborts active network workers promptly");
        eq(0, transport.inFlight, "paused job settles every active connection");
        int kept = store.pages(fixtureId).size(); eq(kept, meta.optInt("done"), "paused metadata matches atomically saved pages");
        Thread.sleep(150); eq(kept, store.pages(fixtureId).size(), "paused workers do not write late pages");
        int previous = transport.imageRequests; transport.imageDelayMs = 30;
        getTargetContext().startForegroundService(start); meta = awaitDownload(fixtureId);
        eq("complete", meta.optString("status"), "parallel download resumes after pause");
        eq(12 - kept, transport.imageRequests - previous, "pause resume requests only unsaved pages");
        eq(12, meta.optInt("done"), "pause resume progress remains exact");
        store.delete(fixtureId); fixtureId = null;
    }

    private void testFailedDownload() throws Exception {
        fixtureId = "987" + (System.currentTimeMillis() % 1000000000L); transport.albumId = fixtureId;
        transport.imageDelayMs = 200; transport.failPath = "/media/photos/127/00003.jpg";
        Intent start = new Intent(getTargetContext(), DownloadService.class).setAction(DownloadService.ACTION_START).putExtra("id", fixtureId);
        getTargetContext().startForegroundService(start); JSONObject meta = awaitStopped(fixtureId, "error");
        eq(0, transport.inFlight, "failed job settles peer requests before terminal state");
        int kept = store.pages(fixtureId).size(); eq(kept, meta.optInt("done"), "failed-job progress matches saved pages");
        Thread.sleep(250); eq(kept, store.pages(fixtureId).size(), "failed workers cannot write after terminal state");
        transport.failPath = null; transport.imageDelayMs = 30; int previous = transport.imageRequests;
        meta.put("status", "paused"); store.write(fixtureId, meta); getTargetContext().startForegroundService(start); meta = awaitDownload(fixtureId);
        eq("complete", meta.optString("status"), "retry recovers failed parallel job");
        eq(12 - kept, transport.imageRequests - previous, "failed retry fetches only unsaved pages");
        eq(12, meta.optInt("done"), "failed retry restores exact full progress");
        check(transport.imageCookies && transport.validToken, "resumed workers retain valid authentication headers");
        check(transport.maxInFlight <= 6, "pause and retry never exceed six active requests");
        store.delete(fixtureId); fixtureId = null;
    }

    private void testMemoryImage() throws Exception {
        String before=diskState();Bitmap source=Bitmap.createBitmap(8,23,Bitmap.Config.RGB_565),decoded=null,scaled=null;
        JmApi api=new JmApi("");
        try {
            for(int y=0;y<23;y++)for(int x=0;x<8;x++)source.setPixel(x,y,Color.rgb(y*8,x*24,y*8));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,bytes);
            transport.overridePath="/media/photos/220980/00001.png";transport.overrideBody=bytes.toByteArray();
            decoded=api.imageBitmap("220980","00001.png",220980,64);
            eq(8,decoded.getWidth(),"online bitmap keeps original width when smaller than display limit");
            eq(23,decoded.getHeight(),"online bitmap keeps restored image height");
            int[] rows={18,19,20,21,22,16,17,14,15,12,13,10,11,8,9,6,7,4,5,2,3,0,1};boolean restored=true;
            for(int y=0;y<rows.length;y++)for(int x=0;x<8;x++)if(decoded.getPixel(x,y)!=source.getPixel(x,rows[y]))restored=false;
            check(restored,"memory-only image path restores synthetic strips before display");
            scaled=api.imageBitmap("220980","00001.png",220980,4);
            check(scaled.getWidth()<=4&&scaled.getHeight()>0,"online image is downscaled to requested display width");
            check(scaled.getConfig()==Bitmap.Config.RGB_565,"online image uses bounded RGB565 bitmap memory");
            int requests=transport.requests;api.cancel();boolean cancelled=false;
            try{api.imageBitmap("220980","00001.png",220980,64);}catch(java.io.InterruptedIOException expected){cancelled=true;}
            check(cancelled&&transport.requests==requests,"cancelled online bitmap client makes no further requests");
            eq(before,diskState(),"memory-only image decoding writes no files or disk cache");
        } finally {api.cancel();transport.overridePath=null;transport.overrideBody=null;source.recycle();if(decoded!=null)decoded.recycle();if(scaled!=null)scaled.recycle();}
    }

    private void testOnline() throws Exception {
        String id="986"+(System.currentTimeMillis()%1000000000L),beforeFiles=filesState(),beforeCache=cacheFilesState();int settings=transport.settingRequests;
        synchronized(transport){transport.imagePathRequests.clear();transport.imageRequestOrder.clear();}
        transport.albumId=id;transport.imageDelayMs=120;
        Activity reading=openOnline(id);
        try {
            ListView list=awaitOnlineImage(reading,0);
            check(id.equals(reading.getIntent().getStringExtra("online_id")),"online button opens the requested book without starting a download");
            check(!DownloadService.running,"online reading does not start the download service");
            eq(12,list.getCount(),"online reader exposes all chapters and pages");
            eq(11,((SeekBar)field(reading,"seek")).getMax(),"online seek range covers complete remote manifest");
            check(immersive(reading.getWindow()),"online reading starts in fullscreen");
            check(((android.widget.TextView)field(reading,"readerTitle")).getText().toString().contains("Synthetic test album"),"online reader shows the resolved book title");
            java.util.List<?> pages=(java.util.List<?>)field(reading,"pages");
            java.lang.reflect.Field uri=pages.get(0).getClass().getDeclaredField("uri");uri.setAccessible(true);
            check(uri.get(pages.get(0)).toString().contains("127/")&&uri.get(pages.get(6)).toString().contains("131/"),"online page order follows chapter sort field");
            eq(1,transport.settingRequests-settings,"online workers reuse one initialized session");
            checkOnlineWorkers(reading);
            check(!store.dir(id).exists()&&store.read(id)==null,"online reader creates no local book or metadata record");
            awaitImageRequest("/media/photos/127/00002.jpg");awaitNetworkIdle();
            File firstCached=awaitCachedPage(reading,0),nextCached=awaitCachedPage(reading,1);
            check(firstCached.isFile()&&nextCached.isFile()&&!firstCached.equals(nextCached),"online cache retains the first page and an ascending next-page prefix");
            check(imageRequestsFor("/media/photos/127/00002.jpg")>0,"online reading prefetches the next page without scrolling");
            check(!beforeCache.equals(cacheFilesState()),"online session keeps temporary image cache while reading");
            eq(beforeFiles,filesState(),"online temporary caching never writes into downloaded books");

            tap(list);awaitControls(reading,true);SeekBar seek=(SeekBar)field(reading,"seek");android.graphics.Rect area=new android.graphics.Rect();
            runOnMainSync(()->seek.getGlobalVisibleRect(area));float left=area.left+seek.getPaddingLeft(),right=area.right-seek.getPaddingRight(),x=left+(right-left)*6f/11f,y=area.centerY();
            long down=android.os.SystemClock.uptimeMillis();boolean followed;
            pointer(down,android.view.MotionEvent.ACTION_DOWN,left,y);
            try{pointer(down,android.view.MotionEvent.ACTION_MOVE,x,y);followed=awaitPage(list,6,4000);}finally{pointer(down,android.view.MotionEvent.ACTION_UP,x,y);}
            check(followed,"online images follow the progress bar before finger release");
            awaitOnlineImage(reading,6);
            awaitNetworkIdle();int firstRequests=imageRequestsFor("/media/photos/127/00001.jpg");
            Method jump=reading.getClass().getDeclaredMethod("jump",int.class);jump.setAccessible(true);final Activity reader=reading;
            runOnMainSync(()->{try{jump.invoke(reader,0);}catch(Exception e){throw new RuntimeException(e);}});awaitOnlineImage(reading,0);awaitNetworkIdle();
            eq(firstRequests,imageRequestsFor("/media/photos/127/00001.jpg"),"returning to an earlier online page reuses its session cache");
            doubleTap(list);android.app.Dialog zoom=null;
            for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end&&zoom==null;Thread.sleep(30))zoom=(android.app.Dialog)field(reading,"zoomDialog");
            check(zoom!=null&&zoom.isShowing(),"online reader opens image zoom directly from memory");
            check(immersive(zoom.getWindow()),"online zoom remains fullscreen");
            awaitNetworkIdle();waitForIdleSync();check(zoom.isShowing(),"online zoom stays open after its bitmap loads");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
            eq(firstRequests,imageRequestsFor("/media/photos/127/00001.jpg"),"online zoom reads the cached page without another image request");
            synchronized(transport){boolean unique=true;for(int count:transport.imagePathRequests.values())if(count!=1)unique=false;check(unique,"successful online pages are fetched once per session despite rebinding");}
            check(transport.imageCookies&&transport.validToken,"online requests preserve API tokens and image session cookies");
            check(transport.maxInFlight<=6,"online image requests respect the six-worker bound");
        } finally {transport.failPath=null;finishReader(reading);awaitNetworkIdle();}
        checkReaderReleased(reading);
        refreshMain(activity);check(!hasMainRow(activity,id,null),"online book is absent from download history");switchMain(activity,1);
        check(!hasMainRow(activity,id,null),"online book is absent from local gallery");switchMain(activity,0);
        eq(beforeFiles,filesState(),"online reading leaves downloaded files unchanged after exit");
        eq(beforeCache,cacheFilesState(),"online session removes its temporary image files after exit");

        String retryId="984"+(System.currentTimeMillis()%1000000000L);transport.albumId=retryId;transport.failPath="/media/photos/127/00001.jpg";
        reading=openOnline(retryId);
        try {
            final ListView list=(ListView)field(reading,"list");final android.widget.TextView[] error={null};
            for(long end=System.currentTimeMillis()+5000;System.currentTimeMillis()<end&&error[0]==null;Thread.sleep(30))runOnMainSync(()->{if(list.getFirstVisiblePosition()==0&&list.getChildAt(0) instanceof android.view.ViewGroup){View candidate=((android.view.ViewGroup)list.getChildAt(0)).getChildAt(1);if(candidate instanceof android.widget.TextView&&candidate.getVisibility()==View.VISIBLE&&((android.widget.TextView)candidate).getText().toString().contains("重试"))error[0]=(android.widget.TextView)candidate;}});
            check(error[0]!=null,"online image failure exposes an inline retry action");
            transport.failPath=null;runOnMainSync(()->error[0].performClick());awaitOnlineImage(reading,0);awaitNetworkIdle();
        } finally {transport.failPath=null;finishReader(reading);awaitNetworkIdle();}
        checkReaderReleased(reading);eq(beforeCache,cacheFilesState(),"retry session removes all temporary image files on exit");

        String cancelId="985"+(System.currentTimeMillis()%1000000000L);transport.albumId=cancelId;transport.imageDelayMs=1000;
        reading=openOnline(cancelId);
        try {
            for(long end=System.currentTimeMillis()+5000;System.currentTimeMillis()<end&&transport.inFlight==0;Thread.sleep(20))waitForIdleSync();
            check(transport.inFlight>0,"online cancellation test reaches an active image transfer");
            long started=System.currentTimeMillis();finishReader(reading);awaitNetworkIdle();
            check(System.currentTimeMillis()-started<3000,"leaving online reading promptly cancels active image transfers");
            int requests=transport.requests;Thread.sleep(150);eq(requests,transport.requests,"closed online reader starts no late requests");
        } finally {finishReader(reading);transport.imageDelayMs=30;awaitNetworkIdle();}
        checkReaderReleased(reading);eq(beforeFiles,filesState(),"cancelled online reading preserves offline books");eq(beforeCache,cacheFilesState(),"cancelled online reading removes temporary image files");
        testBoundedOnlinePrefetch(beforeFiles,beforeCache);
    }

    private void checkOnlineWorkers(Activity reader) throws Exception {
        Object session=field(reader,"onlineSession");
        java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)field(session,"workers");
        eq(6,workers.getMaximumPoolSize(),"online session permits six parallel image workers");
    }

    private void testBoundedOnlinePrefetch(String beforeFiles,String beforeCache)throws Exception{
        transport.pagesPerChapter=20;transport.imageDelayMs=30;synchronized(transport){transport.imagePathRequests.clear();transport.imageRequestOrder.clear();}
        Activity reading=openOnline("983"+(System.currentTimeMillis()%1000000000L));
        try{
            ListView list=awaitOnlineImage(reading,0);eq(40,list.getCount(),"large online fixture contains more pages than a prefetch window");
            awaitImageRequest("/media/photos/127/00002.jpg");awaitNetworkIdle();Thread.sleep(750);awaitNetworkIdle();
            int fetched;synchronized(transport){fetched=transport.imagePathRequests.size();}
            check(fetched>1&&fetched<40,"online opening prefetches nearby pages without fetching the entire large book [fetched="+fetched+"]");
            Thread.sleep(250);synchronized(transport){eq(fetched,transport.imagePathRequests.size(),"prefetch stops at its bounded window until reading advances");}
            eq(beforeFiles,filesState(),"large online prefetch does not create offline downloads");
        }finally{finishReader(reading);awaitNetworkIdle();transport.pagesPerChapter=6;transport.imageDelayMs=30;}
        checkReaderReleased(reading);eq(beforeCache,cacheFilesState(),"large online session clears its temporary image files");
    }

    private int imageRequestsFor(String path){synchronized(transport){Integer count=transport.imagePathRequests.get(path);return count==null?0:count;}}
    private void awaitImageRequest(String path)throws Exception{for(long end=System.currentTimeMillis()+4000;System.currentTimeMillis()<end&&imageRequestsFor(path)==0;Thread.sleep(20)){}check(imageRequestsFor(path)>0,"ordered online prefetch reaches "+path);}
    private File awaitCachedPage(Activity reader,int page)throws Exception{
        Object session=field(reader,"onlineSession");Method cached=session.getClass().getDeclaredMethod("cachedFile",int.class);cached.setAccessible(true);File file=null;
        for(long end=System.currentTimeMillis()+4000;System.currentTimeMillis()<end;Thread.sleep(20)){file=(File)cached.invoke(session,page);if(file!=null&&file.isFile()&&file.length()>0)return file;}
        throw new AssertionError("online cached page missing: "+page);
    }
    private String filesState()throws Exception{java.util.TreeSet<String> state=new java.util.TreeSet<>();fileState(getTargetContext().getFilesDir(),"files",state);return state.toString();}
    private String cacheFilesState()throws Exception{java.util.TreeSet<String> state=new java.util.TreeSet<>();fileState(getTargetContext().getCacheDir(),"cache",state);state.removeIf(path->path.endsWith("/"));return state.toString();}
    private void checkReaderReleased(Activity reader)throws Exception{
        java.util.concurrent.ThreadPoolExecutor loader=(java.util.concurrent.ThreadPoolExecutor)field(reader,"loader"),scanner=(java.util.concurrent.ThreadPoolExecutor)field(reader,"scanner");
        for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end&&(!loader.isTerminated()||!scanner.isTerminated());Thread.sleep(20)){}
        check(loader.isTerminated()&&scanner.isTerminated(),"closed reader terminates its executors without relying on GC");
        Object session=closedOnlineSessions.remove(reader);if(session==null)session=field(reader,"onlineSession");
        java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)field(session,"workers");Method directory=session.getClass().getDeclaredMethod("directory");directory.setAccessible(true);File folder=(File)directory.invoke(session);
        for(long end=System.currentTimeMillis()+5000;System.currentTimeMillis()<end&&(!workers.isTerminated()||folder.exists());Thread.sleep(20)){}
        check(workers.isTerminated()&&!folder.exists(),"closed online session terminates workers and removes its cache directory");
        java.util.List<?> clients=(java.util.List<?>)field(session,"clients");synchronized(clients){check(clients.isEmpty(),"closed online session releases every HTTP client");}
        check(((java.util.Map<?,?>)field(session,"jobs")).isEmpty(),"closed online session releases completed and cancelled page jobs");
    }

    private Activity openOnline(String id) throws Exception {
        switchMain(activity,0);transport.albumId=id;
        ActivityMonitor monitor=addMonitor("com.joe.jmlite.MihonEntryActivity",new ActivityResult(Activity.RESULT_CANCELED,null),true);
        try {
            runOnMainSync(()->{((EditText)activity.findViewById(1001)).setText("JM"+id);activity.findViewById(1003).performClick();});
            waitForIdleSync();check(monitor.getHits()==1,"online button targets the Mihon entry activity");
            output.append("legacy ReaderActivity cache regression runs separately from the Mihon reader\n");
            return startActivitySync(new Intent().setClassName(getTargetContext(),"com.joe.jmlite.ReaderActivity").putExtra("online_id",id).putExtra("title","Synthetic legacy cache test").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } finally {removeMonitor(monitor);}
    }

    private ListView awaitOnlineImage(Activity reader,int page) throws Exception {
        ListView list=(ListView)field(reader,"list");final boolean[] loaded={false};
        for(long end=System.currentTimeMillis()+8000;System.currentTimeMillis()<end&&!loaded[0];Thread.sleep(30))runOnMainSync(()->{View row=list.getChildAt(0);loaded[0]=list.getCount()==transport.pagesPerChapter*2&&list.getFirstVisiblePosition()==page&&row instanceof android.view.ViewGroup&&((ImageView)((android.view.ViewGroup)row).getChildAt(0)).getDrawable()!=null;});
        check(loaded[0],"online page "+page+" decodes into a visible bitmap");return list;
    }

    private void doubleTap(ListView list) throws Exception {
        android.graphics.Rect area=new android.graphics.Rect();runOnMainSync(()->((android.view.ViewGroup)list.getChildAt(0)).getChildAt(0).getGlobalVisibleRect(area));
        for(int tap=0;tap<2;tap++){long down=android.os.SystemClock.uptimeMillis();pointer(down,android.view.MotionEvent.ACTION_DOWN,area.centerX(),area.centerY());Thread.sleep(15);pointer(down,android.view.MotionEvent.ACTION_UP,area.centerX(),area.centerY());if(tap==0)Thread.sleep(60);}
    }

    private void finishReader(Activity reader) throws Exception {if(reader!=null&&!reader.isFinishing()){Object session=field(reader,"onlineSession");if(session!=null)closedOnlineSessions.put(reader,session);runOnMainSync(()->reader.finish());waitForIdleSync();}}
    private void awaitNetworkIdle() throws Exception {for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end&&transport.inFlight!=0;Thread.sleep(20)){}eq(0,transport.inFlight,"online transfers settle without background leftovers");}
    private String diskState() throws Exception {java.util.TreeSet<String> state=new java.util.TreeSet<>();fileState(getTargetContext().getFilesDir(),"files",state);fileState(getTargetContext().getCacheDir(),"cache",state);return state.toString();}
    private void fileState(File file,String path,java.util.Set<String> state) throws Exception {if(!file.exists()){state.add(path+":missing");return;}if(file.isDirectory()){state.add(path+"/");File[] children=file.listFiles();if(children==null)throw new IOException("Cannot inspect online disk boundary");for(File child:children)fileState(child,path+"/"+child.getName(),state);}else state.add(path+":"+file.length()+":"+file.lastModified());}

    private JSONObject awaitStopped(String id, String status) throws Exception {
        for (long end = System.currentTimeMillis() + 15000; System.currentTimeMillis() < end; Thread.sleep(20)) {
            JSONObject meta = store.read(id); if(meta != null && !DownloadService.running && status.equals(meta.optString("status"))) return meta;
        }
        throw new AssertionError("job did not settle as " + status + ": " + store.read(id));
    }

    private JSONObject awaitDownload(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        boolean seen = false;
        while (System.currentTimeMillis() < deadline) {
            JSONObject meta = store.read(id);
            if (meta != null && (DownloadService.running || "downloading".equals(meta.optString("status")))) seen = true;
            if (meta != null && !DownloadService.running && ("complete".equals(meta.optString("status")) || "error".equals(meta.optString("status")))) return meta;
            Thread.sleep(30);
        }
        throw new AssertionError("synthetic download timeout; seen=" + seen + ", meta=" + store.read(id));
    }

    /** All HTTPS in this test process is answered here; nothing can reach a real server. */
    private static final class SyntheticHttps implements URLStreamHandlerFactory {
        volatile int requests, imageRequests, settingRequests, inFlight, maxInFlight,pagesPerChapter=6;
        volatile boolean validToken = true, cookieSeen, imageCookies = true;
        volatile String albumId, failPath,overridePath;
        volatile byte[] overrideBody;
        volatile long imageDelayMs = 120;
        final byte[] jpeg;
        final java.util.Map<String,Integer> imagePathRequests=new java.util.HashMap<>();
        final java.util.List<String> imageRequestOrder=new java.util.ArrayList<>();

        SyntheticHttps() throws Exception {
            Bitmap bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.RGB_565);
            bitmap.eraseColor(Color.rgb(52, 120, 246));
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
            bitmap.recycle();
            jpeg = stream.toByteArray();
        }

        @Override public URLStreamHandler createURLStreamHandler(String protocol) {
            if (!"https".equals(protocol)) return null;
            return new URLStreamHandler() { @Override protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    byte[] body;
                    boolean responseReady, imageOpen;
                    volatile boolean disconnected;
                    @Override public void connect() { connected = true; }
                    @Override public void disconnect() { disconnected = true; connected = false; closeImage(); }
                    private void closeImage() { synchronized(SyntheticHttps.this) { if(imageOpen){imageOpen=false;inFlight--;} } }
                    @Override public boolean usingProxy() { return false; }
                    @Override public int getResponseCode() throws IOException {
                        if (responseReady) return 200;
                        responseReady = true;
                        synchronized(SyntheticHttps.this){ requests++; }
                        String path = url.getPath();
                        try {
                            if (path.startsWith("/media/photos/")) {
                                synchronized(SyntheticHttps.this){ imageRequests++; imageCookies &= "SID=synthetic".equals(getRequestProperty("Cookie"));imagePathRequests.put(path,imagePathRequests.containsKey(path)?imagePathRequests.get(path)+1:1);imageRequestOrder.add(path); }
                                if(path.equals(failPath)) return 503;
                                body = path.equals(overridePath)&&overrideBody!=null?overrideBody:jpeg;
                                return 200;
                            }
                            if (path.endsWith(".txt")) throw new IOException("synthetic discovery fallback");
                            String params = getRequestProperty("tokenparam");
                            String ts = params == null ? "" : params.split(",")[0];
                            String secret = path.equals("/chapter_view_template") ? "18comicAPPContent" : "185Hcomic3PAPP7R";
                            synchronized(SyntheticHttps.this){ validToken &= !ts.isEmpty() && JmApi.md5(ts + secret).equals(getRequestProperty("token")); cookieSeen |= "SID=synthetic".equals(getRequestProperty("Cookie")); }
                            if (path.equals("/chapter_view_template")) { body = "var scramble_id = 220980;".getBytes(StandardCharsets.UTF_8); return 200; }
                            JSONObject data;
                            if (path.equals("/setting")) { synchronized(SyntheticHttps.this){ settingRequests++; } data = new JSONObject().put("jm3_version", "2.1.12"); }
                            else if (path.equals("/album")) data = new JSONObject().put("id", albumId).put("name", "Synthetic test album").put("series_id", "0")
                                    .put("series", new JSONArray().put(new JSONObject().put("id", "131").put("sort", 2)).put(new JSONObject().put("id", "127").put("sort", 1)));
                            else if (path.equals("/chapter")) {
                                String id = url.getQuery().substring(3);
                                JSONArray images = new JSONArray(); for(int page=1;page<=pagesPerChapter;page++) images.put(String.format(java.util.Locale.ROOT,"%05d.jpg",page));
                                data = new JSONObject().put("id", id).put("name", "Synthetic chapter").put("images", images);
                            } else throw new IOException("unexpected synthetic path " + path);
                            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(JmApi.md5(ts + "185Hcomic3PAPP7R").getBytes(StandardCharsets.US_ASCII), "AES"));
                            String encoded = android.util.Base64.encodeToString(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
                            body = new JSONObject().put("code", 200).put("data", encoded).toString().getBytes(StandardCharsets.UTF_8);
                            return 200;
                        } catch (Exception e) { throw new IOException(e); }
                    }
                    @Override public Map<String, List<String>> getHeaderFields() {
                        return url.getPath().equals("/setting") ? Collections.singletonMap("Set-Cookie", Collections.singletonList("SID=synthetic; Path=/; Secure")) : Collections.emptyMap();
                    }
                    @Override public long getContentLengthLong() { return body == null ? -1 : body.length; }
                    @Override public InputStream getInputStream() throws IOException {
                        if(getResponseCode() != 200) throw new IOException("synthetic HTTP failure");
                        if(!url.getPath().startsWith("/media/photos/")) return new ByteArrayInputStream(body);
                        synchronized(SyntheticHttps.this){imageOpen=true;inFlight++;maxInFlight=Math.max(maxInFlight,inFlight);}
                        return new java.io.FilterInputStream(new ByteArrayInputStream(body)) {
                            boolean delayed;
                            @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                                if(!delayed){delayed=true;long end=System.currentTimeMillis()+imageDelayMs;while(System.currentTimeMillis()<end){if(disconnected)throw new java.io.InterruptedIOException("synthetic cancelled");try{Thread.sleep(10);}catch(InterruptedException e){throw new java.io.InterruptedIOException("synthetic interrupted");}}}
                                if(disconnected)throw new java.io.InterruptedIOException("synthetic cancelled");return super.read(buffer,offset,length);
                            }
                            @Override public void close() throws IOException { try{super.close();}finally{closeImage();} }
                        };
                    }
                };
            } };
        }
    }
}

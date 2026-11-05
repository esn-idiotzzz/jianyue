package com.joe.jmlite.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import com.joe.jmlite.Store;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.zip.*;

/** Isolated synthetic book/archive checks; no remote images or user files. */
public final class LibraryTests extends Instrumentation {
    private int checks;
    private final StringBuilder output=new StringBuilder();
    private FixtureContext context;
    private Object library;
    private Class<?> kind;
    private Bundle arguments;
    private final List<Uri> exported=new ArrayList<>();

    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);this.arguments=arguments==null?new Bundle():arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int resultCode=Activity.RESULT_OK;
        try{
            if("true".equalsIgnoreCase(arguments.getString("screenshot_fixture"))){kind=Class.forName("com.joe.jmlite.Library");screenshotFixture("true".equalsIgnoreCase(arguments.getString("cleanup")));result.putString("stream",output.toString());}
            else{
                context=new FixtureContext(getTargetContext());
                kind=Class.forName("com.joe.jmlite.Library");library=kind.getConstructor(Context.class).newInstance(context);
                testBookMetadata();testArchives();testCovers();testCoverQueue();testMainUi();
                result.putString("stream",output+"PASS "+checks+" isolated library checks; synthetic/offline only\n");
            }
        }catch(Throwable failure){
            StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream",output+"FAIL after "+checks+" checks\n"+trace);resultCode=Activity.RESULT_CANCELED;
        }finally{
            if(context!=null){for(Uri uri:exported)try{context.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}context.clear();}
        }
        finish(resultCode,result);
    }
    private void check(boolean yes,String name){if(!yes)throw new AssertionError(name);checks++;output.append("ok ").append(checks).append(' ').append(name).append('\n');}
    private void eq(Object wanted,Object actual,String name){check(wanted.equals(actual),name+" [expected="+wanted+", actual="+actual+"]");}
    private Object call(String name,Class<?>[] types,Object... arguments)throws Exception{
        try{return kind.getMethod(name,types).invoke(library,arguments);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw failure;}
    }
    private Object call(String name)throws Exception{return call(name,new Class<?>[0]);}
    private JSONObject find(String key)throws Exception{return (JSONObject)call("find",new Class<?>[]{String.class},key);}
    private String value(String name,JSONObject book)throws Exception{return (String)call(name,new Class<?>[]{JSONObject.class},book);}
    private List<?> pages(JSONObject book)throws Exception{return (List<?>)call("pages",new Class<?>[]{JSONObject.class},book);}
    private String pageField(Object page,String name)throws Exception{Field f=page.getClass().getDeclaredField(name);f.setAccessible(true);return (String)f.get(page);}
    private byte[] png(int color)throws Exception{
        Bitmap image=Bitmap.createBitmap(600,900,Bitmap.Config.RGB_565);image.eraseColor(color);ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,out);image.recycle();return out.toByteArray();
    }
    private void write(File file,byte[] bytes)throws Exception{file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}}
    private void makeBook(String id,String title,String status,int color)throws Exception{
        Store store=new Store(context);store.write(id,new JSONObject().put("title",title).put("status",status).put("done",1).put("total",1));write(new File(store.dir(id),"0001-1/000001.jpg"),png(color));
    }
    private void testBookMetadata()throws Exception{
        makeBook("923401","Cover red","complete",Color.RED);makeBook("923402","Cover blue","complete",Color.BLUE);makeBook("923403","Incomplete","paused",Color.GREEN);
        List<?> books=(List<?>)call("books");eq(2,books.size(),"only completed downloads enter bookshelf");
        JSONObject first=find("download:923401");check(first!=null,"download has stable library key");eq("923401",first.optString("id"),"original plate ID retained");
        call("rename",new Class<?>[]{String.class,String.class},"download:923401","自定义书名");
        eq("自定义书名",value("title",find("download:923401")),"book rename persisted independently of ID");eq("923401",new Store(context).read("923401").optString("id"),"rename preserves download metadata ID");
        Object reopened=kind.getConstructor(Context.class).newInstance(context);eq("自定义书名",value("title",(JSONObject)kind.getMethod("find",String.class).invoke(reopened,"download:923401")),"custom name survives library reload");
        String folder=(String)call("createFolder",new Class<?>[]{String.class},"我的分类");call("move",new Class<?>[]{String.class,String.class},"download:923401",folder);call("move",new Class<?>[]{String.class,String.class},"download:923402",folder);
        eq(folder,value("folder",find("download:923401")),"first book assigned to folder");eq(folder,value("folder",find("download:923402")),"overlapping books can share one folder");
        call("renameFolder",new Class<?>[]{String.class,String.class},folder,"重命名分类");List<?> folders=(List<?>)call("folders");eq("重命名分类",((JSONObject)folders.get(0)).optString("title"),"folder rename persisted");
        call("deleteFolder",new Class<?>[]{String.class},folder);eq("",value("folder",find("download:923401")),"dissolving folder returns books to root");eq(1,pages(find("download:923401")).size(),"folder deletion preserves book images");eq(0,((List<?>)call("folders")).size(),"dissolved folder removed");
        call("delete",new Class<?>[]{String.class},"download:923402");check(!new Store(context).dir("923402").exists(),"delete removes selected internal book");check(new Store(context).dir("923401").exists(),"delete preserves neighboring book");
    }
    private File archive(String name,String[] entries)throws Exception{
        File file=new File(context.getCacheDir(),name);try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){for(int n=0;n<entries.length;n++){zip.putNextEntry(new ZipEntry(entries[n]));zip.write(png(n==0?Color.BLUE:n==1?Color.GREEN:Color.RED));zip.closeEntry();}}return file;
    }
    private void testArchives()throws Exception{
        File archive=archive("Natural order.cbz",new String[]{"chapter2/10.png","chapter2/2.png","chapter1/1.png"});
        JSONObject imported=(JSONObject)call("importComic",new Class<?>[]{Uri.class},Uri.fromFile(archive));String key=value("key",imported);check(key.startsWith("import:"),"CBZ import gets stable internal key");eq(3,pages(imported).size(),"CBZ import extracts every image");
        String firstPath=pageField(pages(imported).get(0),"uri");try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(firstPath))){Bitmap first=android.graphics.BitmapFactory.decodeStream(in);check(Color.red(first.getPixel(0,0))>200,"natural sort places chapter1 first");first.recycle();}
        String secondPath=pageField(pages(imported).get(1),"uri");try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(secondPath))){Bitmap second=android.graphics.BitmapFactory.decodeStream(in);check(Color.green(second.getPixel(0,0))>200,"natural sort places page2 before page10");second.recycle();}
        check(new File(context.getFilesDir(),"imports").isDirectory(),"imported images stored under private files");
        File traversal=archive("Traversal.cbz",new String[]{"../escape.png","valid.png"});boolean rejected=false;try{call("importComic",new Class<?>[]{Uri.class},Uri.fromFile(traversal));}catch(Exception expected){rejected=true;}check(rejected,"archive parent traversal rejected");check(!new File(context.getFilesDir(),"escape.png").exists(),"archive cannot write outside its own directory");
        File empty=new File(context.getCacheDir(),"No images.cbz");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(empty))){zip.putNextEntry(new ZipEntry("readme.txt"));zip.write(new byte[]{1});zip.closeEntry();}rejected=false;try{call("importComic",new Class<?>[]{Uri.class},Uri.fromFile(empty));}catch(Exception expected){rejected=true;}check(rejected,"empty comic import rejected");
        call("rename",new Class<?>[]{String.class,String.class},key,"QA share "+System.nanoTime());JSONObject renamed=find(key);
        Uri share=(Uri)call("exportCbz",new Class<?>[]{JSONObject.class},renamed);check(share!=null,"share creates a CBZ document");exported.add(share);
        int imageCount=0;try(ZipInputStream zip=new ZipInputStream(context.getContentResolver().openInputStream(share))){for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){if(!entry.isDirectory())imageCount++;}}eq(3,imageCount,"exported CBZ contains all pages");
        if(android.os.Build.VERSION.SDK_INT>=29){try(android.database.Cursor cursor=context.getContentResolver().query(share,new String[]{android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.provider.MediaStore.MediaColumns.IS_PENDING},null,null,null)){check(cursor!=null&&cursor.moveToFirst(),"shared CBZ is published in MediaStore");check(cursor.getString(0).replace('\\','/').endsWith("Download/jmshare/"),"shared comic saved in Downloads/jmshare");eq(0,cursor.getInt(1),"share is finalized without pending record");}}
        call("delete",new Class<?>[]{String.class},key);check(find(key)==null,"imported comic deleted from bookshelf");check(new Store(context).dir("923401").exists(),"import delete preserves downloaded books");
    }
    private void testCovers()throws Exception{
        makeBook("923404","Cover blue","complete",Color.BLUE);
        Class<?> covers=Class.forName("com.joe.jmlite.CoverLoader");Constructor<?> constructor=covers.getDeclaredConstructor(Context.class);constructor.setAccessible(true);Object loader=constructor.newInstance(context);Method load=covers.getDeclaredMethod("load",ImageView.class,JSONObject.class),close=covers.getDeclaredMethod("close");load.setAccessible(true);close.setAccessible(true);
        final ImageView[] target=new ImageView[1];runOnMainSync(()->target[0]=new ImageView(getTargetContext()));JSONObject red=find("download:923401"),blue=find("download:923404");
        runOnMainSync(()->{try{load.invoke(loader,target[0],red);load.invoke(loader,target[0],blue);}catch(Exception e){throw new RuntimeException(e);}});
        boolean ready=false;for(long end=System.currentTimeMillis()+5000;System.currentTimeMillis()<end;){final boolean[] present=new boolean[1];runOnMainSync(()->present[0]=target[0].getDrawable() instanceof BitmapDrawable);if(present[0]){ready=true;break;}Thread.sleep(20);}check(ready,"cover is decoded asynchronously");
        final Bitmap[] shown=new Bitmap[1];runOnMainSync(()->shown[0]=((BitmapDrawable)target[0].getDrawable()).getBitmap());check(Color.blue(shown[0].getPixel(0,0))>200,"reused cover target displays latest book");check(shown[0].getAllocationByteCount()<=1024*1024,"cover decoding limits bitmap allocation");
        close.invoke(loader);Field poolField=covers.getDeclaredField("workers");poolField.setAccessible(true);ThreadPoolExecutor pool=(ThreadPoolExecutor)poolField.get(loader);check(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS),"cover workers terminate on screen close");eq(0,pool.getQueue().size(),"screen close clears queued cover work");Field cacheField=covers.getDeclaredField("cache");cacheField.setAccessible(true);eq(0,((android.util.LruCache<?,?>)cacheField.get(loader)).size(),"screen close releases cover cache");
        for(int n=0;n<6;n++){
            Object repeated=constructor.newInstance(context);
            runOnMainSync(()->{try{for(int bind=0;bind<40;bind++)load.invoke(repeated,target[0],bind%2==0?red:blue);close.invoke(repeated);load.invoke(repeated,target[0],red);}catch(Exception failure){throw new RuntimeException(failure);}});
            ThreadPoolExecutor repeatedPool=(ThreadPoolExecutor)poolField.get(repeated);check(repeatedPool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS),"rapid rebind/close releases workers cycle "+n);eq(0,((android.util.LruCache<?,?>)cacheField.get(repeated)).size(),"rapid rebind/close releases cache cycle "+n);
        }
    }
    private void testMainUi()throws Exception{
        android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
        Context real=getTargetContext();Store store=new Store(real);Object realLibrary=kind.getConstructor(Context.class).newInstance(real);
        SharedPreferences settings=real.getSharedPreferences("settings",Context.MODE_PRIVATE);boolean hadPrivacy=settings.contains("history_revealed"),oldPrivacy=settings.getBoolean("history_revealed",true);
        File[] stateFiles={new File(real.getFilesDir(),"library.json"),new File(real.getFilesDir(),"library.json.bak"),new File(real.getFilesDir(),"library.json.new")};byte[][] states=new byte[stateFiles.length][];for(int n=0;n<stateFiles.length;n++)if(stateFiles[n].isFile())states[n]=readBytes(stateFiles[n]);
        String[] ids=new String[6];Activity main=null;
        try{
            settings.edit().putBoolean("history_revealed",true).commit();
            for(int n=0;n<ids.length;n++){long number=970000000000L+(android.os.SystemClock.uptimeMillis()%9000000L)*10+n;while(store.dir(Long.toString(number)).exists())number+=10;ids[n]=Long.toString(number);store.write(ids[n],new JSONObject().put("title","Shelf fixture "+n).put("status","complete").put("done",1).put("total",1));write(new File(store.dir(ids[n]),"0001-1/000001.jpg"),png(n%2==0?Color.RED:Color.BLUE));}
            android.content.Intent launch=new android.content.Intent().setClassName(real,"com.joe.jmlite.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            main=startActivitySync(launch);settle(main);final Activity first=main;
            runOnMainSync(()->first.findViewById(1004).performClick());waitForIdleSync();check(!settings.getBoolean("history_revealed",true),"closing the eye persists the privacy setting");
            final android.widget.ListView list=(android.widget.ListView)field(main,"list");int position=rowPosition(main,"download:"+ids[5])+list.getHeaderViewsCount();runOnMainSync(()->list.setSelectionFromTop(position,0));waitForIdleSync();Thread.sleep(60);
            final StringBuilder text=new StringBuilder();final int[] images={0};runOnMainSync(()->{View row=list.getChildAt(position-list.getFirstVisiblePosition());viewContents(row,text,images);});check(text.toString().contains(ids[5])&&!text.toString().contains("Shelf fixture 5"),"closed eye replaces downloaded title with original numeric plate number");eq(0,images[0],"closed eye omits first-page cover views");
            runOnMainSync(first::finish);waitForIdleSync();main=startActivitySync(launch);settle(main);check(!(Boolean)field(main,"revealed"),"privacy setting survives activity recreation");
            final Activity shelf=main;invokeUi(shelf,"switchTab",new Class<?>[]{int.class},1);GridView grid=(GridView)field(shelf,"grid");check(grid.getNumColumns()>=3,"bookshelf uses at least three columns on a phone");waitForIdleSync();Thread.sleep(100);check(grid.getChildCount()>=6,"bookshelf displays at least six tiles within the viewport");check(((View)field(shelf,"plus")).getVisibility()==View.VISIBLE,"local import uses a compact top-right plus button");
            JSONObject book=realFind(realLibrary,"download:"+ids[0]);invokeUi(shelf,"manageBook",new Class<?>[]{JSONObject.class},book);
            check(dialogHas("重命名书籍")&&dialogHas("书籍分类")&&dialogHas("删除")&&dialogHas("分享"),"book menu presents rename classify delete and share");clickDialog("重命名书籍");editDialog("UI renamed book");clickDialog("确定");settle(shelf);eq("UI renamed book",realFind(realLibrary,"download:"+ids[0]).optString("title"),"UI rename updates bookshelf title");eq(ids[0],store.read(ids[0]).optString("id"),"UI rename retains original plate ID");
            invokeUi(shelf,"manageBook",new Class<?>[]{JSONObject.class},realFind(realLibrary,"download:"+ids[0]));clickDialog("书籍分类");check(dialogHas("＋ 创建书籍夹"),"classification offers a custom folder creation action");clickDialog("＋ 创建书籍夹");editDialog("UI classify folder");clickDialog("确定");settle(shelf);String classified=realFind(realLibrary,"download:"+ids[0]).optString("folder");check(!classified.isEmpty(),"creating a category immediately moves the selected book into it");
            JSONObject target=realFind(realLibrary,"download:"+ids[1]),source=realFind(realLibrary,"download:"+ids[2]);invokeUi(shelf,"drop",new Class<?>[]{JSONObject.class,JSONObject.class},target,source);check(dialogHas("创建书籍夹"),"overlap drop requests a folder name");editDialog("UI overlap folder");clickDialog("确定");settle(shelf);String grouped=realFind(realLibrary,"download:"+ids[1]).optString("folder");check(!grouped.isEmpty()&&grouped.equals(realFind(realLibrary,"download:"+ids[2]).optString("folder")),"overlap drop groups both books into the same folder");
            JSONObject folder=realFolder(realLibrary,grouped);invokeUi(shelf,"manageFolder",new Class<?>[]{JSONObject.class},folder);check(dialogHas("重命名书籍夹")&&dialogHas("删除书籍夹"),"folder menu presents rename and dissolve");clickDialog("重命名书籍夹");editDialog("UI renamed folder");clickDialog("确定");settle(shelf);eq("UI renamed folder",realFolder(realLibrary,grouped).optString("title"),"UI folder rename persists");
            invokeUi(shelf,"manageBook",new Class<?>[]{JSONObject.class},realFind(realLibrary,"download:"+ids[3]));clickDialog("书籍分类");check(dialogHas("UI renamed folder"),"classification lists existing renamed folders");clickDialog("UI renamed folder");settle(shelf);eq(grouped,realFind(realLibrary,"download:"+ids[3]).optString("folder"),"selecting an existing folder moves the book into it");
            invokeUi(shelf,"manageFolder",new Class<?>[]{JSONObject.class},realFolder(realLibrary,grouped));clickDialog("删除书籍夹");check(dialogHas("夹内书籍会回到书架。"),"folder deletion states that its books are preserved");clickDialog("删除");settle(shelf);check(realFolder(realLibrary,grouped)==null,"deleted folder disappears from categories");check(realFind(realLibrary,"download:"+ids[1]).optString("folder").isEmpty()&&realFind(realLibrary,"download:"+ids[2]).optString("folder").isEmpty(),"dissolving a folder returns grouped books to root");check(store.pages(ids[1]).size()==1&&store.pages(ids[2]).size()==1,"UI folder deletion preserves both downloaded image sets");
            invokeUi(shelf,"importMenu",new Class<?>[0]);check(dialogHas("导入漫画压缩包"),"plus menu exposes comic archive import");sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();
        }finally{
            if(main!=null){Activity closing=main;runOnMainSync(closing::finish);waitForIdleSync();try{java.util.concurrent.ExecutorService disk=(java.util.concurrent.ExecutorService)field(closing,"disk");disk.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}}
            for(String id:ids)if(id!=null)try{store.delete(id);}catch(Exception ignored){}
            for(int n=0;n<stateFiles.length;n++){if(states[n]==null)stateFiles[n].delete();else write(stateFiles[n],states[n]);}
            SharedPreferences.Editor restore=settings.edit();if(hadPrivacy)restore.putBoolean("history_revealed",oldPrivacy);else restore.remove("history_revealed");restore.commit();
        }
    }
    private void testCoverQueue()throws Exception{
        Class<?> type=Class.forName("com.joe.jmlite.CoverLoader");Constructor<?> constructor=type.getDeclaredConstructor(Context.class);constructor.setAccessible(true);Method load=type.getDeclaredMethod("load",ImageView.class,JSONObject.class),close=type.getDeclaredMethod("close");load.setAccessible(true);close.setAccessible(true);
        JSONObject[] books=new JSONObject[36];for(int n=0;n<books.length;n++){String id=Integer.toString(924000+n);makeBook(id,"Queue cover "+n,"complete",n%2==0?Color.RED:Color.BLUE);books[n]=find("download:"+id);}
        ImageView[] views=new ImageView[36];Activity host=startActivitySync(new android.content.Intent().setClassName(getTargetContext(),"com.joe.jmlite.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        Object loader=constructor.newInstance(context);ThreadPoolExecutor pool=(ThreadPoolExecutor)field(loader,"workers");java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1),entered=new java.util.concurrent.CountDownLatch(2);
        Object closingLoader=null;java.util.concurrent.CountDownLatch closingRelease=new java.util.concurrent.CountDownLatch(1);
        try{
            runOnMainSync(()->{android.widget.LinearLayout table=new android.widget.LinearLayout(host);table.setOrientation(1);for(int row=0;row<6;row++){android.widget.LinearLayout line=new android.widget.LinearLayout(host);for(int column=0;column<6;column++){int index=row*6+column;views[index]=new ImageView(host);views[index].setScaleType(ImageView.ScaleType.CENTER_CROP);line.addView(views[index],new android.widget.LinearLayout.LayoutParams(0,-1,1));}table.addView(line,new android.widget.LinearLayout.LayoutParams(-1,0,1));}host.setContentView(table);});waitForIdleSync();
            final boolean[] visible={true};runOnMainSync(()->{for(ImageView view:views)visible[0]&=view.isAttachedToWindow()&&view.isShown()&&view.getGlobalVisibleRect(new android.graphics.Rect());});check(visible[0],"all 36 queue-test covers are attached and visible");
            Runnable blocker=()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}};pool.execute(blocker);pool.execute(blocker);check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"both cover workers can be held while the bounded queue fills");
            runOnMainSync(()->{try{for(int n=0;n<views.length;n++)load.invoke(loader,views[n],books[n]);}catch(Exception failure){throw new RuntimeException(failure);}});
            eq(24,pool.getQueue().size(),"36 visible cold covers fill the queue without growing it");Thread.sleep(180);release.countDown();
            final int[] loaded={0};for(long end=System.currentTimeMillis()+10000;System.currentTimeMillis()<end;){runOnMainSync(()->{loaded[0]=0;for(ImageView view:views)if(view.getDrawable() instanceof BitmapDrawable)loaded[0]++;});if(loaded[0]==36)break;Thread.sleep(20);}eq(36,loaded[0],"queued and initially rejected visible covers all eventually display");
            final boolean[] correct={true};runOnMainSync(()->{for(int n=0;n<views.length;n++){int pixel=((BitmapDrawable)views[n].getDrawable()).getBitmap().getPixel(0,0);correct[0]&=n%2==0?Color.red(pixel)>200:Color.blue(pixel)>200;}});check(correct[0],"queue retries retain the matching book for every visible cover");
            check(pool.getLargestPoolSize()<=2,"cover queue pressure preserves the two-worker bound");check(((android.util.LruCache<?,?>)field(loader,"cache")).size()<=6*1024*1024,"36 visible covers retain the six-MiB cache bound");
            close.invoke(loader);check(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS),"saturated cover loader terminates after close");
            closingLoader=constructor.newInstance(context);Object cancelling=closingLoader;ThreadPoolExecutor closingPool=(ThreadPoolExecutor)field(cancelling,"workers");java.util.concurrent.CountDownLatch closingEntered=new java.util.concurrent.CountDownLatch(2);
            Runnable closingBlocker=()->{closingEntered.countDown();try{closingRelease.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}};closingPool.execute(closingBlocker);closingPool.execute(closingBlocker);check(closingEntered.await(2,java.util.concurrent.TimeUnit.SECONDS),"close-pressure test holds both cover workers");
            runOnMainSync(()->{try{for(int n=0;n<views.length;n++)load.invoke(cancelling,views[n],books[n]);}catch(Exception failure){throw new RuntimeException(failure);}});eq(24,closingPool.getQueue().size(),"close-pressure case contains a full decode queue and delayed retries");
            runOnMainSync(()->{try{close.invoke(cancelling);}catch(Exception failure){throw new RuntimeException(failure);}});check(closingPool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS),"close interrupts blocked workers and cancels queued covers");Thread.sleep(200);waitForIdleSync();
            eq(0,closingPool.getQueue().size(),"closed saturated loader has no remaining queued jobs");check(!((android.os.Handler)field(cancelling,"main")).hasMessages(0),"close removes delayed cover retries from the main handler");eq(0,((android.util.LruCache<?,?>)field(cancelling,"cache")).size(),"closed saturated loader retains no decoded cover cache");
            final int[] late={0};runOnMainSync(()->{for(ImageView view:views)if(view.getDrawable()!=null)late[0]++;});eq(0,late[0],"closed cover loader posts no late images into visible targets");
        }finally{release.countDown();closingRelease.countDown();close.invoke(loader);if(closingLoader!=null)close.invoke(closingLoader);runOnMainSync(host::finish);waitForIdleSync();}
    }
    private byte[] readBytes(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);return out.toByteArray();}}
    private JSONObject realFind(Object realLibrary,String key)throws Exception{return (JSONObject)kind.getMethod("find",String.class).invoke(realLibrary,key);}
    private JSONObject realFolder(Object realLibrary,String id)throws Exception{for(Object row:(List<?>)kind.getMethod("folders").invoke(realLibrary)){JSONObject folder=(JSONObject)row;if(id.equals(folder.optString("id")))return folder;}return null;}
    private Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private void invokeUi(Activity activity,String name,Class<?>[] types,Object...args)throws Exception{Method method=activity.getClass().getDeclaredMethod(name,types);method.setAccessible(true);runOnMainSync(()->{try{method.invoke(activity,args);}catch(Exception failure){throw new RuntimeException(failure);}});waitForIdleSync();}
    private void settle(Activity main)throws Exception{
        for(long end=System.currentTimeMillis()+5000;System.currentTimeMillis()<end;){final boolean[] busy={true};runOnMainSync(()->{try{busy[0]=(Boolean)field(main,"operation")||(Boolean)field(main,"reading");}catch(Exception e){throw new RuntimeException(e);}});if(!busy[0])break;Thread.sleep(20);}
        invokeUi(main,"refresh",new Class<?>[0]);for(long end=System.currentTimeMillis()+5000;System.currentTimeMillis()<end;){final boolean[] ready={false};runOnMainSync(()->{try{ready[0]=!(Boolean)field(main,"reading")&&!(Boolean)field(main,"operation");}catch(Exception e){throw new RuntimeException(e);}});if(ready[0]){waitForIdleSync();return;}Thread.sleep(20);}throw new AssertionError("main library operation did not settle");
    }
    private int rowPosition(Activity main,String key)throws Exception{List<?> rows=(List<?>)field(main,"rows");for(int n=0;n<rows.size();n++)if(key.equals(((JSONObject)rows.get(n)).optString("key")))return n;throw new AssertionError("UI fixture row missing");}
    private void viewContents(View view,StringBuilder text,int[] images){if(view==null)return;if(view instanceof TextView)text.append(((TextView)view).getText()).append('\n');if(view instanceof ImageView)images[0]++;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)viewContents(group.getChildAt(n),text,images);}}
    private List<android.view.accessibility.AccessibilityNodeInfo> dialogRoots(){List<android.view.accessibility.AccessibilityNodeInfo> roots=new ArrayList<>();List<android.view.accessibility.AccessibilityWindowInfo> windows=new ArrayList<>(getUiAutomation().getWindows());windows.sort((a,b)->Integer.compare(b.getLayer(),a.getLayer()));for(android.view.accessibility.AccessibilityWindowInfo window:windows){android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root!=null)roots.add(root);}android.view.accessibility.AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();if(active!=null)roots.add(active);return roots;}
    private boolean dialogHas(String text)throws Exception{for(long end=System.currentTimeMillis()+1500;System.currentTimeMillis()<end;Thread.sleep(20))for(android.view.accessibility.AccessibilityNodeInfo root:dialogRoots())for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&text.contentEquals(node.getText()))return true;return false;}
    private void clickDialog(String text)throws Exception{for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end;Thread.sleep(20))for(android.view.accessibility.AccessibilityNodeInfo root:dialogRoots())for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&text.contentEquals(node.getText())){while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){waitForIdleSync();return;}}throw new AssertionError("dialog action missing: "+text);}
    private android.view.accessibility.AccessibilityNodeInfo editable(android.view.accessibility.AccessibilityNodeInfo node){if(node==null)return null;if(node.isEditable())return node;for(int n=0;n<node.getChildCount();n++){android.view.accessibility.AccessibilityNodeInfo child=editable(node.getChild(n));if(child!=null)return child;}return null;}
    private void editDialog(String value)throws Exception{for(long end=System.currentTimeMillis()+3000;System.currentTimeMillis()<end;Thread.sleep(20))for(android.view.accessibility.AccessibilityNodeInfo root:dialogRoots()){android.view.accessibility.AccessibilityNodeInfo edit=editable(root);if(edit!=null){Bundle arguments=new Bundle();arguments.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);if(edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,arguments)){waitForIdleSync();return;}}}throw new AssertionError("dialog input unavailable");}
    private void screenshotFixture(boolean cleanup)throws Exception{
        Context real=getTargetContext();Store store=new Store(real);Object actualLibrary=kind.getConstructor(Context.class).newInstance(real);File marker=new File(real.getFilesDir(),"qa-screenshot-fixture.json");
        if(cleanup){
            if(!marker.isFile()){output.append("SCREENSHOT fixture already absent\n");return;}
            JSONObject owned=new JSONObject(new String(readBytes(marker),java.nio.charset.StandardCharsets.UTF_8));String token=owned.getString("token");org.json.JSONArray ids=owned.getJSONArray("ids");int removed=0;
            for(int n=0;n<ids.length();n++){String id=ids.getString(n);JSONObject meta=store.read(id);if(meta!=null&&token.equals(meta.optString("qa_fixture"))){kind.getMethod("delete",String.class).invoke(actualLibrary,"download:"+id);removed++;}}
            org.json.JSONArray folders=owned.optJSONArray("folders");if(folders!=null)for(int n=0;n<folders.length();n++)kind.getMethod("deleteFolder",String.class).invoke(actualLibrary,folders.getString(n));
            if(!marker.delete())throw new IOException("Unable to remove screenshot ownership marker");output.append("SCREENSHOT fixture cleaned: ").append(removed).append(" owned books; other books preserved\n");return;
        }
        if(marker.isFile())throw new IOException("Screenshot fixture already exists; use -e cleanup true before creating another");
        String[] names={"山海之间","秋日来信","白昼旅行","午夜星河","云端散步","岛屿日记","静谧森林","蓝色回声","风的方向"};
        int[][] palette={{0xff506b89,0xffafc5d6},{0xffa96555,0xffefd5ae},{0xff4f8190,0xffbfd8cc},{0xff373f70,0xff8d8caf},{0xff899bb1,0xffe8ded2},{0xffab8b66,0xffe8d6ab},{0xff386956,0xffa2b99a},{0xff426a99,0xffbdcde8},{0xffa78283,0xffe6ced0}};
        String token=UUID.randomUUID().toString();org.json.JSONArray ids=new org.json.JSONArray(),folders=new org.json.JSONArray();JSONObject owned=new JSONObject().put("token",token).put("ids",ids).put("folders",folders);saveMarker(marker,owned);
        for(int n=0;n<names.length;n++){
            long number=968000000000L+(android.os.SystemClock.uptimeMillis()%9000000L)*10+n;while(store.dir(Long.toString(number)).exists())number+=10;String id=Long.toString(number);ids.put(id);saveMarker(marker,owned);
            JSONObject meta=new JSONObject().put("title",names[n]).put("status","complete").put("done",1).put("total",1).put("qa_fixture",token);store.write(id,meta);
            write(new File(store.dir(id),"0001-1/000001.jpg"),syntheticCover(names[n],n,palette[n]));
        }
        if("true".equalsIgnoreCase(arguments.getString("group"))){String folder=(String)kind.getMethod("createFolder",String.class).invoke(actualLibrary,"我的收藏");folders.put(folder);saveMarker(marker,owned);for(int n=0;n<3;n++)kind.getMethod("move",String.class,String.class).invoke(actualLibrary,"download:"+ids.getString(n),folder);}
        Activity main=startActivitySync(new android.content.Intent().setClassName(real,"com.joe.jmlite.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));settle(main);invokeUi(main,"switchTab",new Class<?>[]{int.class},1);Thread.sleep(600);waitForIdleSync();
        output.append("SCREENSHOT fixture ready: 9 synthetic books, owned marker ").append(marker.getAbsolutePath()).append("; cleanup with -e screenshot_fixture true -e cleanup true\n");
    }
    private void saveMarker(File marker,JSONObject state)throws Exception{android.util.AtomicFile atomic=new android.util.AtomicFile(marker);FileOutputStream out=null;try{out=atomic.startWrite();out.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}}
    private byte[] syntheticCover(String title,int index,int[] colors)throws Exception{
        Bitmap cover=Bitmap.createBitmap(600,840,Bitmap.Config.RGB_565);android.graphics.Canvas canvas=new android.graphics.Canvas(cover);android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0,0,600,840,colors[0],colors[1],android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(0,0,600,840,paint);paint.setShader(null);
        paint.setColor(0x25ffffff);canvas.drawCircle(550,160,250,paint);paint.setColor(0x22ffffff);canvas.drawCircle(40,720,300,paint);paint.setColor(0x18ffffff);canvas.drawRoundRect(95,290,505,700,170,170,paint);
        paint.setColor(0xddeef1f6);paint.setTextSize(21);paint.setLetterSpacing(0);canvas.drawText("JM  ·  简 阅 合 成 封 面",46,62,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));paint.setTextSize(72);paint.setColor(0xfff5f4ef);canvas.drawText(title.substring(0,2),52,400,paint);canvas.drawText(title.substring(2),52,490,paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-light",android.graphics.Typeface.NORMAL));paint.setTextSize(150);paint.setColor(0x30ffffff);canvas.drawText(String.format(Locale.ROOT,"%02d",index+1),365,770,paint);
        paint.setTextSize(20);paint.setColor(0xddeef1f6);canvas.drawText("阅读，让时间慢下来",52,763,paint);paint.setStrokeWidth(2);canvas.drawLine(52,712,280,712,paint);
        ByteArrayOutputStream output=new ByteArrayOutputStream();cover.compress(Bitmap.CompressFormat.JPEG,90,output);cover.recycle();return output.toByteArray();
    }
    private static final class FixtureContext extends ContextWrapper {
        private final File base;private final String prefix;private final Set<String> preferences=new HashSet<>();
        FixtureContext(Context real){super(real);prefix="library_qa_"+System.nanoTime();base=new File(real.getCacheDir(),prefix);getFilesDir().mkdirs();getCacheDir().mkdirs();}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return new File(base,"files");}
        @Override public File getCacheDir(){return new File(base,"cache");}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){preferences.add(prefix+name);return super.getSharedPreferences(prefix+name,mode);}
        void clear(){for(String name:preferences)getBaseContext().deleteSharedPreferences(name);delete(base);}
        private void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    }
}

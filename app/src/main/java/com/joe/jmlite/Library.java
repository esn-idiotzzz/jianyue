package com.joe.jmlite;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Library organization is kept separate from downloader metadata and original IDs. */
public final class Library {
    private static final Object LOCK=new Object();
    private static final Set<String> IMPORTING=new HashSet<>();
    private final Context context;
    private final File root;
    private final AtomicFile metadata;

    public Library(Context context){this.context=context.getApplicationContext();root=new File(this.context.getFilesDir(),"imports");metadata=new AtomicFile(new File(this.context.getFilesDir(),"library.json"));}
    public static String key(JSONObject book){return book.optString("key",book.has("uri")?"tree:"+book.optString("uri"):"download:"+book.optString("id"));}
    public static String title(JSONObject book){return book.optString("title","未命名");}
    public static String folder(JSONObject book){return book.optString("folder","");}

    public List<JSONObject> books() throws Exception {return books(new Store(context).list());}
    /** Reuse the caller's download snapshot instead of scanning every local book twice. */
    public List<JSONObject> books(List<JSONObject> downloaded) throws Exception {
        JSONObject state; synchronized(LOCK){state=read();migrateOnlineFolders(state);}
        ArrayList<JSONObject> result=new ArrayList<>();
        for(JSONObject book:downloaded)if("complete".equals(book.optString("status"))){book.put("key","download:"+book.getString("id")).put("type","download");result.add(book);}
        JSONArray imports=state.optJSONArray("imports");
        if(imports!=null)for(int n=0;n<imports.length();n++){JSONObject item=imports.getJSONObject(n);String uuid=item.optString("uuid");if(validUuid(uuid)&&new File(importDir(uuid),"pages").isDirectory()){JSONObject book=new JSONObject(item.toString());book.put("key","import:"+uuid).put("type","import").put("done",item.optInt("pages")).put("status","complete");result.add(book);}}
        JSONArray trees;
        try{trees=new JSONArray(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("folders","[]"));}catch(Exception e){trees=new JSONArray();}
        for(int n=0;n<trees.length();n++){JSONObject item=trees.optJSONObject(n);if(item!=null&&item.has("uri")){JSONObject book=new JSONObject(item.toString());book.put("key","tree:"+item.getString("uri")).put("type","tree");result.add(book);}}
        JSONArray online=state.optJSONArray("online");if(online!=null)for(int n=0;n<online.length();n++)result.add(new JSONObject(online.getJSONObject(n).toString()).put("type","online"));
        JSONObject entries=state.optJSONObject("entries");
        if(entries!=null)for(JSONObject book:result){JSONObject entry=entries.optJSONObject(key(book));if(entry!=null){if(entry.has("title"))book.put("title",entry.getString("title"));book.put("folder",entry.optString("folder"));}}
        JSONArray saved=state.optJSONArray("bookOrder");if(saved!=null){ArrayList<String> available=new ArrayList<>(),order=new ArrayList<>();HashMap<String,JSONObject> byKey=new HashMap<>();for(JSONObject book:result){String key=key(book);available.add(key);byKey.put(key,book);}for(int n=0;n<saved.length();n++)order.add(saved.optString(n));result.clear();for(String key:ShelfOrder.restore(available,order))result.add(byKey.get(key));}
        return result;
    }
    public List<JSONObject> folders() throws Exception {synchronized(LOCK){JSONObject state=read();migrateOnlineFolders(state);JSONArray folders=state.optJSONArray("folders");ArrayList<JSONObject> result=new ArrayList<>();if(folders!=null)for(int n=0;n<folders.length();n++)result.add(folders.getJSONObject(n));return result;}}
    public JSONObject find(String key) throws Exception {for(JSONObject book:books())if(key.equals(key(book)))return book;return null;}
    public void rename(String key,String name) throws Exception {name=name(name);synchronized(LOCK){JSONObject state=read();entry(state,key).put("title",name);write(state);}}
    public String createFolder(String name) throws Exception {return createFolder(name, "");}
    public String createFolder(String name,String parent) throws Exception {name=name(name);synchronized(LOCK){JSONObject state=read();JSONArray folders=array(state,"folders");String id=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",id).put("title",name).put("parent",parent));write(state);return id;}}
    public static final String ONLINE_JM="在线JM漫画";
    public static final String ONLINE_WEB="在线全网漫画";
    private static final String ONLINE_JM_SYSTEM="online_jm";
    private static final String ONLINE_WEB_SYSTEM="online_web";
    private static final String ONLINE_LEGACY_SYSTEM="online";
    public static final class LocalComicException extends IOException {public LocalComicException(){super("本地漫画");}}
    static boolean localBookKey(String key){return key.startsWith("download:")||key.startsWith("import:")||key.startsWith("tree:");}
    static boolean onlineFolder(JSONObject state,String folderId) throws Exception {
        JSONArray folders=state.optJSONArray("folders");if(folders==null)return false;
        HashSet<String> visited=new HashSet<>();String cursor=folderId;
        while(!cursor.isEmpty()&&visited.add(cursor)){
            JSONObject folder=null;for(int n=0;n<folders.length();n++){JSONObject candidate=folders.getJSONObject(n);if(cursor.equals(candidate.optString("id"))){folder=candidate;break;}}
            if(folder==null)return false;
            String system=folder.optString("system");if(ONLINE_JM_SYSTEM.equals(system)||ONLINE_WEB_SYSTEM.equals(system)||ONLINE_LEGACY_SYSTEM.equals(system))return true;
            cursor=folder.optString("parent");
        }
        return false;
    }
    static void requirePlacement(JSONObject state,String key,String folderId) throws Exception {if(localBookKey(key)&&onlineFolder(state,folderId))throw new LocalComicException();}
    /** Preserve an existing favorite's title, parent folder and ordering under concurrent completions. */
    public void ensureOnline(JSONObject record) throws Exception {synchronized(LOCK){String key=record.optString("key",OnlineHistory.key(record.optString("sourceId"),record.optString("url")));if(!hasOnline(key))favoriteOnline(record,true);}}
    public void favoriteOnline(JSONObject record,boolean on) throws Exception {synchronized(LOCK){
        JSONObject state=read();String key=record.optString("key",OnlineHistory.key(record.optString("sourceId"),record.optString("url")));if(on&&localBookKey(key))throw new LocalComicException();JSONArray old=array(state,"online"),next=new JSONArray();
        for(int n=0;n<old.length();n++)if(!key.equals(old.getJSONObject(n).optString("key")))next.put(old.getJSONObject(n));
        if(on){boolean jm=OnlineHistory.isJm(record);String system=jm?ONLINE_JM_SYSTEM:ONLINE_WEB_SYSTEM,title=jm?ONLINE_JM:ONLINE_WEB;String folder="";JSONArray folders=array(state,"folders");for(int n=0;n<folders.length();n++)if(system.equals(folders.getJSONObject(n).optString("system")))folder=folders.getJSONObject(n).optString("id");
            if(folder.isEmpty()){folder=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",folder).put("title",title).put("system",system));}
            next.put(new JSONObject(record.toString()).put("key",key).put("type","online"));entry(state,key).put("folder",folder);
        }else{JSONObject entries=state.optJSONObject("entries");if(entries!=null)entries.remove(key);}
        state.put("online",next);write(state);
    }}
    /** Legacy 3.0.0 favorites lived in one shared 在线漫画 folder; split them by kind. */
    private void migrateOnlineFolders(JSONObject state) throws Exception {
        JSONArray folders=state.optJSONArray("folders");JSONArray online=state.optJSONArray("online");JSONObject entries=state.optJSONObject("entries");
        if(folders==null||online==null||entries==null)return;
        String legacy="";for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(ONLINE_LEGACY_SYSTEM.equals(f.optString("system")))legacy=f.optString("id");}
        if(legacy.isEmpty())return;
        boolean changed=false;
        for(int n=0;n<online.length();n++){
            JSONObject record=online.getJSONObject(n);String key=record.optString("key");JSONObject entry=entries.optJSONObject(key);
            if(entry==null||!legacy.equals(entry.optString("folder")))continue;
            boolean jm=OnlineHistory.isJm(record);String system=jm?ONLINE_JM_SYSTEM:ONLINE_WEB_SYSTEM,title=jm?ONLINE_JM:ONLINE_WEB;
            String target="";for(int i=0;i<folders.length();i++)if(system.equals(folders.getJSONObject(i).optString("system")))target=folders.getJSONObject(i).optString("id");
            if(target.isEmpty()){target=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",target).put("title",title).put("system",system));}
            entry.put("folder",target);changed=true;
        }
        if(changed){
            boolean used=false;Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject entry=entries.optJSONObject(keys.next());if(entry!=null&&legacy.equals(entry.optString("folder")))used=true;}
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(legacy.equals(f.optString("parent")))used=true;}
            if(!used){JSONArray next=new JSONArray();for(int n=0;n<folders.length();n++)if(!legacy.equals(folders.getJSONObject(n).optString("id")))next.put(folders.getJSONObject(n));state.put("folders",next);}
            write(state);
        }
    }
    public boolean hasOnline(String key) throws Exception {synchronized(LOCK){JSONArray all=array(read(),"online");for(int n=0;n<all.length();n++)if(key.equals(all.getJSONObject(n).optString("key")))return true;return false;}}
    public String favoriteFolderId(boolean create) throws Exception {
        synchronized(LOCK){
            JSONObject state=read();JSONArray folders=array(state,"folders");
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if("favorites".equals(f.optString("system"))||FAVORITES.equals(f.optString("title")))return f.getString("id");}
            if(!create)return "";
            String id=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",id).put("title",FAVORITES).put("system","favorites"));write(state);return id;
        }
    }
    public void favorite(String key,boolean on) throws Exception {if(on)move(key,favoriteFolderId(true));else move(key,"");}
    public boolean isFavorite(JSONObject book) throws Exception {String id=favoriteFolderId(false);return !id.isEmpty()&&id.equals(book.optString("folder"));}
    public static final String FAVORITES="我的收藏";
    public void renameFolder(String id,String name) throws Exception {name=name(name);synchronized(LOCK){JSONObject state=read();JSONArray folders=array(state,"folders");for(int n=0;n<folders.length();n++)if(id.equals(folders.getJSONObject(n).optString("id"))){folders.getJSONObject(n).put("title",name);write(state);return;}throw new IOException("书籍夹不存在");}}
    /** Moves a folder one position among its siblings. */
    public boolean moveFolder(String id,int delta) throws Exception {synchronized(LOCK){
        JSONObject state=read();JSONArray folders=array(state,"folders");int index=-1;String parent="";
        for(int n=0;n<folders.length();n++)if(id.equals(folders.getJSONObject(n).optString("id"))){index=n;parent=folders.getJSONObject(n).optString("parent");break;}
        if(index<0)return false;
        int sibling=-1;if(delta<0){for(int n=index-1;n>=0;n--)if(parent.equals(folders.getJSONObject(n).optString("parent"))){sibling=n;break;}}
        else{for(int n=index+1;n<folders.length();n++)if(parent.equals(folders.getJSONObject(n).optString("parent"))){sibling=n;break;}}
        if(sibling<0)return false;
        JSONObject a=folders.getJSONObject(index),b=folders.getJSONObject(sibling);folders.put(index,b);folders.put(sibling,a);write(state);return true;
    }}
    /** Moves a folder before or after another folder among the same siblings. */
    public boolean reorderFolder(String sourceId,String targetId) throws Exception {return reorderFolder(sourceId,targetId,false);}
    public boolean reorderFolder(String sourceId,String targetId,boolean after) throws Exception {synchronized(LOCK){
        if(sourceId.equals(targetId))return false;
        JSONObject state=read();JSONArray folders=array(state,"folders");JSONObject source=null,target=null;
        for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(sourceId.equals(f.optString("id")))source=f;if(targetId.equals(f.optString("id")))target=f;}
        if(source==null||target==null)return false;
        if(!source.optString("parent").equals(target.optString("parent")))return false;
        JSONArray next=new JSONArray();for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(sourceId.equals(f.optString("id")))continue;if(targetId.equals(f.optString("id"))&&!after)next.put(source);next.put(f);if(targetId.equals(f.optString("id"))&&after)next.put(source);}
        state.put("folders",next);write(state);return true;
    }}
    public boolean reorderBook(String sourceKey,String targetKey,boolean after) throws Exception {synchronized(LOCK){
        if(sourceKey.equals(targetKey))return false;
        List<JSONObject> all=books();JSONObject source=null,target=null;for(JSONObject book:all){if(sourceKey.equals(key(book)))source=book;if(targetKey.equals(key(book)))target=book;}
        if(source==null||target==null||!folder(source).equals(folder(target)))return false;
        ArrayList<String> siblings=new ArrayList<>();for(JSONObject book:all)if(folder(source).equals(folder(book)))siblings.add(key(book));
        List<String> ordered=ShelfOrder.insert(siblings,sourceKey,targetKey,after);JSONArray saved=new JSONArray();int n=0;for(JSONObject book:all)saved.put(folder(source).equals(folder(book))?ordered.get(n++):key(book));
        JSONObject state=read();state.put("bookOrder",saved);write(state);return true;
    }}
    /** One metadata transaction creates the folder and assigns exactly its two books. */
    public String mergeBooks(String sourceKey,String targetKey,String parent) throws Exception {synchronized(LOCK){
        JSONObject state=read();requirePlacement(state,sourceKey,parent);requirePlacement(state,targetKey,parent);
        List<JSONObject> all=books();ArrayList<String> siblings=new ArrayList<>();for(JSONObject book:all)if(parent.equals(folder(book)))siblings.add(key(book));
        List<String> members=ShelfOrder.mergeMembers(siblings,sourceKey,targetKey);
        state=read();String id=UUID.randomUUID().toString();array(state,"folders").put(new JSONObject().put("id",id).put("title","新建书夹").put("parent",parent));
        for(String key:members)entry(state,key).put("folder",id);
        JSONArray saved=new JSONArray();int n=0;for(JSONObject book:all){String key=key(book);saved.put(members.contains(key)?members.get(n++):key);}state.put("bookOrder",saved);
        write(state);return id;
    }}
    public void move(String key,String folderId) throws Exception {synchronized(LOCK){JSONObject state=read();if(!folderId.isEmpty()){boolean found=false;JSONArray folders=array(state,"folders");for(int n=0;n<folders.length();n++)if(folderId.equals(folders.getJSONObject(n).optString("id")))found=true;if(!found)throw new IOException("书籍夹不存在");}requirePlacement(state,key,folderId);entry(state,key).put("folder",folderId);write(state);}}
    public String moveToNewFolder(String key,String name,String parent) throws Exception {name=name(name);synchronized(LOCK){
        JSONObject state=read();requirePlacement(state,key,parent);String id=UUID.randomUUID().toString();
        array(state,"folders").put(new JSONObject().put("id",id).put("title",name).put("parent",parent));entry(state,key).put("folder",id);write(state);return id;
    }}
    public void deleteFolder(String id) throws Exception {synchronized(LOCK){JSONObject state=read();JSONArray old=array(state,"folders"),next=new JSONArray();for(int n=0;n<old.length();n++)if(!id.equals(old.getJSONObject(n).optString("id")))next.put(old.getJSONObject(n));state.put("folders",next);for(int n=0;n<next.length();n++)if(id.equals(next.getJSONObject(n).optString("parent")))next.getJSONObject(n).put("parent","");JSONObject entries=state.optJSONObject("entries");if(entries!=null){Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject entry=entries.optJSONObject(keys.next());if(entry!=null&&id.equals(entry.optString("folder")))entry.remove("folder");}}write(state);}}
    public void delete(String key) throws Exception {
        synchronized(LOCK){
            if(bookKeyOnline(key)){favoriteOnline(new JSONObject().put("key",key),false);return;}
            if(key.startsWith("download:")){String id=key.substring(9);if(DownloadService.running&&id.equals(DownloadService.activeId))throw new IOException("请先暂停下载");new Store(context).delete(id);}
            else if(key.startsWith("import:")){String uuid=key.substring(7);deleteTree(importDir(uuid),root);}
            else if(key.startsWith("tree:")){String uri=key.substring(5);JSONArray old=new JSONArray(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("folders","[]")),next=new JSONArray();for(int n=0;n<old.length();n++){JSONObject item=old.getJSONObject(n);if(!uri.equals(item.optString("uri")))next.put(item);}context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("folders",next.toString()).commit();}
            else throw new IOException("书籍不存在");
            JSONObject state=read();JSONObject entries=state.optJSONObject("entries");if(entries!=null)entries.remove(key);
            if(key.startsWith("import:")){JSONArray old=array(state,"imports"),next=new JSONArray();for(int n=0;n<old.length();n++)if(!key.equals("import:"+old.getJSONObject(n).optString("uuid")))next.put(old.getJSONObject(n));state.put("imports",next);}write(state);
        }
    }
    private boolean bookKeyOnline(String key)throws Exception{return hasOnline(key);}
    public List<LocalImages.Page> pages(JSONObject book) throws Exception {
        String key=key(book);ArrayList<LocalImages.Page> result=new ArrayList<>();
        if(key.startsWith("download:"))for(File page:new Store(context).pages(key.substring(9)))result.add(new LocalImages.Page(Uri.fromFile(page).toString(),page.getName()));
        else if(key.startsWith("tree:"))return LocalImages.scan(context.getContentResolver(),Uri.parse(key.substring(5)));
        else if(key.startsWith("import:")){File folder=new File(importDir(key.substring(7)),"pages");File[] files=folder.listFiles(file->file.isFile()&&LocalImages.isImage(file.getName(),null));if(files!=null){Arrays.sort(files,(a,b)->LocalImages.natural(a.getName(),b.getName()));for(File page:files)result.add(new LocalImages.Page(Uri.fromFile(page).toString(),page.getName()));}}
        return result;
    }
    public JSONObject importComic(Uri uri) throws Exception {
        String filename=displayName(uri),uuid=UUID.randomUUID().toString();
        File pending=new File(root,".pending-"+uuid),target=importDir(uuid);
        synchronized(LOCK){
            if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建导入目录");
            File[] abandoned=root.listFiles(file->file.getName().matches("\\.pending-[0-9a-f-]{36}"));
            if(abandoned!=null)for(File file:abandoned)if(!IMPORTING.contains(file.getAbsolutePath()))deleteTree(file,root);
            if(!pending.mkdir())throw new IOException("无法创建导入目录");IMPORTING.add(pending.getAbsolutePath());
        }
        boolean complete=false;
        try{
            File archive=new File(pending,"archive");
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(archive))){if(in==null)throw new IOException("无法读取压缩包");ComicArchives.copyArchive(in,out,root);}
            int pages=ComicArchives.extract(archive,new File(pending,"pages"),filename);
            if(!archive.delete())throw new IOException("无法清理导入临时文件");
            ComicArchives.interrupted();
            JSONObject item=new JSONObject().put("uuid",uuid).put("title",filename.replaceFirst("(?i)\\.(cbz|zip|cbr|rar|cb7|7z|cbt|tar)$","")).put("pages",pages).put("created",System.currentTimeMillis());
            synchronized(LOCK){JSONObject state=read();array(state,"imports").put(item);if(!pending.renameTo(target))throw new IOException("无法保存导入内容");try{write(state);}catch(Exception e){deleteTree(target,root);throw e;}}
            complete=true;JSONObject book=new JSONObject(item.toString());return book.put("key","import:"+uuid).put("type","import").put("status","complete").put("done",pages);
        }finally{try{if(!complete&&pending.exists())deleteTree(pending,root);}finally{synchronized(LOCK){IMPORTING.remove(pending.getAbsolutePath());}}}
    }
    public Uri exportCbz(JSONObject book) throws Exception {
        List<LocalImages.Page> pages=pages(book);if(pages.isEmpty())throw new IOException("没有可分享的图片");
        String filename=title(book).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();if(filename.isEmpty())filename="漫画";if(filename.length()>80)filename=filename.substring(0,80);filename+="_"+System.currentTimeMillis()+".cbz";
        ContentResolver resolver=context.getContentResolver();Uri uri=null;File legacy=null;boolean success=false;
        try{
            if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/vnd.comicbook+zip");values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/jmshare");values.put(MediaStore.MediaColumns.IS_PENDING,1);uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("无法在下载目录创建分享文件");}
            else{if(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("请允许存储权限后分享");File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"jmshare");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建下载目录");legacy=new File(folder,filename);uri=Uri.fromFile(legacy);}
            try(OutputStream raw=legacy==null?resolver.openOutputStream(uri):new FileOutputStream(legacy);ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(requireStream(raw)))){
                zip.setLevel(0);byte[] buffer=new byte[64*1024];int index=0;
                for(LocalImages.Page page:pages){ComicArchives.interrupted();String extension=ComicArchives.extension(page.name);zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT,"%06d.%s",++index,extension)));try(InputStream in=resolver.openInputStream(Uri.parse(page.uri))){if(in==null)throw new IOException("无法读取图片");for(int n;(n=in.read(buffer))!=-1;){ComicArchives.interrupted();zip.write(buffer,0,n);}}zip.closeEntry();}
            }
            if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.IS_PENDING,0);if(resolver.update(uri,values,null,null)!=1)throw new IOException("无法完成分享文件");}
            else{ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DATA,legacy.getAbsolutePath());values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/vnd.comicbook+zip");uri=resolver.insert(MediaStore.Files.getContentUri("external"),values);if(uri==null)throw new IOException("无法生成分享文件链接");}
            success=true;return uri;
        }finally{if(!success){if(legacy!=null)legacy.delete();if(uri!=null&&"content".equals(uri.getScheme()))resolver.delete(uri,null,null);}}
    }
    private static OutputStream requireStream(OutputStream out)throws IOException{if(out==null)throw new IOException("无法创建分享文件");return out;}
    private String displayName(Uri uri){if("file".equals(uri.getScheme()))return new File(uri.getPath()).getName();try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}catch(Exception ignored){}return "导入漫画.cbz";}
    private File importDir(String uuid)throws IOException{if(!validUuid(uuid))throw new IOException("导入编号无效");File dir=new File(root,uuid);if(!dir.getCanonicalPath().equals(root.getCanonicalPath()+File.separator+uuid))throw new IOException("保存路径无效");return dir;}
    private static boolean validUuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static String name(String value){String result=value==null?"":value.trim();if(result.isEmpty()||result.length()>100)throw new IllegalArgumentException("名称须为 1–100 个字符");return result;}
    private JSONObject read() throws Exception {File file=metadata.getBaseFile();if(!file.exists()&&!new File(file+".bak").exists())return new JSONObject();return new JSONObject(new String(metadata.readFully(),StandardCharsets.UTF_8));}
    private void write(JSONObject state)throws Exception{FileOutputStream out=null;try{out=metadata.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));metadata.finishWrite(out);}catch(Exception e){if(out!=null)metadata.failWrite(out);throw e;}}
    private static JSONArray array(JSONObject state,String key)throws Exception{JSONArray array=state.optJSONArray(key);if(array==null){array=new JSONArray();state.put(key,array);}return array;}
    private static JSONObject entry(JSONObject state,String key)throws Exception{JSONObject entries=state.optJSONObject("entries");if(entries==null){entries=new JSONObject();state.put("entries",entries);}JSONObject entry=entries.optJSONObject(key);if(entry==null){entry=new JSONObject();entries.put(key,entry);}return entry;}
    private static void deleteTree(File file,File root)throws Exception{String base=root.getCanonicalPath(),path=file.getCanonicalPath();if(!path.startsWith(base+File.separator))throw new IOException("删除路径无效");File[] files=file.listFiles();if(files!=null)for(File child:files)deleteTree(child,root);if(file.exists()&&!file.delete())throw new IOException("无法清理本地文件");}
}

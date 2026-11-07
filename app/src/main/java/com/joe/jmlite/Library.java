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
    private final boolean privateShelf;
    private final File root;
    private final AtomicFile metadata;
    /** The folder currently open on screen; its auto-collapse waits until the user leaves it. */
    private String preservedFolder="";

    public Library(Context context){this(context,false);}
    public Library(Context context,boolean privateShelf){this.privateShelf=privateShelf;this.context=context.getApplicationContext();root=new File(this.context.getFilesDir(),"imports");metadata=new AtomicFile(new File(this.context.getFilesDir(),"library.json"));}
    /** Keep the currently open folder from collapsing until the user actually leaves it. */
    public void preserveFolder(String id){preservedFolder=id==null?"":id;}
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
        if(entries!=null)for(JSONObject book:result){JSONObject entry=entries.optJSONObject(key(book));if(entry!=null){if(entry.has("title"))book.put("title",entry.getString("title"));book.put("folder",entry.optString("folder")).put("privacy",entry.optBoolean("privacy")).put("favorite",entry.optBoolean("favorite",book.has("sourceId")));}}
        // Everything under 我的收藏 is a favorite, including books in folders nested inside it.
        HashSet<String> favorites=favoriteFolders(state);
        if(!favorites.isEmpty())for(JSONObject book:result)if(favorites.contains(folder(book)))book.put("favorite",true);
        JSONArray saved=state.optJSONArray("bookOrder");if(saved!=null){ArrayList<String> available=new ArrayList<>(),order=new ArrayList<>();HashMap<String,JSONObject> byKey=new HashMap<>();for(JSONObject book:result){String key=key(book);available.add(key);byKey.put(key,book);}for(int n=0;n<saved.length();n++)order.add(saved.optString(n));result.clear();for(String key:ShelfOrder.restore(available,order))result.add(byKey.get(key));}
        result.removeIf(book->book.optBoolean("privacy")!=privateShelf);
        return result;
    }
    public List<JSONObject> folders() throws Exception {synchronized(LOCK){JSONObject state=read();migrateOnlineFolders(state);JSONArray folders=state.optJSONArray("folders");ArrayList<JSONObject> result=new ArrayList<>();if(folders!=null)for(int n=0;n<folders.length();n++)if(folders.getJSONObject(n).optBoolean("privacy")==privateShelf)result.add(folders.getJSONObject(n));return result;}}
    public JSONObject find(String key) throws Exception {for(boolean hidden:new boolean[]{false,true})for(JSONObject book:new Library(context,hidden).books())if(key.equals(key(book)))return book;return null;}
    public void rename(String key,String name) throws Exception {name=name(name);synchronized(LOCK){JSONObject state=read();entry(state,key).put("title",name);write(state);}}
    public String createFolder(String name) throws Exception {return createFolder(name, "");}
    public String createFolder(String name,String parent) throws Exception {name=name(name);synchronized(LOCK){JSONObject state=read();requireScope(state,parent);JSONArray folders=array(state,"folders");String id=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",id).put("title",name).put("parent",parent).put("privacy",privateShelf));write(state);return id;}}
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
    public void ensureOnline(JSONObject record) throws Exception {synchronized(LOCK){String key=record.optString("key",OnlineHistory.key(record.optString("sourceId"),record.optString("url")));JSONArray online=array(read(),"online");for(int n=0;n<online.length();n++)if(key.equals(online.getJSONObject(n).optString("key")))return;favoriteOnline(record,true);}}
    public void favoriteOnline(JSONObject record,boolean on) throws Exception {synchronized(LOCK){
        JSONObject state=read();String key=record.optString("key",OnlineHistory.key(record.optString("sourceId"),record.optString("url")));if(on&&localBookKey(key))throw new LocalComicException();JSONObject savedEntry=state.optJSONObject("entries")==null?null:state.getJSONObject("entries").optJSONObject(key);if(savedEntry!=null&&savedEntry.optBoolean("privacy")){savedEntry.put("favorite",on);write(state);return;}JSONArray old=array(state,"online"),next=new JSONArray();
        for(int n=0;n<old.length();n++)if(!key.equals(old.getJSONObject(n).optString("key")))next.put(old.getJSONObject(n));
        if(on){boolean jm=OnlineHistory.isJm(record);String system=jm?ONLINE_JM_SYSTEM:ONLINE_WEB_SYSTEM,title=jm?ONLINE_JM:ONLINE_WEB;String folder="";JSONArray folders=array(state,"folders");for(int n=0;n<folders.length();n++)if(system.equals(folders.getJSONObject(n).optString("system"))&&folders.getJSONObject(n).optBoolean("privacy")==privateShelf)folder=folders.getJSONObject(n).optString("id");
            if(folder.isEmpty()){folder=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",folder).put("title",title).put("system",system).put("privacy",privateShelf));}
            next.put(new JSONObject(record.toString()).put("key",key).put("type","online"));entry(state,key).put("folder",folder).put("favorite",true);
        }else{JSONObject entries=state.optJSONObject("entries");String previous="";if(entries!=null){JSONObject e=entries.optJSONObject(key);if(e!=null)previous=e.optString("folder");entries.remove(key);}collapseSmallFolder(state,previous);}
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
            if(target.isEmpty()){target=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",target).put("title",title).put("system",system).put("privacy",privateShelf));}
            entry.put("folder",target);changed=true;
        }
        if(changed){
            boolean used=false;Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject entry=entries.optJSONObject(keys.next());if(entry!=null&&legacy.equals(entry.optString("folder")))used=true;}
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(legacy.equals(f.optString("parent")))used=true;}
            if(!used){JSONArray next=new JSONArray();for(int n=0;n<folders.length();n++)if(!legacy.equals(folders.getJSONObject(n).optString("id")))next.put(folders.getJSONObject(n));state.put("folders",next);}
            write(state);
        }
    }
    public boolean hasOnline(String key) throws Exception {synchronized(LOCK){JSONObject state=read();JSONArray all=array(state,"online");for(int n=0;n<all.length();n++)if(key.equals(all.getJSONObject(n).optString("key"))){JSONObject entries=state.optJSONObject("entries");JSONObject e=entries==null?null:entries.optJSONObject(key);return e==null||e.optBoolean("favorite",true);}return false;}}
    public String favoriteFolderId(boolean create) throws Exception {
        synchronized(LOCK){
            JSONObject state=read();JSONArray folders=array(state,"folders");
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(f.optBoolean("privacy")==privateShelf&&("favorites".equals(f.optString("system"))||FAVORITES.equals(f.optString("title"))))return f.getString("id");}
            if(!create)return "";
            String id=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",id).put("title",FAVORITES).put("system","favorites").put("privacy",privateShelf));write(state);return id;
        }
    }
    public void favorite(String key,boolean on) throws Exception {synchronized(LOCK){JSONObject state=read();JSONObject entries=state.optJSONObject("entries");JSONObject existing=entries==null?null:entries.optJSONObject(key);if(existing!=null&&existing.optBoolean("privacy")!=privateShelf){new Library(context,existing.optBoolean("privacy")).favorite(key,on);return;}}if(on)move(key,favoriteFolderId(true));else move(key,"");synchronized(LOCK){JSONObject state=read();entry(state,key).put("favorite",on);write(state);}}
    public boolean isFavorite(JSONObject book) throws Exception {synchronized(LOCK){JSONObject state=read();JSONObject entries=state.optJSONObject("entries"),e=entries==null?null:entries.optJSONObject(key(book));if(e!=null){if(inFavorites(state,e.optString("folder")))return true;if(e.has("favorite"))return e.optBoolean("favorite");}String id=favoriteFolderId(false);return book.optBoolean("favorite")||(!id.isEmpty()&&(id.equals(book.optString("folder"))||inFavorites(state,book.optString("folder"))));}}
    public static final String FAVORITES="我的收藏";
    /** The 我的收藏 directory and every folder nested inside it. */
    static boolean isFavoriteFolder(JSONObject folder){return "favorites".equals(folder.optString("system"))||FAVORITES.equals(folder.optString("title"));}
    static HashSet<String> favoriteFolders(JSONObject state){
        HashSet<String> ids=new HashSet<>();JSONArray folders=state.optJSONArray("folders");
        if(folders==null)return ids;
        for(int n=0;n<folders.length();n++){JSONObject f=folders.optJSONObject(n);if(f!=null&&isFavoriteFolder(f))ids.add(f.optString("id"));}
        boolean changed=true;
        while(changed){changed=false;for(int n=0;n<folders.length();n++){JSONObject f=folders.optJSONObject(n);if(f!=null&&ids.contains(f.optString("parent"))&&ids.add(f.optString("id")))changed=true;}}
        return ids;
    }
    /** True for 我的收藏 itself and any descendant, however deep the nesting. */
    static boolean inFavorites(JSONObject state,String folderId){
        if(folderId==null||folderId.isEmpty())return false;
        JSONArray folders=state.optJSONArray("folders");if(folders==null)return false;
        HashSet<String> seen=new HashSet<>();String cursor=folderId;
        while(!cursor.isEmpty()&&seen.add(cursor)){
            JSONObject folder=null;for(int n=0;n<folders.length();n++){JSONObject candidate=folders.optJSONObject(n);if(candidate!=null&&cursor.equals(candidate.optString("id"))){folder=candidate;break;}}
            if(folder==null)return false;
            if(isFavoriteFolder(folder))return true;
            cursor=folder.optString("parent");
        }
        return false;
    }
    static String folderParent(JSONObject state,String folderId){
        JSONArray folders=state.optJSONArray("folders");if(folders==null)return "";
        for(int n=0;n<folders.length();n++){JSONObject f=folders.optJSONObject(n);if(f!=null&&folderId.equals(f.optString("id")))return f.optString("parent");}
        return "";
    }
    public static boolean isProtectedFolder(JSONObject folder){String system=folder.optString("system");return "favorites".equals(system)||"online_jm".equals(system)||"online_web".equals(system)||"online".equals(system)||FAVORITES.equals(folder.optString("title"));}
    public void renameFolder(String id,String name) throws Exception {name=name(name);synchronized(LOCK){JSONObject state=read();JSONArray folders=array(state,"folders");for(int n=0;n<folders.length();n++)if(id.equals(folders.getJSONObject(n).optString("id"))){if(isProtectedFolder(folders.getJSONObject(n)))throw new IOException("系统书籍夹不支持重命名");folders.getJSONObject(n).put("title",name);write(state);return;}throw new IOException("书籍夹不存在");}}
    /** Moves a folder one position among its siblings. */
    public boolean moveFolder(String id,int delta) throws Exception {synchronized(LOCK){
        JSONObject state=read();JSONArray folders=array(state,"folders");int index=-1;String parent="";
        for(int n=0;n<folders.length();n++)if(id.equals(folders.getJSONObject(n).optString("id"))){index=n;parent=folders.getJSONObject(n).optString("parent");break;}
        if(index<0)return false;
        int sibling=-1;if(delta<0){for(int n=index-1;n>=0;n--)if(parent.equals(folders.getJSONObject(n).optString("parent"))&&folders.getJSONObject(n).optBoolean("privacy")==privateShelf){sibling=n;break;}}
        else{for(int n=index+1;n<folders.length();n++)if(parent.equals(folders.getJSONObject(n).optString("parent"))&&folders.getJSONObject(n).optBoolean("privacy")==privateShelf){sibling=n;break;}}
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
        JSONObject state=read();saveScopedOrder(state,saved);write(state);return true;
    }}
    /** One metadata transaction creates the folder and assigns exactly its two books. */
    public String mergeBooks(String sourceKey,String targetKey,String parent) throws Exception {synchronized(LOCK){
        JSONObject state=read();requireBookScope(state,sourceKey);requireBookScope(state,targetKey);requireScope(state,parent);requirePlacement(state,sourceKey,parent);requirePlacement(state,targetKey,parent);
        List<JSONObject> all=books();ArrayList<String> siblings=new ArrayList<>();for(JSONObject book:all)if(parent.equals(folder(book)))siblings.add(key(book));
        List<String> members=ShelfOrder.mergeMembers(siblings,sourceKey,targetKey);
        state=read();String id=UUID.randomUUID().toString();array(state,"folders").put(new JSONObject().put("id",id).put("title","新建书夹").put("parent",parent).put("privacy",privateShelf));
        String oldSource=entry(state,sourceKey).optString("folder"),oldTarget=entry(state,targetKey).optString("folder");
        boolean favorite=inFavorites(state,parent);
        for(String key:members){JSONObject entry=entry(state,key);entry.put("folder",id);if(favorite)entry.put("favorite",true);}
        JSONArray saved=new JSONArray();int n=0;for(JSONObject book:all){String key=key(book);saved.put(members.contains(key)?members.get(n++):key);}saveScopedOrder(state,saved);
        // Books that left an unrelated folder may leave it with at most one book: collapse it.
        if(!insideFolder(state,id,oldSource))collapseSmallFolder(state,oldSource);
        if(!oldTarget.equals(oldSource)&&!insideFolder(state,id,oldTarget))collapseSmallFolder(state,oldTarget);
        write(state);return id;
    }}
    public void move(String key,String folderId) throws Exception {synchronized(LOCK){JSONObject state=read();if(!folderId.isEmpty()){boolean found=false;JSONArray folders=array(state,"folders");for(int n=0;n<folders.length();n++)if(folderId.equals(folders.getJSONObject(n).optString("id")))found=true;if(!found)throw new IOException("书籍夹不存在");}requireBookScope(state,key);requireScope(state,folderId);requirePlacement(state,key,folderId);JSONObject entry=entry(state,key);String previous=entry.optString("folder");entry.put("folder",folderId);if(inFavorites(state,folderId))entry.put("favorite",true);if(!insideFolder(state,folderId,previous))collapseSmallFolder(state,previous);write(state);}}
    public String moveToNewFolder(String key,String name,String parent) throws Exception {name=name(name);synchronized(LOCK){
        JSONObject state=read();requireBookScope(state,key);requireScope(state,parent);requirePlacement(state,key,parent);String id=UUID.randomUUID().toString();
        array(state,"folders").put(new JSONObject().put("id",id).put("title",name).put("parent",parent).put("privacy",privateShelf));JSONObject entry=entry(state,key);String previous=entry.optString("folder");entry.put("folder",id);if(inFavorites(state,parent))entry.put("favorite",true);if(!insideFolder(state,id,previous))collapseSmallFolder(state,previous);write(state);return id;
    }}
    /** Dismantles a folder and every nested folder; all of their books land in its parent list. */
    public void deleteFolder(String id) throws Exception {synchronized(LOCK){
        JSONObject state=read();JSONArray old=array(state,"folders");
        String parent="";JSONObject target=null;
        for(int n=0;n<old.length();n++)if(id.equals(old.getJSONObject(n).optString("id"))){target=old.getJSONObject(n);parent=target.optString("parent");break;}
        if(target==null)return;
        // 我的收藏 / 在线JM漫画 / 在线全网漫画 are system directories and cannot be dismantled.
        if(isProtectedFolder(target))throw new IOException("系统书籍夹不能拆除");
        // Collect the whole subtree first: nested folders are dismantled with their parent instead of
        // being lifted one level, so every book below them is reassigned in the same transaction.
        // A protected folder nested below is kept and lifted to the dismantled folder's parent.
        HashSet<String> removed=new HashSet<>();removed.add(id);
        boolean grew=true;
        while(grew){grew=false;for(int n=0;n<old.length();n++){JSONObject f=old.getJSONObject(n);if(!isProtectedFolder(f)&&removed.contains(f.optString("parent"))&&removed.add(f.optString("id")))grew=true;}}
        JSONArray next=new JSONArray();
        for(int n=0;n<old.length();n++){JSONObject f=old.getJSONObject(n);if(removed.contains(f.optString("id")))continue;if(removed.contains(f.optString("parent")))f.put("parent",parent);next.put(f);}
        state.put("folders",next);
        JSONObject entries=state.optJSONObject("entries");boolean favorite=inFavorites(state,parent);
        if(entries!=null){Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject entry=entries.optJSONObject(keys.next());if(entry==null||!removed.contains(entry.optString("folder")))continue;if(parent.isEmpty())entry.remove("folder");else entry.put("folder",parent);if(favorite)entry.put("favorite",true);}}
        write(state);
    }}
    public void delete(String key) throws Exception {
        synchronized(LOCK){
            requireBookScope(read(),key);
            if(bookKeyOnline(key)){JSONObject state=read();JSONArray old=array(state,"online"),next=new JSONArray();for(int n=0;n<old.length();n++)if(!key.equals(old.getJSONObject(n).optString("key")))next.put(old.getJSONObject(n));state.put("online",next);JSONObject entries=state.optJSONObject("entries");String previous="";if(entries!=null){JSONObject e=entries.optJSONObject(key);if(e!=null)previous=e.optString("folder");entries.remove(key);}collapseSmallFolder(state,previous);write(state);return;}
            if(key.startsWith("download:")){String id=key.substring(9);if(DownloadService.running&&id.equals(DownloadService.activeId))throw new IOException("请先暂停下载");new Store(context).delete(id);}
            else if(key.startsWith("import:")){String uuid=key.substring(7);deleteTree(importDir(uuid),root);}
            else if(key.startsWith("tree:")){String uri=key.substring(5);JSONArray old=new JSONArray(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("folders","[]")),next=new JSONArray();for(int n=0;n<old.length();n++){JSONObject item=old.getJSONObject(n);if(!uri.equals(item.optString("uri")))next.put(item);}context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("folders",next.toString()).commit();}
            else throw new IOException("书籍不存在");
            JSONObject state=read();JSONObject entries=state.optJSONObject("entries");String previous="";if(entries!=null){JSONObject e=entries.optJSONObject(key);if(e!=null)previous=e.optString("folder");entries.remove(key);}
            if(key.startsWith("import:")){JSONArray old=array(state,"imports"),next=new JSONArray();for(int n=0;n<old.length();n++)if(!key.equals("import:"+old.getJSONObject(n).optString("uuid")))next.put(old.getJSONObject(n));state.put("imports",next);}collapseSmallFolder(state,previous);write(state);
        }
    }
    private boolean bookKeyOnline(String key)throws Exception{synchronized(LOCK){JSONArray all=array(read(),"online");for(int n=0;n<all.length();n++)if(key.equals(all.getJSONObject(n).optString("key")))return true;return false;}}

    /**
     * A regular folder that just lost books collapses: 0 books removes it, 1 book moves to the end of
     * its parent directory. System folders and folders holding a protected descendant are left alone.
     */
    private boolean collapseSmallFolder(JSONObject state,String folderId) throws Exception {
        if(folderId==null||folderId.isEmpty()||folderId.equals(preservedFolder))return false;
        JSONArray folders=state.optJSONArray("folders");if(folders==null)return false;
        String parent="";boolean found=false;
        for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(folderId.equals(f.optString("id"))){parent=f.optString("parent");found=true;break;}}
        if(!found)return false;
        HashSet<String> removed=new HashSet<>();removed.add(folderId);
        boolean grew=true;
        while(grew){grew=false;for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(removed.contains(f.optString("parent"))&&removed.add(f.optString("id")))grew=true;}}
        for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(removed.contains(f.optString("id"))&&isProtectedFolder(f))return false;}
        JSONObject entries=state.optJSONObject("entries");ArrayList<String> books=new ArrayList<>();
        if(entries!=null){Iterator<String> keys=entries.keys();while(keys.hasNext()){String key=keys.next();JSONObject e=entries.optJSONObject(key);if(e!=null&&removed.contains(e.optString("folder")))books.add(key);}}
        if(books.size()>1)return false;
        if(books.size()==1){JSONObject e=entries.optJSONObject(books.get(0));if(parent.isEmpty())e.remove("folder");else e.put("folder",parent);if(inFavorites(state,parent))e.put("favorite",true);appendBookOrder(state,books.get(0),parent);}
        JSONArray next=new JSONArray();for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(!removed.contains(f.optString("id")))next.put(f);}
        state.put("folders",next);return true;
    }
    /** Persist the deferred collapse of the folder the user has just left. */
    public boolean releasePreservedFolder(String folderId) throws Exception {synchronized(LOCK){
        if(folderId!=null&&folderId.equals(preservedFolder))preservedFolder="";
        JSONObject state=read();boolean changed=collapseSmallFolder(state,folderId);if(changed)write(state);return changed;
    }}
    /** Put the key after every sibling so the collapsed book becomes the last in its directory. */
    private void appendBookOrder(JSONObject state,String key,String parent) throws Exception {
        JSONArray old=state.optJSONArray("bookOrder"),next=new JSONArray();
        if(old!=null)for(int n=0;n<old.length();n++){String item=old.optString(n);if(!key.equals(item))next.put(item);}
        // Unsaved siblings are appended by ShelfOrder.restore after the saved list, so pin them in
        // their current effective order before the collapsed book to keep it truly last.
        try{
            JSONObject entries=state.optJSONObject("entries");HashSet<String> siblings=new HashSet<>();
            if(entries!=null){Iterator<String> keys=entries.keys();while(keys.hasNext()){String item=keys.next();JSONObject e=entries.optJSONObject(item);if(e!=null&&parent.equals(e.optString("folder"))&&!key.equals(item))siblings.add(item);}}
            if(!siblings.isEmpty()){
                ArrayList<String> ordered=new ArrayList<>();
                for(JSONObject book:new Library(context,privateShelf).books()){String item=key(book);if(siblings.remove(item))ordered.add(item);}
                if(old!=null)for(int n=0;n<old.length();n++){String item=old.optString(n);if(siblings.remove(item))ordered.add(item);}
                ordered.addAll(siblings);
                for(String item:ordered)next.put(item);
            }
        }catch(Exception ignored){/* The saved order still puts the book after any ordered sibling. */}
        next.put(key);state.put("bookOrder",next);
    }
    /** True when folderId is ancestorId itself or nested somewhere below it. */
    private static boolean insideFolder(JSONObject state,String folderId,String ancestorId) throws Exception {
        if(folderId.isEmpty()||ancestorId.isEmpty())return false;
        JSONArray folders=state.optJSONArray("folders");if(folders==null)return false;
        HashSet<String> seen=new HashSet<>();String cursor=folderId;
        while(!cursor.isEmpty()&&seen.add(cursor)){
            if(cursor.equals(ancestorId))return true;
            String parent="";for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(cursor.equals(f.optString("id"))){parent=f.optString("parent");break;}}
            cursor=parent;
        }
        return false;
    }

    private void saveScopedOrder(JSONObject state,JSONArray ordered)throws Exception {JSONArray combined=new JSONArray(),old=state.optJSONArray("bookOrder");JSONObject entries=state.optJSONObject("entries");if(old!=null)for(int n=0;n<old.length();n++){String key=old.optString(n);JSONObject e=entries==null?null:entries.optJSONObject(key);if((e!=null&&e.optBoolean("privacy"))!=privateShelf)combined.put(key);}for(int n=0;n<ordered.length();n++)combined.put(ordered.get(n));state.put("bookOrder",combined);}
    private void requireBookScope(JSONObject state,String key)throws Exception {JSONObject entries=state.optJSONObject("entries"),e=entries==null?null:entries.optJSONObject(key);if((e!=null&&e.optBoolean("privacy"))!=privateShelf)throw new IOException("不能跨书架分类");}
    private void requireScope(JSONObject state,String id)throws Exception {if(id.isEmpty())return;for(int n=0;n<array(state,"folders").length();n++){JSONObject f=array(state,"folders").getJSONObject(n);if(id.equals(f.optString("id"))&&f.optBoolean("privacy")!=privateShelf)throw new IOException("不能跨书架分类");}}
    /** Transfer membership in one metadata transaction; media files are never copied or deleted. */
    public void transfer(String key,boolean folder,boolean target)throws Exception {synchronized(LOCK){
        JSONObject state=read();JSONArray folders=array(state,"folders");JSONObject entries=state.optJSONObject("entries");if(entries==null){entries=new JSONObject();state.put("entries",entries);}
        if(folder){
            HashSet<String> ids=new HashSet<>();ids.add(key);boolean changed=true;
            while(changed){changed=false;for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(ids.contains(f.optString("parent"))&&ids.add(f.optString("id")))changed=true;}}
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(ids.contains(f.optString("id"))){f.put("privacy",target);if(key.equals(f.optString("id")))f.put("parent","");}}
            Iterator<String> keys=entries.keys();while(keys.hasNext()){JSONObject e=entries.getJSONObject(keys.next());if(ids.contains(e.optString("folder")))e.put("privacy",target);}
            JSONObject rootFolder=null;String counterpart="";
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(key.equals(f.optString("id")))rootFolder=f;}
            if(rootFolder!=null&&!rootFolder.optString("system").isEmpty())for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(!key.equals(f.optString("id"))&&f.optBoolean("privacy")==target&&rootFolder.optString("system").equals(f.optString("system")))counterpart=f.optString("id");}
            if(!counterpart.isEmpty()){Iterator<String> members=entries.keys();while(members.hasNext()){JSONObject e=entries.getJSONObject(members.next());if(key.equals(e.optString("folder")))e.put("folder",counterpart);}JSONArray next=new JSONArray();for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(key.equals(f.optString("id")))continue;if(key.equals(f.optString("parent")))f.put("parent",counterpart);next.put(f);}state.put("folders",next);}
            // A moved directory that lives under 我的收藏 keeps every member favorited, nested folders included.
            Iterator<String> favorites=entries.keys();while(favorites.hasNext()){JSONObject e=entries.getJSONObject(favorites.next());if(inFavorites(state,e.optString("folder")))e.put("favorite",true);}
        }else{
            JSONObject e=entry(state,key);String destination="";String old=e.optString("folder");
            for(int n=0;n<folders.length();n++){JSONObject f=folders.getJSONObject(n);if(old.equals(f.optString("id"))){String system=f.optString("system");if("favorites".equals(system))e.put("favorite",true);if(!system.isEmpty()){
                for(int j=0;j<folders.length();j++){JSONObject t=folders.getJSONObject(j);if(t.optBoolean("privacy")==target&&system.equals(t.optString("system")))destination=t.optString("id");}
                if(destination.isEmpty()){destination=UUID.randomUUID().toString();folders.put(new JSONObject().put("id",destination).put("system",system).put("title",f.optString("title")).put("privacy",target));}
            }break;}}
            e.put("privacy",target).put("folder",destination);
            if(inFavorites(state,destination))e.put("favorite",true);
            collapseSmallFolder(state,old);
        }write(state);
    }}
    public JSONObject findLocalDownload(String id)throws Exception {for(boolean hidden:new boolean[]{false,true})for(JSONObject b:new Library(context,hidden).books())if(("download:"+id).equals(key(b)))return b;return null;}

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

package com.joe.jmlite;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-refresh membership and visible-cover fingerprints, built off the UI thread. */
final class FolderCoverIndex {
    private static final int VISIBLE=4,MAX_SIGNATURE_DEPTH=4;
    private final Map<String,List<JSONObject>> members;
    private final Map<String,String> signatures;
    private FolderCoverIndex(Map<String,List<JSONObject>> members,Map<String,String> signatures){this.members=members;this.signatures=signatures;}
    static FolderCoverIndex from(List<JSONObject> books){return from(books,Collections.emptyList());}
    /** Folders themselves are visible members, so a preview can show 书籍夹中夹 exactly like books. */
    static FolderCoverIndex from(List<JSONObject> books,List<JSONObject> folders){
        Map<String,List<JSONObject>> grouped=new HashMap<>();
        for(JSONObject folder:folders){
            JSONObject entry;
            try{entry=new JSONObject(folder.toString());entry.put("book_folder",true);}catch(Exception e){continue;}
            String parent=entry.optString("parent");
            if(!parent.isEmpty())grouped.computeIfAbsent(parent,key->new ArrayList<>()).add(entry);
        }
        for(JSONObject book:books){String folder=book.optString("folder");if(!folder.isEmpty())grouped.computeIfAbsent(folder,key->new ArrayList<>()).add(book);}
        for(Map.Entry<String,List<JSONObject>> entry:grouped.entrySet())entry.setValue(Collections.unmodifiableList(entry.getValue()));
        Map<String,String> signatures=new HashMap<>();
        for(String folder:grouped.keySet())signatures.put(folder,signature(folder,grouped,new HashMap<>(),new HashSet<>(),0));
        return new FolderCoverIndex(grouped,signatures);
    }
    private static String signature(String folder,Map<String,List<JSONObject>> members,Map<String,String> memo,Set<String> visiting,int depth){
        String cached=memo.get(folder);if(cached!=null)return cached;
        if(depth>MAX_SIGNATURE_DEPTH||!visiting.add(folder))return "";
        List<JSONObject> group=members.get(folder);
        JSONArray face=new JSONArray().put(group==null?0:group.size());
        if(group!=null)for(int n=0;n<Math.min(VISIBLE,group.size());n++){
            JSONObject item=group.get(n);
            if(item.optBoolean("book_folder"))face.put(new JSONArray().put("folder:"+item.optString("id")).put(signature(item.optString("id"),members,memo,visiting,depth+1)));
            else face.put(new JSONArray().put(Library.key(item)).put(item.optString("thumbnail")));
        }
        visiting.remove(folder);
        String result=face.toString();memo.put(folder,result);return result;
    }
    List<JSONObject> members(String folder){
        List<JSONObject> group=members.get(folder);
        return group==null?Collections.emptyList():group;
    }
    String signature(String folder){return signatures.getOrDefault(folder,"");}
}

package com.joe.jmlite;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-refresh membership and visible-cover fingerprints, built off the UI thread. */
final class FolderCoverIndex {
    private final Map<String,List<JSONObject>> members;
    private final Map<String,String> signatures;
    private FolderCoverIndex(Map<String,List<JSONObject>> members,Map<String,String> signatures){this.members=members;this.signatures=signatures;}
    static FolderCoverIndex from(List<JSONObject> books){
        Map<String,List<JSONObject>> grouped=new HashMap<>();Map<String,String> signatures=new HashMap<>();
        for(JSONObject book:books){String folder=book.optString("folder");if(!folder.isEmpty())grouped.computeIfAbsent(folder,key->new ArrayList<>()).add(book);}
        for(Map.Entry<String,List<JSONObject>> entry:grouped.entrySet()){
            List<JSONObject> group=entry.getValue();JSONArray face=new JSONArray().put(group.size());
            for(int n=0;n<Math.min(4,group.size());n++){JSONObject book=group.get(n);face.put(new JSONArray().put(Library.key(book)).put(book.optString("thumbnail")));}
            signatures.put(entry.getKey(),face.toString());entry.setValue(Collections.unmodifiableList(group));
        }
        return new FolderCoverIndex(grouped,signatures);
    }
    List<JSONObject> members(String folder){return members.getOrDefault(folder,Collections.emptyList());}
    String signature(String folder){return signatures.getOrDefault(folder,"");}
}

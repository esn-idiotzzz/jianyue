package com.joe.jmlite;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searches every saved book while preserving shelf order. */
public final class BookSearch {
    private BookSearch(){}
    public static boolean matches(JSONObject book,String query){
        String q=query.trim().toLowerCase(Locale.ROOT);
        if(q.isEmpty()||book.optString("title").toLowerCase(Locale.ROOT).contains(q))return true;
        String id=!book.has("sourceId")||OnlineHistory.isJm(book)?book.optString("id"):"";if(id.isEmpty()&&OnlineHistory.isJm(book))id=book.optString("url");
        String number=q.replaceFirst("^jm\\s*","");
        return !number.isEmpty()&&!id.isEmpty()&&id.toLowerCase(Locale.ROOT).contains(number);
    }
    public static List<JSONObject> shelf(List<JSONObject> books,String folder,String query){
        ArrayList<JSONObject> result=new ArrayList<>();boolean searching=!query.trim().isEmpty();
        for(JSONObject book:books)if(searching?matches(book,query):folder.equals(book.optString("folder")))result.add(book);
        return result;
    }
}

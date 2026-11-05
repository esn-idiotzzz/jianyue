package com.joe.jmlite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Grid drag decisions use untransformed slots; previews never modify saved order. */
public final class ShelfOrder {
    public static final int NONE=0,BEFORE=1,AFTER=2,MERGE=3,INTO=4;
    private ShelfOrder(){}

    public static int zone(float x,float width,boolean sourceFolder,boolean targetFolder){
        if(width<=0||x<0||x>width)return NONE;
        if(sourceFolder)return targetFolder?(x<width/2?BEFORE:AFTER):NONE;
        if(targetFolder)return INTO;
        return x<width*.25f?BEFORE:x>width*.75f?AFTER:MERGE;
    }
    public static List<String> insert(List<String> order,String source,String target,boolean after){
        ArrayList<String> next=new ArrayList<>(order);
        if(new HashSet<>(next).size()!=next.size())throw new IllegalArgumentException("书架记录重复");
        if(!next.contains(source)||!next.contains(target))throw new IllegalArgumentException("书籍不在当前目录");
        if(source.equals(target))return next;
        next.remove(source);
        next.add(next.indexOf(target)+(after?1:0),source);
        return next;
    }
    public static List<String> restore(List<String> available,List<String> saved){
        ArrayList<String> next=new ArrayList<>();HashSet<String> remaining=new HashSet<>(available);
        for(String key:saved)if(remaining.remove(key))next.add(key);
        for(String key:available)if(remaining.remove(key))next.add(key);
        return next;
    }
    public static List<String> mergeMembers(List<String> siblings,String source,String target){
        if(source.equals(target)||!siblings.contains(source)||!siblings.contains(target))throw new IllegalArgumentException("请选择同一目录中的两本不同书籍");
        ArrayList<String> members=new ArrayList<>();members.add(target);members.add(source);return members;
    }
}

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
        // Keep most of the book as a merge target; only the narrow outer strips push it aside.
        return x<width*.12f?BEFORE:x>width*.88f?AFTER:MERGE;
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
    /**
     * Display order while a tile is lifted: the dragged item no longer occupies a slot, so the
     * following books and folders can close the gap instead of leaving an empty cell behind.
     */
    public static List<String> without(List<String> order,String source){
        if(new HashSet<>(order).size()!=order.size())throw new IllegalArgumentException("书架记录重复");
        ArrayList<String> next=new ArrayList<>(order);
        if(!next.remove(source))throw new IllegalArgumentException("书籍不在当前目录");
        return next;
    }
    /** Landing preview: the lifted item occupies the gap, every other sibling keeps the filled order. */
    public static List<String> landing(List<String> order,String source,int gap){
        List<String> next=without(order,source);
        if(gap<0||gap>next.size())throw new IllegalArgumentException("插入位置无效");
        next.add(gap,source);
        return next;
    }
    /** Map an adapter insertion boundary into the compacted order after removing the source. */
    public static int compactIndex(List<String> order,String source,int boundary){
        int index=order.indexOf(source);
        if(index<0)throw new IllegalArgumentException("书籍不在当前目录");
        if(boundary<0||boundary>order.size())throw new IllegalArgumentException("插入位置无效");
        return boundary-(index<boundary?1:0);
    }
    /** Books stay after every folder; folders can only land among folders. */
    public static int clampGap(int gap,int compactSize,int folderCount,boolean sourceFolder){
        int low=sourceFolder?0:Math.min(folderCount,compactSize);
        int high=sourceFolder?Math.min(folderCount,compactSize):compactSize;
        return Math.max(low,Math.min(high,gap));
    }
    /** Index in the compacted order of the sibling the lifted item lands next to, or -1. */
    public static int dropTargetIndex(List<String> compact,int gap,boolean sourceFolder,int folderCount){
        if(compact.isEmpty())return -1;
        if(sourceFolder){
            if(folderCount<=0)return -1;
            return Math.min(Math.max(gap,0),folderCount-1);
        }
        if(compact.size()<=folderCount)return -1;
        return Math.min(Math.max(gap,0),compact.size()-1);
    }
    /** True when the lifted item lands after its neighbour rather than before it. */
    public static boolean dropAfter(int gap,int compactSize,int folderCount,boolean sourceFolder){
        return sourceFolder?gap>=folderCount:gap>=compactSize;
    }
}

package com.joe.jmlite;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LocalImages {
    static final class Page {
        final String uri, name;
        Page(String uri, String name) { this.uri=uri; this.name=name; }
    }
    static List<Page> scan(ContentResolver r, Uri tree) throws Exception {
        ArrayList<Page> out = new ArrayList<>();
        walk(r,tree,DocumentsContract.getTreeDocumentId(tree),"",out,0);
        Collections.sort(out,(a,b)->natural(a.name,b.name));
        return out;
    }
    private static void walk(ContentResolver r, Uri tree, String id, String prefix, List<Page> out, int depth) throws Exception {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if(depth>24) throw new IllegalArgumentException("文件夹层级太深");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        List<String[]> dirs=new ArrayList<>();
        try(Cursor c=r.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
            if(c==null) throw new java.io.IOException("无法读取文件夹，请重新选择");
            while(c.moveToNext()) {
                String child=c.getString(0),name=c.getString(1),mime=c.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(new String[]{child,prefix+name+"/"});
                else if(isImage(name,mime)) out.add(new Page(DocumentsContract.buildDocumentUriUsingTree(tree,child).toString(),prefix+name));
                if(out.size()>30000) throw new java.io.IOException("图片过多，请选择更小的文件夹");
            }
        }
        for(String[] d:dirs) walk(r,tree,d[0],d[1],out,depth+1);
    }
    static boolean isImage(String name,String mime) {
        String n=name.toLowerCase(java.util.Locale.ROOT);
        return (mime!=null && mime.startsWith("image/")) || n.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif|avif)$");
    }
    static int natural(String a,String b) {
        int x=0,y=0;
        while(x<a.length()&&y<b.length()) {
            char ac=Character.toLowerCase(a.charAt(x)),bc=Character.toLowerCase(b.charAt(y));
            if(Character.isDigit(ac)&&Character.isDigit(bc)) {
                int xe=x,ye=y; while(xe<a.length()&&Character.isDigit(a.charAt(xe)))xe++; while(ye<b.length()&&Character.isDigit(b.charAt(ye)))ye++;
                String an=a.substring(x,xe).replaceFirst("^0+(?!$)",""),bn=b.substring(y,ye).replaceFirst("^0+(?!$)","");
                int n=Integer.compare(an.length(),bn.length()); if(n==0)n=an.compareTo(bn); if(n!=0)return n; x=xe;y=ye;
            } else { if(ac!=bc)return ac-bc; x++;y++; }
        }
        return Integer.compare(a.length(),b.length());
    }
}

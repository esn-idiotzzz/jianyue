package com.joe.jmlite;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic-only transport; never packaged in the application APK. */
public final class ReaderSyntheticHttps implements URLStreamHandlerFactory {
    final Map<String,AtomicInteger> imageRequests=new ConcurrentHashMap<>();
    final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
    final byte[] jpeg;
    volatile long delayMs=60;
    volatile long metadataDelayMs=0;
    volatile int pageCount=20;

    public ReaderSyntheticHttps() throws Exception {
        Bitmap image=Bitmap.createBitmap(40,60,Bitmap.Config.RGB_565);image.eraseColor(Color.rgb(52,120,246));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,90,bytes);image.recycle();jpeg=bytes.toByteArray();
    }
    @Override public URLStreamHandler createURLStreamHandler(String protocol){
        if(!"https".equals(protocol))return null;
        return new URLStreamHandler(){@Override protected URLConnection openConnection(URL url){return new HttpURLConnection(url){
            byte[] body;boolean ready,opened;volatile boolean closed;
            @Override public void connect(){connected=true;}
            @Override public void disconnect(){closed=true;connected=false;closeImage();}
            @Override public boolean usingProxy(){return false;}
            private synchronized void closeImage(){if(opened){opened=false;active.decrementAndGet();}}
            @Override public int getResponseCode() throws IOException {
                if(ready)return 200;ready=true;String path=url.getPath();
                try{
                    if(path.startsWith("/media/photos/")){imageRequests.computeIfAbsent(path,k->new AtomicInteger()).incrementAndGet();body=jpeg;return 200;}
                    if(path.endsWith(".txt"))throw new IOException("synthetic discovery is disabled");
                    if(path.equals("/chapter_view_template")){body="var scramble_id = 220980;".getBytes(StandardCharsets.UTF_8);return 200;}
                    if(path.equals("/album")){long until=System.currentTimeMillis()+metadataDelayMs;while(System.currentTimeMillis()<until){if(closed)throw new InterruptedIOException();try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}}
                    String params=getRequestProperty("tokenparam"),ts=params==null?"":params.split(",")[0];
                    JSONObject data;
                    if(path.equals("/setting"))data=new JSONObject().put("jm3_version","2.1.12");
                    else if(path.equals("/album"))data=new JSONObject().put("id",url.getQuery().substring(3)).put("name","Synthetic framework album").put("series_id","0").put("series",new JSONArray().put(new JSONObject().put("id","127").put("sort",1)));
                    else if(path.equals("/chapter")){JSONArray images=new JSONArray();for(int i=1;i<=pageCount;i++)images.put(String.format(Locale.ROOT,"%05d.jpg",i));data=new JSONObject().put("id",url.getQuery().substring(3)).put("name","Synthetic chapter").put("images",images);}
                    else throw new IOException("blocked synthetic path "+path);
                    Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(JmApi.md5(ts+"185Hcomic3PAPP7R").getBytes(StandardCharsets.US_ASCII),"AES"));
                    body=new JSONObject().put("code",200).put("data",Base64.encodeToString(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).toString().getBytes(StandardCharsets.UTF_8);return 200;
                }catch(Exception e){throw new IOException(e);}
            }
            @Override public Map<String,List<String>> getHeaderFields(){return url.getPath().equals("/setting")?Collections.singletonMap("Set-Cookie",Collections.singletonList("SID=synthetic; Path=/; Secure")):Collections.emptyMap();}
            @Override public long getContentLengthLong(){return body==null?-1:body.length;}
            @Override public InputStream getInputStream() throws IOException {
                getResponseCode();if(!url.getPath().startsWith("/media/photos/"))return new ByteArrayInputStream(body);
                synchronized(this){opened=true;}int now=active.incrementAndGet();peak.updateAndGet(old->Math.max(old,now));
                return new FilterInputStream(new ByteArrayInputStream(body)){
                    boolean delayed;
                    @Override public int read(byte[] b,int offset,int count)throws IOException{if(!delayed){delayed=true;long until=System.currentTimeMillis()+delayMs;while(System.currentTimeMillis()<until){if(closed)throw new InterruptedIOException();try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}}if(closed)throw new InterruptedIOException();return super.read(b,offset,count);}
                    @Override public void close()throws IOException{try{super.close();}finally{closeImage();}}
                };
            }
        };}};
    }
}

package com.joe.jmlite;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;

/** Per-reader disk cache. It holds no Activity, listener, or decoded Bitmap. */
final class OnlineSession implements AutoCloseable {
    static final class Page {
        final String chapterId, filename;
        final int scrambleId;
        Page(String chapterId,String filename,int scrambleId){this.chapterId=chapterId;this.filename=filename;this.scrambleId=scrambleId;}
    }
    private static final Set<String> ACTIVE=new HashSet<>();
    private static final int AHEAD=12;private static final int BACK=4;
    private static final int MAX_JOBS=22;
    private static final int FAR_RADIUS=14;
    private final File directory;
    private final List<Page> pages;
    private final JmApi source;
    private final List<JmApi> clients=new ArrayList<>();
    private final Map<Integer,Job> jobs=new HashMap<>();
    private final Set<Integer> failed=new HashSet<>();
    private final Map<Integer,Integer> readerDemands=new HashMap<>();
    private final LinkedBlockingDeque<Runnable> queue=new LinkedBlockingDeque<>(AHEAD+BACK);
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(6,6,0,TimeUnit.MILLISECONDS,queue,r->new Thread(r,"jm-online-cache"));
    private boolean closed;
    private boolean all;
    private int start=0,end=0,focus=0;

    OnlineSession(Context context,JmApi source,List<Page> pages)throws Exception{
        this.source=source;this.pages=new ArrayList<>(pages);
        File root=new File(context.getApplicationContext().getCacheDir(),"online");
        synchronized(ACTIVE){
            if(!root.isDirectory()&&!root.mkdirs()&&!root.isDirectory())throw new IOException("无法创建在线缓存");
            File[] old=root.listFiles();
            if(old!=null)for(File file:old)if(file.isDirectory()&&!ACTIVE.contains(file.getCanonicalPath()))deleteTree(file,root.getCanonicalPath());
            directory=new File(root,UUID.randomUUID().toString());
            if(!directory.mkdirs())throw new IOException("无法创建在线缓存");
            ACTIVE.add(directory.getCanonicalPath());
        }
    }
    File directory(){return directory;}
    synchronized File cachedFile(int index){
        File file=target(index);return file.isFile()&&file.length()>0?file:null;
    }
    private File target(int index){return new File(directory,String.format(Locale.ROOT,"%06d.jpg",index+1));}

    synchronized void prefetch(int index){
        if(closed||pages.isEmpty())return;
        int next=Math.max(0,Math.min(index,pages.size()-1));focus=next;
        if(all){
            // A single-chapter reader keeps the full chapter queued. Moving the focus only
            // re-prioritizes work; it must not throw away images that are still being fetched.
            cancelFarQueued();
            fillWindow();
            return;
        }
        start=Math.max(0,next-BACK);end=Math.min(pages.size(),start+BACK+AHEAD);
        ArrayList<Job> removed=new ArrayList<>();
        for(Map.Entry<Integer,Job> entry:jobs.entrySet())if((entry.getKey()<start||entry.getKey()>=end)&&!readerDemands.containsKey(entry.getKey())){
            Job job=entry.getValue();if(workers.remove(job)||job.isDone())removed.add(job);
        }
        for(Job job:removed){jobs.remove(job.index);job.cancel(false);}
        fillWindow();
    }

    /** Loads the whole chapter, starting at the current position and spreading outwards. */
    synchronized void prefetchAll(){prefetchAll(focus);}
    synchronized void prefetchAll(int index){
        if(closed||pages.isEmpty())return;
        all=true;start=0;end=pages.size();
        focus=Math.max(0,Math.min(index,pages.size()-1));
        cancelFarQueued();
        fillWindow();
    }

    /** Drops queued prefetches far from the current page so the visible neighborhood stays first. */
    private void cancelFarQueued(){
        for(Iterator<Map.Entry<Integer,Job>> it=jobs.entrySet().iterator();it.hasNext();){
            Map.Entry<Integer,Job> entry=it.next();int index=entry.getKey();
            if(Math.abs(index-focus)<=FAR_RADIUS||readerDemands.containsKey(index))continue;
            Job job=entry.getValue();
            if(workers.remove(job)){it.remove();job.cancel(false);}
        }
    }

    private void fillWindow(){
        if(closed)return;
        // Current position first, then both directions. In full-chapter mode this keeps at most
        // MAX_JOBS queued and lets completed jobs expose the next-nearest pages, so a very large
        // album never creates one thread/task per image.
        if(!all&&start==0&&cachedFile(0)==null){enqueue(0,true);return;}
        int center=Math.max(start,Math.min(focus,end-1));
        enqueue(center,true);
        int reach=Math.max(center-start,end-1-center);
        for(int distance=1;distance<=reach&&jobs.size()<MAX_JOBS;distance++){
            if(center-distance>=start)enqueue(center-distance,false);
            if(center+distance<end&&jobs.size()<MAX_JOBS)enqueue(center+distance,false);
        }
    }
    private Job enqueue(int index,boolean priority){
        if(closed||failed.contains(index)||index<0||index>=pages.size()||cachedFile(index)!=null)return null;
        Job job=jobs.get(index);
        if(job!=null){if(priority&&workers.remove(job))queue.offerFirst(job);return job;}
        job=new Job(index);jobs.put(index,job);
        try{workers.execute(job);if(priority&&workers.remove(job))queue.offerFirst(job);}
        catch(RejectedExecutionException e){jobs.remove(index);job.cancel(false);}
        return job;
    }
    File file(int index)throws Exception{
        Job first;
        synchronized(this){
            if(closed)throw new InterruptedIOException("阅读已关闭");
            File cached=cachedFile(index);if(cached!=null)return cached;
            first=!all&&index>0&&start==0&&cachedFile(0)==null?enqueue(0,true):null;
        }
        if(first!=null)await(first);
        Job job;
        synchronized(this){
            if(closed)throw new InterruptedIOException("阅读已关闭");
            File cached=cachedFile(index);if(cached!=null)return cached;
            if(index<start||index>=end)throw new InterruptedIOException("阅读位置已改变");
            job=enqueue(index,true);
        }
        if(job==null)throw new IOException("图片加载未开始，请重试");
        return await(job);
    }
    /** Mihon may request neighboring holders concurrently, independently of the lookahead window. */
    File fileForReader(int index)throws Exception{
        Job first;
        synchronized(this){
            if(closed)throw new InterruptedIOException("阅读已关闭");
            first=!all&&index>0&&start==0&&cachedFile(0)==null?enqueue(0,true):null;
        }
        if(first!=null)await(first);
        Job job=null;boolean demanded=false;
        try{
            synchronized(this){
                if(closed)throw new InterruptedIOException("阅读已关闭");
                if(index<0||index>=pages.size())throw new IOException("图片页码无效");
                File cached=cachedFile(index);if(cached!=null)return cached;
                readerDemands.put(index,readerDemands.getOrDefault(index,0)+1);demanded=true;
                // Replace an unused prefetch slot, or wait interruptibly for bounded capacity.
                while(!jobs.containsKey(index)&&queue.remainingCapacity()==0){
                    Iterator<Runnable> pending=queue.descendingIterator();
                    while(pending.hasNext()){
                        Runnable candidate=pending.next();
                        if(candidate instanceof OnlineSession.Job){Job queued=(Job)candidate;if(!readerDemands.containsKey(queued.index)&&workers.remove(queued)){jobs.remove(queued.index,queued);queued.cancel(false);break;}}
                    }
                    if(queue.remainingCapacity()==0){
                        try{wait(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("图片加载已取消");}
                        if(closed)throw new InterruptedIOException("阅读已关闭");
                    }
                }
                cached=cachedFile(index);if(cached!=null)return cached;
                job=enqueue(index,true);
                if(job==null)throw new IOException("图片加载未开始，请重试");
            }
            return await(job);
        }finally{if(demanded)synchronized(this){
            int count=readerDemands.getOrDefault(index,0);if(count<=1)readerDemands.remove(index);else readerDemands.put(index,count-1);
            if(!readerDemands.containsKey(index)&&Thread.currentThread().isInterrupted()&&job!=null&&!job.isDone()&&workers.remove(job)){jobs.remove(index,job);job.cancel(false);}
            notifyAll();
        }}
    }
    private File await(Job job)throws Exception{
        try{return job.get();}
        catch(CancellationException e){throw new InterruptedIOException("图片加载已取消");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("图片加载已取消");}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("图片加载失败",cause);}
    }
    synchronized void retry(int index){
        failed.remove(index);
        Job job=jobs.get(index);if(job!=null&&job.isDone()){jobs.remove(index);job.cancel(false);}
        if(!closed){if(all){focus=index;}else{start=index;end=Math.min(pages.size(),index+AHEAD);}enqueue(index,true);fillWindow();}
    }
    private File download(int index)throws Exception{
        JmApi client=source.fork();
        synchronized(this){if(closed){client.cancel();throw new InterruptedIOException("阅读已关闭");}clients.add(client);}
        try{Page page=pages.get(index);File file=target(index);client.image(page.chapterId,page.filename,page.scrambleId,file);return file;}
        finally{synchronized(this){clients.remove(client);}}
    }
    private final class Job extends FutureTask<File>{
        final int index;
        Job(int index){super(()->download(index));this.index=index;}
        @Override protected void done(){
            boolean saved=false;try{saved=!isCancelled()&&get().isFile();}catch(Exception ignored){}
            synchronized(OnlineSession.this){
                jobs.remove(index,this);
                if(!saved&&!isCancelled()&&!closed)failed.add(index);
                if(saved&&!closed)fillWindow();
                OnlineSession.this.notifyAll();
            }
        }
    }
    @Override public void close(){
        ArrayList<JmApi> cancel;
        synchronized(this){
            if(closed)return;closed=true;
            cancel=new ArrayList<>(clients);
            ArrayList<Job> pending=new ArrayList<>(jobs.values());jobs.clear();readerDemands.clear();queue.clear();failed.clear();
            for(Job job:pending)job.cancel(true);
            workers.shutdownNow();
            notifyAll();
        }
        source.cancel();for(JmApi client:cancel)client.cancel();
        final ThreadPoolExecutor pool=workers;final File folder=directory;
        new Thread(()->{
            try{
                while(!pool.awaitTermination(35,TimeUnit.SECONDS)){}
                synchronized(ACTIVE){try{deleteTree(folder,folder.getParentFile().getCanonicalPath());}finally{ACTIVE.remove(folder.getCanonicalPath());}}
            }catch(Exception ignored){/* A later session retries removal of any closed orphan. */}
        },"jm-online-cleanup").start();
    }
    private static void deleteTree(File file,String root)throws IOException{
        String path=file.getCanonicalPath();
        if(!path.startsWith(root+File.separator))throw new IOException("在线缓存路径无效");
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child,root);
        if(file.exists()&&!file.delete())throw new IOException("无法清理在线缓存");
    }
}

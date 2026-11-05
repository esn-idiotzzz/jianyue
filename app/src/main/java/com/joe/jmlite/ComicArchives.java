package com.joe.jmlite;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipMethod;
import java.io.*;
import java.util.*;

/**
 * Stream archive entries into flat, app-owned page files; archive paths are never extracted.
 * Junrar is used only for extraction. Its code may not be used to develop a RAR-compatible archiver.
 */
final class ComicArchives {
    private static final long MIB=1024*1024,MAX_ARCHIVE=2L*1024*MIB,MAX_PAGE=64*MIB,MAX_TOTAL=4L*1024*MIB;
    private static final int MAX_ENTRIES=30000;
    private static final int MEMORY_KIB=(int)Math.max(4096,Math.min(65536,Runtime.getRuntime().maxMemory()/4096));

    static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("操作已取消");}
    static void copyArchive(InputStream in,OutputStream out,File disk)throws IOException{
        byte[] buffer=new byte[64*1024];long written=0,limit=Math.min(MAX_ARCHIVE,Math.max(0,disk.getUsableSpace()-64*MIB));
        for(int n;(n=in.read(buffer))!=-1;){interrupted();written+=n;if(written>limit)throw new IOException("压缩包过大或存储空间不足");out.write(buffer,0,n);}
    }
    static int extract(File source,File target,String filename)throws Exception{
        if(!target.mkdir())throw new IOException("无法创建图片目录");
        byte[] signature=new byte[512];int length;try(InputStream in=new FileInputStream(source)){length=in.read(signature);}
        Budget budget=new Budget(target);
        if(length>=4&&signature[0]=='P'&&signature[1]=='K')zip(source,budget);
        else if(length>=6&&(signature[0]&255)==0x37&&(signature[1]&255)==0x7a&&(signature[2]&255)==0xbc&&(signature[3]&255)==0xaf&&(signature[4]&255)==0x27&&(signature[5]&255)==0x1c)seven(source,budget);
        else if(length>=7&&signature[0]=='R'&&signature[1]=='a'&&signature[2]=='r'&&signature[3]=='!')rar(source,budget);
        else if(length>=265&&signature[257]=='u'&&signature[258]=='s'&&signature[259]=='t'&&signature[260]=='a'&&signature[261]=='r')tar(source,budget);
        else throw new IOException("支持 CBZ/ZIP、CBR/RAR、CB7/7Z、CBT/TAR，暂不支持此文件或加密压缩包");
        if(budget.pages.isEmpty())throw new IOException("压缩包中没有可读取的图片");
        budget.pages.sort((a,b)->LocalImages.natural(a.name,b.name));
        for(int n=0;n<budget.pages.size();n++){interrupted();Page page=budget.pages.get(n);File to=new File(target,String.format(Locale.ROOT,"%06d.%s",n+1,extension(page.name)));if(!page.file.renameTo(to))throw new IOException("无法保存图片顺序");}
        return budget.pages.size();
    }
    private static void zip(File source,Budget budget)throws Exception{
        zipDirectoryLimit(source);
        try(ZipFile zip=ZipFile.builder().setFile(source).get()){
            Enumeration<ZipArchiveEntry> entries=zip.getEntries();
            while(entries.hasMoreElements()){
                interrupted();ZipArchiveEntry entry=entries.nextElement();budget.entry(entry.getName(),entry.getSize());
                if(entry.isUnixSymlink())throw new IOException("不支持包含链接的压缩包");
                if(entry.isDirectory()||!image(entry.getName()))continue;
                if(entry.getMethod()==ZipMethod.ZSTD.getCode()||entry.getMethod()==ZipMethod.ZSTD_DEPRECATED.getCode())throw new IOException("暂不支持 Zstandard 压缩的 ZIP，请重新打包为普通 ZIP/CBZ");
                if(!zip.canReadEntryData(entry))throw new IOException("不支持加密或此压缩方式");
                if(entry.getSize()>16*MIB&&entry.getCompressedSize()>0&&entry.getSize()/entry.getCompressedSize()>1000)throw new IOException("图片展开比例异常");
                try(InputStream in=zip.getInputStream(entry)){budget.save(in,entry.getName());}
            }
        }
    }
    private static void zipDirectoryLimit(File source)throws IOException{
        // Reject excessive central directories before ZipFile allocates its entry objects.
        try(RandomAccessFile in=new RandomAccessFile(source,"r")){
            int size=(int)Math.min(in.length(),65557);byte[] tail=new byte[size];in.seek(in.length()-size);in.readFully(tail);
            for(int n=size-22;n>=0;n--)if(tail[n]==0x50&&tail[n+1]==0x4b&&tail[n+2]==0x05&&tail[n+3]==0x06){
                int comment=(tail[n+20]&255)|((tail[n+21]&255)<<8);if(n+22+comment!=size)continue;
                int entries=(tail[n+10]&255)|((tail[n+11]&255)<<8);
                if(entries>MAX_ENTRIES)throw new IOException("压缩包条目过多");return;
            }
            throw new IOException("ZIP 目录已损坏");
        }
    }
    private static void seven(File source,Budget budget)throws Exception{
        try(SevenZFile seven=SevenZFile.builder().setFile(source).setMaxMemoryLimitKiB(MEMORY_KIB).get()){
            SevenZArchiveEntry entry;
            while((entry=seven.getNextEntry())!=null){
                interrupted();budget.entry(entry.getName(),entry.getSize());
                if(entry.isDirectory()||!image(entry.getName()))continue;
                final SevenZFile archive=seven;
                budget.save(new InputStream(){public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int off,int len)throws IOException{return archive.read(b,off,len);}},entry.getName());
            }
        }
    }
    private static void rar(File source,Budget budget)throws Exception{
        try(Archive archive=new Archive(source,ArchiveOptions.builder().maxDictionarySize((long)MEMORY_KIB*1024).build())){
            if(archive.isPasswordProtected())throw new IOException("暂不支持加密压缩包");
            FileHeader entry;
            while((entry=archive.nextFileHeader())!=null){
                interrupted();budget.entry(entry.getFileName(),entry.getFullUnpackSize());
                if(entry.getRedirection()!=null)throw new IOException("不支持包含链接的压缩包");
                if(entry.isDirectory()||!image(entry.getFileName()))continue;
                if(entry.isEncrypted()||entry.isSplitBefore()||entry.isSplitAfter())throw new IOException("暂不支持加密或分卷压缩包");
                final String name=entry.getFileName();File page=budget.newPage();
                try(OutputStream out=budget.output(page)){archive.extractFile(entry,out);}
                budget.complete(page,name);
            }
            if(archive.hasBrokenHeaders())throw new IOException("压缩包目录已损坏");
        }
    }
    private static void tar(File source,Budget budget)throws Exception{
        try(TarArchiveInputStream tar=new TarArchiveInputStream(new BufferedInputStream(new FileInputStream(source)))){
            TarArchiveEntry entry;
            while((entry=tar.getNextEntry())!=null){
                interrupted();budget.entry(entry.getName(),entry.getSize());
                if(entry.isLink()||entry.isSymbolicLink()||entry.isSparse())throw new IOException("不支持包含链接或稀疏文件的压缩包");
                if(entry.isDirectory()||!image(entry.getName()))continue;
                if(!entry.isFile()||!tar.canReadEntryData(entry))throw new IOException("不支持此 TAR 条目");
                budget.save(tar,entry.getName());
            }
        }
    }
    private static boolean image(String name){String normalized=name.replace('\\','/');return !normalized.startsWith("__MACOSX/")&&!normalized.substring(normalized.lastIndexOf('/')+1).startsWith("._")&&LocalImages.isImage(name,null);}
    static String extension(String name){int dot=name.lastIndexOf('.');String value=dot<0?"jpg":name.substring(dot+1).toLowerCase(Locale.ROOT);return value.matches("jpg|jpeg|png|webp|gif|bmp|heic|heif|avif")?value:"jpg";}
    private static void path(String name)throws IOException{
        if(name==null||name.indexOf('\0')>=0||name.length()>4096)throw new IOException("压缩包内路径无效");
        String value=name.replace('\\','/');
        if(value.startsWith("/")||value.matches("^[A-Za-z]:.*"))throw new IOException("压缩包内路径无效");
        for(String part:value.split("/"))if("..".equals(part))throw new IOException("压缩包内路径无效");
    }
    private static final class Page{final File file;final String name;Page(File file,String name){this.file=file;this.name=name;}}
    private static final class Budget{
        final File target;final ArrayList<Page> pages=new ArrayList<>();final long limit;int entries;long total,declared;
        Budget(File target){this.target=target;limit=Math.min(MAX_TOTAL,Math.max(0,target.getUsableSpace()-64*MIB));}
        void entry(String name,long size)throws IOException{path(name);if(++entries>MAX_ENTRIES)throw new IOException("压缩包条目过多");if(size>0){if(size>MAX_TOTAL-declared)throw new IOException("压缩包展开内容过大");declared+=size;}if(image(name)&&size>MAX_PAGE)throw new IOException("单张图片超过 64 MB");}
        File newPage(){return new File(target,"page-"+pages.size()+".tmp");}
        void save(InputStream in,String name)throws IOException{File file=newPage();try(OutputStream out=output(file)){byte[] buffer=new byte[64*1024];for(int n;(n=in.read(buffer))!=-1;){interrupted();out.write(buffer,0,n);}}complete(file,name);}
        OutputStream output(File file)throws IOException{return new FilterOutputStream(new BufferedOutputStream(new FileOutputStream(file))){long size;@Override public void write(int value)throws IOException{byte[] one={(byte)value};write(one,0,1);}@Override public void write(byte[] b,int off,int len)throws IOException{interrupted();size+=len;total+=len;if(size>MAX_PAGE||total>limit)throw new IOException("图片过大或存储空间不足");out.write(b,off,len);}};}
        void complete(File file,String name)throws IOException{if(file.length()==0)throw new IOException("压缩包包含空图片");pages.add(new Page(file,name));}
    }
}

package tuning;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.*;
public final class FilesIO {
    private FilesIO() {}
    public static <T>T read(Path p,Class<T> type)throws IOException{return JSON.parseObject(new String(Files.readAllBytes(p),StandardCharsets.UTF_8),type);}
    public static JSONObject object(Path p)throws IOException{return read(p,JSONObject.class);}
    public static void write(Path p,Object data)throws IOException {
        Files.createDirectories(p.toAbsolutePath().getParent());
        // Atomic publication prevents a half-written trial from being resumed as successful.
        Path tmp=p.resolveSibling(p.getFileName()+".partial");
        Files.write(tmp,JSON.toJSONString(data,JSONWriter.Feature.PrettyFormat,JSONWriter.Feature.WriteNulls).getBytes(StandardCharsets.UTF_8));
        try{Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}
    }
    public static String hash(String value){return digest(value.getBytes(StandardCharsets.UTF_8));}
    public static String digest(byte[] bytes){try{MessageDigest md=MessageDigest.getInstance("SHA-256");return hex(md.digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] b){StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();}
    public static List<Path> javaFiles(Path root)throws IOException {
        try(Stream<Path> stream=Files.walk(root)){return stream.filter(p->Files.isRegularFile(p)&&p.toString().endsWith(".java"))
            .sorted(Comparator.comparing(p->root.relativize(p).toString())).collect(Collectors.toList());}
    }
    public static String inputHash(Path input)throws IOException {
        if(!Files.exists(input))throw new FileNotFoundException("Missing input: "+input);
        if(!Files.isDirectory(input))return digest(Files.readAllBytes(input));
        try{MessageDigest md=MessageDigest.getInstance("SHA-256");List<Path> files=javaFiles(input);
            if(files.isEmpty())throw new IOException("No Java source files: "+input);
            for(Path p:files){byte[] name=input.relativize(p).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8);
                md.update(name);md.update((byte)0);md.update(Files.readAllBytes(p));md.update((byte)0);}
            return hex(md.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}

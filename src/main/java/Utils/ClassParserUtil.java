package Utils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class  ClassParserUtil {

    public static HashMap<String, List<String>> getfromdiagram(String projectPath) throws ClassNotFoundException {
        // String projectPath = "C:/Users/Dell/Desktop/github案例/light-reading-cloud";
        //这里统计了所有的类，代码之前重构过，懒得改了
        HashMap<String, List<String>> classMap = generateClassMap(projectPath);

        // 输出结果
        for (Map.Entry<String, List<String>> entry : classMap.entrySet()) {
            System.out.println("Module: " + entry.getKey());
            System.out.println("Classes: " + entry.getValue());
            System.out.println();
        }
        return classMap;
    }


    private static int countMicroserviceModules(String projectPath) {
        File projectDir = new File(projectPath);
        return countMicroserviceModulesRecursive(projectDir);
    }

    private static int countMicroserviceModulesRecursive(File directory) {
        int count = 0;
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // 如果目录名包含 "src/main/java"，则认为是一个微服务模块
                    if (file.getAbsolutePath().contains("src/main/java")) {
                        count++;
                        continue;
                    }
                    count += countMicroserviceModulesRecursive(file); // 递归遍历子目录
                }
            }
        }

        return count;
    }

    private static HashMap<String, List<String>> generateClassMap(String projectPath) throws ClassNotFoundException {
        HashMap<String, List<String>> classMap = new HashMap<>();
        File projectDirectory = new File(projectPath);

        //int num = countMicroserviceModules(projectPath);

        if (projectDirectory.exists() && projectDirectory.isDirectory()) {
            if(false){
                for (File module : projectDirectory.listFiles()) {
                    if (module.isDirectory()) {
                        String moduleName = module.getName();
                        List<String> classes = getAllClasses(module);
                        classMap.put(moduleName, classes);
                    }
                }
            }
            else{
                if (projectDirectory.isDirectory()) {
                    String moduleName = projectDirectory.getName();
                    List<String> classes = getAllClasses(projectDirectory);
                    classMap.put(moduleName, classes);
                }
            }
        } else {
            System.err.println("Invalid project directory path: " + projectPath);
        }

        return classMap;
    }

    private static List<String> getAllClasses(File directory) throws ClassNotFoundException {
        List<String> classes = new ArrayList<>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                // 递归调用，更新包名
                classes.addAll(getAllClasses(file));
            } else if (file.getName().endsWith(".java")) {
                // 获取类名并添加到列表中
                classes.add(file.getAbsolutePath());
            }
         }

        return classes;
    }

}




/*
 class FileClassLoader extends ClassLoader {
    private final File directory;
    private String ModName;
    public FileClassLoader(File directory,String Mname) {
        this.directory = directory;
        ModName = Mname;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            //StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            File file = new File(name);
            //Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(file);
            // 设置编译参数
*//*
            String[] compilerOptions = new String[]{"-d", directory.getPath()};
            String[] strings = {"-d", ".",file.getPath()};
            System.out.println("准备编译类:"+file.getPath());
            int i =  compiler.run(null, null, null,strings);
            if (i == 0 ) {
                System.out.println("成功");
            }else {
                System.out.println("错误");
            }
*//*

*//*              JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null,  Arrays.asList(compilerOptions), null, compilationUnits);

            boolean success = task.call();*//*
*//*
            if (!success) {
                throw new RuntimeException("Compilation failed.");
            }
*//*          System.out.println(name);
            System.out.println("动态加载类："+file.getPath());
*//*

            if (!file.getPath().contains("classes")){
                System.out.println("not contains!");
                return Class.forName(className);
            }
*//*

            System.out.println("ModeName is " + ModName);

            if (!file.getPath().contains("classes") && file.getPath().contains("cn")){
                //System.out.println("change the dire");
                String tempName = "G:\\light-reading-cloud\\"+ModName+"\\target\\classes\\"+name.replace(".", "\\")+".class";
                //System.out.println(tempName);
                file = new File(tempName);
            }
            else if(!file.getPath().contains("classes") ){
               // System.out.println("hahahahha");
                String tempName = name.replace(".", "\\")+".class";
                file = new File(tempName);
            }

            // 从文件中加载类的字节码
            byte[] bytes = Files.readAllBytes(file.toPath());
            name = extractClassName(file.getPath());
            System.out.println("name名称："+name);
            // 使用 defineClass 方法加载类
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException("Class not found: " + name, e);
        }
    }
}

*/
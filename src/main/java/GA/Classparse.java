package GA;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Classparse {

    public static  HashMap<String, List<String>> getfromdiagram(String projectPath) {
       // String projectPath = "C:/Users/Dell/Desktop/github案例/light-reading-cloud-reading-1.0";
        HashMap<String, List<String>> classMap = generateClassMap(projectPath);

        // 输出结果
        for (Map.Entry<String, List<String>> entry : classMap.entrySet()) {
            System.out.println("Module: " + entry.getKey());
            System.out.println("Classes: " + entry.getValue());
            System.out.println();
        }
        return classMap;
    }

    private static HashMap<String, List<String>> generateClassMap(String projectPath) {
        HashMap<String, List<String>> classMap = new HashMap<>();
        File projectDirectory = new File(projectPath);

        if (projectDirectory.exists() && projectDirectory.isDirectory()) {
            for (File module : projectDirectory.listFiles()) {
                if (module.isDirectory()) {
                    String moduleName = module.getName();
                    List<String> classes = getAllClasses(module);
                    classMap.put(moduleName, classes);
                }
            }
        } else {
            System.err.println("Invalid project directory path: " + projectPath);
        }

        return classMap;
    }

    private static List<String> getAllClasses(File directory) {
        List<String> classes = new ArrayList<>();
        Pattern classPattern = Pattern.compile("\\bclass\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\b");

        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                // 递归处理子目录
                classes.addAll(getAllClasses(file));
            } else if (file.isFile() && file.getName().endsWith(".java")) {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    Matcher matcher = classPattern.matcher(content);
                    while (matcher.find()) {
                        classes.add(matcher.group(1));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return classes;
    }
}

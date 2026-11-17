import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClassNameExtractor {

    public static String getFullyQualifiedName(String filePath) {
        // 将文件路径转换为Path对象
        Path path = Paths.get(filePath);

        // 获取文件名
        String fileName = path.getFileName().toString();

        // 获取包路径
        Path packagePath = path.getParent();
        String packageName = packagePath.toString().replace(File.separator, ".");

        // 提取类名
        String className = fileName.substring(0, fileName.lastIndexOf('.'));

        // 组合包名和类名
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    public static void main(String[] args) {
        String filePath = "C:\\path\\to\\your\\project\\src\\com\\example\\YourClass.java";
        String fullyQualifiedName = getFullyQualifiedName(filePath);
        System.out.println("Fully Qualified Name: " + fullyQualifiedName);
    }
}

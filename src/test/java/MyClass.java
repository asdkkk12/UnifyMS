import org.junit.Test;

import java.lang.reflect.Method;



public class MyClass {
    public void method1() {
        // Some implementation
    }

    public void method2() {
        // Some implementation
    }
    @Test
    public static void main(String[] args) {
        Class<?> myClass = MyClass.class;

        // 获取类中声明的所有公共方法
        Method[] methods = myClass.getMethods();

        // 遍历并打印方法信息
        for (Method method : methods) {
            System.out.println("Method Name: " + method.getName());
            System.out.println("Return Type: " + method.getReturnType().getName());
            // 可以添加更多的方法信息，如参数类型等
            System.out.println("-----------------------------");
        }
    }
}
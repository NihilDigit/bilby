import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 逐个加载发行包里的类,让 JVM 校验字节码,有 VerifyError 就以非零退出。
 *
 * 为的是只在打包版才出现的崩溃:字节码经打包流程改写后可能校验不过(ProGuard 曾把
 * PlayerShell 的栈帧算错),而 gradle run 不经过打包,开发时发现不了,装上之后一进那一页
 * 才崩。依赖也查:Bilby 自己的 jar 裁剪后换回了原件,依赖却是 ProGuard 重算过栈帧的。
 * 全部一万多个类几秒钟查完。
 *
 * 用法(单文件源码直接运行): java -Xverify:all desktop/package/VerifyClasses.java <应用目录>/app
 */
public class VerifyClasses {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        List<URL> urls = new ArrayList<>();
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".jar")) urls.add(f.toURI().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        int checked = 0;
        int failed = 0;
        for (URL url : urls) {
            try (JarFile jar = new JarFile(new File(url.toURI()))) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.startsWith("META-INF/")) continue;
                    checked++;
                    String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    try {
                        // 取方法表会让类完成链接,校验发生在链接时;只 forName 不初始化是不校验的。
                        Class.forName(className, false, loader).getDeclaredMethods();
                    } catch (VerifyError error) {
                        failed++;
                        System.out.println("VerifyError " + className + ": " + error.getMessage().lines().findFirst().orElse(""));
                    } catch (Throwable ignored) {
                        // 缺某个只在特定平台才有的依赖之类,与字节码是否合法无关。
                    }
                }
            }
        }
        System.out.println("checked " + checked + " classes, " + failed + " failed verification");
        if (checked == 0 || failed > 0) System.exit(1);
    }
}

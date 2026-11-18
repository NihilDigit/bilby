import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Spawns a child JVM inside an isolated hidden Win32 virtual desktop ("winsta0\\<DesktopName>")
 * via CreateDesktopW + CreateProcessW without ever calling SwitchDesktop, so window creation,
 * resizes, focus changes, and AWT/Skia rendering never disturb the active user desktop.
 *
 * Also provisions a temporary sandboxed APPDATA directory in %TEMP% containing only cloned
 * DataStore preferences (`datastore/*.preferences_pb`), never touching live `bilby.db`, and
 * unconditionally wipes the sandbox in a finally block on exit so credentials never linger.
 *
 * Runs directly on JDK 25+ via single-file source launch (`java --enable-native-access=ALL-UNNAMED ...`).
 */
public final class HiddenDesktopLauncher {
    private static final int GENERIC_ALL = 0x10000000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int WAIT_OBJECT_0 = 0x00000000;
    private static final int STARTUPINFOW_SIZE = 104;
    private static final int PROCESS_INFORMATION_SIZE = 24;

    private static MemorySegment wideString(Arena arena, String s) {
        byte[] utf16 = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length, 2);
        MemorySegment.copy(utf16, 0, seg, JAVA_BYTE, 0, utf16.length);
        return seg;
    }

    private static MemorySegment buildEnvironmentBlock(
        Arena arena,
        String appDataOverride,
        Path childLogFile
    ) {
        TreeMap<String, String> env = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.putAll(System.getenv());
        env.put("APPDATA", appDataOverride);
        env.put("BILBY_SHOT_LOG", childLogFile.toAbsolutePath().toString());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().isEmpty()) continue;
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\0');
        }
        sb.append('\0');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(bytes.length, 2);
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static void copyDirectoryIfPresent(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) return;
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteDirectoryTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                if (!Files.exists(root)) return;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void populateSandboxProfile(
        Path sourceBilbyDir,
        Path targetBilbyDir,
        boolean copyCoilCache
    ) throws IOException {
        Path targetDatastore = targetBilbyDir.resolve("datastore");
        Files.createDirectories(targetDatastore);
        if (sourceBilbyDir == null || !Files.isDirectory(sourceBilbyDir)) {
            return;
        }
        Path srcDatastore = sourceBilbyDir.resolve("datastore");
        if (Files.isDirectory(srcDatastore)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(srcDatastore, "*.preferences_pb")) {
                for (Path srcFile : stream) {
                    if (Files.isRegularFile(srcFile)) {
                        Files.copy(
                            srcFile,
                            targetDatastore.resolve(srcFile.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                }
            }
        }
        if (copyCoilCache) {
            copyDirectoryIfPresent(sourceBilbyDir.resolve("coil_cache"), targetBilbyDir.resolve("coil_cache"));
        }
    }

    private static String quoteWinArg(String arg) {
        if (!arg.isEmpty() && arg.indexOf(' ') < 0 && arg.indexOf('\t') < 0 && arg.indexOf('"') < 0) {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                if (backslashes > 0) {
                    sb.append("\\".repeat(backslashes));
                    backslashes = 0;
                }
                sb.append(c);
            }
        }
        if (backslashes > 0) {
            sb.append("\\".repeat(backslashes * 2));
        }
        sb.append('"');
        return sb.toString();
    }

    private static String requireValue(String flag, String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    public static void main(String[] args) throws Throwable {
        String javaExe = System.getProperty("bilby.shot.javaExe");
        String argFile = System.getProperty("bilby.shot.argFile");
        String workDir = System.getProperty("bilby.shot.workDir", new File(".").getAbsolutePath());
        String fromProfile = null;
        boolean freshProfile = false;
        boolean copyCoilCache = false;
        String desktopNameStr = System.getProperty(
            "bilby.shot.desktopName",
            "BilbyShot_" + ProcessHandle.current().pid()
        );
        int timeoutSec = 420;
        List<String> passthroughArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--".equals(arg)) {
                for (int j = i + 1; j < args.length; j++) {
                    passthroughArgs.add(args[j]);
                }
                break;
            }
            if (arg.startsWith("--java-exe=")) {
                javaExe = arg.substring("--java-exe=".length());
            } else if ("--java-exe".equals(arg)) {
                javaExe = requireValue(arg, args, ++i);
            } else if (arg.startsWith("--arg-file=")) {
                argFile = arg.substring("--arg-file=".length());
            } else if ("--arg-file".equals(arg)) {
                argFile = requireValue(arg, args, ++i);
            } else if (arg.startsWith("--work-dir=")) {
                workDir = arg.substring("--work-dir=".length());
            } else if ("--work-dir".equals(arg)) {
                workDir = requireValue(arg, args, ++i);
            } else if (arg.startsWith("--from-profile=")) {
                fromProfile = arg.substring("--from-profile=".length());
            } else if ("--from-profile".equals(arg)) {
                fromProfile = requireValue(arg, args, ++i);
            } else if ("--fresh-profile".equals(arg)) {
                freshProfile = true;
            } else if ("--copy-coil-cache".equals(arg)) {
                copyCoilCache = true;
            } else if (arg.startsWith("--desktop-name=")) {
                desktopNameStr = arg.substring("--desktop-name=".length());
            } else if ("--desktop-name".equals(arg)) {
                desktopNameStr = requireValue(arg, args, ++i);
            } else if (arg.startsWith("--timeout-sec=")) {
                timeoutSec = Integer.parseInt(arg.substring("--timeout-sec=".length()));
            } else if ("--timeout-sec".equals(arg)) {
                timeoutSec = Integer.parseInt(requireValue(arg, args, ++i));
            } else {
                passthroughArgs.add(arg);
            }
        }

        if (javaExe == null || javaExe.isBlank() || argFile == null || argFile.isBlank()) {
            System.err.println(
                "Usage: HiddenDesktopLauncher --java-exe <javaw/java> --arg-file <jvm.args> " +
                "[--work-dir <dir>] [--from-profile <BilbyDir>] [--fresh-profile] " +
                "[--copy-coil-cache] [--desktop-name <name>] [--timeout-sec <sec>] [-- <appArgs...>]"
            );
            System.exit(2);
        }

        Path resolvedJava = Path.of(javaExe).toAbsolutePath().normalize();
        Path resolvedArgFile = Path.of(argFile).toAbsolutePath().normalize();
        Path resolvedWorkDir = Path.of(workDir).toAbsolutePath().normalize();

        Path defaultSourceProfile = null;
        if (!freshProfile) {
            if (fromProfile != null && !fromProfile.isBlank()) {
                defaultSourceProfile = Path.of(fromProfile).toAbsolutePath().normalize();
            } else {
                String hostAppData = System.getenv("APPDATA");
                if (hostAppData != null && !hostAppData.isBlank()) {
                    defaultSourceProfile = Path.of(hostAppData, "Bilby");
                }
            }
        }

        Path sandboxRoot = Files.createTempDirectory("bilby-shot-sandbox-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteDirectoryTreeQuietly(sandboxRoot)));

        int childExitCode = 1;
        try {
            Path sandboxBilbyDir = sandboxRoot.resolve("Bilby");
            populateSandboxProfile(defaultSourceProfile, sandboxBilbyDir, copyCoilCache);
            Path childLogFile = sandboxRoot.resolve("screenshot.log");

            Linker linker = Linker.nativeLinker();
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

                MethodHandle createDesktopW = linker.downcallHandle(
                    user32.find("CreateDesktopW").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
                );
                MethodHandle closeDesktop = linker.downcallHandle(
                    user32.find("CloseDesktop").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)
                );
                MethodHandle createProcessW = linker.downcallHandle(
                    kernel32.find("CreateProcessW").orElseThrow(),
                    FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT,
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS
                    )
                );
                MethodHandle waitForSingleObject = linker.downcallHandle(
                    kernel32.find("WaitForSingleObject").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
                );
                MethodHandle getExitCodeProcess = linker.downcallHandle(
                    kernel32.find("GetExitCodeProcess").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
                );
                MethodHandle terminateProcess = linker.downcallHandle(
                    kernel32.find("TerminateProcess").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
                );
                MethodHandle closeHandle = linker.downcallHandle(
                    kernel32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)
                );
                MethodHandle getLastError = linker.downcallHandle(
                    kernel32.find("GetLastError").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT)
                );

                MemorySegment desktopName = wideString(arena, desktopNameStr);
                MemorySegment hDesktop = (MemorySegment) createDesktopW.invokeExact(
                    desktopName, MemorySegment.NULL, MemorySegment.NULL, 0, GENERIC_ALL, MemorySegment.NULL
                );
                if (hDesktop.equals(MemorySegment.NULL)) {
                    int err = (int) getLastError.invokeExact();
                    throw new RuntimeException("CreateDesktopW failed with Win32 error " + err);
                }
                System.out.println("Created hidden Win32 desktop winsta0\\" + desktopNameStr);

                try {
                    MemorySegment startupInfo = arena.allocate(STARTUPINFOW_SIZE, 8);
                    startupInfo.fill((byte) 0);
                    startupInfo.set(JAVA_INT, 0, STARTUPINFOW_SIZE);
                    startupInfo.set(ADDRESS, 16, wideString(arena, "winsta0\\" + desktopNameStr));

                    MemorySegment procInfo = arena.allocate(PROCESS_INFORMATION_SIZE, 8);
                    procInfo.fill((byte) 0);

                    StringBuilder cmdBuilder = new StringBuilder();
                    cmdBuilder.append(quoteWinArg(resolvedJava.toString()))
                        .append(' ')
                        .append(quoteWinArg("@" + resolvedArgFile));
                    for (String extra : passthroughArgs) {
                        cmdBuilder.append(' ').append(quoteWinArg(extra));
                    }

                    MemorySegment appNameSeg = wideString(arena, resolvedJava.toString());
                    MemorySegment cmdLineSeg = wideString(arena, cmdBuilder.toString());
                    MemorySegment envSeg = buildEnvironmentBlock(
                        arena,
                        sandboxRoot.toAbsolutePath().toString(),
                        childLogFile
                    );
                    MemorySegment workDirSeg = wideString(arena, resolvedWorkDir.toString());

                    int ok = (int) createProcessW.invokeExact(
                        appNameSeg,
                        cmdLineSeg,
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                        0,
                        CREATE_UNICODE_ENVIRONMENT,
                        envSeg,
                        workDirSeg,
                        startupInfo,
                        procInfo
                    );
                    if (ok == 0) {
                        int err = (int) getLastError.invokeExact();
                        throw new RuntimeException("CreateProcessW failed with Win32 error " + err);
                    }

                    MemorySegment hProcess = procInfo.get(ADDRESS, 0);
                    MemorySegment hThread = procInfo.get(ADDRESS, 8);
                    int pid = procInfo.get(JAVA_INT, 16);
                    System.out.println(
                        "Spawned screenshot JVM on winsta0\\" + desktopNameStr +
                        " (pid=" + pid + ", timeout=" + timeoutSec + "s)"
                    );

                    try {
                        int waitRes = (int) waitForSingleObject.invokeExact(hProcess, timeoutSec * 1000);
                        if (waitRes != WAIT_OBJECT_0) {
                            System.err.println("Screenshot JVM timed out after " + timeoutSec + "s; terminating...");
                            int ignored = (int) terminateProcess.invokeExact(hProcess, 124);
                            childExitCode = 124;
                        } else {
                            MemorySegment exitCodePtr = arena.allocate(JAVA_INT);
                            int ignored = (int) getExitCodeProcess.invokeExact(hProcess, exitCodePtr);
                            childExitCode = exitCodePtr.get(JAVA_INT, 0);
                            System.out.println("Screenshot JVM exited with code " + childExitCode);
                        }
                    } finally {
                        int ignored1 = (int) closeHandle.invokeExact(hThread);
                        int ignored2 = (int) closeHandle.invokeExact(hProcess);
                    }
                } finally {
                    int ignored = (int) closeDesktop.invokeExact(hDesktop);
                }
            } finally {
                if (Files.isRegularFile(childLogFile)) {
                    for (String line : Files.readAllLines(childLogFile, StandardCharsets.UTF_8)) {
                        System.out.println("[shot] " + line);
                    }
                }
            }
        } finally {
            deleteDirectoryTreeQuietly(sandboxRoot);
            System.out.println("Cleaned up temporary profile sandbox.");
        }
        System.exit(childExitCode);
    }
}

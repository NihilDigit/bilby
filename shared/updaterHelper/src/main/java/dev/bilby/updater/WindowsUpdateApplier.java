package dev.bilby.updater;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Pure {@code java.base} Windows update applier executed by {@code javaw.exe -jar},
 * replacing PowerShell / cmd scripts during in-app updates to avoid LOLBin AV false positives.
 */
public final class WindowsUpdateApplier {
    private static final String JOURNAL_FILE = ".bilby-update.journal";
    private static final long[] RETRY_DELAYS_MS = {100L, 300L, 1000L, 2000L, 4000L};
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final List<String> SANITIZED_ENV_KEYS = List.of(
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
        "CLASSPATH",
        "_JPACKAGE_LAUNCHER"
    );

    private final Path installDir;
    private final String mode;
    private final String executable;
    private final Path logFile;
    private final Path source;
    private final Path checksums;
    private final Path keepList;
    private final int waitSeconds;
    private final List<WatchedProcess> watchedProcesses;
    private final Map<String, String> relaunchProps;
    private final Path journalPath;

    private record WatchedProcess(long pid, long startEpochMillis) {}

    private record StagedItem(Path file, String relative) {}

    private record SwapEntry(Path source, String relative, Path target, boolean existed) {}

    private record JournalEntry(String relative, boolean existed) {}

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private WindowsUpdateApplier(Properties props) throws IOException {
        String rawInstallDir = requireProp(props, "installDir");
        Path rawInstallPath = Path.of(rawInstallDir).toAbsolutePath().normalize();
        this.installDir = Files.exists(rawInstallPath) ? rawInstallPath.toRealPath() : rawInstallPath;
        this.mode = requireProp(props, "mode");
        if (!Set.of("patch", "msi", "recover").contains(this.mode)) {
            throw new IllegalArgumentException("Unsupported mode: " + this.mode);
        }
        this.executable = requireProp(props, "executable");
        this.logFile = Path.of(requireProp(props, "logFile")).toAbsolutePath().normalize();
        this.source = optionalPath(props.getProperty("source", ""));
        this.checksums = optionalPath(props.getProperty("checksums", ""));
        this.keepList = optionalPath(props.getProperty("keepList", ""));
        this.waitSeconds = Integer.parseInt(props.getProperty("waitSeconds", "120").trim());
        this.watchedProcesses = parseWatchedProcesses(props.getProperty("pids", ""));
        this.relaunchProps = parseRelaunchProps(props);
        this.journalPath = this.installDir.resolve(JOURNAL_FILE);

        Path logParent = this.logFile.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String ownerPidPath = props.getProperty("ownerPidFile", "").trim();
        if (!ownerPidPath.isEmpty()) {
            Path ownerFile = Path.of(ownerPidPath);
            long myPid = ProcessHandle.current().pid();
            long myStart = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
            Files.writeString(ownerFile, myPid + ":" + myStart + "\n", StandardCharsets.UTF_8);
        }
        String readyPath = props.getProperty("readyFile", "").trim();
        if (!readyPath.isEmpty()) {
            Files.writeString(Path.of(readyPath), "ok\n", StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WindowsUpdateApplier <request.properties>");
            System.exit(2);
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            props.load(reader);
            WindowsUpdateApplier applier = new WindowsUpdateApplier(props);
            int code = applier.execute();
            System.exit(code);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private int execute() {
        writeLog("update started: mode=" + mode + " pids=" + formatPids() + " dir=" + installDir);
        if (!waitForAppExit()) {
            writeLog("app did not exit within " + waitSeconds + " s, update aborted");
            setFailed("the app did not exit within " + waitSeconds + " s");
            startApp();
            return 1;
        }
        writeLog("app exited");

        int exitCode = 0;
        try {
            if ("recover".equals(mode)) {
                boolean shouldRelaunch = restoreJournal();
                if (!shouldRelaunch) {
                    return 0;
                }
            } else {
                assertStagedFiles();
                if ("patch".equals(mode)) {
                    installPatch();
                } else {
                    installMsi();
                }
                writeLog("update applied");
                removeStagedUpdate();
            }
        } catch (Throwable e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            writeLog("update failed: " + msg);
            exitCode = 1;
            setFailed(msg);
        }

        if (!startApp()) {
            exitCode = 1;
        }
        return exitCode;
    }

    private boolean waitForAppExit() {
        long deadlineNanos = System.nanoTime() + Math.max(1L, waitSeconds) * 1_000_000_000L;
        for (WatchedProcess wp : watchedProcesses) {
            while (isSameProcessAlive(wp)) {
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSameProcessAlive(WatchedProcess wp) {
        Optional<ProcessHandle> handleOpt = ProcessHandle.of(wp.pid());
        if (handleOpt.isEmpty()) return false;
        ProcessHandle handle = handleOpt.get();
        if (!handle.isAlive()) return false;
        if (wp.startEpochMillis() > 0L) {
            Optional<Instant> actualStart = handle.info().startInstant();
            if (actualStart.isPresent()) {
                long diff = Math.abs(actualStart.get().toEpochMilli() - wp.startEpochMillis());
                if (diff > 5_000L) {
                    return false;
                }
            }
        }
        return true;
    }

    private void assertStagedFiles() throws Exception {
        if (checksums == null || !Files.isRegularFile(checksums)) {
            throw new IOException("missing checksum list: " + checksums);
        }
        Path stagingDir = checksums.getParent().toAbsolutePath().normalize();
        List<String> lines = Files.readAllLines(checksums, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.trim().isEmpty())
            .toList();
        if (lines.isEmpty()) {
            throw new IOException("empty checksum list " + checksums);
        }
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length < 2) {
                throw new IOException("malformed checksum entry: " + line);
            }
            String expected = parts[0].trim().toLowerCase(Locale.ROOT);
            String relative = parts[1].trim();
            Path target = resolveConfined(stagingDir, relative);
            if (!Files.isRegularFile(target)) {
                throw new IOException("staged file missing: " + relative);
            }
            String actual = sha256Hex(target);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("staged file changed: " + relative + " (" + actual + " != " + expected + ")");
            }
        }
        writeLog("verified " + lines.size() + " staged files");
    }

    private List<StagedItem> collectStagedFiles(Path rootDir) throws IOException {
        Path normalizedRoot = rootDir.toAbsolutePath().normalize();
        Path realRoot = Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;
        List<StagedItem> result = new ArrayList<>();
        if (!Files.isDirectory(realRoot)) {
            return result;
        }
        Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = realRoot.relativize(file).toString().replace('/', '\\');
                result.add(new StagedItem(file, rel));
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private void assertStagedSetMatches(List<StagedItem> staged) throws IOException {
        if (source == null || checksums == null) {
            throw new IOException("missing patch source or checksums");
        }
        String prefix = source.getFileName().toString() + "\\";
        String prefixLower = prefix.toLowerCase(Locale.ROOT);
        Set<String> expected = new HashSet<>();
        for (String raw : Files.readAllLines(checksums, StandardCharsets.UTF_8)) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 2);
            String rel = parts[1].trim().replace('/', '\\');
            if (!rel.toLowerCase(Locale.ROOT).startsWith(prefixLower)) {
                throw new IOException("checksum entry outside " + prefix + " : " + rel);
            }
            expected.add(rel.substring(prefix.length()).toLowerCase(Locale.ROOT));
        }
        Set<String> actual = new HashSet<>();
        for (StagedItem item : staged) {
            actual.add(item.relative().toLowerCase(Locale.ROOT));
        }
        for (String key : actual) {
            if (!expected.contains(key)) {
                throw new IOException("staged file not in the checksum list: " + key);
            }
        }
        for (String key : expected) {
            if (!actual.contains(key)) {
                throw new IOException("listed file not staged: " + key);
            }
        }
    }

    private static int swapRank(SwapEntry entry) {
        String relLower = entry.relative().toLowerCase(Locale.ROOT);
        if (relLower.startsWith("app\\") && !relLower.substring(4).contains("\\") && relLower.endsWith(".cfg")) {
            return 2;
        }
        return entry.existed() ? 1 : 0;
    }

    private void installPatch() throws Exception {
        if (source == null) {
            throw new IOException("missing patch source directory");
        }
        // Fail before touching anything or entering the rollback catch block, so an unrecovered
        // journal from a prior interrupted update is never deleted without rolling it back.
        if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("existing update journal found; recovery required");
        }
        List<StagedItem> staged = collectStagedFiles(source);
        if (staged.isEmpty()) {
            throw new IOException("no staged files in " + source);
        }
        assertStagedSetMatches(staged);

        List<SwapEntry> entries = new ArrayList<>(staged.size());
        for (StagedItem item : staged) {
            Path target = resolveConfined(installDir, item.relative());
            removeIfPresent(siblingWithSuffix(target, ".new"));
            removeIfPresent(siblingWithSuffix(target, ".old"));
            entries.add(new SwapEntry(item.file(), item.relative(), target, Files.exists(target, LinkOption.NOFOLLOW_LINKS)));
        }
        entries.sort(Comparator.comparingInt(WindowsUpdateApplier::swapRank));

        Files.createDirectories(installDir);
        FileChannel journalChannel = FileChannel.open(
            journalPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );
        FileLock journalLock = null;
        try {
            journalLock = journalChannel.tryLock();
            if (journalLock == null) {
                throw new IOException("could not acquire exclusive lock on " + journalPath);
            }
            writeJournalEntries(journalChannel, entries);

            for (SwapEntry entry : entries) {
                Path target = entry.target();
                Path parent = target.getParent();
                if (parent != null && !Files.isDirectory(parent)) {
                    Files.createDirectories(parent);
                }
                Path dotNew = siblingWithSuffix(target, ".new");
                FileTime sourceMtime = Files.getLastModifiedTime(entry.source(), LinkOption.NOFOLLOW_LINKS);
                invokeRetrying("copy " + dotNew, () ->
                    Files.copy(entry.source(), dotNew, StandardCopyOption.REPLACE_EXISTING)
                );
                clearReadOnly(dotNew);
                invokeRetrying("set mtime of " + dotNew, () ->
                    Files.getFileAttributeView(dotNew, BasicFileAttributeView.class).setTimes(sourceMtime, null, null)
                );
            }

            for (SwapEntry entry : entries) {
                Path target = entry.target();
                Path dotNew = siblingWithSuffix(target, ".new");
                Path dotOld = siblingWithSuffix(target, ".old");
                FileTime originalCreated = null;
                if (entry.existed()) {
                    originalCreated = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).creationTime();
                    clearReadOnly(target);
                    moveRetrying(target, dotOld);
                }
                moveRetrying(dotNew, target);
                if (originalCreated != null) {
                    final FileTime createdToKeep = originalCreated;
                    invokeRetrying("set creation time of " + target, () ->
                        Files.getFileAttributeView(target, BasicFileAttributeView.class).setTimes(null, null, createdToKeep)
                    );
                }
                writeLog("replaced " + target);
            }
        } catch (Throwable err) {
            writeLog("patch failed, rolling back: " + err);
            List<JournalEntry> rollbackEntries = entries.stream()
                .map(e -> new JournalEntry(e.relative(), e.existed()))
                .toList();
            boolean restored = undoJournal(rollbackEntries);
            closeQuietly(journalLock, journalChannel);
            if (restored) {
                try {
                    removeIfPresent(journalPath);
                } catch (Exception ignored) {
                }
            } else {
                writeLog("rollback incomplete, journal kept");
            }
            throw err;
        }

        closeQuietly(journalLock, journalChannel);
        removeIfPresent(journalPath);
        for (SwapEntry entry : entries) {
            if (!entry.existed()) continue;
            Path dotOld = siblingWithSuffix(entry.target(), ".old");
            try {
                removeIfPresent(dotOld);
            } catch (Exception e) {
                writeLog("could not remove " + dotOld + " : " + e.getMessage());
            }
        }
        removeStaleFiles();
    }

    /**
     * @return {@code true} if recovery handled a journal (and threw an error to record {@code failed} and relaunch);
     *         {@code false} if another updater currently holds the journal lock (exit quietly without relaunching).
     */
    private boolean restoreJournal() throws Exception {
        FileChannel channel;
        try {
            channel = FileChannel.open(journalPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (NoSuchFileException e) {
            writeLog("no journal, nothing to recover");
            return true;
        } catch (IOException e) {
            writeLog("journal in use, leaving it: " + e.getMessage());
            return false;
        }

        FileLock lock = null;
        List<JournalEntry> entries;
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException | IOException e) {
                lock = null;
            }
            if (lock == null) {
                writeLog("journal lock held by active updater, leaving it");
                channel.close();
                return false;
            }
            entries = readJournalEntries(channel);
            writeLog("rolling back an interrupted update of " + entries.size() + " files");
            boolean restored = undoJournal(entries);
            closeQuietly(lock, channel);
            // Always drop journal after recovery attempt to avoid an unbreakable relaunch recovery loop.
            try {
                removeIfPresent(journalPath);
            } catch (Exception e) {
                writeLog("could not remove journal after recovery: " + e.getMessage());
            }
            if (!restored) {
                throw new IOException("interrupted update could not be fully rolled back");
            }
            throw new IOException("the update was interrupted and has been rolled back");
        } finally {
            closeQuietly(lock, channel);
        }
    }

    private void writeJournalEntries(FileChannel channel, List<SwapEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        Path stagingDir = logFile.getParent();
        sb.append("staging\t").append(stagingDir != null ? stagingDir.toString() : "").append('\n');
        for (SwapEntry entry : entries) {
            sb.append(entry.existed() ? '~' : '+').append('\t').append(entry.relative()).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true);
    }

    private List<JournalEntry> readJournalEntries(FileChannel channel) throws IOException {
        channel.position(0L);
        List<JournalEntry> entries = new ArrayList<>();
        BufferedReader reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String kind = line.substring(0, tab);
            String rel = line.substring(tab + 1);
            if ("~".equals(kind) || "+".equals(kind)) {
                entries.add(new JournalEntry(rel, "~".equals(kind)));
            }
        }
        return entries;
    }

    private boolean undoJournal(List<JournalEntry> entries) {
        boolean complete = true;
        for (int i = entries.size() - 1; i >= 0; i--) {
            JournalEntry entry = entries.get(i);
            Path target;
            try {
                target = resolveConfined(installDir, entry.relative());
            } catch (Exception e) {
                writeLog("rollback rejected unsafe path " + entry.relative() + " : " + e.getMessage());
                complete = false;
                continue;
            }
            Path dotOld = siblingWithSuffix(target, ".old");
            Path dotNew = siblingWithSuffix(target, ".new");
            try {
                if (entry.existed()) {
                    if (Files.exists(dotOld, LinkOption.NOFOLLOW_LINKS)) {
                        removeIfPresent(target);
                        moveRetrying(dotOld, target);
                    }
                } else {
                    removeIfPresent(target);
                }
                removeIfPresent(dotNew);
            } catch (Exception e) {
                writeLog("rollback failed for " + target + " : " + e.getMessage());
                complete = false;
            }
        }
        return complete;
    }

    private void removeStaleFiles() throws IOException {
        if (keepList == null || !Files.isRegularFile(keepList)) return;
        Set<String> keep = new HashSet<>();
        for (String line : Files.readAllLines(keepList, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                Path candidate = resolveConfined(installDir, trimmed.replace('/', '\\'));
                keep.add(candidate.toString().toLowerCase(Locale.ROOT));
            }
        }
        if (keep.isEmpty()) return;

        for (String folder : List.of("app", "runtime")) {
            Path subDir = installDir.resolve(folder);
            if (!Files.isDirectory(subDir)) continue;
            List<Path> discovered = new ArrayList<>();
            Files.walkFileTree(subDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.startsWith(".")) {
                        discovered.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path file : discovered) {
                String normalized = file.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
                if (!keep.contains(normalized)) {
                    try {
                        clearReadOnly(file);
                        Files.deleteIfExists(file);
                        writeLog("removed stale " + file);
                    } catch (IOException e) {
                        writeLog("could not remove stale " + file + " : " + e.getMessage());
                    }
                }
            }
        }
    }

    private void installMsi() throws Exception {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("missing staged MSI: " + source);
        }
        Path logParent = logFile.getParent();
        Path msiLog = (logParent != null ? logParent : installDir).resolve("msiexec.log");
        String sysRoot = System.getenv("SystemRoot");
        Path msiexec = Path.of((sysRoot != null && !sysRoot.isBlank()) ? sysRoot : "C:\\Windows", "System32", "msiexec.exe");
        String msiexecCmd = Files.isRegularFile(msiexec) ? msiexec.toString() : "msiexec.exe";

        ProcessBuilder pb = new ProcessBuilder(
            msiexecCmd,
            "/i", source.toString(),
            "/passive",
            "/norestart",
            "/l*v", msiLog.toString()
        )
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        sanitizeEnvironment(pb.environment());
        Process proc = pb.start();
        try {
            proc.getOutputStream().close();
        } catch (IOException ignored) {
        }
        int code = proc.waitFor();
        if (code == 3010) {
            writeLog("msiexec completed with 3010 (reboot required)");
        } else if (code != 0) {
            throw new IOException("msiexec exited with " + code + ", see " + msiLog);
        }
    }

    private void removeStagedUpdate() {
        for (Path p : new Path[]{source, checksums, keepList}) {
            if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                invokeRetrying("delete " + p, () -> deleteTree(p));
            } catch (Exception e) {
                writeLog("could not remove staged " + p + " : " + e.getMessage());
            }
        }
    }

    private boolean startApp() {
        Path exe = resolveConfinedQuietly(installDir, executable);
        if (exe == null) {
            writeLog("refused unsafe executable path: " + executable);
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toString())
                .directory(installDir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> env = pb.environment();
            sanitizeEnvironment(env);
            if (!relaunchProps.isEmpty()) {
                StringBuilder toolOpts = new StringBuilder();
                for (Map.Entry<String, String> e : relaunchProps.entrySet()) {
                    if (!toolOpts.isEmpty()) toolOpts.append(' ');
                    toolOpts.append(quoteJvmOption("-D" + e.getKey() + "=" + e.getValue()));
                }
                env.put("JAVA_TOOL_OPTIONS", toolOpts.toString());
            }
            invokeRetrying("start " + exe, () -> {
                Process p = pb.start();
                try {
                    p.getOutputStream().close();
                } catch (IOException ignored) {
                }
            });
            writeLog("started " + exe);
            return true;
        } catch (Exception e) {
            writeLog("could not start " + exe + " : " + e.getMessage());
            return false;
        }
    }

    private static String quoteJvmOption(String arg) {
        if (arg.indexOf(' ') < 0 && arg.indexOf('\t') < 0 && arg.indexOf('"') < 0) {
            return arg;
        }
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void sanitizeEnvironment(Map<String, String> env) {
        Set<String> toRemove = new HashSet<>();
        for (String existing : env.keySet()) {
            for (String blocked : SANITIZED_ENV_KEYS) {
                if (existing.equalsIgnoreCase(blocked)) {
                    toRemove.add(existing);
                }
            }
        }
        toRemove.forEach(env::remove);
    }

    private void setFailed(String reason) {
        Path parent = logFile.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
            Files.writeString(parent.resolve("failed"), reason, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void writeLog(String message) {
        String line = LOG_TS.format(LocalDateTime.now()) + " " + message + System.lineSeparator();
        try {
            Path parent = logFile.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )) {
                writer.write(line);
            }
        } catch (IOException ignored) {
        }
    }

    private void invokeRetrying(String what, IoAction action) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                if (attempt >= RETRY_DELAYS_MS.length) {
                    throw e;
                }
                writeLog("retrying " + what + " : " + e.getMessage());
                Thread.sleep(RETRY_DELAYS_MS[attempt]);
            }
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null && view.readAttributes().isReadOnly()) {
                view.setReadOnly(false);
            }
        } catch (Exception ignored) {
        }
    }

    private void removeIfPresent(Path path) throws Exception {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            clearReadOnly(path);
            invokeRetrying("delete " + path, () -> Files.deleteIfExists(path));
        }
    }

    private void moveRetrying(Path from, Path to) throws Exception {
        clearReadOnly(from);
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            clearReadOnly(to);
        }
        invokeRetrying("move " + from, () -> {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path child : entries) {
                    deleteTree(child);
                }
            }
        }
        clearReadOnly(root);
        Files.deleteIfExists(root);
    }

    private static Path siblingWithSuffix(Path target, String suffix) {
        return target.resolveSibling(target.getFileName().toString() + suffix);
    }

    private static Path resolveConfined(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative.replace('/', '\\')).normalize();
        if (!resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot)) {
            throw new IOException("Path escapes root (" + normalizedRoot + "): " + relative);
        }
        return resolved;
    }

    private static Path resolveConfinedQuietly(Path root, String relative) {
        try {
            return resolveConfined(root, relative);
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void closeQuietly(FileLock lock, FileChannel channel) {
        if (lock != null) {
            try {
                if (lock.isValid()) lock.release();
            } catch (IOException ignored) {
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String requireProp(Properties props, String key) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return val.trim();
    }

    private static Path optionalPath(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.isEmpty() ? null : Path.of(trimmed).toAbsolutePath().normalize();
    }

    private static List<WatchedProcess> parseWatchedProcesses(String raw) {
        List<WatchedProcess> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int colon = t.indexOf(':');
            if (colon > 0) {
                long pid = Long.parseLong(t.substring(0, colon));
                long start = Long.parseLong(t.substring(colon + 1));
                out.add(new WatchedProcess(pid, start));
            } else {
                out.add(new WatchedProcess(Long.parseLong(t), -1L));
            }
        }
        return out;
    }

    private static Map<String, String> parseRelaunchProps(Properties props) {
        Map<String, String> map = new HashMap<>();
        String prefix = "relaunchProp.";
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                String key = name.substring(prefix.length());
                String val = props.getProperty(name);
                if (val != null && !val.isEmpty()) {
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    private String formatPids() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < watchedProcesses.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(watchedProcesses.get(i).pid());
        }
        return sb.toString();
    }
}

package com.agentflow.agent.task.recovery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** No close/destroy hook: the operating system releases this lock only when the JVM exits. */
public final class TaskExecutionProcessLock {
    private static final List<TaskExecutionProcessLock> HELD_UNTIL_PROCESS_EXIT = new ArrayList<>();
    private final FileChannel channel;
    private final FileLock lock;
    private final Path path;
    private TaskExecutionProcessLock(FileChannel channel, FileLock lock, Path path) {
        this.channel = channel;
        this.lock = lock;
        this.path = path;
    }
    public static synchronized TaskExecutionProcessLock acquire(TaskRecoveryProperties properties) {
        properties.validate();
        Path path = Path.of(properties.getLockPath());
        if (!path.isAbsolute()) throw new IllegalStateException("Task process lock-path must be absolute and stable");
        FileChannel channel = null;
        try {
            Files.createDirectories(path.getParent());
            path = path.getParent().toRealPath().resolve(path.getFileName()).normalize();
            // On POSIX, closing a second descriptor can release process locks on the same file.
            // Reject aliases before opening any second channel in this JVM.
            for (TaskExecutionProcessLock held : HELD_UNTIL_PROCESS_EXIT) {
                if (held.path.equals(path) || (Files.exists(path) && Files.isSameFile(held.path, path)))
                    throw new IllegalStateException("Task execution domain is already held in this JVM");
            }
            String type = Files.getFileStore(path.getParent()).type().toLowerCase(Locale.ROOT);
            if (type.contains("nfs") || type.contains("smb") || type.contains("cifs") || type.contains("fuse"))
                throw new IllegalStateException("Task process lock requires a supported local filesystem");
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null || lock.isShared())
                throw new IllegalStateException("Task execution domain is already locked by another JVM");
            TaskExecutionProcessLock held = new TaskExecutionProcessLock(channel, lock, path);
            HELD_UNTIL_PROCESS_EXIT.add(held);
            return held;
        } catch (IOException | RuntimeException failure) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            throw new IllegalStateException("TASK_EXECUTION_NOT_READY: task process lock acquisition failed", failure);
        }
    }
    public void requireHeld() {
        if (!channel.isOpen() || !lock.isValid()) throw new IllegalStateException("Task execution process lock is no longer held");
    }
    public Path path() { return path; }
}

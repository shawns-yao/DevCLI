package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** 可跨 checkpoint 保存的文件权限快照；POSIX 文件系统保存完整 rwx 位。 */
public record FileModeSnapshot(Integer posixMode, Boolean executable) {
    private static final PosixFilePermission[] ORDER = {
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE
    };

    public static FileModeSnapshot capture(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return fromPosix(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static FileModeSnapshot fromPosix(Set<PosixFilePermission> permissions) {
        Set<PosixFilePermission> values = permissions == null
                ? Set.of() : Set.copyOf(permissions);
        int encoded = 0;
        for (int index = 0; index < ORDER.length; index++) {
            if (values.contains(ORDER[index])) {
                encoded |= 1 << (ORDER.length - 1 - index);
            }
        }
        boolean executable = values.contains(PosixFilePermission.OWNER_EXECUTE)
                || values.contains(PosixFilePermission.GROUP_EXECUTE)
                || values.contains(PosixFilePermission.OTHERS_EXECUTE);
        return new FileModeSnapshot(encoded, executable);
    }

    public static FileModeSnapshot executableOnly(Boolean executable) {
        return executable == null ? null : new FileModeSnapshot(null, executable);
    }

    public boolean matches(Path path) throws IOException {
        if (posixMode != null) {
            FileModeSnapshot actual = capture(path);
            return actual != null && posixMode.equals(actual.posixMode());
        }
        return executable == null || Files.isExecutable(path) == executable;
    }

    public void apply(Path path) throws IOException {
        if (posixMode != null) {
            Files.setPosixFilePermissions(path, decode(posixMode));
        } else if (executable != null) {
            boolean changed = path.toFile().setExecutable(executable, false);
            if (!changed || Files.isExecutable(path) != executable) {
                throw new IOException("无法设置文件可执行标记: " + path);
            }
        }
        if (!matches(path)) {
            throw new IOException("文件权限写入后校验失败: " + path);
        }
    }

    private static Set<PosixFilePermission> decode(int encoded) {
        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (int index = 0; index < ORDER.length; index++) {
            if ((encoded & (1 << (ORDER.length - 1 - index))) != 0) {
                permissions.add(ORDER[index]);
            }
        }
        return permissions;
    }
}

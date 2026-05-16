package minicode.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

public final class WorkspacePathResolver {

    /**
     * 解析并规范化一次工具侧路径请求。
     *
     * <p>该方法会根据请求中的 {@code cwd}、原始路径、解析意图和路径策略，
     * 得到一个稳定的目标路径视图，包括规范化后的路径、真实路径、是否存在、
     * 文件类型判断以及是否仍位于工作区 {@code cwd} 边界内。</p>
     *
     * <p>工具层应通过该方法统一处理用户或模型传入的路径，避免各工具自行拼接、
     * 规范化或判断工作区边界。</p>
     *
     * @param request 路径解析请求，包含 cwd、原始路径、用途意图和路径策略
     * @return 路径解析结果，包含规范化路径、真实路径、存在性、类型和边界信息
     * @throws WorkspacePathException 当路径为空、非法、父目录不满足策略要求、
     *                                或路径策略校验失败时抛出
     */
    public WorkspacePathResult resolve(WorkspacePathRequest request) {
        Path cwd = normalizeCwd(request.cwd());
        String rawPath = request.rawPath().trim();
        Path requested = Path.of(rawPath);
        Path normalizedPath = requested.isAbsolute()
                ? requested.normalize()
                : cwd.resolve(requested).normalize();

        boolean exists = Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS);
        if (request.policy().mustExist() && !exists) {
            throw new WorkspacePathException("Path does not exist: " + rawPath);
        }

        Optional<Path> realPath = readRealPath(normalizedPath, rawPath);
        if (exists && !request.policy().allowDirectory() && Files.isDirectory(normalizedPath)) {
            throw new WorkspacePathException("Expected file but found directory: " + rawPath);
        }
        if (exists && request.policy().allowDirectory() && !Files.isDirectory(normalizedPath)) {
            throw new WorkspacePathException("Expected directory but found file: " + rawPath);
        }

        Path cwdRealPath = cwdRealPath(cwd);
        Optional<Path> parentRealPath = parentRealPathForMissingTarget(request, normalizedPath, exists);
        WorkspaceBoundary boundary = boundary(cwd, cwdRealPath, normalizedPath, realPath, parentRealPath);
        ResolvedWorkspacePath resolved = new ResolvedWorkspacePath(rawPath, normalizedPath, realPath, boundary);
        return new WorkspacePathResult(resolved, exists, parentRealPath);
    }

    private static Path normalizeCwd(Path cwd) {
        Path normalized = cwd.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new WorkspacePathException("cwd does not exist: " + normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new WorkspacePathException("cwd is not a directory: " + normalized);
        }
        return normalized;
    }

    private static Optional<Path> readRealPath(Path normalizedPath, String rawPath) {
        try {
            return Optional.of(normalizedPath.toRealPath());
        } catch (IOException exception) {
            if (Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspacePathException("Unable to resolve real path conservatively: " + rawPath, exception);
            }
            return Optional.empty();
        }
    }

    private static Path cwdRealPath(Path cwd) {
        try {
            return cwd.toRealPath();
        } catch (IOException exception) {
            throw new WorkspacePathException("Unable to resolve cwd real path: " + cwd, exception);
        }
    }

    private static Optional<Path> parentRealPathForMissingTarget(WorkspacePathRequest request, Path normalizedPath,
                                                                 boolean exists) {
        if (exists || request.policy() != WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT) {
            return Optional.empty();
        }
        Path parent = normalizedPath.getParent();
        if (parent == null || !Files.exists(parent)) {
            throw new WorkspacePathException("Parent path does not exist: " + normalizedPath);
        }
        if (!Files.isDirectory(parent)) {
            throw new WorkspacePathException("Parent path is not a directory: " + parent);
        }
        try {
            return Optional.of(parent.toRealPath());
        } catch (IOException exception) {
            throw new WorkspacePathException("Unable to resolve parent real path conservatively: " + parent, exception);
        }
    }

    private static WorkspaceBoundary boundary(Path cwd, Path cwdRealPath, Path normalizedPath,
                                              Optional<Path> realPath, Optional<Path> parentRealPath) {
        if (!normalizedPath.startsWith(cwd)) {
            return WorkspaceBoundary.OUTSIDE_CWD;
        }
        if (realPath.isPresent() && !realPath.get().startsWith(cwdRealPath)) {
            return WorkspaceBoundary.OUTSIDE_CWD;
        }
        if (parentRealPath.isPresent() && !parentRealPath.get().startsWith(cwdRealPath)) {
            return WorkspaceBoundary.OUTSIDE_CWD;
        }
        return WorkspaceBoundary.INSIDE_CWD;
    }
}

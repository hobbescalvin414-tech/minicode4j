package minicode.edit;

import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public final class FileWriteService {
    private final PermissionService permissionService;

    public FileWriteService(PermissionService permissionService) {
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
    }

    public FileWriteResult apply(Path targetPath, String filePath, String nextContent, Optional<String> toolUseId,
                                 PermissionContext permissionContext, Runnable beforeWriteCheck) throws IOException {
        Path actualTargetPath = Objects.requireNonNull(targetPath, "targetPath");
        Optional<String> beforeContent = readExistingContent(actualTargetPath);
        PermissionResource.EditOperation operation = beforeContent.isPresent()
                ? PermissionResource.EditOperation.OVERWRITE
                : PermissionResource.EditOperation.CREATE;
        return applyReviewedReplacement(
                actualTargetPath,
                filePath,
                operation,
                operation == PermissionResource.EditOperation.CREATE
                        ? "Create file: " + filePath
                        : "Overwrite file: " + filePath,
                nextContent,
                toolUseId,
                permissionContext,
                beforeWriteCheck,
                beforeContent
        );
    }
    /**
     * 对目标文件应用一次经过 review 的整文件替换。
     *
     * <p>调用方负责提前解析目标路径，并计算出修改后的完整文件内容。
     * 本方法集中管理文件写入边界：读取当前文件内容、跳过 no-op 修改、
     * 创建 edit review、请求 edit 权限、在真正写入前执行最后的检查，
     * 最后才把新内容写入磁盘。</p>
     *
     * @param targetPath 已解析后的目标文件路径
     * @param filePath 面向用户展示的文件路径，用于摘要和提示信息
     * @param operation 本次编辑操作类型，例如 EDIT、PATCH 或 MODIFY
     * @param summary 用于 review 提示的简短摘要
     * @param nextContent 修改后的完整文件内容
     * @param toolUseId 触发本次写入的工具调用 id，可为空
     * @param permissionContext 权限 review 所需的 session、turn 和 tool 上下文
     * @param beforeWriteCheck 用户批准后、真正写入磁盘前执行的检查回调
     * @return 文件写入结果，说明修改已应用或被判定为 no-op
     * @throws IOException 当读取旧文件或写入新内容失败时抛出
     */

    public FileWriteResult applyReviewedReplacement(Path targetPath, String filePath,
                                                    PermissionResource.EditOperation operation,
                                                    String summary,
                                                    String nextContent,
                                                    Optional<String> toolUseId,
                                                    PermissionContext permissionContext,
                                                    Runnable beforeWriteCheck) throws IOException {
        return applyReviewedReplacement(
                targetPath,
                filePath,
                operation,
                summary,
                nextContent,
                toolUseId,
                permissionContext,
                beforeWriteCheck,
                readExistingContent(Objects.requireNonNull(targetPath, "targetPath"))
        );
    }


    /**
     * 执行文件写入链路的核心实现。
     *
     * <p>该方法接收已经读取好的旧内容，判断是否为 no-op，生成 edit review，
     * 请求 edit 权限，并在权限通过后写入新内容。所有公开写入入口最终都应
     * 收敛到这里，确保文件修改不会绕过 review 和 permission 边界。</p>
     *
     * @param targetPath 已解析后的目标文件路径
     * @param filePath 面向用户展示的文件路径，用于摘要和提示信息
     * @param operation 本次编辑操作类型，例如 CREATE、OVERWRITE、EDIT、PATCH 或 MODIFY
     * @param summary 用于 review 提示的简短摘要
     * @param nextContent 修改后的完整文件内容
     * @param toolUseId 触发本次写入的工具调用 id，可为空
     * @param permissionContext 权限 review 所需的 session、turn 和 tool 上下文
     * @param beforeWriteCheck 用户批准后、真正写入磁盘前执行的检查回调
     * @param beforeContent 已读取到的旧文件内容；文件不存在时为空
     * @return 文件写入结果，说明修改已应用或被判定为 no-op
     * @throws IOException 当读取旧文件或写入新内容失败时抛出
     */

    private FileWriteResult applyReviewedReplacement(Path targetPath, String filePath,
                                                     PermissionResource.EditOperation operation,
                                                     String summary,
                                                     String nextContent,
                                                     Optional<String> toolUseId,
                                                     PermissionContext permissionContext,
                                                     Runnable beforeWriteCheck,
                                                     Optional<String> beforeContent) throws IOException {
        Path actualTargetPath = Objects.requireNonNull(targetPath, "targetPath");
        String actualFilePath = Objects.requireNonNull(filePath, "filePath");
        PermissionResource.EditOperation actualOperation = Objects.requireNonNull(operation, "operation");
        String actualSummary = Objects.requireNonNull(summary, "summary");
        if (actualSummary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        String actualNextContent = Objects.requireNonNull(nextContent, "nextContent");
        Optional<String> actualToolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        PermissionContext actualPermissionContext = Objects.requireNonNull(permissionContext, "permissionContext");
        Runnable actualBeforeWriteCheck = Objects.requireNonNull(beforeWriteCheck, "beforeWriteCheck");
        Optional<String> actualBeforeContent = Objects.requireNonNull(beforeContent, "beforeContent");

        if (actualBeforeContent.filter(actualNextContent::equals).isPresent()) {
            return FileWriteResult.noOp("No changes needed for " + actualFilePath);
        }

        PermissionResource.EditResource review = EditReviewFactory.review(
                actualTargetPath,
                actualOperation,
                actualSummary,
                actualBeforeContent,
                actualNextContent,
                actualToolUseId
        );

        permissionService.ensureEdit(review, actualPermissionContext);
        actualBeforeWriteCheck.run();

        Files.writeString(
                actualTargetPath,
                actualNextContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        return FileWriteResult.applied(actualOperation, "Applied reviewed changes to " + actualFilePath);
    }

    private static Optional<String> readExistingContent(Path targetPath) throws IOException {
        if (!Files.exists(targetPath)) {
            return Optional.empty();
        }
        if (Files.isDirectory(targetPath)) {
            throw new IOException("Expected file but found directory: " + targetPath);
        }
        return Optional.of(Files.readString(targetPath, StandardCharsets.UTF_8));
    }

}

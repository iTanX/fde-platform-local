package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 节点文件在需求 workspace 内的路径规则（T5-1 唯一的"路径真源"）。
 * <p>
 * <b>约定（docs/database.md §5.5 + docs/plugin-integration.md §5.1）</b>：需求 workspace 根为
 * {@code <storage-root>/ws/<requirementId>}（= {@code wb_requirement.dsh_workspace_path}），其下：
 * <pre>
 * &lt;ws&gt;/inputs/&lt;nodeSeq&gt;/&lt;fileId&gt;_&lt;原始文件名&gt;   节点输入（用户上传）
 * &lt;ws&gt;/outputs/&lt;nodeSeq&gt;/&lt;itemKey&gt;.v&lt;N&gt;.&lt;ext&gt;  已登记的输出副本（下载 / 预览读这里）
 * &lt;ws&gt;/.workbench/&lt;nodeInstanceId&gt;/...          模型实际写入的目录（唯一允许的落盘位置）
 * </pre>
 * <b>为什么输出目录是 {@code <ws>/.workbench/<nodeInstanceId>/} 而不是 docs 里写的
 * {@code .workbench/<nodeInstanceId>/}</b>：两者是同一个东西——docs 里的写法是"相对 workspace 根"的
 * 子目录名，模型的 cwd 就是 workspace 根，所以插件侧照旧用相对路径
 * {@code .workbench/<nodeInstanceId>/<itemKey>.<ext>} 读写即可。Java 侧拼接绝对路径时
 * 一律经过 {@link WorkbenchStorage#resolveWithin} 做"归一化 + 仍在目录内"的判定，
 * 越界（{@code ../}、绝对路径、盘符、反斜杠）在这里统一被拒绝。
 * <p>
 * <b>为什么要有 outputs 目录的副本</b>：模型写入 {@code .workbench/} 的文件可能被后续对话改写，
 * 甚至被模型自己的写工具覆盖；页面上的"输出项"必须稳定可下载、可预览，所以提交成功时把文件
 * 复制一份到 {@code outputs/<seq>/}，{@code wb_file} 指向副本，并记录副本的 sha256。
 */
@Component
public class NodeStoragePaths {

    private static final Logger log = LoggerFactory.getLogger(NodeStoragePaths.class);

    /** 模型输出目录名（相对 workspace 根） */
    public static final String MODEL_DIR = ".workbench";

    /** 节点输入目录名（相对 workspace 根） */
    public static final String INPUTS_DIR = "inputs";

    /** 已登记输出副本目录名（相对 workspace 根） */
    public static final String OUTPUTS_DIR = "outputs";

    /**
     * 文件类型白名单（docs/database/001_init.sql 的 {@code file_kind} 注释）：
     * {@code text = md/txt}，{@code document = docx/pdf/xlsx/pptx}，{@code any = 不限}。
     */
    private static final List<String> TEXT_EXTENSIONS = List.of("md", "txt", "markdown");

    /** document 类型允许的扩展名 */
    private static final List<String> DOCUMENT_EXTENSIONS = List.of("docx", "pdf", "xlsx", "pptx");

    private final WorkbenchStorage storage;

    public NodeStoragePaths(WorkbenchStorage storage) {
        this.storage = storage;
    }

    /**
     * 由需求的 workspace 绝对路径还原 workspace 目录（仍经存储根校验，防止库里被写入根外路径）。
     *
     * @param workspaceAbsolutePath {@code wb_requirement.dsh_workspace_path}
     * @return workspace 目录
     * @throws BizException 路径越出存储根 → 400/10001
     */
    public Path workspaceDir(String workspaceAbsolutePath) {
        return storage.resolveWithin(storage.root(), relativizeRoot(workspaceAbsolutePath));
    }

    /**
     * 模型输出目录：{@code <ws>/.workbench/<nodeInstanceId>}。
     *
     * @param workspace         需求 workspace 目录
     * @param nodeInstanceId    节点实例 ID
     * @return 输出目录（不创建）
     */
    public Path modelOutputDir(Path workspace, Long nodeInstanceId) {
        return requireInside(storage.resolveWithin(workspace, MODEL_DIR + "/" + nodeInstanceId),
                workspace, MODEL_DIR + "/" + nodeInstanceId);
    }

    /**
     * 模型输出目录（相对 workspace 根的写法，写给插件与模型看）：{@code .workbench/<nodeInstanceId>/}。
     *
     * @param nodeInstanceId 节点实例 ID
     * @return 相对路径（以 {@code /} 结尾）
     */
    public static String modelOutputDirRelative(Long nodeInstanceId) {
        return MODEL_DIR + "/" + nodeInstanceId + "/";
    }

    /**
     * 已登记输出副本的所在目录：{@code <ws>/outputs/<nodeSeq>/}。
     *
     * @param workspace 需求 workspace 目录
     * @param nodeSeq   节点 seq
     * @return 目录（不创建）
     */
    public Path outputCopyDir(Path workspace, Integer nodeSeq) {
        return requireInside(storage.resolveWithin(workspace, OUTPUTS_DIR + "/" + nodeSeq),
                workspace, OUTPUTS_DIR + "/" + nodeSeq);
    }

    /**
     * 新登记输出副本的路径。已有版本仍按数据库记录的路径读取。
     */
    public Path outputCopyPath(Path workspace, Integer nodeSeq, String itemKey, int versionNo, String extension) {
        String relative = OUTPUTS_DIR + "/" + nodeSeq + "/" + itemKey + ".v" + versionNo + "." + extension;
        return requireInside(storage.resolveWithin(workspace, relative), workspace, relative);
    }

    /** 同目录硬链接一次性发布完整文件，若目标已存在则保留原文件。 */
    void publishStagedFile(Path staged, Path target) {
        try {
            Files.createLink(target, staged);
        } catch (IOException | UnsupportedOperationException ex) {
            log.error("发布节点文件失败：{}", target, ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件写入失败：" + target.getFileName());
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 只有明确回滚才删除此次发布的文件，提交结果未知时保留以免丢失已登记的内容。
                if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(target);
                }
            }
        });
    }

    void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("清理节点文件失败：{}", path, ex);
        }
    }

    /**
     * 节点输入目录：{@code <ws>/inputs/<nodeSeq>/}。
     *
     * @param workspace 需求 workspace 目录
     * @param nodeSeq   节点 seq
     * @return 目录（不创建）
     */
    public Path inputDir(Path workspace, Integer nodeSeq) {
        return requireInside(storage.resolveWithin(workspace, INPUTS_DIR + "/" + nodeSeq),
                workspace, INPUTS_DIR + "/" + nodeSeq);
    }

    /**
     * 节点输入文件路径：{@code <ws>/inputs/<nodeSeq>/<fileId>_<原始文件名>}。
     * <p>
     * 文件名前缀用 {@code fileId} 而不是节点 ID：同一固定输入项允许重复上传，
     * 用文件 ID 保证同一目录下不会出现同名文件互相覆盖，也便于从文件名反查 {@code wb_file}。
     *
     * @param workspace       需求 workspace 目录
     * @param nodeSeq         节点 seq
     * @param fileId          文件 ID（先插入 {@code wb_file} 拿到）
     * @param safeFileName    已净化的原始文件名（只剩文件名部分）
     * @return 文件绝对路径（不创建）
     */
    public Path inputFilePath(Path workspace, Integer nodeSeq, Long fileId, String safeFileName) {
        String relative = INPUTS_DIR + "/" + nodeSeq + "/" + fileId + "_" + safeFileName;
        return requireInside(storage.resolveWithin(workspace, relative), workspace, relative);
    }

    /**
     * 计算"相对 workspace 根"的路径串（给模型 / 插件用，{@code /} 分隔）。
     *
     * @param workspace 需求 workspace 目录
     * @param file      其下的文件
     * @return 相对路径，如 {@code inputs/1/12_客户原话.md}
     */
    public static String relativeToWorkspace(Path workspace, Path file) {
        return workspace.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /**
     * 判断扩展名是否符合 {@code file_kind}（docs/database/001_init.sql 的列注释）。
     *
     * @param fileKind      text / document / any
     * @param fileName      文件名（取最后一个点之后的部分做扩展名）
     * @param allowAnyEmpty {@code any} 时是否允许"没有扩展名"（模型输出必须有扩展名，输入可以没有）
     * @return true 表示允许
     */
    public static boolean extensionAllowed(String fileKind, String fileName, boolean allowAnyEmpty) {
        String extension = extensionOf(fileName);
        String kind = fileKind == null ? "any" : fileKind.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "text" -> TEXT_EXTENSIONS.contains(extension);
            case "document" -> DOCUMENT_EXTENSIONS.contains(extension);
            // any：不限类型；但要求"有扩展名"时（模型输出）也不能是无名文件
            default -> allowAnyEmpty || !extension.isEmpty();
        };
    }

    /**
     * 取小写扩展名（不含点）。
     *
     * @param fileName 文件名
     * @return 扩展名；没有返回空串
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 存储根相对化：库里存的是绝对路径，转成相对存储根后再交给 resolveWithin 校验 */
    private String relativizeRoot(String absolutePath) {
        Path path = Path.of(absolutePath).toAbsolutePath().normalize();
        if (!path.startsWith(storage.root())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "需求 workspace 路径不在存储根内：" + absolutePath);
        }
        return storage.relativize(path);
    }

    /** 断言结果在 workspace 内（resolveWithin 已保证，这里再显式判一次，防未来改动绕过） */
    private static Path requireInside(Path candidate, Path workspace, String relative) {
        Path base = workspace.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "路径越出需求 workspace：" + relative);
        }
        return normalized;
    }
}

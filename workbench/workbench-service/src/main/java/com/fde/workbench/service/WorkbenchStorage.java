package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储根与相对路径的统一编排（docs/database.md §5.5 文件存储路径约定）。
 * <p>
 * 本类回答两个问题，且全项目只在这里回答一次：
 * <ol>
 *   <li><b>存储根在哪</b>：{@code fde.workbench.storage-root}（默认 {@code ./data}），
 *       按启动工作目录解析为绝对路径后规范化；不写死任何绝对路径。</li>
 *   <li><b>相对路径能不能用</b>：{@link #resolve(String)} / {@link #relativize(Path)}。
 *       所有拼接出来的路径都要经过它们，做"归一化 + 仍在根内"的判定。</li>
 * </ol>
 * <b>越界防护的统一口径</b>（技能解包、单文件预览、下载重打包都走这里）：
 * <ol>
 *   <li>只接受 {@code /} 分隔的相对路径串，空串、{@code \}、盘符（{@code C:}）、
 *       以 {@code /} 或 {@code ~} 开头的路径一律拒绝——绝对路径无法保证落在根内；</li>
 *   <li>按段组装，遇到 {@code .} / {@code ..} / 空段直接拒绝——不靠"拼接后 normalize 再比较"去救，
 *       因为那一步救得回 {@code a/../b}，救不回符号链接指向外部；直接拒绝更严格也更好排查；</li>
 *   <li>做 {@link String#startsWith} 之外再用 {@link Path#startsWith(Path)} 校验层级——防止
 *       {@code /data-evil} 被 {@code /data} 的前缀匹配误放过；</li>
 *   <li>对已存在的父目录做 {@link Files#isSymbolicLink} 检查——防"先放一个软链，再往里写"，
 *       写入会被重定向到根外。</li>
 * </ol>
 */
@Component
public class WorkbenchStorage {

    /** 技能包在存储根下的固定前缀：projects/&lt;projectId&gt;/skills/&lt;skillId&gt;/v&lt;versionNo&gt;/ */
    private static final String PROJECTS_DIR = "projects";

    /** 技能子目录名 */
    private static final String SKILLS_DIR = "skills";

    /** DSH workspace 根目录名（docs/database.md §5.5：{@code <storage-root>/ws/<requirementId>}） */
    private static final String WORKSPACE_DIR = "ws";

    /** 相对路径长度上限（与 wb_file.storage_rel_path VARCHAR(1000) 对齐，留出余量） */
    private static final int MAX_RELATIVE_LENGTH = 1000;

    private final Path root;

    /**
     * 构造时解析并创建存储根。
     *
     * @param properties 存储配置
     */
    public WorkbenchStorage(WorkbenchStorageProperties properties) {
        String configured = properties.getStorageRoot();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("fde.workbench.storage-root 未配置");
        }
        // 相对路径按启动工作目录解析（默认 ./data → workbench/data），toAbsolutePath 后再 normalize 消除 ./ 与 ../
        this.root = Paths.get(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("存储根目录无法创建：" + root, ex);
        }
    }

    /** 规范化后的存储根绝对路径（日志与排查用） */
    public Path root() {
        return root;
    }

    /**
     * 技能版本目录：{@code <root>/projects/<projectId>/skills/<skillId>/v<versionNo>}。
     * <p>
     * 只做路径拼接与"根内"校验，不创建目录（目录由 {@link #createVersionDir} 负责），
     * 这样读取类操作（预览 / 下载）不会因为路径不存在而在磁盘上造出空目录。
     *
     * @param projectId 项目 ID
     * @param skillId   技能 ID
     * @param versionNo 版本号
     * @return 版本目录绝对路径
     */
    public Path skillVersionDir(Long projectId, Long skillId, Integer versionNo) {
        if (projectId == null || skillId == null || versionNo == null) {
            // ID 与版本号必须先是已落库的数字，才能拼出目录；为空说明调用方顺序错了
            throw new IllegalStateException("技能版本目录参数为空：projectId=" + projectId
                    + ", skillId=" + skillId + ", versionNo=" + versionNo);
        }
        String relative = PROJECTS_DIR + "/" + projectId
                + "/" + SKILLS_DIR + "/" + skillId
                + "/v" + versionNo;
        return resolve(relative);
    }

    /**
     * 需求 DSH workspace 目录：{@code <root>/ws/<requirementId>}（绝对路径，docs/database.md §5.5）。
     * <p>
     * 只做拼接与"根内"校验，不创建目录（创建由 {@link #createRequirementWorkspace} 负责），
     * 这样读取类操作（T5-1 的节点上下文、会话定位）不会因为目录不存在而在磁盘上造出空目录。
     * 数据库 {@code wb_requirement.dsh_workspace_path} 存的就是本方法的返回值（字符串形态）：
     * 模型与 DSH 都以"绝对路径"作为 workspace 根（docs/architecture.md AD-4：需求 = 一个 workspace）。
     *
     * @param requirementId 需求 ID（必须已是已落库的自增 ID）
     * @return workspace 目录绝对路径
     */
    public Path requirementWorkspaceDir(Long requirementId) {
        if (requirementId == null) {
            throw new IllegalStateException("需求 workspace 目录参数为空：requirementId=null");
        }
        return resolve(WORKSPACE_DIR + "/" + requirementId);
    }

    /**
     * 创建需求 workspace 目录，返回绝对路径字符串（用于 {@code dsh_workspace_path}）。
     * <p>
     * 若目录已存在，返回"是否由本次创建"的标记而不是直接成功：按 {@code ws/<requirementId>}
     * 这个可预测路径创建时，<b>已存在的目录可能属于另一个需求</b>（MySQL 自增 ID 在回滚后会被复用）。
     * 调用方据此判定"撞路径 → 409 重试"，避免两个需求共用一个 workspace 造成会话与文件互相可见
     * （PRD REQ-X1 要求需求级隔离）。
     *
     * @param requirementId 需求 ID
     * @return 创建结果（绝对路径 + 是否新建）
     * @throws BizException 路径越界 → 400/10001；磁盘不可写 → 502/19902
     */
    public WorkspaceCreation createRequirementWorkspace(Long requirementId) {
        Path dir = requirementWorkspaceDir(requirementId);
        // 先检查符号链接：ws/<id> 若被换成指向根外的软链，写入会被重定向到存储根之外
        requireNoSymlink(dir);
        boolean existed = Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "需求 workspace 目录创建失败：" + dir);
        }
        return new WorkspaceCreation(dir.toString(), !existed);
    }

    /**
     * 删除需求 workspace 目录（需求物理删除后清理自己的目录）。
     * <p>
     * 只删 {@code ws/<requirementId>} 这一层：编辑器 / 模型可能在其中写了大量文件，递归删除会
     * 变成潜在的危险操作，因此目录非空时保留并返回 false，由运维按留存策略处理
     * （api.md §7 / PRD AC-F1.3："文件按留存策略保留或清理"——这里选择保留非空目录）。
     *
     * @param requirementId 需求 ID
     * @return true 表示目录已不存在或已删除；false 表示目录非空被保留
     */
    public boolean deleteRequirementWorkspaceIfEmpty(Long requirementId) {
        Path dir = requirementWorkspaceDir(requirementId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.delete(dir);
            return true;
        } catch (DirectoryNotEmptyException ex) {
            return false;
        } catch (IOException ex) {
            // 删除失败不影响业务结果（数据库已删干净），只记录日志
            return false;
        }
    }

    /**
     * workspace 目录创建结果。
     *
     * @param absolutePath 目录绝对路径（字符串，直接写入 dsh_workspace_path）
     * @param created      true 表示本次新建；false 表示目录在创建前就已存在
     */
    public record WorkspaceCreation(String absolutePath, boolean created) {
    }

    /**
     * 把存储根下的相对路径解析为绝对路径，并保证仍在根内。
     *
     * @param relativePath 相对存储根的路径（{@code /} 分隔）
     * @return 绝对路径
     * @throws BizException 路径非法或越出存储根 → 400/10001
     */
    public Path resolve(String relativePath) {
        String normalized = validateRelative(relativePath);
        Path candidate = root;
        for (String segment : normalized.split("/")) {
            candidate = candidate.resolve(segment);
        }
        // 层级校验：candidate 必须以 root 为父级链（Path.startsWith 按路径段比较，不是字符串前缀）
        if (!candidate.normalize().startsWith(root)) {
            throw reject(relativePath, "路径越出存储根");
        }
        return candidate;
    }

    /**
     * 计算路径相对存储根的路径串（{@code /} 分隔），用于 {@code wb_file.storage_rel_path}。
     *
     * @param path 根内的绝对路径
     * @return 相对路径，如 {@code projects/1/skills/3/v1/SKILL.md}
     * @throws BizException 路径不在根内 → 400/10001
     */
    public String relativize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw reject(path.toString(), "路径越出存储根");
        }
        // 统一用 / 分隔：Windows 上 Path.toString 是 \\，入库后再解析会歧义
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    /**
     * 校验"用户输入的相对路径"并返回规范化结果，同时保证它落在指定目录内。
     * <p>
     * 供单文件预览使用：{@code path=SKILL.md} 这类参数必须同时满足
     * "是版本清单里的文件"（调用方校验）与"归一化后仍在版本目录内"（本方法校验）。
     *
     * @param baseDir    必须落在这里面的目录（某版本的绝对目录）
     * @param relativePath 用户输入的相对路径
     * @return 绝对路径（已确认在 baseDir 内）
     * @throws BizException 路径非法 / 越界 → 400/10001
     */
    public Path resolveWithin(Path baseDir, String relativePath) {
        String normalized = validateRelative(relativePath);
        Path base = baseDir.toAbsolutePath().normalize();
        Path candidate = base;
        for (String segment : normalized.split("/")) {
            candidate = candidate.resolve(segment);
        }
        if (!candidate.normalize().startsWith(base)) {
            throw reject(relativePath, "路径越出目标目录");
        }
        return candidate;
    }

    /**
     * 创建技能版本目录（已存在则直接返回）。
     *
     * @param projectId 项目 ID
     * @param skillId   技能 ID
     * @param versionNo 版本号
     * @return 版本目录
     */
    public Path createVersionDir(Long projectId, Long skillId, Integer versionNo) {
        Path dir = skillVersionDir(projectId, skillId, versionNo);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "技能存储目录创建失败");
        }
        return dir;
    }

    /**
     * 在存储根下创建（必要时）一个目录，并确认路径仍在根内。
     *
     * @param relativePath 相对存储根的目录路径
     * @return 目录绝对路径
     * @throws BizException 路径非法 → 400/10001；创建失败 → 502/19902
     */
    public Path createDir(String relativePath) {
        Path dir = resolve(relativePath);
        requireNoSymlink(dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "存储目录创建失败");
        }
        return dir;
    }

    /**
     * 确保存储根下的某个目录存在（已存在直接返回），并做软链校验。
     * <p>
     * 与 {@link #createDir(String)} 的区别：本方法接受<b>绝对路径</b>（调用方已经从
     * workspace 解析出绝对目录），内部再校验它仍在存储根内。T5-1 的
     * {@code <ws>/.workbench/<nodeInstanceId>/}、{@code <ws>/inputs/<seq>/} 用它创建。
     *
     * @param dir 根内的目录绝对路径
     * @return 目录绝对路径
     * @throws BizException 路径越出根 / 经过符号链接 → 400/10001；创建失败 → 502/19902
     */
    public Path createDirWithin(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw reject(dir.toString(), "路径越出存储根");
        }
        requireNoSymlink(normalized);
        try {
            Files.createDirectories(normalized);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "存储目录创建失败：" + normalized);
        }
        return normalized;
    }

    /**
     * 校验并规范化相对路径串。
     *
     * @param relativePath 原始相对路径
     * @return 规范化后的相对路径（段之间单个 {@code /}，已去掉 {@code ./} 之类无意义成分）
     * @throws BizException 非法 → 400/10001
     */
    private String validateRelative(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            throw reject(relativePath, "路径为空");
        }
        if (relativePath.length() > MAX_RELATIVE_LENGTH) {
            throw reject(relativePath, "路径过长");
        }
        // 反斜杠：ZipEntry 规范用 /；Windows 下 \ 也是分隔符，混用会导致 normalize 语义不一致
        if (relativePath.indexOf('\\') >= 0) {
            throw reject(relativePath, "路径不得包含反斜杠");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("~")) {
            throw reject(relativePath, "不允许绝对路径");
        }
        // 盘符（C:、c:/）：Windows 绝对路径，在 Unix 上会被当成普通文件名，必须显式拒绝
        if (relativePath.length() >= 2 && relativePath.charAt(1) == ':') {
            throw reject(relativePath, "不允许带盘符的绝对路径");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw reject(relativePath, "路径包含非法段：" + segment);
            }
            if (segment.indexOf('\0') >= 0) {
                throw reject(relativePath, "路径包含空字符");
            }
        }
        return relativePath;
    }

    /**
     * 检查路径的各级目录祖先是否为符号链接（同级目录先被软链换掉的情况）。
     * <p>
     * 场景：攻击者把 {@code projects/1/skills/3} 换成指向 {@code /etc} 的软链，后续上传就会写到根外。
     * 路径段校验已经挡住 {@code ..} 与绝对路径，这里只需自顶向下检查"已存在的目录"不是软链即可
     * （不存在的目录稍后由本类创建，必然是普通目录）。
     * <p>
     * 只检查目录、不检查最后一级文件本身：上传流程总是"先写临时文件再原子 rename"，
     * rename 会替换掉目标处的软链而不是顺着它写入，所以文件级软链不会把内容写到根外。
     *
     * @param file 根内的文件路径（可以是尚不存在的文件）
     * @throws BizException 目录祖先中存在符号链接 → 400/10001
     */
    public void requireNoSymlink(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw reject(file.toString(), "路径越出存储根");
        }
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw reject(relativize(current), "路径经过符号链接");
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                // 已到文件级或尚不存在：余下各级都由本类创建，不会是软链
                return;
            }
        }
    }

    private static BizException reject(String path, String reason) {
        return BizException.of(ErrorCode.PARAM_INVALID, "非法路径（" + reason + "）：" + path);
    }
}

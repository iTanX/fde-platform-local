package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.FileRecord;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.mapper.FileMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.vo.FileVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 文件接口（docs/api.md §10）：元信息、下载、文本 / 图片预览。
 * <p>
 * <b>权限口径</b>：登录用户必须是文件所属项目的成员（M）。文件自身只记录 {@code project_id}，
 * 不再回溯到需求 / 节点做逐级校验——需求与节点都隶属于同一个项目，
 * 项目成员边界就是文件可见边界（与项目内其他资源一致）。
 * <p>
 * <b>磁盘读取的两道防线</b>：
 * <ol>
 *   <li>库里的 {@code storage_rel_path} 只是"数据"，读之前一律经
 *       {@link WorkbenchStorage#resolveWithin} 重新校验（防库被写入 {@code ../} 之类路径）；</li>
 *   <li>拒绝符号链接与非常规文件（{@link Files#isSymbolicLink}），防止有人把文件换成指向存储根外的软链。</li>
 * </ol>
 * 技能包文件（{@code ref_type=skill_file}）没有 {@code requirement_id}，本条链路不负责它们的下载
 * （技能下载走 {@code /api/skills/{id}/versions/{v}/download}）。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** 可预览的文本后缀 */
    private static final Set<String> TEXT_TYPES = Set.of("md", "markdown", "txt");

    /** 可预览的图片后缀 */
    private static final Set<String> IMAGE_TYPES = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** 预览文本的最大字节数：超过就截断，避免把几十 MiB 的文本塞给浏览器 */
    private static final int MAX_PREVIEW_BYTES = 512 * 1024;

    private final FileMapper fileMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    private final WorkbenchStorage storage;

    public FileService(FileMapper fileMapper,
                       ProjectAccessService projectAccessService,
                       CurrentUserContext currentUserContext,
                       WorkbenchStorage storage) {
        this.fileMapper = fileMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.storage = storage;
    }

    /**
     * 文件元信息（项目成员即可）。
     *
     * @param fileId 文件 ID
     * @return 元信息
     * @throws BizException 文件不存在 404/10301；项目不存在 404/10301；非成员 403/10201
     */
    public FileVO metadata(Long fileId) {
        FileRecord file = requireAccessibleFile(fileId);
        return new FileVO(file.getId(), file.getProjectId(), file.getRequirementId(), file.getRefType(),
                file.getOriginalName(), file.getMimeType(), file.getSizeBytes(), file.getSha256(),
                file.getUploadedBy(), FileVO.toOffset(file.getCreatedAt()), previewable(file.getOriginalName()));
    }

    /**
     * 下载（内容整体读入内存：节点输入 / 输出都有大小上限，最大 20 MiB，不会撑爆堆）。
     *
     * @param fileId 文件 ID
     * @return 下载内容
     * @throws BizException 文件不存在 404/10301；非成员 403/10201；磁盘文件缺失 404/10301
     */
    public Download download(Long fileId) {
        FileRecord file = requireAccessibleFile(fileId);
        Path path = requireReadablePath(file);
        try {
            return new Download(file.getOriginalName(), file.getMimeType(), Files.readAllBytes(path));
        } catch (IOException ex) {
            log.error("读取文件失败：fileId={}, path={}", fileId, path, ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件读取失败");
        }
    }

    /**
     * 预览：md / txt 返回 UTF-8 文本（超长截断），png/jpg 等返回原始字节，其他类型 415。
     *
     * @param fileId 文件 ID
     * @return 预览内容
     * @throws BizException 文件不存在 404/10301；非成员 403/10201；类型不支持预览 415/10502
     */
    public Preview preview(Long fileId) {
        FileRecord file = requireAccessibleFile(fileId);
        String extension = extensionOf(file.getOriginalName());
        Path path = requireReadablePath(file);
        try {
            if (TEXT_TYPES.contains(extension)) {
                byte[] bytes = Files.readAllBytes(path);
                int length = Math.min(bytes.length, MAX_PREVIEW_BYTES);
                String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
                return new Preview("text", "text/plain; charset=utf-8", text, null,
                        bytes.length > MAX_PREVIEW_BYTES, bytes.length);
            }
            if (IMAGE_TYPES.contains(extension)) {
                byte[] bytes = Files.readAllBytes(path);
                return new Preview("image", mimeOfImage(extension), null, bytes, false, bytes.length);
            }
        } catch (IOException ex) {
            log.error("读取文件失败：fileId={}, path={}", fileId, path, ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件读取失败");
        }
        throw BizException.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "该文件类型不支持预览，请直接下载：" + file.getOriginalName());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 取文件并校验"登录用户是本项目成员"。
     *
     * @param fileId 文件 ID
     * @return 文件登记
     * @throws BizException 文件不存在 404/10301；项目不存在 404/10301；非成员 403/10201
     */
    private FileRecord requireAccessibleFile(Long fileId) {
        CurrentUser user = currentUserContext.require();
        FileRecord file = fileId == null ? null : fileMapper.selectById(fileId);
        if (file == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        }
        ProjectWithStats project = projectAccessService.requireExistingProject(file.getProjectId());
        projectAccessService.requireMember(project.getId(), user.userId());
        return file;
    }

    /**
     * 由库中相对路径得到可读的磁盘文件（路径重校验 + 拒绝软链）。
     *
     * @param file 文件登记
     * @return 磁盘路径
     * @throws BizException 路径越界 400/10001；文件缺失 / 异常 404/10301
     */
    private Path requireReadablePath(FileRecord file) {
        // resolveWithin(root, rel) 会拒绝 ""、"/"、"~"、"C:"、".." 等非法段，越界即 400
        Path path = storage.resolveWithin(storage.root(), file.getStorageRelPath());
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件内容不存在：" + file.getOriginalName());
        }
        return path;
    }

    /** 是否可预览（md / txt / 常见图片） */
    private static boolean previewable(String fileName) {
        String extension = extensionOf(fileName);
        return TEXT_TYPES.contains(extension) || IMAGE_TYPES.contains(extension);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mimeOfImage(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    /**
     * 下载内容。
     *
     * @param fileName 原始文件名（下载名）
     * @param mimeType 库中 MIME，可空（为空时浏览器按二进制处理）
     * @param content  文件字节
     */
    public record Download(String fileName, String mimeType, byte[] content) {
    }

    /**
     * 预览内容。
     *
     * @param kind       text / image
     * @param mimeType   响应用的 MIME
     * @param text       文本内容（kind=text 时非空）
     * @param bytes      原始字节（kind=image 时非空）
     * @param truncated  文本是否被截断
     * @param totalBytes 文件总字节数
     */
    public record Preview(String kind, String mimeType, String text, byte[] bytes,
                          boolean truncated, long totalBytes) {
    }
}

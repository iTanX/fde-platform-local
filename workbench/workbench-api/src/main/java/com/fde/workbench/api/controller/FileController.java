package com.fde.workbench.api.controller;

import com.fde.workbench.api.vo.FileVO;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.FileService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件接口（docs/api.md §10）：元信息、下载、预览。
 * <p>
 * 权限（登录用户是文件所属项目成员）在 {@link FileService} 中判定。
 * 下载用 {@code ContentDisposition} 生成 {@code filename*=UTF-8''...}（RFC 5987），
 * 同时给 ASCII 回退名，保证中文文件名在各浏览器下都不乱码。
 */
@RestController
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * GET /api/files/{fileId} —— 文件元信息（项目成员即可）。
     *
     * @param fileId 文件 ID
     * @return 元信息
     */
    @GetMapping(value = "/api/files/{fileId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<FileVO> metadata(@PathVariable Long fileId) {
        return ApiResult.ok(FileVO.of(fileService.metadata(fileId)));
    }

    /**
     * GET /api/files/{fileId}/download —— 下载（{@code attachment}）。
     *
     * @param fileId 文件 ID
     * @return 文件流
     */
    @GetMapping("/api/files/{fileId}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable Long fileId) {
        FileService.Download download = fileService.download(fileId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        MediaType contentType = resolveContentType(download.fileName(), download.mimeType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 输出文件可能被重新提交（新版本新文件 ID），按路径缓存是安全的
                .cacheControl(CacheControl.noCache())
                .contentType(contentType)
                .contentLength(download.content().length)
                .body(new ByteArrayResource(download.content()));
    }

    /**
     * GET /api/files/{fileId}/preview —— 预览（md / txt 返回文本，png/jpg 等返回图片字节）。
     * <p>
     * 其他类型返回 {@code 415/10502}，由全局异常处理统一输出 JSON。
     *
     * @param fileId 文件 ID
     * @return 预览内容
     */
    @GetMapping("/api/files/{fileId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long fileId) {
        FileService.Preview preview = fileService.preview(fileId);
        // 文本统一用 UTF-8 字节输出（与 "text/plain; charset=utf-8" 一致），图片原样输出
        byte[] body = preview.bytes() != null
                ? preview.bytes()
                : preview.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(preview.mimeType()))
                .header("X-Preview-Truncated", Boolean.toString(preview.truncated()))
                .header("X-File-Total-Bytes", Long.toString(preview.totalBytes()))
                .contentLength(body.length)
                .body(body);
    }

    /**
     * 下载响应的 Content-Type。
     * <p>
     * 优先用库里记录的 MIME；没有时按扩展名兜底（节点输出由模型写入，入库时没有探测 MIME），
     * 兜底只认文本 / 图片这几类——其它一律 {@code application/octet-stream}，避免浏览器错误内联渲染
     * 未知内容（安全与体验都更稳）。
     *
     * @param fileName 原始文件名
     * @param mimeType 库中 MIME，可空
     * @return 响应类型
     */
    private static MediaType resolveContentType(String fileName, String mimeType) {
        if (mimeType != null && !mimeType.isBlank()) {
            try {
                return MediaType.parseMediaType(mimeType);
            } catch (IllegalArgumentException ex) {
                // 库里写了非法的 MIME（历史数据）→ 退回扩展名判断，不让下载失败
            }
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return MediaType.parseMediaType("text/markdown; charset=utf-8");
        }
        if (lower.endsWith(".txt")) {
            return MediaType.parseMediaType("text/plain; charset=utf-8");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}

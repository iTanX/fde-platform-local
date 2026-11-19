package com.fde.workbench.api.controller;

import com.fde.workbench.api.vo.SkillFileVO;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.SkillService;
import com.fde.workbench.service.vo.SkillVO;
import com.fde.workbench.service.vo.SkillVersionVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.List;

/**
 * 技能接口（docs/api.md §5）：列表、上传、版本列表、按版本下载、单文件预览、删除。
 * <p>
 * Controller 只负责参数绑定与 VO 转换；解包校验、大小限制、路径越界防护、版本递增、引用校验
 * 全部在 {@link SkillService} / {@link com.fde.workbench.service.SkillPackageExtractor} /
 * {@link com.fde.workbench.service.WorkbenchStorage} 中完成。
 * <p>
 * 路由说明：列表与上传挂在 {@code /api/projects/{projectId}/skills} 下；
 * 版本 / 下载 / 预览 / 删除挂在 {@code /api/skills/{skillId}} 下（与 api.md 表格一致），
 * 因此本类不加类级 {@code @RequestMapping}，每个方法写完整路径。
 */
@RestController
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * GET /api/projects/{projectId}/skills —— 技能列表（项目成员即可）。
     *
     * @param projectId 项目 ID
     * @return 技能列表（含当前版本号与引用数）
     */
    @GetMapping(value = "/api/projects/{projectId}/skills", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<SkillVO>> list(@PathVariable Long projectId) {
        return ApiResult.ok(skillService.list(projectId));
    }

    /**
     * POST /api/projects/{projectId}/skills —— 上传 / 升级技能包（multipart，字段名 {@code file}）。
     * <p>
     * 失败：项目内没有 SKILL.md → 400/10001；路径穿越或含符号链接 → 400/10001；
     * 超出大小限制 → 413/10501。
     *
     * @param projectId 项目 ID
     * @param file      技能包 zip
     * @return 上传后的技能（{@code currentVersionNo} 为新版本号）
     */
    @PostMapping(value = "/api/projects/{projectId}/skills", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SkillVO> upload(@PathVariable Long projectId,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        // Spring 的 MultipartFile 由容器负责"过大"判定（超过 spring.servlet.multipart.max-file-size 时
        // 直接由容器拒绝，不会进到这里）；这里只把流交给 service，由它边解边判单文件 / 总量上限。
        try (InputStream in = file.getInputStream()) {
            return ApiResult.ok(skillService.upload(projectId, file.getOriginalFilename(), in));
        }
    }

    /**
     * GET /api/skills/{skillId}/versions —— 版本列表（项目成员即可，版本号升序）。
     *
     * @param skillId 技能 ID
     * @return 版本列表（含文件清单）
     */
    @GetMapping(value = "/api/skills/{skillId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<SkillVersionVO>> versions(@PathVariable Long skillId) {
        return ApiResult.ok(skillService.listVersions(skillId));
    }

    /**
     * GET /api/skills/{skillId}/versions/{versionNo}/download —— 按版本重打包下载（zip）。
     * <p>
     * {@code Content-Disposition} 使用 {@code filename*=UTF-8''...}（RFC 5987）编码，
     * 同时给出 ASCII 回退名，保证中文技能名在各浏览器下都不乱码。
     *
     * @param skillId   技能 ID
     * @param versionNo 版本号
     * @return zip 文件流
     */
    @GetMapping("/api/skills/{skillId}/versions/{versionNo}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long skillId, @PathVariable Integer versionNo) {
        SkillService.DownloadContent content = skillService.download(skillId, versionNo);
        String ascii = "skill-v" + versionNo + ".zip";
        // URLEncoder 把空格编成 +，而 RFC 5987 的百分号编码里空格必须是 %20，这里补齐
        String encoded = URLEncoder.encode(content.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(content.content().length)
                .body(content.content());
    }

    /**
     * GET /api/skills/{skillId}/files?path=SKILL.md[&amp;versionNo=1] —— 单文件预览。
     * <p>
     * {@code versionNo} 缺省时预览当前版本：api.md §5 只要求 {@code path}，
     * 但版本抽屉里需要"看某一版的 SKILL.md"，因此多给一个可选参数（不改变缺省行为）。
     *
     * @param skillId   技能 ID
     * @param path      版本内相对路径（必须在该版本文件清单内）
     * @param versionNo 版本号，缺省为当前版本
     * @return 文件内容（文本）
     */
    @GetMapping(value = "/api/skills/{skillId}/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SkillFileVO> preview(@PathVariable Long skillId,
                                          @RequestParam("path") String path,
                                          @RequestParam(value = "versionNo", required = false) Integer versionNo) {
        return ApiResult.ok(SkillFileVO.of(skillService.previewFile(skillId, versionNo, path)));
    }

    /**
     * DELETE /api/skills/{skillId} —— 删除技能（仅上传者或项目 owner；逻辑删除）。
     * <p>
     * 被未完成需求的节点实例引用时返回 {@code 409/10403}，{@code data.references} 给出引用清单。
     *
     * @param skillId 技能 ID
     * @return 空数据
     */
    @DeleteMapping(value = "/api/skills/{skillId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Void> delete(@PathVariable Long skillId) {
        skillService.delete(skillId);
        return ApiResult.ok();
    }
}

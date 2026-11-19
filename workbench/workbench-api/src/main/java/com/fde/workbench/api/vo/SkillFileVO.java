package com.fde.workbench.api.vo;

import com.fde.workbench.service.SkillService.SkillFileContent;

/**
 * 单文件预览视图（docs/api.md §5 GET /api/skills/{skillId}/files?path=SKILL.md）。
 * <p>
 * {@code content} 为按 UTF-8 解码的文本；{@code sha256} 是清单里记录的摘要，
 * 前端可以用它判断"预览内容是否与上传时一致"。
 *
 * @param path    版本内相对路径
 * @param size    文件大小（字节）
 * @param sha256  文件摘要
 * @param content 文本内容
 */
public record SkillFileVO(String path, long size, String sha256, String content) {

    /**
     * 由服务层结果转换。
     *
     * @param file 文件内容
     * @return 预览视图
     */
    public static SkillFileVO of(SkillFileContent file) {
        return new SkillFileVO(file.path(), file.size(), file.sha256(), file.content());
    }
}

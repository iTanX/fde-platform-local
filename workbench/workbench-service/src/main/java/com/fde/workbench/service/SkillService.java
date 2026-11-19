package com.fde.workbench.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.Skill;
import com.fde.workbench.dao.entity.SkillReference;
import com.fde.workbench.dao.entity.SkillVersion;
import com.fde.workbench.dao.entity.SkillWithStats;
import com.fde.workbench.dao.mapper.SkillMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.vo.SkillReferencesVO;
import com.fde.workbench.service.vo.SkillVO;
import com.fde.workbench.service.vo.SkillVersionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 技能管理服务（docs/api.md §5，PRD REQ-D1 上传 / REQ-D2 删除受引用约束）。
 * <p>
 * 权限（统一走 {@link ProjectAccessService}，本类不另写一套）：
 * <ul>
 *   <li>列表 / 上传 / 版本 / 下载 / 预览：项目成员即可（M）；</li>
 *   <li>删除：数据创建者本人或项目 owner（C）。</li>
 * </ul>
 * 事务边界（为什么把"磁盘写入"放在事务外）：
 * <ol>
 *   <li>校验（权限、大小、解包、SKILL.md）与<b>暂存目录解包</b>都在事务外完成——
 *       这些都只读或只写临时目录，失败时不需要回滚数据库，也不该让长事务占着连接；</li>
 *   <li>短事务内只做"插技能行 + 插版本行 + 回填 current_version_id"（{@link TransactionTemplate}），
 *       保证不会出现"版本写了、技能没写"或"技能指向不存在的版本"；</li>
 *   <li>事务提交成功后再"暂存目录 → 正式目录"原子 rename，并清理失败残留。
 *       反过来（先落正式目录再提交）一旦提交失败就会留下无人引用的孤儿目录；
 *       现在的顺序最坏情况是"提交成功但 rename 失败"，此时下一版上传会覆盖同一目录，不会脏读。</li>
 * </ol>
 * 删除：{@code wb_skill.deleted=1} 逻辑删除，版本行保留（历史需求按 {@code skills_snapshot} 追溯）。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** SKILL.md 中可选的 frontmatter 字段：技能说明 */
    private static final String FRONTMATTER_KEY_DESCRIPTION = "description";

    /** wb_skill.name 长度上限 */
    private static final int MAX_NAME_LENGTH = 128;

    /** wb_skill.description 长度上限 */
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    /** 暂存目录前缀：与正式目录同级（同一文件系统 → rename 才能原子） */
    private static final String STAGING_PREFIX = ".uploading-";

    private final SkillMapper skillMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    private final WorkbenchStorage storage;

    private final SkillPackageExtractor extractor;

    private final TransactionTemplate transactionTemplate;

    /** files_json 的序列化与解析（结构见 {@link SkillPackageExtractor.ExtractResult.FileEntry}） */
    private final ObjectMapper objectMapper;

    public SkillService(SkillMapper skillMapper,
                        ProjectAccessService projectAccessService,
                        CurrentUserContext currentUserContext,
                        WorkbenchStorage storage,
                        SkillPackageExtractor extractor,
                        TransactionTemplate transactionTemplate,
                        ObjectMapper objectMapper) {
        this.skillMapper = skillMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.storage = storage;
        this.extractor = extractor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 项目技能列表（项目成员即可）。
     *
     * @param projectId 项目 ID
     * @return 技能列表（含当前版本号与引用数）
     * @throws BizException 项目不存在 404/10301；非成员 403/10201
     */
    public List<SkillVO> list(Long projectId) {
        Long userId = currentUserContext.require().userId();
        projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, userId);
        return skillMapper.selectByProject(projectId).stream().map(SkillVO::of).toList();
    }

    // ------------------------------------------------------------------
    // 上传 / 升级
    // ------------------------------------------------------------------

    /**
     * 上传技能包（同名 → 新版本，不同名 → 新技能 + v1）。
     * <p>
     * 技能名取"解包后入口 SKILL.md 所在目录名；SKILL.md 在包根时取上传文件名（去 .zip）"，
     * 这样不需要前端额外传 name，也符合"一个 zip 就是一个技能包"的直觉。
     *
     * @param projectId      项目 ID
     * @param originalFileName 上传的 zip 原始文件名（用于包根 SKILL.md 的命名）
     * @param zip            zip 输入流（只在本次调用内读取）
     * @return 上传后的技能（{@code currentVersionNo} 已是新版本号）
     * @throws BizException 项目不存在 404/10301；非成员 403/10201；
     *                      无 SKILL.md / 非法路径 / 包名无法得到技能名 400/10001；超出大小限制 413/10501
     */
    public SkillVO upload(Long projectId, String originalFileName, InputStream zip) {
        CurrentUser user = currentUserContext.require();
        projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, user.userId());

        // ① 解包到暂存目录（事务外）：任何校验失败都在这里返回，数据库与正式目录都不会被动过
        // 暂存目录放在"正式目录的父目录"下：同一文件系统内的 move 才能保证原子
        Path skillDir = storage.createDir("projects/" + projectId + "/skills");
        Path staging = skillDir.resolve(STAGING_PREFIX + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            SkillPackageExtractor.ExtractResult result = extractor.extract(zip, staging);
            String skillName = resolveSkillName(result, originalFileName);
            String description = readFrontmatterDescription(staging, result.entryPath());

            // ② 短事务：写技能行与版本行，并回填 current_version_id
            UploadOutcome outcome = transactionTemplate.execute(status ->
                    persistUpload(projectId, user.userId(), skillName, description, result));

            // ③ 事务提交后：暂存目录原子搬到正式目录 <root>/projects/<pid>/skills/<skillId>/v<versionNo>
            Path formalDir = storage.skillVersionDir(projectId, outcome.skillId(), outcome.versionNo());
            storage.requireNoSymlink(formalDir);
            Files.createDirectories(formalDir.getParent());
            Files.move(staging, formalDir, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("上传技能：projectId={}, skillId={}, versionNo={}, 文件数={}, 入口={}, 操作人={}",
                    projectId, outcome.skillId(), outcome.versionNo(), result.files().size(),
                    result.entryPath(), user.userId());
            return reloadSkillVO(outcome.skillId());
        } catch (IOException ex) {
            log.error("技能包落盘失败：projectId={}", projectId, ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "技能包存储失败");
        } finally {
            // 无论成功失败都清理暂存目录（成功时已被 rename，这里只剩空壳或失败残留）
            deleteQuietly(staging);
        }
    }

    /**
     * 事务内：插入技能（或复用同名技能）与版本，回填 {@code current_version_id}。
     *
     * @param projectId    项目 ID
     * @param userId       上传人
     * @param skillName    技能名
     * @param description  技能说明
     * @param result       解包结果
     * @return 技能 ID 与版本号
     */
    private UploadOutcome persistUpload(Long projectId, Long userId, String skillName,
                                        String description, SkillPackageExtractor.ExtractResult result) {
        LocalDateTime now = LocalDateTime.now();
        Skill existing = skillMapper.selectByProjectAndName(projectId, skillName);
        long skillId;
        if (existing == null) {
            Skill skill = new Skill();
            skill.setProjectId(projectId);
            skill.setName(skillName);
            skill.setDescription(description);
            skill.setCreatedBy(userId);
            skill.setCreatedAt(now);
            skill.setUpdatedAt(now);
            skillMapper.insert(skill);
            skillId = skill.getId();
        } else {
            skillId = existing.getId();
        }

        Integer maxVersionNo = skillMapper.selectMaxVersionNo(skillId);
        int nextVersionNo = maxVersionNo == null ? 1 : maxVersionNo + 1;

        SkillVersion version = new SkillVersion();
        version.setSkillId(skillId);
        version.setVersionNo(nextVersionNo);
        version.setFilesJson(toJson(result.files()));
        version.setChecksum(result.checksum());
        version.setEntryPath(result.entryPath());
        version.setUploadedBy(userId);
        version.setCreatedAt(now);
        skillMapper.insertVersion(version);
        // 新版本总是成为当前版本（PRD AC-D1.2："节点默认使用最新版本"）
        skillMapper.updateCurrentVersion(skillId, version.getId(), now);
        return new UploadOutcome(skillId, nextVersionNo);
    }

    /**
     * 技能名：入口在 {@code skills/<name>/} 下取该目录名；入口在包根取 zip 文件名（去 .zip）。
     *
     * @param result           解包结果
     * @param originalFileName zip 原始文件名
     * @return 技能名（长度已校验）
     */
    private static String resolveSkillName(SkillPackageExtractor.ExtractResult result, String originalFileName) {
        String name = result.skillName() != null ? result.skillName() : stripZipSuffix(originalFileName);
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            // 包根放 SKILL.md 时必须靠文件名命名；文件名给不出来就明确报错，不猜
            throw BizException.of(ErrorCode.PARAM_INVALID, "无法确定技能名：请把 SKILL.md 放在 skills/<name>/ 下，或提供 zip 文件名");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "技能名不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return name;
    }

    /**
     * 从入口 SKILL.md 的 frontmatter 读取 {@code description}（可空）。
     * <p>
     * 只认最简单的 {@code key: value} 形式，不引 YAML 依赖：解析失败 / 文件不是 UTF-8 都视为"没有说明"，
     * 不应让一个格式不完美的技能包传不上去。
     *
     * @param staging   暂存目录
     * @param entryPath 入口文件相对路径
     * @return 说明文本；不存在返回 null
     */
    private String readFrontmatterDescription(Path staging, String entryPath) {
        try {
            Path entry = storage.resolveWithin(staging, entryPath);
            String content = Files.readString(entry, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R");
            if (lines.length == 0 || !lines[0].trim().equals("---")) {
                return null;
            }
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.equals("---")) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                if (FRONTMATTER_KEY_DESCRIPTION.equals(line.substring(0, colon).trim())) {
                    String value = line.substring(colon + 1).trim();
                    boolean quoted = value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"));
                    if (quoted) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (value.isEmpty()) {
                        return null;
                    }
                    return value.length() > MAX_DESCRIPTION_LENGTH
                            ? value.substring(0, MAX_DESCRIPTION_LENGTH) : value;
                }
            }
            return null;
        } catch (IOException ex) {
            log.warn("读取 SKILL.md frontmatter 失败，description 置空：{}", ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    /**
     * 版本列表（项目成员即可），版本号升序。
     *
     * @param skillId 技能 ID
     * @return 版本列表（含文件清单）
     * @throws BizException 技能不存在 404/10301；非成员 403/10201
     */
    public List<SkillVersionVO> listVersions(Long skillId) {
        Skill skill = requireAccessibleSkill(skillId);
        return skillMapper.selectVersions(skill.getId()).stream()
                .map(version -> SkillVersionVO.of(version, parseFiles(version.getFilesJson())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 下载（按版本重打包）
    // ------------------------------------------------------------------

    /**
     * 下载某版本：按 {@code files_json} 把磁盘上的文件重新打成 zip。
     * <p>
     * 为什么不保存原始 zip（docs/database.md 待确认项 D1）：解包时已做过路径与大小校验，
     * 磁盘上只有"已校验过的文件"；下载时按清单重打包，内容永远与清单一致，
     * 也不会因为保留了原始 zip 而多出一份不可信的输入。
     * 每个文件边读边写 zip，不把整个包读进内存。
     *
     * @param skillId   技能 ID
     * @param versionNo 版本号
     * @return 下载内容
     * @throws BizException 技能 / 版本不存在 404/10301；非成员 403/10201；磁盘文件缺失 404/10301
     */
    public DownloadContent download(Long skillId, Integer versionNo) {
        Skill skill = requireAccessibleSkill(skillId);
        SkillVersion version = requireVersion(skill, versionNo);
        List<SkillPackageExtractor.ExtractResult.FileEntry> files = parseFiles(version.getFilesJson());
        if (files.isEmpty()) {
            // 例如演示数据里的技能：只有版本行与一个占位 files_json，磁盘上无文件
            throw BizException.of(ErrorCode.NOT_FOUND, "该版本没有可下载的文件");
        }
        Path versionDir = storage.skillVersionDir(skill.getProjectId(), skill.getId(), version.getVersionNo());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
            for (SkillPackageExtractor.ExtractResult.FileEntry file : files) {
                // 逐条按清单解析并再校验一次路径：即使库里被写入异常路径，也不会读到版本目录之外
                Path source = storage.resolveWithin(versionDir, file.path());
                // 符号链接指向包外时 Files.size / newInputStream 会跟过去读，必须先拦
                if (Files.isSymbolicLink(source)) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR, "技能文件异常（符号链接）：" + file.path());
                }
                if (!Files.isRegularFile(source)) {
                    throw BizException.of(ErrorCode.NOT_FOUND, "技能文件缺失：" + file.path());
                }
                zos.putNextEntry(new ZipEntry(file.path()));
                Files.copy(source, zos);
                zos.closeEntry();
            }
        } catch (IOException ex) {
            log.error("技能包重打包失败：skillId={}, versionNo={}", skillId, versionNo, ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "技能包导出失败");
        }
        return new DownloadContent(fileName(skill, version), buffer.toByteArray());
    }

    // ------------------------------------------------------------------
    // 单文件预览
    // ------------------------------------------------------------------

    /**
     * 单文件预览内容（项目成员即可）。
     * <p>
     * 两道校验缺一不可：① 只允许读版本清单里的文件（拒绝 {@code ../} 之类夹带）；
     * ② 归一化后仍在该版本目录内（拒绝库中异常清单）。只返回文本内容，
     * 二进制文件交给前端自行判断是否可预览。
     *
     * @param skillId   技能 ID
     * @param versionNo 版本号
     * @param path      版本内的相对路径，如 {@code SKILL.md}
     * @return 文件内容
     * @throws BizException 技能 / 版本不存在 404/10301；非成员 403/10201；路径不在清单内 / 越界 400/10001
     */
    public SkillFileContent previewFile(Long skillId, Integer versionNo, String path) {
        Skill skill = requireAccessibleSkill(skillId);
        SkillVersion version = requireVersion(skill, versionNo);
        List<SkillPackageExtractor.ExtractResult.FileEntry> files = parseFiles(version.getFilesJson());
        SkillPackageExtractor.ExtractResult.FileEntry target = files.stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.PARAM_INVALID, "文件不在该版本清单内：" + path));

        Path versionDir = storage.skillVersionDir(skill.getProjectId(), skill.getId(), version.getVersionNo());
        Path file = storage.resolveWithin(versionDir, target.path());
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "技能文件缺失：" + target.path());
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            // 内容一致性校验：清单里的 sha256 是上传时的摘要，读回来不一致说明文件被外部改动
            String actualSha256 = SkillPackageExtractor.sha256OfBytes(bytes);
            if (!actualSha256.equals(target.sha256())) {
                log.warn("技能文件内容与清单不一致：skillId={}, versionNo={}, path={}", skillId, versionNo, target.path());
            }
            return new SkillFileContent(target.path(), target.size(), target.sha256(),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.error("读取技能文件失败：skillId={}, path={}", skillId, target.path(), ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "技能文件读取失败");
        }
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 删除技能（逻辑删除）：创建者本人或项目 owner。
     * <p>
     * 引用校验口径（docs/api.md §5）：若技能仍被<b>未完成需求</b>的节点实例
     * {@code skills_snapshot} 引用（且该流程节点仍在模板 {@code wb_workflow_node_skill} 中挂着该技能）→
     * {@code 409/10403} 并返回引用清单；已完成需求的引用不阻止删除（PRD AC-D2.2）。
     * <p>
     * 事务边界：本方法<b>不</b>加 {@code @Transactional}。原因是"校验 → 抛异常"这一步在遇到引用时
     * 只有一次 SELECT，没有需要回滚的写；真正的写只有最后一条 {@code UPDATE ... WHERE deleted = 0}，
     * 它本身是单条语句（原子），并发重复删除时后到者影响 0 行，转为 404，不会出现半更新状态。
     * 少开一个事务也就少一次"校验后紧跟着别人新建需求"的窗口锁竞争。
     * <p>
     * 为什么"未被引用"也只做<b>逻辑删除</b>：版本行被历史需求的 skills_snapshot 按 versionId 引用，
     * 物理删除后历史需求就查不到"当时用的哪一版"；逻辑删除让列表消失、历史可追溯。
     *
     * @param skillId 技能 ID
     * @throws BizException 技能不存在 404/10301；非成员 403/10201；非创建者且非 owner 403/10202；
     *                      被引用 409/10403（携带引用清单）
     */
    public void delete(Long skillId) {
        CurrentUser user = currentUserContext.require();
        Skill skill = requireExistingSkill(skillId);
        ProjectWithStats project = projectAccessService.requireExistingProject(skill.getProjectId());
        projectAccessService.requireMember(skill.getProjectId(), user.userId());

        // 权限 C：数据创建者，或项目 owner（双源校验在 ProjectAccessService 中，这里只做"或"）
        boolean isCreator = Objects.equals(skill.getCreatedBy(), user.userId());
        if (!isCreator && !projectAccessService.isOwner(project, user.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有技能上传者或项目 owner 可以删除该技能");
        }

        List<SkillReference> references = skillMapper.selectUnfinishedReferences(skillId);
        if (!references.isEmpty()) {
            log.warn("删除技能被拒绝：skillId={}, 引用数={}", skillId, references.size());
            throw SkillReferencedException.of(references);
        }
        if (skillMapper.markDeleted(skillId, skill.getProjectId(), LocalDateTime.now()) != 1) {
            // 并发下被别人先删掉：按"已不存在"处理，避免前端误以为还有数据
            throw BizException.of(ErrorCode.NOT_FOUND, "技能不存在或已被删除");
        }
        log.info("删除技能（逻辑删除）：skillId={}, 操作人={}", skillId, user.userId());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 取技能并做"存在 + 成员"校验（读取类接口共用）。
     *
     * @param skillId 技能 ID
     * @return 技能
     * @throws BizException 技能不存在 404/10301；项目不存在 404/10301；非成员 403/10201
     */
    private Skill requireAccessibleSkill(Long skillId) {
        Long userId = currentUserContext.require().userId();
        Skill skill = requireExistingSkill(skillId);
        projectAccessService.requireExistingProject(skill.getProjectId());
        projectAccessService.requireMember(skill.getProjectId(), userId);
        return skill;
    }

    private Skill requireExistingSkill(Long skillId) {
        Skill skill = skillId == null ? null : skillMapper.selectById(skillId);
        if (skill == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "技能不存在");
        }
        return skill;
    }

    /**
     * 定位版本；{@code versionNo} 为空时取技能当前版本。
     * <p>
     * 为什么允许空：api.md §5 的单文件预览只声明了 {@code path}，不传版本号时应预览"当前版本"，
     * 而不是报"版本不存在"。技能还没有任何版本（例如演示数据里版本未回填）才算 404。
     *
     * @param skill     技能
     * @param versionNo 版本号，可空
     * @return 版本
     * @throws BizException 版本不存在 → 404/10301
     */
    private SkillVersion requireVersion(Skill skill, Integer versionNo) {
        if (versionNo != null) {
            SkillVersion version = skillMapper.selectVersion(skill.getId(), versionNo);
            if (version == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "技能版本不存在：v" + versionNo);
            }
            return version;
        }
        SkillVersion current = skill.getCurrentVersionId() == null
                ? null : skillMapper.selectVersionById(skill.getCurrentVersionId());
        if (current == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "技能还没有可用版本");
        }
        return current;
    }

    /** 重新查一次技能（带当前版本号与引用数），保证响应与列表口径一致 */
    private SkillVO reloadSkillVO(Long skillId) {
        return skillMapper.selectByProject(requireExistingSkill(skillId).getProjectId()).stream()
                .filter(item -> item.getId().equals(skillId))
                .findFirst()
                .map(SkillVO::of)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "技能不存在"));
    }

    /**
     * 解析 {@code files_json}。
     *
     * @param filesJson 库中 JSON
     * @return 文件清单（按路径升序）
     */
    private List<SkillPackageExtractor.ExtractResult.FileEntry> parseFiles(String filesJson) {
        if (filesJson == null || filesJson.isBlank()) {
            return List.of();
        }
        try {
            List<SkillPackageExtractor.ExtractResult.FileEntry> files = objectMapper.readValue(
                    filesJson, new TypeReference<List<SkillPackageExtractor.ExtractResult.FileEntry>>() {
                    });
            return files.stream()
                    .sorted(Comparator.comparing(SkillPackageExtractor.ExtractResult.FileEntry::path))
                    .toList();
        } catch (IOException ex) {
            // 库里的 JSON 坏了属于数据异常：不能让预览 / 下载静默返回空内容
            log.error("files_json 解析失败：{}", filesJson, ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "技能文件清单数据异常");
        }
    }

    /**
     * 文件清单 → JSON 字符串（写入 {@code wb_skill_version.files_json}）。
     *
     * @param files 文件清单
     * @return JSON
     */
    private String toJson(List<SkillPackageExtractor.ExtractResult.FileEntry> files) {
        try {
            return objectMapper.writeValueAsString(files);
        } catch (IOException ex) {
            throw new IllegalStateException("文件清单序列化失败", ex);
        }
    }

    private static String stripZipSuffix(String fileName) {
        if (fileName == null) {
            return null;
        }
        // 有些客户端会带上路径（带路径时含 / 或 \，按文件名尾部截取）
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        return base.toLowerCase().endsWith(".zip") ? base.substring(0, base.length() - 4) : base;
    }

    private static String fileName(Skill skill, SkillVersion version) {
        // 文件名带技能名与版本号，前端下载时无需再拼
        return skill.getName() + "-v" + version.getVersionNo() + ".zip";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.debug("清理暂存文件失败：{}", path);
                }
            });
        } catch (IOException ex) {
            log.warn("清理暂存目录失败：{}", dir);
        }
    }

    /** 上传事务结果：技能 ID 与本次写入的版本号（用于计算正式目录） */
    private record UploadOutcome(long skillId, int versionNo) {
    }

    /**
     * 单文件预览结果。
     *
     * @param path   版本内相对路径
     * @param size   清单记录的大小
     * @param sha256 清单记录的摘要
     * @param content 文件文本内容（UTF-8）
     */
    public record SkillFileContent(String path, long size, String sha256, String content) {
    }

    /**
     * 技能包下载内容。
     *
     * @param fileName 下载文件名（中文，Content-Disposition 用 filename* 编码）
     * @param content  zip 字节
     */
    public record DownloadContent(String fileName, byte[] content) {
    }
}

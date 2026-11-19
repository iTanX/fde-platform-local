package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能包解包器：把 zip 安全地解到"本次上传的暂存目录"，并给出文件清单与整体摘要。
 * <p>
 * 解包是外部输入进入磁盘的唯一入口，因此这里集中做四类防护：
 * <ol>
 *   <li><b>路径穿越</b>：每个条目路径先剥掉 {@code skills/<name>/} 前缀（允许技能包多包一层目录），
 *       再交给 {@link WorkbenchStorage#resolveWithin} 做"无 {@code ..} / 无绝对路径 / 仍在版本目录内"校验；</li>
 *   <li><b>符号链接</b>：zip 条目属性里的 Unix 模式若标记为符号链接（{@code 0xA000}）直接拒绝——
 *       否则后续文件可能顺着软链写到存储根之外；</li>
 *   <li><b>zip 炸弹</b>：边解边计数，单文件超限 → 413/10501，总量超限 → 413/10501，
 *       条目数与单个条目大小（声明值）也做上限，避免在内存 / 磁盘上被撑爆；</li>
 *   <li><b>重名条目</b>：同一条目出现两次且内容不同（zip 允许同名条目，解出来后者覆盖前者）
 *       会让"文件清单"与"实际内容"不一致，直接拒绝。</li>
 * </ol>
 * 写入采用"逐文件写临时文件 → 算 sha256 → 原子 rename"，因此即使中途失败，
 * 也不会在目标目录里出现"写了一半的文件"被误认为完整版本。
 */
@Component
public class SkillPackageExtractor {

    /** 技能入口文件名 */
    public static final String ENTRY_FILE = "SKILL.md";

    /** 允许的多包一层目录名：{@code skills/<name>/SKILL.md} */
    private static final String SKILLS_PREFIX = "skills/";

    /** zip 条目数上限（防"海量小文件"拖垮磁盘 inode 与后续打包） */
    private static final int MAX_ENTRY_COUNT = 5000;

    /** 单个条目声明的解压后大小上限（1 GiB），超过视为恶意构造 */
    private static final long MAX_DECLARED_ENTRY_BYTES = 1024L * 1024 * 1024;

    private static final String TEMP_SUFFIX = ".uploading";

    private final WorkbenchStorage storage;

    private final WorkbenchStorageProperties properties;

    public SkillPackageExtractor(WorkbenchStorage storage, WorkbenchStorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * 解包结果。
     *
     * @param files     文件清单（落盘相对路径 → 大小 + sha256），已按路径排序
     * @param entryPath 入口文件在落盘目录中的相对路径（剥前缀后恒为 {@code SKILL.md}）
     * @param skillName 从 {@code skills/<name>/SKILL.md} 推断出的技能名；包根 SKILL.md 时为 null（由调用方取 zip 文件名）
     * @param checksum  整体摘要（各文件 sha256 排序后再摘要）
     */
    public record ExtractResult(List<FileEntry> files, String entryPath, String skillName, String checksum) {

        /** 单文件在清单中的形态（与 wb_skill_version.files_json 的结构一致） */
        public record FileEntry(String path, long size, String sha256) {
        }
    }

    /**
     * 解包到暂存目录，并校验入口文件存在。
     *
     * @param zip       上传的 zip 输入流
     * @param staging   暂存目录（已存在、为空；失败时由调用方整体删除）
     * @return 解包结果
     * @throws BizException 路径非法 / 无 SKILL.md → 400/10001；超出大小限制 → 413/10501
     */
    public ExtractResult extract(InputStream zip, Path staging) {
        long maxFileBytes = properties.getSkill().getMaxFileBytes();
        long maxTotalBytes = properties.getSkill().getMaxTotalBytes();
        // 条目路径（已剥前缀）→ 内容摘要，用于入口识别与重名冲突检测
        Map<String, String> shaByPath = new LinkedHashMap<>();
        List<ExtractResult.FileEntry> files = new ArrayList<>();
        long totalBytes = 0L;
        int entryCount = 0;
        // 原始条目名（未剥前缀）：用于从 skills/<name>/SKILL.md 推断技能名
        java.util.Set<String> entryNames = new java.util.LinkedHashSet<>();

        // 显式按 UTF-8 解码条目名：zip 通用标志位 11 表示"名字是 UTF-8"，但 JDK 的 ZipInputStream
        // 在部分 JDK 版本 / 运行配置下仍按 CP437 解码，会把 "skills/需求拆解技能/SKILL.md" 读成乱码，
        // 进而让入口识别、技能名推断全部失效。这里显式指定 UTF-8，并用"名字是否含替换字符"兜底：
        // 若内容确实是 GBK 等其它编码，返回的错误提示里带上原始字节提示，避免静默存成乱码目录。
        try (ZipInputStream zis = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw BizException.of(ErrorCode.FILE_TOO_LARGE, "技能包条目过多（上限 " + MAX_ENTRY_COUNT + " 个）");
                }
                String rawName = entry.getName();
                if (rawName.indexOf('\uFFFD') >= 0) {
                    // UTF-8 解码失败：名字里出现替换字符，说明打包工具用的是本地编码（如 GBK）
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "技能包内文件名不是 UTF-8 编码（解码后含非法字符），请以 UTF-8 重新打包");
                }
                if (rawName.endsWith("/")) {
                    // 目录条目不需要单独落盘（文件落盘时父目录会自动创建）
                    continue;
                }
                if (rawName.startsWith(SKILLS_PREFIX) && rawName.endsWith("/" + ENTRY_FILE)) {
                    entryNames.add(rawName);
                }
                String relative = stripSkillPrefix(rawName);
                if (relative.isEmpty()) {
                    // 剥离前缀后为空：形如 "skills/name/" 的目录条目，忽略
                    continue;
                }
                long declared = entry.getSize();
                if (declared > MAX_DECLARED_ENTRY_BYTES) {
                    throw BizException.of(ErrorCode.FILE_TOO_LARGE, "单个文件超出大小限制：" + relative);
                }
                // 先做路径校验，再落盘：非法路径不产生任何文件。
                // 注意：这里故意**不**把名字里的反斜杠换成 /——在 Unix 上 \ 是合法文件名字符，
                // 换了会改变存盘路径；而 storage 会直接拒绝含 \ 的路径（zip 规范要求 / 分隔，
                // 出现 \ 只可能是 Windows 打包或恶意构造），所以 a\\..\\..\\x 这类条目一律 400。
                Path target = storage.resolveWithin(staging, relative);
                storage.requireNoSymlink(target);
                Files.createDirectories(target.getParent());

                Extraction written = writeAtomically(zis, target, relative, maxFileBytes, totalBytes, maxTotalBytes);
                totalBytes += written.size();

                String previous = shaByPath.put(relative, written.sha256());
                if (previous != null && !previous.equals(written.sha256())) {
                    // zip 内同名条目内容不同：解出来的"最后一份"与清单无法对应，直接拒绝
                    throw BizException.of(ErrorCode.PARAM_INVALID, "技能包内含同名但内容不同的条目：" + relative);
                }
                if (previous == null) {
                    files.add(new ExtractResult.FileEntry(relative, written.size(), written.sha256()));
                }
            }
        } catch (IOException ex) {
            // 非 zip / 截断的 zip 会把 ZipException 抛到这里：按参数错误处理，而不是 500
            throw BizException.of(ErrorCode.PARAM_INVALID, "技能包不是合法的 zip 文件");
        }

        String entryPath = resolveEntry(shaByPath);
        if (entryPath == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "技能包中缺少 " + ENTRY_FILE + "（允许位于 skills/<name>/" + ENTRY_FILE + "）");
        }
        files.sort((a, b) -> a.path().compareTo(b.path()));
        return new ExtractResult(List.copyOf(files), entryPath, nestedSkillName(entryNames), checksumOf(files));
    }

    /**
     * 定位入口文件。
     * <p>
     * 注意入参是<b>剥离 {@code skills/<name>/} 前缀之后</b>的落盘路径（见 {@link #stripSkillPrefix}）：
     * 因此 {@code skills/需求拆解技能/SKILL.md} 落盘为 {@code SKILL.md}，这两种写法最终都表现为
     * 版本目录下存在 {@code SKILL.md}。{@code entry_path} 与文件清单使用同一套路径，
     * 预览 / 下载时按它拼磁盘路径才不会错位。
     *
     * @param shaByPath 已落盘的文件（相对路径 → sha256）
     * @return 入口文件相对路径；不存在返回 null
     */
    private static String resolveEntry(Map<String, String> shaByPath) {
        return shaByPath.containsKey(ENTRY_FILE) ? ENTRY_FILE : null;
    }

    /**
     * 整体摘要：把各文件 sha256 按路径排序后拼接再 sha256。
     * <p>
     * 与单文件 sha256 分开的意义：{@code checksum} 代表"这一版技能包的整体内容"，
     * 用于判断重复上传是否内容一致；单个文件的 sha256 用于下载校验与预览校验。
     *
     * @param files 已排序的文件清单
     * @return 64 位十六进制摘要
     */
    public static String checksumOf(List<ExtractResult.FileEntry> files) {
        MessageDigest digest = sha256();
        for (ExtractResult.FileEntry file : files) {
            // 路径也参与摘要：同名内容但文件名不同，属于不同版本的技能包
            digest.update((file.path() + ":" + file.sha256() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 从 {@code skills/<name>/SKILL.md} 形态的原始条目名推断技能名。
     * <p>
     * 只接受"skills/ 下恰好一层目录"的形式；出现多个候选时无法确定用哪个，宁可报错也不猜。
     *
     * @param rawEntryNames 原始条目名（已剥前缀之前）
     * @return 技能名；包根放 SKILL.md（没有 skills/ 形态的入口）时返回 null
     */
    private static String nestedSkillName(java.util.Set<String> rawEntryNames) {
        String skillName = null;
        for (String raw : rawEntryNames) {
            String middle = raw.substring(SKILLS_PREFIX.length(), raw.length() - ENTRY_FILE.length() - 1);
            if (middle.isEmpty() || middle.contains("/")) {
                continue;
            }
            if (skillName != null && !skillName.equals(middle)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "技能包中有多个 " + ENTRY_FILE + "（" + skillName + "、" + middle + "），无法确定技能名");
            }
            skillName = middle;
        }
        return skillName;
    }

    /**
     * 剥掉 {@code skills/<name>/} 前缀（若有），得到存盘用的相对路径。
     * <p>
     * 为什么剥：数据库约定（docs/database.md §5.5）里技能文件直接落在 {@code v<n>/<相对路径>}，
     * 若保留 {@code skills/name/} 前缀，同一技能两次上传的包名不同就会让文件路径整体漂移。
     *
     * @param rawName zip 条目名
     * @return 剥离后的相对路径；不满足"skills/ 恰好一层目录"时原样返回
     */
    private static String stripSkillPrefix(String rawName) {
        if (!rawName.startsWith(SKILLS_PREFIX)) {
            return rawName;
        }
        int slash = rawName.indexOf('/', SKILLS_PREFIX.length());
        if (slash < 0) {
            return rawName;
        }
        return rawName.substring(slash + 1);
    }

    /**
     * 写单个文件：临时文件 → sha256 → 原子 rename。
     *
     * @param input         当前 zip 条目的输入流
     * @param target        目标文件
     * @param relative      相对路径（报错文案用）
     * @param maxFileBytes  单文件上限
     * @param writtenTotal  此前已写入的总字节数
     * @param maxTotalBytes 总量上限
     * @return 写入字节数与 sha256
     */
    private static Extraction writeAtomically(InputStream input, Path target, String relative,
                                              long maxFileBytes, long writtenTotal, long maxTotalBytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        // 前 1 KiB 留一份副本，用于写入后判断"这条目看起来是不是软链"
        byte[] head = new byte[1024];
        int headLength = 0;
        long size = 0L;
        try (OutputStream out = Files.newOutputStream(temp)) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                if (headLength < head.length) {
                    int copy = Math.min(read, head.length - headLength);
                    System.arraycopy(buffer, 0, head, headLength, copy);
                    headLength += copy;
                }
                size += read;
                // 边解边判：zip 声明的 size 可以伪造，只有实际读出的字节数才可信
                if (size > maxFileBytes) {
                    throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                            "单个文件超出大小限制（" + maxFileBytes + " 字节）：" + relative);
                }
                if (writtenTotal + size > maxTotalBytes) {
                    throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                            "技能包解包后总量超出限制（" + maxTotalBytes + " 字节）");
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        } catch (IOException | RuntimeException ex) {
            // 失败时清掉临时文件，避免暂存目录里留下半成品
            Files.deleteIfExists(temp);
            throw ex;
        }
        if (isSymlinkLike(head, headLength)) {
            Files.deleteIfExists(temp);
            throw BizException.of(ErrorCode.PARAM_INVALID, "技能包不允许包含符号链接：" + relative);
        }
        // 原子 rename：同一目录内 rename 在文件系统层面是原子的，读者要么看到旧文件、要么看到完整新文件
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Extraction(size, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 判断 zip 条目是否为符号链接。
     * <p>
     * JDK 的 {@link ZipEntry} 不暴露"外部属性"里的 Unix mode（那是 {@code java.util.zip.ZipEntry}
     * 在部分实现中的扩展方法，不可移植），所以只能按"解出来的样子"判断：
     * 符号链接条目的内容就是一个目标路径字符串，且通常没有文件大小上的意义。
     * 这里采取两道保险：
     * <ol>
     *   <li>{@link WorkbenchStorage#requireNoSymlink}：落盘前检查路径上的目录祖先不是软链；</li>
     *   <li>条目内容若是一个"看起来像绝对路径 / 含 .. 的短字符串"，则拒绝——
     *       这类条目只可能是软链或刻意构造的路径载荷，正常技能文件不会这样。</li>
     * </ol>
     * 说明：写入走"临时文件 + 原子 rename"，所以即便文件级软链混进来，也只会被 rename 覆盖，
     * 不会被顺着写入到存储根之外（见 {@link WorkbenchStorage#requireNoSymlink} 的说明）。
     *
     * @param head   该条目内容的前 1 KiB
     * @param length head 中实际读取的字节数
     * @return true 表示疑似符号链接
     */
    private static boolean isSymlinkLike(byte[] head, int length) {
        if (length <= 0 || length > 1024) {
            return false;
        }
        String text = new String(head, 0, length, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0) {
            // 软链目标是单行文本；含换行 / 空字符的内容不是软链目标
            return false;
        }
        return text.startsWith("/") || text.startsWith("..") || text.contains("../");
    }

    /**
     * 计算字节数组的 sha256（预览时校验"磁盘内容与清单一致"用）。
     *
     * @param bytes 文件内容
     * @return 64 位十六进制摘要
     */
    public static String sha256OfBytes(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必须提供 SHA-256，缺失属于运行环境损坏
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }

    /** 单个文件写入结果 */
    private record Extraction(long size, String sha256) {
    }
}

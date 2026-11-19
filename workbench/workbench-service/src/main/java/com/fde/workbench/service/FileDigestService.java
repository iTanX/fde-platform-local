package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件落盘的原子写入与摘要计算（T5-1：上传输入、提交输出）。
 * <p>
 * <b>为什么必须"先写临时文件、再原子 rename"</b>：上传 / 复制过程中进程被中断、磁盘满，
 * 都会在正式路径上留下半个文件；而数据库登记一旦提交，页面就会把它当成可用文件并提供下载。
 * 临时文件 + {@link StandardCopyOption#ATOMIC_MOVE} 保证正式路径上要么是完整内容、要么不存在。
 * <p>
 * 临时文件与目标文件<b>同目录</b>（同一文件系统才能原子 move），并以 {@code .uploading-} 前缀命名，
 * 失败时在 finally 里清理，不污染工作区。
 */
@Component
public class FileDigestService {

    /** 摘要算法：与 {@code wb_file.sha256}、插件侧一致性校验保持一致 */
    private static final String SHA256 = "SHA-256";

    /** 读文件计算 sha256 时的缓冲区大小 */
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 临时文件前缀（同目录，rename 才能原子） */
    private static final String TEMP_PREFIX = ".uploading-";

    /**
     * 把输入流写入目标文件（原子），同时返回大小与 sha256。
     * <p>
     * 分两遍读是不必要的：边写边算摘要，写完再 move，代价与"写一次再读一次"相比减半。
     *
     * @param in     输入流（调用方负责关闭）
     * @param target 目标文件（父目录需已存在且已做过符号链接校验）
     * @param maxBytes 字节上限，超过即中断并抛 413/10501；{@code <= 0} 表示不限
     * @return 写入结果（大小 + sha256）
     * @throws BizException 超限 → 413/10501；磁盘失败 → 502/19902
     */
    public WriteResult writeAtomically(InputStream in, Path target, long maxBytes) {
        Path temp = target.resolveSibling(TEMP_PREFIX + UUID.randomUUID());
        MessageDigest digest = sha256Digest();
        long total = 0;
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                            "文件超出大小限制（上限 " + maxBytes + " 字节）");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (BizException ex) {
            deleteQuietly(temp);
            throw ex;
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件写入失败：" + target.getFileName());
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件写入失败：" + target.getFileName());
        }
        return new WriteResult(total, toHex(digest.digest()));
    }

    /**
     * 复制一个已存在的文件到目标路径（原子），返回副本的大小与 sha256。
     *
     * @param source   源文件
     * @param target   目标文件
     * @param maxBytes 字节上限，{@code <= 0} 表示不限
     * @return 副本的大小与 sha256
     * @throws BizException 超限 413/10501；读写失败 502/19902
     */
    public WriteResult copyAtomically(Path source, Path target, long maxBytes) {
        try (InputStream in = Files.newInputStream(source)) {
            return writeAtomically(in, target, maxBytes);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件读取失败：" + source.getFileName());
        }
    }

    /**
     * 计算文件内容的 sha256（小写十六进制）。
     *
     * @param file 文件
     * @return sha256
     * @throws BizException 读取失败 → 502/19902
     */
    public String sha256Of(Path file) {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件读取失败：" + file.getFileName());
        }
        return toHex(digest.digest());
    }

    /**
     * 规范化 sha256 输入：去空白、转小写，必须正好 64 位十六进制。
     *
     * @param sha256 原始值
     * @param message 不合法时的中文提示
     * @return 规范化的 sha256
     * @throws BizException 400/10001
     */
    public static String normalizeSha256(String sha256, String message) {
        String value = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("^[0-9a-f]{64}$")) {
            throw BizException.of(ErrorCode.PARAM_INVALID, message);
        }
        return value;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance(SHA256);
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必须提供 SHA-256，缺失属于运行环境损坏
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程：临时文件带 .uploading- 前缀，运维可安全清理
        }
    }

    /**
     * 写入结果。
     *
     * @param bytes  字节数
     * @param sha256 内容的 SHA-256（小写十六进制）
     */
    public record WriteResult(long bytes, String sha256) {
    }
}

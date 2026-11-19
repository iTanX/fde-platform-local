package com.fde.workbench.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 技能包存储与大小限制配置，对应 {@code application.yml} 的 {@code fde.workbench.*}（docs/architecture.md §10）。
 * <p>
 * 为什么要做成配置项而不是常量：单文件 / 总量限制随部署环境不同（本地演示 20/100 MiB 够用，
 * 生产可能收紧或放宽），存储根也要能从 {@code ./data} 换到挂载盘；写死会导致换环境就要改代码。
 * <ul>
 *   <li>{@code storage-root}：存储根（相对路径按启动时的工作目录解析），对应 docs/database.md §5.5；</li>
 *   <li>{@code skill.max-file-bytes}：技能包内单个文件上限，默认 20 MiB；</li>
 *   <li>{@code skill.max-total-bytes}：技能包解包后总量上限，默认 100 MiB；
 *       它同时是解包时的"防 zip 炸弹"上限（见 {@code SkillPackageExtractor}）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "fde.workbench")
public class WorkbenchStorageProperties {

    /** 存储根目录，默认 ./data（相对启动目录解析），不写死绝对路径 */
    private String storageRoot = "./data";

    /** 技能包相关限制 */
    private Skill skill = new Skill();

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    /** 技能包大小限制 */
    public static class Skill {

        /** 单个文件大小上限，默认 20 MiB */
        private long maxFileBytes = 20L * 1024 * 1024;

        /** 解包后总量上限，默认 100 MiB */
        private long maxTotalBytes = 100L * 1024 * 1024;

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public long getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public void setMaxTotalBytes(long maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
        }
    }
}

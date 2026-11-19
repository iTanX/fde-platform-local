package com.fde.workbench;

/**
 * 集成测试统一使用的数据库连接串（T5-1）。
 * <p>
 * <b>为什么要单独抽一个常量</b>：共享库 {@code fde_workbench} 是演示库，每跑一轮集成测试
 * （7 个 {@code @SpringBootTest} 用例类）都会往里写几十行用户 / 项目 / 流程 / 需求，
 * 事后还要手工复位（docs/task-decomposition.md §0.4）。测试改连独立的
 * <b>{@code fde_workbench_test}</b>：结构与演示数据由 {@code docs/database/001_init.sql} +
 * {@code 002_demo_seed.sql} 初始化（把脚本里的库名换掉执行即可），与演示库物理隔离，
 * 跑完不需要任何复位，也不会碰 demo / demo2 / 项目 1 / 技能 1 / 流程 1。
 * <p>
 * <b>为什么写在注解上而不是 {@code src/test/resources/application.yml}</b>：
 * 测试 classpath 上的同名 {@code application.yml} 会<b>整体覆盖</b>主配置
 * （Spring Boot 只取第一个同名资源），JWT / multipart 等配置会全部丢失；
 * {@code @SpringBootTest#properties} 只覆盖单键，最稳。
 * <p>
 * 已经过验证：每个用例类的上下文都以本连接串启动（见各自 {@code @SpringBootTest} 注解）。
 */
final class TestDatabases {

    /** 独立测试库连接串（与会话时区等参数与主配置一致，只有库名不同） */
    static final String URL = "jdbc:mysql://192.168.2.125:3306/fde_workbench_test"
            + "?sessionVariables=time_zone='%2B08:00'&serverTimezone=Asia/Shanghai&useUnicode=true"
            + "&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";

    private TestDatabases() {
    }
}

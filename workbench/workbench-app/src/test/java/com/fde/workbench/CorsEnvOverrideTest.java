package com.fde.workbench;

import com.fde.workbench.service.WorkbenchStorageProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 环境变量覆盖 CORS 允许来源（<b>不连数据库、不启动上下文</b>）。
 * <p>
 * 为什么单独一个类并在子进程里跑：JUnit 进程的环境变量无法在运行时修改，而
 * {@link WorkbenchStorageProperties#resolvedAllowedOrigins()} 读的是 {@link System#getenv()}。
 * 这里用 {@code ProcessBuilder} 起一个子 JVM，显式带上
 * {@code FDE_WORKBENCH_ALLOWED_ORIGINS="http://127.0.0.1:3099/, http://localhost:3099"}，
 * 断言"环境变量优先于 application.yml 的默认值、并做了尾斜杠归一化"。
 * <p>
 * 这条用例守的是真实踩过的坑：Spring 的宽松绑定<b>匹配不到</b>全大写的
 * {@code FDE_WORKBENCH_ALLOWED_ORIGINS}（实测不生效），所以必须有一处显式读取 + 一层回归保护。
 */
class CorsEnvOverrideTest {

    /** 子进程里跑的方法名（由 {@link #probe()} 分发） */
    private static final String PROBE_ARG = "probe";

    @Test
    void envVarOverridesConfiguredOrigins() throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-cp",
                System.getProperty("java.class.path"), CorsEnvOverrideTest.class.getName(), PROBE_ARG);
        builder.environment().put(WorkbenchStorageProperties.ALLOWED_ORIGINS_ENV,
                "http://127.0.0.1:3099/, http://localhost:3099");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "子进程失败：\n" + output);
        assertEquals("[http://127.0.0.1:3099, http://localhost:3099]", output.trim());
    }

    /**
     * 子进程入口。
     *
     * @param args 命令行参数（只有一个 {@value #PROBE_ARG}）
     */
    public static void main(String[] args) {
        if (args.length != 1 || !PROBE_ARG.equals(args[0])) {
            System.err.println("usage: CorsEnvOverrideTest probe");
            System.exit(2);
        }
        // 配置里的默认值应被环境变量完整替换（且尾斜杠、空白都被归一化）
        List<String> resolved = CorsConfigTest.resolvedFromEnv(List.of("http://127.0.0.1:3080"));
        System.out.println(resolved);
    }
}

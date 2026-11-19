package com.fde.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web 工作台后端启动类：扫描 com.fde.workbench 下的全部模块（api / service / dao）。
 */
@SpringBootApplication(scanBasePackages = "com.fde.workbench")
public class WorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkbenchApplication.class, args);
    }
}

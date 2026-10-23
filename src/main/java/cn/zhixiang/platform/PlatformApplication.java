package cn.zhixiang.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 知项平台启动入口；API 与后台任务通过 profile 隔离，默认不执行数据库迁移。 */
@SpringBootApplication
public class PlatformApplication {
    /**
     * 启动应用，敏感配置由环境变量注入。
     *
 * @param args Spring Boot 命令行参数，不应携带明文密钥
     */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}

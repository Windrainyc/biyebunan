package cn.keking;

/** 仅启动kkFileView Office进程管理组件及知项私有转换入口，不装配通用URL预览服务。 */
@org.springframework.boot.autoconfigure.SpringBootApplication
public class PrivatePreviewApplication {
    /** 创建容器专用启动入口。 */
    public PrivatePreviewApplication() {}

    /**
     * 启动独立转换服务，拒绝误在宿主机运行。
     *
     * @param args Spring启动参数
     * @throws java.io.IOException 临时工作目录无法创建
     */
    public static void main(String[] args) throws java.io.IOException {
        if (!"true".equals(System.getenv("ZX_PRIVATE_PREVIEW_CONTAINER")))
            throw new IllegalStateException("仅允许在独立转换容器启动，避免影响宿主Office进程");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("/tmp/office-home"));
        org.springframework.boot.SpringApplication.run(PrivatePreviewApplication.class, args);
    }
}

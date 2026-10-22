# 先运行 Maven verify 构建制品；运行镜像不携带源码、构建工具或任何凭证。
FROM eclipse-temurin:21.0.11_10-jre-jammy@sha256:8208b3a3733529f42c4b565bfcbf16d914eb628f3ead8488b6b4d6363fcbe7cf
WORKDIR /app
COPY --chown=10001:10001 target/platform-0.1.0-SNAPSHOT.jar /app/platform.jar
USER 10001:10001
ENV ZX_BIND_ADDRESS=0.0.0.0
EXPOSE 18080
# API、worker、migrate 使用相同制品不同 profile；生产编排配置独立健康探针。
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/platform.jar"]
CMD ["--spring.profiles.active=api"]

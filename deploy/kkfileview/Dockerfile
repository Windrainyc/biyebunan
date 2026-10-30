# 使用已固定的上游源码及Maven制品；镜像不包含业务数据或凭据。
FROM eclipse-temurin:21.0.11_10-jre-jammy@sha256:8208b3a3733529f42c4b565bfcbf16d914eb628f3ead8488b6b4d6363fcbe7cf
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-writer libreoffice-calc libreoffice-impress fonts-noto-cjk && rm -rf /var/lib/apt/lists/*
# 安全仓库若改变主组件版本则构建失败，必须显式审核升级，避免静默漂移。
RUN for p in libreoffice-writer libreoffice-calc libreoffice-impress; do \
      test "$(dpkg-query -W -f='${Version}' "$p")" = '1:7.3.7-0ubuntu0.22.04.13' || exit 1; \
    done && test "$(dpkg-query -W -f='${Version}' fonts-noto-cjk)" = '1:20220127+repack1-1'
WORKDIR /opt/kkfileview
COPY --chown=10001:10001 --chmod=0444 target/kkfileview-private-office-5.0.3-zx1.jar /opt/kkfileview/app.jar
ENV ZX_PRIVATE_PREVIEW_CONTAINER=true HOME=/tmp/office-home KK_OFFICE_HOME=/usr/lib/libreoffice KK_FILE_DIR=/tmp/kk-cache KK_CACHE_ENABLED=false
USER 10001:10001
EXPOSE 8012
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-Djava.io.tmpdir=/tmp","-jar","/opt/kkfileview/app.jar"]

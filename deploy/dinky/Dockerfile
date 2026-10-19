FROM eclipse-temurin:8-jre

WORKDIR /opt/dinky

# 把解压好的 Dinky release 目录拷进镜像
COPY dinky-release-1.20-1.2.5/ /opt/dinky/

# 建运行所需目录（auto.sh 会用到 customJar/logs/run）
RUN mkdir -p /opt/dinky/customJar /opt/dinky/logs /opt/dinky/run \
    && chmod -R 777 /opt/dinky/

EXPOSE 8888

# 前台启动（startOnPending 不 nohup，保证容器主进程是 java）
CMD ["bash", "bin/auto.sh", "startOnPending"]

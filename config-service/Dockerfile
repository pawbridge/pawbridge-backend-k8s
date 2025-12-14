FROM openjdk:17-jdk-slim

WORKDIR /app

# JAR 파일 복사
COPY build/libs/*.jar app.jar

# 헬스체크
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8888/actuator/health || exit 1

# 포트 노출
EXPOSE 8888

# 실행
ENTRYPOINT ["java", "-jar", "app.jar"]

# Java 17 버전의 가벼운 리눅스 환경 사용
FROM openjdk:17-jdk-slim

# 빌드된 Jar 파일을 app.jar로 복사
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

# 실행 명령어
ENTRYPOINT ["java", "-jar", "/app.jar"]
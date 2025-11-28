# Java 17 버전의 가벼운 리눅스(우분투) 환경 사용
FROM eclipse-temurin:17-jdk-jammy

# 빌드된 Jar 파일을 app.jar로 복사
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

# 실행 명령어
ENTRYPOINT ["java", "-jar", "/app.jar"]
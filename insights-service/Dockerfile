FROM maven@sha256:c2a2c58516d160f43b50f12baa427ca86989e0bc942609e04aff61da5d9a7d74 AS build
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY transaction-service/pom.xml transaction-service/pom.xml
COPY insights-service/pom.xml insights-service/pom.xml
RUN mvn -q -pl insights-service -am dependency:go-offline

COPY common common
COPY insights-service insights-service
RUN mvn -q -pl insights-service -am package -DskipTests

FROM build AS extract
WORKDIR /workspace/insights-service/target
RUN java -Djarmode=tools -jar insights-service.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=extract --chown=spring:spring /workspace/insights-service/target/extracted/dependencies/ ./
COPY --from=extract --chown=spring:spring /workspace/insights-service/target/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /workspace/insights-service/target/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /workspace/insights-service/target/extracted/application/ ./
USER spring:spring
# One JSON object per log line for CloudWatch / docker logs; see common's logback-spring.xml.
ENV LOG_FORMAT=json
EXPOSE 8083
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

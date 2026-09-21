FROM maven@sha256:c2a2c58516d160f43b50f12baa427ca86989e0bc942609e04aff61da5d9a7d74 AS build
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY transaction-service/pom.xml transaction-service/pom.xml
RUN mvn -q -pl transaction-service -am dependency:go-offline

COPY common common
COPY account-service account-service
COPY transaction-service transaction-service
RUN mvn -q -pl transaction-service -am package -DskipTests

FROM build AS extract
WORKDIR /workspace/transaction-service/target
RUN java -Djarmode=layertools -jar transaction-service.jar extract

FROM eclipse-temurin@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=extract --chown=spring:spring /workspace/transaction-service/target/dependencies/ ./
COPY --from=extract --chown=spring:spring /workspace/transaction-service/target/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /workspace/transaction-service/target/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /workspace/transaction-service/target/application/ ./
USER spring:spring
EXPOSE 8082
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

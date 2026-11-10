# Imagem única para api, worker e invoice-webhook; o papel é escolhido por SPRING_PROFILES_ACTIVE.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/fiscal-flow-*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]

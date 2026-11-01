# Etapa de compilación: genera el código desde api.yml, compila y empaqueta el jar
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY api/ api/
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B package -DskipTests

# Etapa de ejecución: solo el JRE y el jar, con un usuario sin privilegios
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/messaging-lab-backend-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

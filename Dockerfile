# ---------- Estágio 1: build ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# cache de dependências
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline

# código + empacotamento (testes rodam com --enable-preview via surefire)
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---------- Estágio 2: runtime ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# usuário não-root
RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/monitoring-defi-1.0.0.jar app.jar
USER appuser

EXPOSE 8080

# --enable-preview é obrigatório: Structured Concurrency (JEP 505) ainda é preview no Java 25
ENTRYPOINT ["java", "--enable-preview", "-XX:+UseZGC", "-jar", "app.jar"]

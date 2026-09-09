# ---- Stage 1: build the Vite/React SPA ----
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- Stage 2: build the Spring Boot jar (D-01: SPA is packaged into the jar's static resources) ----
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon
COPY src ./src
COPY frontend/package.json frontend/package-lock.json ./frontend/
COPY --from=frontend-build /app/frontend/dist ./frontend/dist
# skipFrontendBuild: dist/ is already populated above, so processResources copies it straight in
# without gradle re-running npm (node isn't even installed in this stage).
RUN ./gradlew bootJar -PskipFrontendBuild --no-daemon -x test

# ---- Stage 3: runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S muvimatchr && adduser -S muvimatchr -G muvimatchr
WORKDIR /app
COPY --from=backend-build /app/build/libs/*.jar app.jar
USER muvimatchr
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# ==========================================
# STAGE 1: Build the application
# ==========================================
# Use a generic Java 21 JDK image (no Gradle pre-installed)
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy the Gradle wrapper files
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 1. Convert Windows line endings to Linux line endings
# 2. Make the wrapper executable
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Copy your source code
COPY src ./src

# Build the .jar file using the project's specific Gradle Wrapper
RUN ./gradlew clean build --no-daemon -x test

# ==========================================
# STAGE 2: Run the application
# ==========================================
# Use a highly optimized, lightweight Java 21 Runtime image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy ONLY the finished .jar file from STAGE 1
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
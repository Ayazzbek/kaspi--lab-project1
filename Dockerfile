# Build stage
FROM gradle:8.3-jdk17-alpine AS build
WORKDIR /app

# Копируем файлы сборки
COPY build.gradle settings.gradle ./
COPY src ./src

# Скачиваем зависимости
RUN gradle dependencies --no-daemon

# Собираем проект
RUN gradle clean bootJar --no-daemon

# Runtime stage
FROM openjdk:17-alpine
WORKDIR /app

# Устанавливаем curl для healthcheck
RUN apk add --no-cache curl bash

# Создаем пользователя для безопасности
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Копируем собранный JAR
COPY --from=build /app/build/libs/file-uploader.jar app.jar

# Создаем директории для логов
RUN mkdir -p /app/logs

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
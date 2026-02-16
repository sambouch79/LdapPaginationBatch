# Étape 1 : Build avec Maven
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copier uniquement le POM d'abord pour meilleur cache
COPY pom.xml .

# Télécharger les dépendances (cache layer)
RUN mvn dependency:go-offline -B

# Copier le code source
COPY src ./src

# Build l'application
RUN mvn clean package -DskipTests -B

# Étape 2 : Runtime avec JRE
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Installer des outils utiles pour debug (optionnel)
RUN apk add --no-cache \
    bash \
    curl \
    tzdata

# Définir le fuseau horaire
ENV TZ=Europe/Paris

# Créer un utilisateur non-root pour sécurité
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copier le JAR depuis l'étape build
COPY --from=builder --chown=spring:spring /app/target/*.jar app.jar

# Exposition du port
EXPOSE 8080

# Point d'entrée avec optimisations JVM
ENTRYPOINT ["java", \
    "-Xmx512m", \
    "-Xms256m", \
    "-XX:+UseZGC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-docker}", \
    "-jar", \
    "/app/app.jar"]
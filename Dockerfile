FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN MAVEN_CONFIG= ./mvnw -B -DskipTests dependency:go-offline

COPY src src
RUN MAVEN_CONFIG= ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

ENV JAVA_OPTS=""

COPY --from=build /workspace/target/flash-sale-platform-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

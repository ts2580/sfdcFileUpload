FROM eclipse-temurin:17-jre
ARG JAR_FILE=*.jar
COPY ${JAR_FILE} yuri.jar
EXPOSE 443
ENTRYPOINT ["java","-jar","yuri.jar"]
FROM openjdk:17
ARG JAR_FILE=*.jar
COPY ${JAR_FILE} yuri.jar
EXPOSE 5432
ENTRYPOINT ["java","-jar","yuri.jar"]
FROM maven@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1
WORKDIR /app
COPY --chmod=0444 server.jar /app/server.jar
COPY --chmod=0444 publication.json /app/publication.json
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/server.jar"]

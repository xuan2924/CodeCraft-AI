FROM maven:3.9.15-eclipse-temurin-21@sha256:d6e32a254897415a445654a3c43c30fbc731a4d946cf5c66d1cb9184141c20c1

COPY initial/pom.xml /opt/contract/pom.xml
COPY acceptance /opt/acceptance
COPY initial/src /opt/initial/src
# Only this image-build step may resolve dependencies. Execute the actual target
# rather than assuming dependency:go-offline covers Surefire provider artifacts.
RUN mvn -B -ntp -f /opt/contract/pom.xml -Dmaven.repo.local=/opt/maven-repository \
    -Dcandidate.sources=/opt/initial/src/main/java -Dacceptance.sources=/opt/acceptance \
    -Dbuild.output=/tmp/warmup -Dmaven.test.failure.ignore=true test \
    && rm -rf /tmp/warmup && chmod -R a+rX /opt
USER 10001:10001
ENV HOME=/tmp MAVEN_OPTS="-Xmx256m -XX:ActiveProcessorCount=1 -Djava.io.tmpdir=/tmp"
WORKDIR /work
ENTRYPOINT ["/bin/sh"]

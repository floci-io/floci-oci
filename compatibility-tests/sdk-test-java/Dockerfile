FROM maven:3.9-eclipse-temurin-21
WORKDIR /suite

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src/ src/
RUN mvn -q test-compile

ENV FLOCI_OCI_ENDPOINT=http://floci-oci:4599
ENV FLOCI_OCI_NAMESPACE=floci-local

# Runs the suite and copies JUnit XML to /results (the CI contract).
ENTRYPOINT ["sh", "-c", "mvn test; status=$?; mkdir -p /results; cp -f target/surefire-reports/*.xml /results/ 2>/dev/null; exit $status"]

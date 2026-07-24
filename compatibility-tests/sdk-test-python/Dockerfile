FROM python:3.12-slim

WORKDIR /suite

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY pytest.ini conftest.py ./
COPY tests/ tests/

ENV FLOCI_OCI_ENDPOINT=http://floci-oci:4599
ENV FLOCI_OCI_NAMESPACE=floci-local

# Runs the suite and copies JUnit XML to /results (the CI contract).
ENTRYPOINT ["sh", "-c", "python -m pytest --junitxml=/tmp/junit.xml; status=$?; mkdir -p /results; cp -f /tmp/junit.xml /results/ 2>/dev/null; exit $status"]

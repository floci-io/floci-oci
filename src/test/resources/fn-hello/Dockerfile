FROM python:3.11-slim
RUN pip install --no-cache-dir fdk
WORKDIR /fn
COPY func.py .
ENTRYPOINT ["fdk", "func.py", "handler"]

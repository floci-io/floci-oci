---
name: Bug report
about: An OCI API call returns wrong behavior or an error
title: '[BUG] '
labels: bug
assignees: ''
---

## Service

<!-- e.g. SQS, DynamoDB, Lambda -->

## OCI API Action

<!-- e.g. SendMessage, PutItem, CreateFunction -->

## Expected behavior

<!-- What the real OCI SDK/CLI returns -->

## Actual behavior

<!-- What Floci returns — include the full error message or response body -->

## Reproduction

```bash
# Minimal OCI CLI or SDK snippet that triggers the issue
oci os ns get --endpoint http://localhost:4599
```

## Environment

- Floci version / image tag:
- Java SDK version (if applicable):
- How you're running Floci (Docker / native / `mvn quarkus:dev`):
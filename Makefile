# floci-oci repo tasks.

.PHONY: build run test package

build: ## Compile and package (skip tests)
	./mvnw clean package -DskipTests

test: ## Run the unit/integration test suite
	./mvnw test

run: ## Start in dev mode on port 4599
	./mvnw quarkus:dev

package: build

# ── OCI reference sources ────────────────────────────────────────────────────
#
# Shallow clones of the official OCI SDKs / CLI / Terraform provider under the
# gitignored local/oracle/. These are the wire-contract reference: see
# "OCI Source as Reference" in AGENTS.md. Idempotent: clones if missing,
# otherwise moves to the upstream default-branch tip. `git pull --ff-only`
# cannot do that on a depth-1 clone (the new tip shares no history with the
# old one), so existing checkouts are fetched at depth 1 and hard-reset to
# FETCH_HEAD. A checkout with local changes is skipped, never reset.

REF_REPOS = oci-go-sdk oci-java-sdk oci-python-sdk oci-typescript-sdk oci-cli terraform-provider-oci

.PHONY: refs

refs: ## Download/refresh the OCI reference checkouts into local/oracle/
	@mkdir -p local/oracle
	@failed=""; \
	for repo in $(REF_REPOS) fn; do \
		case $$repo in \
			fn) url="https://github.com/fnproject/fn.git" ;; \
			*)  url="https://github.com/oracle/$$repo.git" ;; \
		esac; \
		dir="local/oracle/$$repo"; \
		if [ -d "$$dir/.git" ]; then \
			if [ -n "$$(git -C "$$dir" status --porcelain)" ]; then \
				echo "skipping $$repo: local changes in $$dir"; \
				continue; \
			fi; \
			echo "updating $$repo"; \
			git -C "$$dir" fetch --quiet --depth 1 origin HEAD \
				&& git -C "$$dir" reset --quiet --hard FETCH_HEAD \
				|| failed="$$failed $$repo"; \
		else \
			echo "cloning $$repo"; \
			git clone --quiet --depth 1 "$$url" "$$dir" || failed="$$failed $$repo"; \
		fi; \
	done; \
	if [ -n "$$failed" ]; then echo "refs failed for:$$failed"; exit 1; fi

# ── Compatibility suites ─────────────────────────────────────────────────────
#
# Each suite is a Docker image whose entrypoint runs the tests and writes JUnit
# XML to /results. `make compat-docker` runs every suite against a compose-built
# emulator; the test-*-compat targets run a single suite against an emulator
# already listening on localhost:4599 (e.g. `make run`).

COMPAT_NETWORK ?= floci_oci_default
COMPAT_SUITES  ?= sdk-test-java sdk-test-python sdk-test-cli compat-terraform compat-opentofu
LOCAL_ENDPOINT ?= http://localhost:4599
HOST_OVERRIDES = oci_identity.IdentityClient=$(LOCAL_ENDPOINT);oci_object_storage.ObjectStorageClient=$(LOCAL_ENDPOINT);oci_containerengine.ContainerEngineClient=$(LOCAL_ENDPOINT)

.PHONY: compat-docker test-java-compat test-python-compat test-cli-compat test-terraform-compat test-opentofu-compat

test-java-compat: ## Run the oci-java-sdk suite against a locally running emulator
	cd compatibility-tests/sdk-test-java && FLOCI_OCI_ENDPOINT=$(LOCAL_ENDPOINT) mvn test

test-python-compat: ## Run the oci Python SDK suite against a locally running emulator (needs `pip install -r requirements.txt`)
	cd compatibility-tests/sdk-test-python && FLOCI_OCI_ENDPOINT=$(LOCAL_ENDPOINT) python3 -m pytest --junitxml=/tmp/floci-oci-py-junit.xml

test-go-compat: ## Run the oci-go-sdk suite against a locally running emulator (go.sum is gitignored, so tidy first)
	cd compatibility-tests/sdk-test-go && go mod tidy && FLOCI_OCI_ENDPOINT=$(LOCAL_ENDPOINT) go test -v ./...

test-cli-compat: ## Run the oci-cli suite against a locally running emulator (needs: oci, bats, jq)
	cd compatibility-tests/sdk-test-cli && FLOCI_OCI_ENDPOINT=$(LOCAL_ENDPOINT) bats test/

test-terraform-compat: ## Run the Terraform IaC suite against a locally running emulator
	cd compatibility-tests/compat-terraform && FLOCI_OCI_ENDPOINT=$(LOCAL_ENDPOINT) TF_BIN=terraform bats test/

test-opentofu-compat: ## Run the OpenTofu IaC suite against a locally running emulator
	cd compatibility-tests/compat-opentofu && FLOCI_OCI_ENDPOINT=$(LOCAL_ENDPOINT) TF_BIN=tofu bats test/

compat-docker: ## Build the emulator + suite images and run every suite on a shared network
	docker compose up -d --build
	@set -e; for suite in $(COMPAT_SUITES); do \
		echo "=== $$suite ==="; \
		docker build -t floci-oci-compat-$$suite compatibility-tests/$$suite; \
		mkdir -p test-results/$$suite; \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_OCI_ENDPOINT=http://floci-oci:4599 \
			-v $(CURDIR)/test-results/$$suite:/results \
			floci-oci-compat-$$suite; \
	done
	docker compose down

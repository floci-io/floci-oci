"""Queue, Vault/KMS/Secrets and Streaming validated through the real oci Python SDK."""

import base64
import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


# ── Queue ────────────────────────────────────────────────────────────────────


@pytest.fixture(scope="module")
def queue_admin(sdk_config):
    return oci.queue.QueueAdminClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def queue_data(sdk_config):
    return oci.queue.QueueClient(sdk_config, service_endpoint=ENDPOINT)


def create_queue(queue_admin, name):
    """Work-request driven: no body, opc-work-request-id header, then poll."""
    created = queue_admin.create_queue(
        oci.queue.models.CreateQueueDetails(display_name=name, compartment_id=TENANCY)
    )
    work_request_id = created.headers["opc-work-request-id"]
    work_request = queue_admin.get_work_request(work_request_id).data
    assert work_request.status == "SUCCEEDED"
    assert work_request.time_finished is not None
    assert work_request.resources
    return work_request.resources[0].identifier


def test_queue_lifecycle(queue_admin):
    queue_id = create_queue(queue_admin, unique("py-queue"))

    queue = queue_admin.get_queue(queue_id).data
    assert queue.lifecycle_state == "ACTIVE"
    assert queue.messages_endpoint
    assert queue.retention_in_seconds == 86400

    listed = queue_admin.list_queues(compartment_id=TENANCY).data
    assert any(item.id == queue_id for item in listed.items)

    deleted = queue_admin.delete_queue(queue_id)
    assert deleted.headers["opc-work-request-id"]


def test_queue_message_roundtrip(queue_admin, queue_data):
    queue_id = create_queue(queue_admin, unique("py-messages"))

    put = queue_data.put_messages(
        queue_id,
        oci.queue.models.PutMessagesDetails(
            messages=[
                oci.queue.models.PutMessagesDetailsEntry(content="python one"),
                oci.queue.models.PutMessagesDetailsEntry(content="python two"),
            ]
        ),
    ).data
    assert len(put.messages) == 2
    assert put.messages[0].id == 1

    got = queue_data.get_messages(queue_id, limit=10, timeout_in_seconds=0).data
    assert len(got.messages) == 2
    first = got.messages[0]
    assert first.content == "python one"
    assert first.receipt
    assert first.delivery_count == 1

    stats = queue_data.get_stats(queue_id).data
    assert stats.queue.in_flight_messages == 2

    queue_data.delete_message(queue_id, first.receipt)
    queue_admin.delete_queue(queue_id)


# ── Vault / KMS / Secrets ────────────────────────────────────────────────────


@pytest.fixture(scope="module")
def kms_vault(sdk_config):
    return oci.key_management.KmsVaultClient(sdk_config, service_endpoint=ENDPOINT)


def create_vault(kms_vault, name):
    return kms_vault.create_vault(
        oci.key_management.models.CreateVaultDetails(
            compartment_id=TENANCY, display_name=name, vault_type="DEFAULT"
        )
    ).data


def test_vault_and_key_through_returned_endpoints(sdk_config, kms_vault):
    vault = create_vault(kms_vault, unique("py-vault"))
    assert vault.lifecycle_state == "ACTIVE"
    assert vault.management_endpoint
    assert vault.crypto_endpoint

    # The SDK builds clients straight from the returned endpoints.
    management = oci.key_management.KmsManagementClient(
        sdk_config, service_endpoint=vault.management_endpoint
    )
    key = management.create_key(
        oci.key_management.models.CreateKeyDetails(
            compartment_id=TENANCY,
            display_name="py-key",
            key_shape=oci.key_management.models.KeyShape(algorithm="AES", length=32),
        )
    ).data
    # Keys reach ENABLED, not ACTIVE.
    assert key.lifecycle_state == "ENABLED"
    assert key.vault_id == vault.id
    assert key.current_key_version


def test_crypto_roundtrip(sdk_config, kms_vault):
    vault = create_vault(kms_vault, unique("py-crypto-vault"))
    management = oci.key_management.KmsManagementClient(
        sdk_config, service_endpoint=vault.management_endpoint
    )
    key = management.create_key(
        oci.key_management.models.CreateKeyDetails(
            compartment_id=TENANCY,
            display_name="py-crypto-key",
            key_shape=oci.key_management.models.KeyShape(algorithm="AES", length=32),
        )
    ).data

    crypto = oci.key_management.KmsCryptoClient(
        sdk_config, service_endpoint=vault.crypto_endpoint
    )
    plaintext = base64.b64encode(b"python crypto payload").decode()
    encrypted = crypto.encrypt(
        oci.key_management.models.EncryptDataDetails(key_id=key.id, plaintext=plaintext)
    ).data
    assert encrypted.ciphertext != plaintext

    decrypted = crypto.decrypt(
        oci.key_management.models.DecryptDataDetails(
            key_id=key.id, ciphertext=encrypted.ciphertext
        )
    ).data
    assert decrypted.plaintext == plaintext
    assert decrypted.plaintext_checksum


def test_secret_is_write_only_and_readable_via_bundle(sdk_config, kms_vault):
    vault = create_vault(kms_vault, unique("py-secret-vault"))
    management = oci.key_management.KmsManagementClient(
        sdk_config, service_endpoint=vault.management_endpoint
    )
    key = management.create_key(
        oci.key_management.models.CreateKeyDetails(
            compartment_id=TENANCY,
            display_name="py-secret-key",
            key_shape=oci.key_management.models.KeyShape(algorithm="AES", length=32),
        )
    ).data

    vaults = oci.vault.VaultsClient(sdk_config, service_endpoint=ENDPOINT)
    secret_value = base64.b64encode(b"python-s3cret").decode()
    secret = vaults.create_secret(
        oci.vault.models.CreateSecretDetails(
            compartment_id=TENANCY,
            vault_id=vault.id,
            key_id=key.id,
            secret_name=unique("py-secret"),
            secret_content=oci.vault.models.Base64SecretContentDetails(
                content_type="BASE64", content=secret_value
            ),
        )
    ).data
    assert secret.lifecycle_state == "ACTIVE"
    assert secret.current_version_number == 1

    # Content only comes back through the separate secrets client.
    secrets = oci.secrets.SecretsClient(sdk_config, service_endpoint=ENDPOINT)
    bundle = secrets.get_secret_bundle(secret.id).data
    assert bundle.version_number == 1
    assert bundle.secret_bundle_content.content == secret_value


# ── Streaming ────────────────────────────────────────────────────────────────


@pytest.fixture(scope="module")
def stream_admin(sdk_config):
    return oci.streaming.StreamAdminClient(sdk_config, service_endpoint=ENDPOINT)


def test_stream_lifecycle_and_cursor_consumption(sdk_config, stream_admin):
    created = stream_admin.create_stream(
        oci.streaming.models.CreateStreamDetails(
            name=unique("py-stream"), partitions=1, compartment_id=TENANCY
        )
    )
    # Dual-mode: full body AND a work-request id.
    assert created.headers["opc-work-request-id"]
    stream = created.data
    assert stream.lifecycle_state == "ACTIVE"
    assert stream.messages_endpoint
    assert stream.retention_in_hours == 24

    data_client = oci.streaming.StreamClient(
        sdk_config, service_endpoint=stream.messages_endpoint
    )
    put = data_client.put_messages(
        stream.id,
        oci.streaming.models.PutMessagesDetails(
            messages=[
                oci.streaming.models.PutMessagesDetailsEntry(
                    key=base64.b64encode(b"k1").decode(),
                    value=base64.b64encode(b"first").decode(),
                ),
                oci.streaming.models.PutMessagesDetailsEntry(
                    value=base64.b64encode(b"second").decode()
                ),
            ]
        ),
    ).data
    assert put.failures == 0
    assert len(put.entries) == 2

    cursor = data_client.create_cursor(
        stream.id,
        oci.streaming.models.CreateCursorDetails(partition="0", type="TRIM_HORIZON"),
    ).data
    assert cursor.value

    messages = data_client.get_messages(stream.id, cursor.value, limit=10)
    assert len(messages.data) == 2
    # Message.stream carries the stream NAME, not the OCID.
    assert messages.data[0].stream == stream.name
    assert base64.b64decode(messages.data[0].value) == b"first"
    assert messages.headers["opc-next-cursor"]

    stream_admin.delete_stream(stream.id)


# ── Functions (management plane; invocation lives in FunctionsDockerTest) ─────


def test_functions_management_plane(sdk_config):
    client = oci.functions.FunctionsManagementClient(
        sdk_config, service_endpoint=ENDPOINT
    )
    app = client.create_application(
        oci.functions.models.CreateApplicationDetails(
            compartment_id=TENANCY,
            display_name=unique("py-app"),
            subnet_ids=["ocid1.subnet.oc1.iad.pysubnet"],
        )
    ).data
    assert app.lifecycle_state == "ACTIVE"
    assert app.shape == "GENERIC_X86"

    fn = client.create_function(
        oci.functions.models.CreateFunctionDetails(
            application_id=app.id,
            display_name=unique("py-fn"),
            image="iad.ocir.io/tenant/py-hello:0.0.1",
            memory_in_mbs=256,
        )
    ).data
    assert fn.lifecycle_state == "ACTIVE"
    assert fn.memory_in_mbs == 256
    assert fn.image_digest.startswith("sha256:")
    assert fn.invoke_endpoint

    listed = client.list_functions(app.id).data
    assert any(item.id == fn.id for item in listed)

    client.delete_function(fn.id)
    client.delete_application(app.id)

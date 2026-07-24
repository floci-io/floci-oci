"""Object Storage API validated through the real oci Python SDK."""

import io
import time

import oci
import pytest

from conftest import NAMESPACE, TENANCY


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


@pytest.fixture
def bucket(object_storage):
    name = unique("py-bucket")
    object_storage.create_bucket(
        NAMESPACE,
        oci.object_storage.models.CreateBucketDetails(
            name=name, compartment_id=TENANCY
        ),
    )
    yield name
    listing = object_storage.list_objects(NAMESPACE, name).data
    for obj in listing.objects:
        object_storage.delete_object(NAMESPACE, name, obj.name)
    object_storage.delete_bucket(NAMESPACE, name)


def test_namespace(object_storage):
    assert object_storage.get_namespace().data == NAMESPACE


def test_bucket_lifecycle(object_storage):
    name = unique("py-bucket-crud")
    created = object_storage.create_bucket(
        NAMESPACE,
        oci.object_storage.models.CreateBucketDetails(
            name=name, compartment_id=TENANCY
        ),
    )
    assert created.data.namespace == NAMESPACE
    assert created.headers.get("etag")

    fetched = object_storage.get_bucket(NAMESPACE, name).data
    assert fetched.etag

    object_storage.delete_bucket(NAMESPACE, name)
    with pytest.raises(oci.exceptions.ServiceError) as err:
        object_storage.get_bucket(NAMESPACE, name)
    assert err.value.status == 404
    assert err.value.code == "BucketNotFound"


def test_object_put_get_head_delete(object_storage, bucket):
    payload = b"python sdk payload"
    put = object_storage.put_object(
        NAMESPACE,
        bucket,
        "greeting.txt",
        payload,
        content_type="text/plain",
        opc_meta={"owner": "python-compat"},
    )
    assert put.headers.get("opc-content-md5")

    got = object_storage.get_object(NAMESPACE, bucket, "greeting.txt")
    assert got.data.content == payload
    assert got.headers["opc-meta-owner"] == "python-compat"

    head = object_storage.head_object(NAMESPACE, bucket, "greeting.txt")
    assert int(head.headers["content-length"]) == len(payload)

    object_storage.delete_object(NAMESPACE, bucket, "greeting.txt")
    with pytest.raises(oci.exceptions.ServiceError) as err:
        object_storage.get_object(NAMESPACE, bucket, "greeting.txt")
    assert err.value.status == 404
    assert err.value.code == "ObjectNotFound"


def test_list_objects_with_prefix_and_delimiter(object_storage, bucket):
    for name in ["a/1.txt", "a/2.txt", "top.txt"]:
        object_storage.put_object(NAMESPACE, bucket, name, b"x")

    listing = object_storage.list_objects(NAMESPACE, bucket, delimiter="/").data
    assert len(listing.objects) == 1
    assert listing.prefixes == ["a/"]

    prefixed = object_storage.list_objects(NAMESPACE, bucket, prefix="a/").data
    assert len(prefixed.objects) == 2


def test_copy_object_via_work_request(object_storage, bucket):
    object_storage.put_object(NAMESPACE, bucket, "source", b"copy me")
    copy = object_storage.copy_object(
        NAMESPACE,
        bucket,
        oci.object_storage.models.CopyObjectDetails(
            source_object_name="source",
            destination_region="us-ashburn-1",
            destination_namespace=NAMESPACE,
            destination_bucket=bucket,
            destination_object_name="copied",
        ),
    )
    work_request_id = copy.headers["opc-work-request-id"]
    work_request = object_storage.get_work_request(work_request_id).data
    assert work_request.status == "COMPLETED"

    copied = object_storage.get_object(NAMESPACE, bucket, "copied")
    assert copied.data.content == b"copy me"


def test_multipart_upload(object_storage, bucket):
    upload = object_storage.create_multipart_upload(
        NAMESPACE,
        bucket,
        oci.object_storage.models.CreateMultipartUploadDetails(object="assembled.bin"),
    ).data

    part1 = object_storage.upload_part(
        NAMESPACE, bucket, "assembled.bin", upload.upload_id, 1, b"hello "
    )
    part2 = object_storage.upload_part(
        NAMESPACE, bucket, "assembled.bin", upload.upload_id, 2, b"python"
    )

    object_storage.commit_multipart_upload(
        NAMESPACE,
        bucket,
        "assembled.bin",
        upload.upload_id,
        oci.object_storage.models.CommitMultipartUploadDetails(
            parts_to_commit=[
                oci.object_storage.models.CommitMultipartUploadPartDetails(
                    part_num=1, etag=part1.headers["etag"]
                ),
                oci.object_storage.models.CommitMultipartUploadPartDetails(
                    part_num=2, etag=part2.headers["etag"]
                ),
            ]
        ),
    )

    assembled = object_storage.get_object(NAMESPACE, bucket, "assembled.bin")
    assert assembled.data.content == b"hello python"


def test_upload_manager_streams_large_object(object_storage, bucket):
    """UploadManager drives the multipart flow internally — a stronger contract test."""
    data = b"x" * (5 * 1024 * 1024)
    upload_manager = oci.object_storage.UploadManager(
        object_storage, allow_multipart_uploads=True, allow_parallel_uploads=False
    )
    upload_manager.upload_stream(NAMESPACE, bucket, "large.bin", io.BytesIO(data))

    head = object_storage.head_object(NAMESPACE, bucket, "large.bin")
    assert int(head.headers["content-length"]) == len(data)

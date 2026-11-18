"""Amazon S3 Compatibility API validated through boto3, read back with the oci SDK.

OCI serves the S3 API on <namespace>.compat.objectstorage.<region>.<domain>, path style. The
suite connects to the emulator's usual endpoint and sets that Host on every request, because the
compat network gives the emulator no such DNS name; floci-oci routes on the Host header alone.
"""

import time
from urllib.parse import urlparse

import boto3
import oci
import pytest
from botocore.config import Config

from conftest import ENDPOINT, NAMESPACE, TENANCY

COMPAT_HOST = f"{NAMESPACE}.compat.objectstorage.us-ashburn-1.localhost.floci.io"


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


@pytest.fixture(scope="module")
def access_key(identity):
    user = identity.create_user(
        oci.identity.models.CreateUserDetails(
            compartment_id=TENANCY, name=unique("py-s3-user"), description="s3 compat"
        )
    ).data
    key = identity.create_customer_secret_key(
        oci.identity.models.CreateCustomerSecretKeyDetails(display_name="s3"), user.id
    ).data
    yield key
    identity.delete_customer_secret_key(user.id, key.id)
    identity.delete_user(user.id)


@pytest.fixture(scope="module")
def s3(access_key):
    client = boto3.client(
        "s3",
        endpoint_url=ENDPOINT,
        region_name="us-ashburn-1",
        aws_access_key_id=access_key.id,
        aws_secret_access_key=access_key.key,
        config=Config(s3={"addressing_style": "path"}, signature_version="s3v4"),
    )
    port = urlparse(ENDPOINT).port

    def compat_host(request, **kwargs):
        request.headers["Host"] = f"{COMPAT_HOST}:{port}" if port else COMPAT_HOST

    client.meta.events.register("before-send.s3.*", compat_host)
    return client


def test_customer_secret_key_is_returned_once(identity, access_key):
    assert access_key.key
    listed = identity.list_customer_secret_keys(access_key.user_id).data
    assert [k.id for k in listed] == [access_key.id]


def test_put_list_get_and_read_back_with_the_oci_sdk(s3, object_storage):
    bucket = unique("py-s3")
    s3.create_bucket(Bucket=bucket)
    s3.put_object(Bucket=bucket, Key="dir/a.txt", Body=b"hello s3", Metadata={"owner": "py"})

    listed = s3.list_objects_v2(Bucket=bucket, Prefix="dir/")
    assert [o["Key"] for o in listed["Contents"]] == ["dir/a.txt"]

    got = s3.get_object(Bucket=bucket, Key="dir/a.txt")
    assert got["Body"].read() == b"hello s3"
    assert got["Metadata"] == {"owner": "py"}

    native = object_storage.get_object(NAMESPACE, bucket, "dir/a.txt")
    assert native.data.content == b"hello s3"
    assert native.headers["opc-meta-owner"] == "py"

    s3.delete_object(Bucket=bucket, Key="dir/a.txt")
    s3.delete_bucket(Bucket=bucket)


def test_multipart_upload(s3):
    bucket = unique("py-s3-mp")
    s3.create_bucket(Bucket=bucket)
    part = b"x" * (5 * 1024 * 1024)
    upload = s3.create_multipart_upload(Bucket=bucket, Key="big.bin")
    etags = []
    for number in (1, 2):
        response = s3.upload_part(
            Bucket=bucket, Key="big.bin", UploadId=upload["UploadId"], PartNumber=number, Body=part
        )
        etags.append({"PartNumber": number, "ETag": response["ETag"]})
    result = s3.complete_multipart_upload(
        Bucket=bucket, Key="big.bin", UploadId=upload["UploadId"], MultipartUpload={"Parts": etags}
    )
    assert result["ETag"].endswith('-2"')
    assert s3.head_object(Bucket=bucket, Key="big.bin")["ContentLength"] == 2 * len(part)

    s3.delete_objects(Bucket=bucket, Delete={"Objects": [{"Key": "big.bin"}]})
    s3.delete_bucket(Bucket=bucket)


def test_missing_key_is_no_such_key(s3):
    bucket = unique("py-s3-empty")
    s3.create_bucket(Bucket=bucket)
    with pytest.raises(s3.exceptions.NoSuchKey):
        s3.get_object(Bucket=bucket, Key="missing")
    s3.delete_bucket(Bucket=bucket)

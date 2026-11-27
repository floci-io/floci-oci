# Identity (IAM)

OCI Identity and Access Management — API version `20160918`.

## Supported operations

| Resource | Operations |
|---|---|
| Compartments | Create, Get, List (`compartmentIdInSubtree`, `name`, `lifecycleState`, `sortBy`, `sortOrder`, `accessLevel`), Update, Delete and Move (async, work request), Recover |
| Users | Create, Get, List, Update, Delete |
| Groups | Create, Get, List, Update, Delete |
| User group memberships | AddUserToGroup, Get, List (by user/group), RemoveUserFromGroup |
| Policies | Create, Get, List, Update, Delete |
| Auth tokens | Create, List, Update, Delete |
| Availability domains | List (3 ADs) |
| Fault domains | List (3 per AD) |
| Regions | List (every region in the realm of the request's region) |
| Region subscriptions | List, Create |
| Tenancies | Get |
| Work requests | Get, List |

## Quickstart

```bash
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci iam compartment create --endpoint http://localhost:4599 \
  --compartment-id "$TENANCY" --name dev --description "Development"

oci iam compartment list --endpoint http://localhost:4599 --compartment-id "$TENANCY"
```

## Notes & limitations

- The root compartment is the tenancy itself; `GET /compartments/{tenancyOcid}` returns it.
- Deleting a compartment is asynchronous, like real OCI: `202` + `opc-work-request-id`. The
  compartment must be empty, in every region: no child compartments, no policies, and no
  resources of any other service (buckets, queues, streams, vaults, keys, secrets, functions
  applications, OKE clusters and node pools). If anything is left, the work request ends
  `FAILED` with a `Conflict` error listing it, and the compartment stays `ACTIVE`. Otherwise it
  ends `SUCCEEDED` and the compartment is `DELETED` with a random suffix appended to its name
  (`dev.qR5hP2BD`), which `RecoverCompartment` keeps. Deleting a `DELETED` compartment again
  succeeds and leaves it unchanged. The work request finishes immediately (real OCI takes a few
  minutes and passes through `DELETING`).
- Compartments nest at most six levels below the tenancy, and a tenancy holds at most 1,000
  compartments that are not `DELETED`, as on real OCI. OCI documents both limits but not their
  errors, so the codes are emulator choices: creating, moving or recovering a compartment past
  the depth limit is `400 InvalidParameter`, and the count limit is `400 LimitExceeded` with a message
  that starts "Maximum number of compartments", the text the Terraform provider looks for.
- Availability domains follow the real naming, `<prefix>:<region>-AD-<n>`: the prefix is `Floc`
  for the default tenancy and a stable four letters derived from the OCID for any other, and the
  region part is `PHX` and `US-ASHBURN` for the two original regions and the upper-cased region
  name elsewhere (`Floc:US-ASHBURN-AD-1`, `Floc:PHX-AD-1`, `Floc:US-CHICAGO-1-AD-1`). Fault
  domains are `FAULT-DOMAIN-1` to `FAULT-DOMAIN-3`.
- `compartmentIdInSubtree=true` is only accepted on the tenancy (root compartment), as on
  real OCI. `accessLevel` is validated but not enforced: every compartment is accessible.
- Region subscriptions complete immediately with status `READY`. Each tenancy has its own home
  region, reported by `GetTenancy` as `homeRegionKey`: a tenancy seen for the first time gets
  `default-region` and keeps it, so changing `default-region` later does not move an existing
  tenancy's home.
- A tenancy can only use the regions it is subscribed to, as on real OCI: a request whose
  `Host` names another region returns `401 NotAuthenticated`. The home region is always
  subscribed, and `CreateRegionSubscription` adds one more at once, so a multi-region setup
  needs that one call per extra region (by hand or from an init hook). A region outside the
  tenancy's realm is rejected the same way; OCI documents that rule but not its error code, so
  the `401` there is an emulator choice. A `Host` that names no region (`localhost`) always
  reaches the tenancy's home region. Emulator endpoints, pre-authenticated requests and
  `CreateRegionSubscription` itself are never blocked.
- Every other service requires its `compartmentId` to be an `ACTIVE` compartment created here,
  or the tenancy itself (the root compartment), as on real OCI: an unknown, deleted or blank
  one is `400 RelatedResourceNotAuthorizedOrNotFound` in a request body and
  `404 NotAuthorizedOrNotFound` in a query. Create the compartment first, or use the tenancy
  OCID. The check is skipped only when Identity is disabled, since nothing could then create a
  compartment.
- As on real OCI, compartments, users, groups, group memberships and policies can only be
  created, updated or deleted through the home region's endpoint. A write sent with another
  region in the `Host` header returns `403 NotAllowed` ("This operation must be directed at the
  home region."). Reads work in every region, and changes are visible everywhere at once (real
  OCI takes a few minutes to propagate them). Clients that never set a region host reach
  their tenancy's home region, so they are unaffected.
  `CreateRegionSubscription` is accepted in every region: the SDKs do not tie it to the home
  region, so floci-oci does not either.
- Policy statements are stored verbatim; the policy language is not parsed or enforced.
- Customer Secret Keys (`/20160918/users/{userId}/customerSecretKeys`) are served for the
  [Amazon S3 Compatibility API](object-storage.md#amazon-s3-compatibility-api): create returns the
  secret once, list returns summaries without it.
- API signing keys (`/20160918/users/{userId}/apiKeys`): upload takes an RSA public key in PEM
  format and returns it ACTIVE, with `keyId` `<tenancy OCID>/<user OCID>/<fingerprint>`. The
  fingerprint is the MD5 of the DER public key, as `oci setup` computes it. A user holds at most
  three keys (`400 LimitExceeded`), list returns a bare array, and delete takes the fingerprint.
  Deleting a user deletes its keys. An uploaded key verifies requests only when signature
  verification is on: see [Request signing](#request-signing).
- Auth tokens (`/20160918/users/{userId}/authTokens`): create returns the token ACTIVE with its
  `token`, the only response that carries it. List (a bare array) and update return it without.
  Tokens never expire (`timeExpires` is null). A user holds at most two tokens (`400 LimitExceeded`),
  the limit `oci-go-sdk/identity/auth_token.go` documents. Only the token's SHA-256 is stored.
  Deleting a user deletes its tokens. In `strict` mode an auth token is the password of
  [OCIR `docker login`](artifacts.md#docker-login-in-strict-mode).
- Identity domains, dynamic groups and tag namespaces are not implemented yet.

## Request signing

OCI clients sign every request with an `Authorization: Signature ...` header (draft-cavage HTTP
Signatures). floci-oci always parses its `keyId` for the caller's tenancy and user. Whether it also
checks the signature is `FLOCI_OCI_AUTH_SIGNATURE_VERIFICATION`:

| Mode | API key (`keyId="<tenancy>/<user>/<fingerprint>"`) | Security token (`keyId="ST$<jwt>"`) |
|---|---|---|
| `off` (default) | parsed only | parsed only |
| `known-keys` | an uploaded or trusted key must verify; an unknown key passes unverified | must verify against the token's `jwk` |
| `strict` | the key must be uploaded or trusted, and verify | must verify against the token's `jwk` |

A request that fails gets `401 NotAuthenticated` with the standard error body and `opc-request-id`.
The message is the catalog's ("The required authentication information was not provided or was
incorrect") followed by the reason, such as "the signature does not match the signing key" or
"the signing key is not known". It never repeats the signature.

`FLOCI_OCI_AUTH_REQUIRE_SIGNATURE` keeps its own meaning: it refuses requests with no Signature
header. Verification applies to signed requests, so set both to refuse everything that is not signed
with a known key. The exempt paths (`/health`, `/_floci/*`, `/_floci-oci/*`, pre-authenticated
requests, the OCIR registry protocol and the Notifications subscription links) are never verified.

**What is checked**, following oci-go-sdk `common/http_signer.go`, cross-checked against oci-python-sdk
`signer.py` and oci-java-sdk `RequestSignerImpl`:

- The signing string is rebuilt from the Signature's `headers=` list, in the order given, as
  `name: value` lines joined by `\n`. The list is never inferred from the method, because oci-go-sdk's
  PutObject and oci-python-sdk's `enforce_content_headers=False` sign a PUT or POST with
  `date (request-target) host` only. A listed header that the request lacks fails verification.
- `(request-target)` is the lowercase method, a space, and the raw path with `?` and the raw query,
  exactly as the client sent them. `host` is the `Host` header.
- The algorithm must be `rsa-sha256`. The signature is checked as RSA PKCS#1 v1.5 with SHA-256, then
  as RSASSA-PSS with SHA-256, since oci-go-sdk writes `algorithm="rsa-sha256"` for its PSS scheme too.
- The signed `date` must be within 5 minutes of the emulator's clock, either way. A signature that
  does not cover `date` (or `x-date`) fails.
- When `x-content-sha256` is signed, it must be the base64 SHA-256 of the body. Unsigned bodies are
  never read for this. A signed body over 64 MiB is refused.

**Which key verifies:**

- An API key: the ACTIVE key uploaded for the keyId's user, in the keyId's tenancy, with the keyId's
  fingerprint. While Identity is disabled, no uploaded key counts.
- Any key listed in `FLOCI_OCI_AUTH_TRUSTED_PUBLIC_KEYS` (comma-separated PEM paths) verifies every
  keyId with that key's fingerprint, whatever tenancy and user it names. That is how the first
  `CreateUser` and `UploadApiKey` of a `strict` emulator are signed, the same idea as floci-aws's
  `test` credentials, made explicit. A private key file (`oci setup keys` output, unencrypted) is
  accepted too, and only its public half is used.
- A security token: the RSA key in the JWT's `jwk` claim, which oci-java-sdk's `SecurityTokenAdapter`
  reads as the session key. The JWT's own signature is **not** verified, because Oracle's token
  issuer key is not available, so anyone can mint a token naming any tenancy. Resource principal
  tokens, which carry `res_tenant` instead of `tenant`, name no caller and are not verified.

**Not covered:** SigV4 on the Amazon S3 Compatibility API is never verified, because a Customer
Secret Key's secret is returned once and not stored. OCIR `docker login` checks the password against
the user's auth tokens in `strict` mode only, and takes any password otherwise. The Streaming Kafka
endpoint has no SASL yet, so it takes no password in any mode.

OKE's `generate-token` bearer tokens are verified the same way when the mode is not `off`: the
signature over `date (request-target) host`, with the URL's `date`, `get /cluster_request/<cluster-ocid>`
and the Container Engine host.

**Inferred** (no Oracle source states it):

- Real OCI's exact checks and messages are not public, so the reasons in the message are floci-oci's.
- `x-date` in place of `date`: none of the SDK signers in `local/oracle` sends it.
- A Signature with no `headers=` parameter covers `date` alone, the draft-cavage default.
- The 64 MiB cap on a signed body: the SDKs sign only JSON-sized bodies and stream uploads unsigned.
- Trying both PSS salt lengths (the hash length and the maximum): oci-go-sdk's `PSSSaltLengthAuto`
  signs with the maximum.

## Emulator choices

Behaviour that OCI's SDKs and documentation do not settle, and what floci-oci does:

- **Auth token values are 20 characters** from letters, digits and `-_.:()+`. OCI documents no
  format or length; this set needs no quoting in a shell, a Docker config or a Kafka JAAS line.
- **Auth token OCIDs are `ocid1.credential.<realm>..<unique>`.** No source in `local/oracle` shows
  an auth token OCID.
- **Uploading a key the user already has is `409 Conflict`.** No Oracle source names the error,
  so floci-oci uses the catalog's code for a request that conflicts with existing state.
- **Compartment names are unique per parent.** The SDK's `CreateCompartment` comment says a
  name must be unique across the tenancy, while the compartments documentation says two
  compartments within the same parent can't share a name. floci-oci follows the documentation.
- **`MoveCompartment` work requests use operation type `MOVE_COMPARTMENT`.** Identity's
  `WorkRequest` enum only lists `DELETE_COMPARTMENT` and `DELETE_TAG_DEFINITION`.
- **`ListRegions` lists the regions of the request's realm**, the realm of the region named by
  the `Host` header (or the home region), since a tenancy can only use regions in its realm.
- **Changes are immediate.** Region subscriptions are `READY` at once (OCI: `IN_PROGRESS` for
  several minutes), IAM changes are visible in every region at once (OCI: a few minutes to
  propagate from the home region), and compartment delete and move work requests finish at once.
- **Error codes OCI does not document** for a rule it does document: `400 InvalidParameter` for
  the compartment depth limit, `400 LimitExceeded` for the compartment count limit,
  `401 NotAuthenticated` for a region outside the realm, and a `Conflict` work-request error
  for a non-empty compartment.

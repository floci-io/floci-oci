# DNS

OCI DNS is served under `/20180115` on port 4599: zones, their records, and private views. Real OCI
serves it on the `dns.{region}.oci.oraclecloud.com` host.

## Supported operations

| Area | Operations |
|---|---|
| Zones | CreateZone, GetZone, ListZones, UpdateZone, DeleteZone, ChangeZoneCompartment |
| Zone records | GetZoneRecords, UpdateZoneRecords, PatchZoneRecords |
| Domain records | GetDomainRecords, UpdateDomainRecords, PatchDomainRecords, DeleteDomainRecords |
| RRSets | GetRRSet, UpdateRRSet, PatchRRSet, DeleteRRSet |
| Views | CreateView, GetView, ListViews, UpdateView, DeleteView, ChangeViewCompartment |

Not emulated: steering policies and their attachments, TSIG keys, DNSSEC (`dnssecState: ENABLED`),
zone transfers and secondary zones (`zoneType: SECONDARY`), zone migration, and CreateZoneFromZoneFile.
Those that have a route answer `501 MethodNotImplemented`; the others have no route.

## Wire notes

- **Zones by name or OCID:** every zone path takes `zoneNameOrId`. A name without `scope` means the
  `GLOBAL` zone of that name. With `scope=PRIVATE`, a name also needs `viewId`, or the request is
  `400 MissingParameter`, as the SDK documentation says.
- **Create:** answers `201` with the zone, `etag` and `Location`. The zone is `ACTIVE` at once.
  - `scope` defaults to `GLOBAL`, `zoneType` to `PRIMARY`, `resolutionMode` to `STATIC` and
    `dnssecState` to `DISABLED`.
  - A `PRIVATE` zone needs a `viewId` (`400 MissingParameter`) naming an existing view
    (`400 RelatedResourceNotAuthorizedOrNotFound`). A `GLOBAL` zone with a `viewId` is
    `400 InvalidParameter`.
  - A zone name is unique among `GLOBAL` zones, and among the zones of one view (`409 Conflict`).
  - A trailing dot on the name is dropped.
- **Delete:** DeleteZone, DeleteView, DeleteRRSet and DeleteDomainRecords answer `204`, and remove the
  resource at once; a later GET is `404`.
- **List shapes:** ListZones and ListViews return bare arrays. ListZones also sends `opc-total-items`.
  Zone summaries leave out `nameservers`, `externalMasters` and `externalDownstreams`, as
  `ZoneSummary` does.
- **Records:** every record read and write returns `{"items": [...]}` with `opc-total-items`,
  `opc-next-page` and the zone's `etag`. Records sort by `domain` by default (also `rtype`, `ttl`).
  Each record carries `domain`, `rtype`, `rdata`, `ttl`, `isProtected`, `recordHash` and
  `rrsetVersion`.
- **Protected apex records:** every zone is created with an SOA record and four NS records at its
  apex, marked `isProtected`. Adding, removing or replacing them is `400 InvalidParameter`. A PUT
  that lists them unchanged (the OCI CLI's own test sends the whole zone back) is accepted, and a PUT
  that leaves them out keeps them.
- **Versions:** a write that changes the records raises the zone `serial` by one. `version` is the
  serial as a string. Every RRSet that changed, and the SOA, carry the new `rrsetVersion`; the SOA
  rdata shows the new serial; the `etag` changes.
- **PATCH:** the operations apply in order to a copy, and the result is stored only if all of them
  succeed.
  - An operation selects records by any of `recordHash`, `domain`, `rtype` and `rdata`; `ttl` is not
    a selector.
  - `REQUIRE` with no match, or `PROHIBIT` with a match, fails the whole request with
    `412 NoEtagMatch`.
  - `ADD` adds a record, or sets the TTL of one that is already there. `ADD` with a `ttl` and no
    `rdata` sets the TTL of the whole RRSet, as the SDK documents.
  - `REMOVE` removes every match; removing nothing succeeds.
  - On a domain or RRSet path, `domain` and `rtype` default to the path's, and a different value
    is `400 InvalidParameter`.
- **PUT** replaces every unprotected record in its scope (zone, domain, or RRSet).
- **Domains** must lie within the zone (`400 InvalidParameter`).
- **Conditional requests:** `If-Match` on every write is checked against the zone or view `etag`
  (`412 NoEtagMatch`). `If-None-Match` on GetZone, GetView and the record reads answers `304` when
  it matches.
- **Views:** CreateView answers `201` with an `etag`, `ACTIVE`. A view that still holds zones cannot
  be deleted (`409 Conflict`).
- **OCIDs** use `dns-zone` and `dnsview`, with the region in the OCID.

## RDATA normalization

OCI says "All RDATA is normalized and the returned presentation of your RDATA may differ from its
initial input". floci-oci applies the transforms that Terraform's `normalizeRData` undoes
(`terraform-provider-oci/internal/service/dns/dns_record_resource.go`), so a plan after an apply is
empty:

| Type | Stored and returned as |
|---|---|
| `A` | the IPv4 address; anything else is `400 InvalidParameter` |
| `AAAA` | the compressed IPv6 form: `2001:0db8:85a3:0000:0000:8a2e:0370:7334` becomes `2001:db8:85a3::8a2e:370:7334` |
| `ALIAS`, `CNAME`, `DNAME`, `NS`, `PTR` | the name with a trailing dot |
| `MX` | the preference and the exchange with a trailing dot |
| `TXT`, `SPF` | every word or quoted string as its own quoted string: `v=spf1 ~all` becomes `"v=spf1" "~all"` |

Other types keep their rdata with whitespace collapsed.

## Inferred behaviour

These points are not stated by the OCI SDKs, the CLI or the Terraform provider, and follow the
closest evidence:

- **Seeded SOA and NS:** the Terraform provider skips protected records when it deletes an RRSet,
  which implies protected records exist; their values are floci-oci's own. The nameservers are
  `ns1` to `ns4` under `dns.localhost.floci.io`, and the SOA is
  `ns1.dns.localhost.floci.io. hostmaster.<zone>. <serial> 3600 600 604800 1800`, with TTLs 300
  (SOA) and 86400 (NS).
- **Status codes:** `201` for CreateZone and CreateView, `204` for the deletes (the SDK documents
  `204` for DeleteZone), `200` for the compartment moves. No DNS work request API exists, so no
  `opc-work-request-id` is sent.
- **412 code:** Oracle's error catalog has one `412` code, `NoEtagMatch`, which is what a failed
  `REQUIRE` or `PROHIBIT` sends. Terraform only checks the `412` status.
- **Protected-record errors** are `400 InvalidParameter`. DeleteDomainRecords at the apex removes
  only the unprotected records.
- **`recordHash`** is an MD5 of the domain, type, rdata and TTL. **`zoneVersion`** is accepted and
  ignored: no history is kept.
- **GLOBAL zones are regional** in floci-oci: they live in the region the request addressed.
- **OCID types** `dns-zone` and `dnsview`.
- **GetRRSet** for an RRSet with no records answers `200` with no items.

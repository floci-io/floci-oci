package io.floci.oci.services.dns.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One DNS resource record of a zone (oci-go-sdk dns/record.go). {@code rdata} is stored already
 * normalized, as the service returns it; {@code recordHash} is derived from the other fields.
 */
@RegisterForReflection
public class StoredRecord {

    private String domain;
    private String rtype;
    private String rdata;
    private int ttl;
    private boolean protectedRecord;
    private String rrsetVersion;
    private String recordHash;

    public StoredRecord() {
    }

    public StoredRecord(String domain, String rtype, String rdata, int ttl, boolean protectedRecord) {
        this.domain = domain;
        this.rtype = rtype;
        this.rdata = rdata;
        this.ttl = ttl;
        this.protectedRecord = protectedRecord;
    }

    public StoredRecord copy() {
        StoredRecord copy = new StoredRecord(domain, rtype, rdata, ttl, protectedRecord);
        copy.setRrsetVersion(rrsetVersion);
        copy.setRecordHash(recordHash);
        return copy;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getRtype() {
        return rtype;
    }

    public void setRtype(String rtype) {
        this.rtype = rtype;
    }

    public String getRdata() {
        return rdata;
    }

    public void setRdata(String rdata) {
        this.rdata = rdata;
    }

    public int getTtl() {
        return ttl;
    }

    public void setTtl(int ttl) {
        this.ttl = ttl;
    }

    public boolean isProtectedRecord() {
        return protectedRecord;
    }

    public void setProtectedRecord(boolean protectedRecord) {
        this.protectedRecord = protectedRecord;
    }

    public String getRrsetVersion() {
        return rrsetVersion;
    }

    public void setRrsetVersion(String rrsetVersion) {
        this.rrsetVersion = rrsetVersion;
    }

    public String getRecordHash() {
        return recordHash;
    }

    public void setRecordHash(String recordHash) {
        this.recordHash = recordHash;
    }
}

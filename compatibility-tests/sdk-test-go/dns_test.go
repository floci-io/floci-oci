package sdktestgo

import (
	"context"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/dns"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func dnsClient(t *testing.T) dns.DnsClient {
	client, err := dns.NewDnsClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	client.Host = getEndpoint()
	return client
}

func TestDnsZoneAndRRSetRoundTrip(t *testing.T) {
	client := dnsClient(t)
	ctx := context.Background()
	name := unique("go-dns") + ".example"

	created, err := client.CreateZone(ctx, dns.CreateZoneRequest{
		CreateZoneDetails: dns.CreateZoneDetails{
			Name:          common.String(name),
			CompartmentId: common.String(tenancy()),
			ZoneType:      dns.CreateZoneDetailsZoneTypePrimary,
		},
	})
	require.NoError(t, err)
	zoneId := created.Id
	t.Cleanup(func() {
		_, _ = client.DeleteZone(context.Background(), dns.DeleteZoneRequest{
			ZoneNameOrId: zoneId, RequestMetadata: noRetry()})
	})
	assert.Equal(t, dns.ZoneLifecycleStateActive, created.LifecycleState)
	assert.Equal(t, dns.ScopeGlobal, created.Scope)
	require.NotNil(t, created.ETag)

	listed, err := client.ListZones(ctx, dns.ListZonesRequest{CompartmentId: common.String(tenancy()),
		Name: common.String(name)})
	require.NoError(t, err)
	require.Len(t, listed.Items, 1)
	assert.Equal(t, *zoneId, *listed.Items[0].Id)

	domain := "app." + name
	patched, err := client.PatchRRSet(ctx, dns.PatchRRSetRequest{
		ZoneNameOrId: zoneId, Domain: common.String(domain), Rtype: common.String("TXT"),
		PatchRrSetDetails: dns.PatchRrSetDetails{Items: []dns.RecordOperation{
			{Operation: dns.RecordOperationOperationProhibit, Domain: common.String(domain), Rtype: common.String("TXT")},
			{Operation: dns.RecordOperationOperationAdd, Domain: common.String(domain), Rtype: common.String("TXT"),
				Rdata: common.String("hello go"), Ttl: common.Int(30)},
		}},
	})
	require.NoError(t, err)
	require.Len(t, patched.Items, 1)
	assert.Equal(t, `"hello" "go"`, *patched.Items[0].Rdata)
	assert.Equal(t, 1, *patched.OpcTotalItems)

	_, err = client.PatchRRSet(ctx, dns.PatchRRSetRequest{
		ZoneNameOrId: zoneId, Domain: common.String(domain), Rtype: common.String("TXT"),
		PatchRrSetDetails: dns.PatchRrSetDetails{Items: []dns.RecordOperation{
			{Operation: dns.RecordOperationOperationRequire, Domain: common.String(domain),
				Rtype: common.String("TXT"), Rdata: common.String("absent")},
		}},
		RequestMetadata: noRetry(),
	})
	requireServiceError(t, err, 412, "NoEtagMatch")

	records, err := client.GetZoneRecords(ctx, dns.GetZoneRecordsRequest{ZoneNameOrId: common.String(name)})
	require.NoError(t, err)
	assert.Equal(t, 6, *records.OpcTotalItems)
	assert.NotNil(t, records.ETag)

	zone, err := client.GetZone(ctx, dns.GetZoneRequest{ZoneNameOrId: zoneId})
	require.NoError(t, err)
	assert.Equal(t, int64(2), *zone.Serial)
	assert.Equal(t, "2", *zone.Version)
}

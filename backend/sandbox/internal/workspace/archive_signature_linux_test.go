package workspace

import (
	"net/http/httptest"
	"testing"
	"time"
)

func TestArchiveSignatureVector(t *testing.T) {
	a := frozenArchive{accessKey: "fixture", secretKey: "fixture-secret", region: "us-east-1", now: func() time.Time { return time.Date(2025, 1, 2, 3, 4, 5, 0, time.UTC) }}
	payload := sum(nil)
	req := httptest.NewRequest("GET", "https://archive.example/bucket/blobs/"+payload, nil)
	req.Header.Set("X-Amz-Content-Sha256", payload)
	a.sign(req, payload)
	const expected = "AWS4-HMAC-SHA256 Credential=fixture/20250102/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=d3a07d85e0c046fe9a3583c42769575ee5b9e4c1b36606e6ef5d5ebe2ddbb38c"
	if got := req.Header.Get("Authorization"); got != expected {
		t.Fatalf("Authorization = %q", got)
	}
}

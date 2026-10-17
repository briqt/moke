package codes

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"syscall"
	"testing"
)

func TestBoundaryIsJSONWithCodeAndRedactedDetail(t *testing.T) {
	err := Boundary("start node", errors.New("bad key tskey-auth-abc123 and pw hunter22 at https://login.example/a/XyZ_123"), Internal, "hunter22")
	var e Error
	if json.Unmarshal([]byte(err.Error()), &e) != nil {
		t.Fatalf("not JSON: %s", err)
	}
	if e.Code != Internal || !strings.HasPrefix(e.Detail, "start node: ") {
		t.Fatalf("unexpected %+v", e)
	}
	for _, leaked := range []string{"tskey-auth-abc123", "hunter22", "XyZ_123"} {
		if strings.Contains(e.Detail, leaked) {
			t.Fatalf("detail leaks %q: %s", leaked, e.Detail)
		}
	}
}

func TestBoundaryKeepsExistingCode(t *testing.T) {
	inner := fmt.Errorf("wrapped: %w", New(NotInTailnet, "x"))
	var e *Error
	if !errors.As(Boundary("dial", inner, Internal), &e) || e.Code != NotInTailnet {
		t.Fatalf("code lost: %v", e)
	}
	if Boundary("noop", nil, Internal) != nil {
		t.Fatal("nil must stay nil")
	}
}

func TestClassify(t *testing.T) {
	cases := []struct {
		err  error
		want Code
	}{
		{context.DeadlineExceeded, Timeout},
		{&net.OpError{Op: "dial", Err: syscall.ECONNREFUSED}, TargetRefused},
		{&net.OpError{Op: "dial", Err: syscall.EHOSTUNREACH}, TargetUnreachable},
		{&net.DNSError{Err: "no such host", Name: "x", IsNotFound: true}, NameNotFound},
		{errors.New("other"), Internal},
	}
	for _, c := range cases {
		if got := Classify(c.err, Internal); got != c.want {
			t.Errorf("Classify(%v) = %s, want %s", c.err, got, c.want)
		}
	}
}

func TestRedactBoundsLength(t *testing.T) {
	if got := Redact(strings.Repeat("a", 5000)); len(got) > 2100 {
		t.Fatalf("not truncated: %d", len(got))
	}
}

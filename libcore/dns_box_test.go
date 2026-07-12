package libcore

import (
	"context"
	"net/netip"
	"sync"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
)

func TestExchangeContextSuccessCompletes(t *testing.T) {
	done := make(chan struct{})
	response := &ExchangeContext{
		done: sync.OnceFunc(func() {
			close(done)
		}),
	}

	response.Success("192.0.2.1")

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Success did not signal completion")
	}
	if len(response.addresses) != 1 || response.addresses[0] != netip.MustParseAddr("192.0.2.1") {
		t.Fatalf("unexpected addresses after completion: %v", response.addresses)
	}
}

func TestExchangeContextTerminalCallbacksCompleteOnce(t *testing.T) {
	completions := 0
	response := &ExchangeContext{
		done: sync.OnceFunc(func() {
			completions++
		}),
	}

	response.Success("192.0.2.2")
	response.ErrorCode(mDNS.RcodeNameError)
	response.ErrnoCode(1)
	response.Success("192.0.2.3")

	if completions != 1 {
		t.Fatalf("terminal callbacks completed %d times, want 1", completions)
	}
}

func TestPlatformLocalDNSTransportExchangeA(t *testing.T) {
	response := exchangeLookupResponse(t, mDNS.TypeA, "192.0.2.4")

	answer, ok := response.Answer[0].(*mDNS.A)
	if !ok {
		t.Fatalf("unexpected A response type: %T", response.Answer[0])
	}
	if got := answer.A.String(); got != "192.0.2.4" {
		t.Fatalf("unexpected A response: %s", got)
	}
}

func TestPlatformLocalDNSTransportExchangeAAAA(t *testing.T) {
	response := exchangeLookupResponse(t, mDNS.TypeAAAA, "2001:db8::4")

	answer, ok := response.Answer[0].(*mDNS.AAAA)
	if !ok {
		t.Fatalf("unexpected AAAA response type: %T", response.Answer[0])
	}
	if got := answer.AAAA.String(); got != "2001:db8::4" {
		t.Fatalf("unexpected AAAA response: %s", got)
	}
}

type successfulLookupTransport struct {
	result string
}

func (t *successfulLookupTransport) Raw() bool {
	return false
}

func (t *successfulLookupTransport) NetworkHandle() int64 {
	return 0
}

func (t *successfulLookupTransport) Lookup(ctx *ExchangeContext, network string, domain string) error {
	ctx.Success(t.result)
	return nil
}

func (t *successfulLookupTransport) Exchange(ctx *ExchangeContext, message []byte) error {
	return nil
}

func exchangeLookupResponse(t *testing.T, queryType uint16, result string) *mDNS.Msg {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	transport := &platformLocalDNSTransport{
		iif: &successfulLookupTransport{result: result},
	}
	request := new(mDNS.Msg)
	request.SetQuestion("example.invalid.", queryType)
	response, err := transport.Exchange(ctx, request)
	if err != nil {
		t.Fatalf("Exchange failed: %v", err)
	}
	if len(response.Answer) != 1 {
		t.Fatalf("unexpected answer count: %d", len(response.Answer))
	}
	return response
}

package boxapi

import (
	"context"
	"net"
	"net/http"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
)

func CreateProxyHttpClient(box *box.Box, tracker adapter.ConnectionTracker) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   time.Second * 3,
		ResponseHeaderTimeout: time.Second * 3,
	}

	if box != nil {
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return DialContext(ctx, box, tracker, network, addr)
		}
	}

	client := &http.Client{
		Transport: transport,
	}

	return client
}

// CreateProxyHttpClientWithTag 返回的 client 所有请求都强制经由指定出站 tag。
func CreateProxyHttpClientWithTag(box *box.Box, tracker adapter.ConnectionTracker, tag string) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   time.Second * 3,
		ResponseHeaderTimeout: time.Second * 3,
	}

	if box != nil {
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return DialContextWithTag(ctx, box, tracker, tag, network, addr)
		}
	}

	return &http.Client{Transport: transport}
}

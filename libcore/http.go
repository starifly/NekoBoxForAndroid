package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

const (
	defaultHTTPTimeout     = 120 * time.Second
	defaultHTTPDialTimeout = 30 * time.Second
	// defaultHTTPRequestTimeout bounds the whole request including body reads,
	// so GetContentLimited/WriteToLimited cannot block forever on a stalled body.
	// It is generous enough for large rule-asset downloads over slow links.
	defaultHTTPRequestTimeout = 10 * time.Minute
	defaultHTTPStringLimit    = 10 * 1024 * 1024
	defaultHTTPFileLimit      = 256 * 1024 * 1024
)

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32, username string, password string)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentLimited(limit int64) ([]byte, error)
	GetContentString() (*StringBox, error)
	GetContentStringLimited(limit int64) (*StringBox, error)
	WriteTo(path string) error
	WriteToLimited(path string, limit int64) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	dialer := &net.Dialer{Timeout: defaultHTTPDialTimeout}
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.DialContext = dialer.DialContext
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.TLSHandshakeTimeout = defaultHTTPTimeout
	client.h1h2Transport.ResponseHeaderTimeout = defaultHTTPTimeout
	client.h1h2Transport.DisableKeepAlives = true
	// Bound the full request (including body read) so callers cannot hang forever.
	client.h1h2Client.Timeout = defaultHTTPRequestTimeout
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32, username string, password string) {
	dialer := &net.Dialer{Timeout: defaultHTTPDialTimeout}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), username, password)
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer device.DeferPanicToError("http execute", func(err error) { log.Println(err) })
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	response, err := r.h1h2Client.Do(&r.request)
	if err != nil {
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

type requestFunc func() (response *http.Response, err error)

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	successCh := make(chan *http.Response, 1)
	var finalErr error
	var failedCount atomic.Uint32
	var successCount atomic.Uint32
	var mu sync.Mutex

	funcs := []requestFunc{
		// Http(s) With Ech
		func() (response *http.Response, err error) {
			request := r.request.Clone(context.Background())
			echClient := &http.Client{
				Timeout: defaultHTTPRequestTimeout,
				Transport: &http.Transport{
					DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
						var d net.Dialer
						c, err := d.DialContext(ctx, network, addr)
						if err != nil {
							return c, err
						}
						domain := addr
						if host, _, _ := net.SplitHostPort(addr); host != "" {
							domain = host
						}
						echTls := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
						return echTls.Client(ctx, c)
					},
					DisableKeepAlives: true,
				},
			}
			return echClient.Do(request)
		},
		// H3 HTTPS
		func() (response *http.Response, err error) {
			request := r.request.Clone(context.Background())
			h3Client := &http.Client{
				Timeout: defaultHTTPRequestTimeout,
				Transport: &http3.Transport{
					TLSClientConfig: r.tls.Clone(),
					QUICConfig: &quic.Config{
						MaxIdleTimeout: time.Second,
					},
				},
			}
			return h3Client.Do(request)
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	for i, f := range funcs {
		go func(f requestFunc) {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })
			defer func() {
				if successCount.Load() == 0 {
					if failedCount.Add(1) >= uint32(len(funcs)) {
						// all failed
						cancel()
					}
				}
			}()

			var t string
			switch i {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			// execute the HTTP request
			rsp, err := f()
			if rsp == nil || err != nil {
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				if rsp != nil && rsp.Body != nil {
					rsp.Body.Close()
				}
				return
			}

			// handle the HTTP status code
			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				return
			}

			select {
			case successCh <- rsp:
				// first successful request, don't close the body
				successCount.Add(1)
			default:
				rsp.Body.Close()
			}
		}(f)
	}

	select {
	case result := <-successCh:
		return &httpResponse{Response: result}, nil
	case <-ctx.Done():
		return nil, finalErr
	}
}

type httpResponse struct {
	*http.Response

	contentMu    sync.Mutex
	contentRead  bool
	content      []byte
	contentError error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	return h.GetContentLimited(defaultHTTPStringLimit)
}

func (h *httpResponse) GetContentLimited(limit int64) ([]byte, error) {
	h.contentMu.Lock()
	defer h.contentMu.Unlock()
	if h.contentRead {
		if h.contentError != nil {
			return nil, h.contentError
		}
		if int64(len(h.content)) > limit {
			return nil, fmt.Errorf("HTTP response body exceeds %d bytes", limit)
		}
		return h.content, nil
	}
	defer h.Body.Close()
	h.contentRead = true
	h.content, h.contentError = readAllLimited(h.Body, limit)
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	return h.GetContentStringLimited(defaultHTTPStringLimit)
}

func (h *httpResponse) GetContentStringLimited(limit int64) (*StringBox, error) {
	content, err := h.getContentStringLimited(limit)
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	return h.getContentStringLimited(defaultHTTPStringLimit)
}

func (h *httpResponse) getContentStringLimited(limit int64) (string, error) {
	content, err := h.GetContentLimited(limit)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	return h.WriteToLimited(path, defaultHTTPFileLimit)
}

func (h *httpResponse) WriteToLimited(path string, limit int64) (err error) {
	defer h.Body.Close()
	dir, base := filepath.Split(path)
	if dir == "" {
		dir = "."
	}
	file, err := os.CreateTemp(dir, base+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := file.Name()
	defer func() {
		if err != nil {
			_ = os.Remove(tmpPath)
		}
	}()
	defer func() {
		if closeErr := file.Close(); err == nil {
			err = closeErr
		}
		if err == nil {
			err = os.Rename(tmpPath, path)
		}
	}()
	_, err = copyLimited(file, h.Body, limit)
	return err
}

func readAllLimited(reader io.Reader, limit int64) ([]byte, error) {
	if limit < 0 {
		return nil, fmt.Errorf("invalid HTTP response limit %d", limit)
	}
	content, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(content)) > limit {
		return nil, fmt.Errorf("HTTP response body exceeds %d bytes", limit)
	}
	return content, nil
}

func copyLimited(dst io.Writer, src io.Reader, limit int64) (int64, error) {
	if limit < 0 {
		return 0, fmt.Errorf("invalid HTTP response limit %d", limit)
	}
	written, err := io.Copy(dst, io.LimitReader(src, limit+1))
	if err != nil {
		return written, err
	}
	if written > limit {
		return written, fmt.Errorf("stream exceeds %d bytes", limit)
	}
	return written, nil
}

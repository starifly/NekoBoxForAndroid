package v2ray

import (
	"context"

	"github.com/justinwoo280/sing-xhttp/xhttp"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Xray XHTTP（split-http）传输的 sing-box 适配层。
//
// 实现来自 github.com/justinwoo280/sing-xhttp：该库不依赖 sing-box，
// 自行声明了与 adapter.V2RayClientTransport / V2RayServerTransport
// 结构完全一致的接口，所以这里只做 option → xhttp.Options 的字段映射。

func toXHTTPRange(value *option.V2RayXHTTPRange) *xhttp.Range {
	if value == nil {
		return nil
	}
	return &xhttp.Range{From: value.From, To: value.To}
}

func toXHTTPOptions(options option.V2RayXHTTPOptions) xhttp.Options {
	converted := xhttp.Options{
		Mode:                 options.Mode,
		Host:                 options.Host,
		Path:                 options.Path,
		Method:               options.UplinkHTTPMethod,
		Headers:              options.Headers,
		NoGRPCHeader:         options.NoGRPCHeader,
		NoSSEHeader:          options.NoSSEHeader,
		XPaddingBytes:        toXHTTPRange(options.XPaddingBytes),
		ScMaxEachPostBytes:   toXHTTPRange(options.ScMaxEachPostBytes),
		ScMaxBufferedPosts:   options.ScMaxBufferedPosts,
		ScMinPostsIntervalMs: toXHTTPRange(options.ScMinPostsIntervalMs),
		ScStreamUpServerSecs: toXHTTPRange(options.ScStreamUpServerSecs),
		XPaddingObfsMode:     options.XPaddingObfsMode,
		XPaddingPlacement:    options.XPaddingPlacement,
		XPaddingKey:          options.XPaddingKey,
		XPaddingHeader:       options.XPaddingHeader,
		XPaddingMethod:       options.XPaddingMethod,
		SessionPlacement:     options.SessionPlacement,
		SessionKey:           options.SessionKey,
		SessionIDTable:       options.SessionIDTable,
		SessionIDLength:      toXHTTPRange(options.SessionIDLength),
		SeqPlacement:         options.SeqPlacement,
		SeqKey:               options.SeqKey,
		UplinkDataPlacement:  options.UplinkDataPlacement,
		UplinkDataKey:        options.UplinkDataKey,
		UplinkChunkSize:      toXHTTPRange(options.UplinkChunkSize),
		ServerMaxHeaderBytes: options.ServerMaxHeaderBytes,
	}
	// App 侧下发的独立下载配置目前只取 host/path（与上游实现的子集对齐）
	if download, loaded := options.Download.(map[string]any); loaded {
		downloadConfig := &xhttp.DownloadConfig{}
		if host, loaded := download["host"].(string); loaded {
			downloadConfig.Host = host
		}
		if path, loaded := download["path"].(string); loaded {
			downloadConfig.Path = path
		}
		converted.DownloadSettings = downloadConfig
	}
	if options.Xmux != nil {
		converted.Xmux = &xhttp.XmuxConfig{
			MaxConcurrency:   toXHTTPRange(options.Xmux.MaxConcurrency),
			MaxConnections:   toXHTTPRange(options.Xmux.MaxConnections),
			CMaxReuseTimes:   toXHTTPRange(options.Xmux.CMaxReuseTimes),
			HMaxRequestTimes: toXHTTPRange(options.Xmux.HMaxRequestTimes),
			HMaxReusableSecs: toXHTTPRange(options.Xmux.HMaxReusableSecs),
			HKeepAlivePeriod: options.Xmux.HKeepAlivePeriod,
		}
	}
	return converted
}

func NewXHTTPClient(
	ctx context.Context,
	dialer N.Dialer,
	serverAddr M.Socksaddr,
	options option.V2RayXHTTPOptions,
	tlsConfig tls.Config,
) (adapter.V2RayClientTransport, error) {
	return xhttp.NewClient(ctx, dialer, serverAddr, toXHTTPOptions(options), tlsConfig)
}

func NewXHTTPServer(
	ctx context.Context,
	logger logger.ContextLogger,
	options option.V2RayXHTTPOptions,
	tlsConfig tls.ServerConfig,
	handler adapter.V2RayServerTransportHandler,
) (adapter.V2RayServerTransport, error) {
	return xhttp.NewServer(ctx, logger, toXHTTPOptions(options), tlsConfig, handler)
}

package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		// sing-box 1.15 removed the conntrack package. Connections dialed by
		// outbound dialers are tracked by the box ConnectionManager, which is
		// also what upstream closes on network changes and on the daemon's
		// "close all connections" command.
		if mainInstance != nil && mainInstance.connectionManager != nil {
			mainInstance.connectionManager.CloseAll()
			log.Println("Reset system connections done")
		} else {
			log.Println("Reset system connections: no running instance")
		}
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api             *v2rayapi.StatsService
	connectionManager adapter.ConnectionManager
	selector          *group.Selector
	pauseManager      pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:               instance,
		cancel:            cancel,
		pauseManager:      service.FromContext[pause.Manager](ctx),
		connectionManager: service.FromContext[adapter.ConnectionManager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	// boxapi was removed in sing-box 1.15: the V2Ray stats service now lives in
	// experimental/v2rayapi and implements adapter.ConnectionTracker directly.
	statsService := v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	if statsService == nil {
		return
	}
	b.v2api = statsService
	b.Box.Router().AppendTracker(statsService)
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	// The Android traffic looper accumulates per-interval deltas, so counters
	// are reset on read (same behavior as the removed boxapi helper).
	response, err := b.v2api.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name:   fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct),
		Reset_: true,
	})
	if err != nil || response == nil || response.Stat == nil {
		return 0
	}
	return response.Stat.Value
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return false
	}
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func (b *BoxInstance) SelectOutboundFor(selectorTag, tag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return false
	}
	proxy, ok := b.Outbound().Outbound(selectorTag)
	if !ok {
		return false
	}
	selector, ok := proxy.(*group.Selector)
	if !ok {
		return false
	}
	return selector.SelectOutbound(tag)
}

func (b *BoxInstance) CurrentOutboundFor(groupTag string) string {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return ""
	}
	proxy, ok := b.Outbound().Outbound(groupTag)
	if !ok {
		return ""
	}
	switch outbound := proxy.(type) {
	case *group.Selector:
		return outbound.Now()
	case *group.URLTest:
		return outbound.Now()
	default:
		return ""
	}
}

func (b *BoxInstance) RefreshURLTestFor(groupTag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 {
		return false
	}
	proxy, ok := b.Outbound().Outbound(groupTag)
	if !ok {
		return false
	}
	urlTest, ok := proxy.(*group.URLTest)
	if !ok {
		return false
	}
	// The group's context is cancelled by Close. Do not hold up Android's
	// caller or core shutdown while probes wait for unreachable nodes.
	go urlTest.CheckOutbounds()
	return true
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })

	ctx := context.Background()
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeout)*time.Millisecond)
		defer cancel()
	}

	detour, err := urlTestDetour(i)
	if err != nil {
		return 0, err
	}

	// urltest.URLTest is the upstream replacement for the removed
	// boxapi.CreateProxyHttpClient: the request is dialed through detour, so
	// the probe really leaves through the outbound under test.
	latencyValue, err := urltest.URLTest(ctx, link, detour)
	if err != nil {
		return 0, err
	}
	return int32(latencyValue), nil
}

// urlTestDetour resolves the dialer a URL test has to go through:
//
//   - a test instance: that instance's default outbound, i.e. the node under test;
//   - no instance: the default outbound of the running main instance;
//   - no instance at all: the system network (direct).
//
// Since sing-box 1.15 some protocols (WireGuard) are endpoints instead of
// outbounds, so a proxy endpoint is picked when no proxy outbound exists.
func urlTestDetour(i *BoxInstance) (N.Dialer, error) {
	if i == nil {
		i = mainInstance
	}
	if i == nil {
		return N.SystemDialer, nil
	}
	if outbound := i.proxyOutbound(); outbound != nil {
		return outbound, nil
	}
	if endpoints := i.Endpoint().Endpoints(); len(endpoints) > 0 {
		return endpoints[0], nil
	}
	if outbound := i.Outbound().Default(); outbound != nil {
		return outbound, nil
	}
	return nil, errors.New("no outbound to test in the box instance")
}

// proxyOutbound returns the first outbound that actually proxies, skipping the
// direct / block / dns helper outbounds the app always appends.
func (b *BoxInstance) proxyOutbound() adapter.Outbound {
	for _, outbound := range b.Outbound().Outbounds() {
		switch outbound.Type() {
		case "direct", "block", "dns":
			continue
		}
		return outbound
	}
	return nil
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}

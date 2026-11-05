package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"net/netip"
	"strings"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

// sing-box 1.14 的 adapter.PlatformInterface 有 34 个方法（1.12 只有 12 个），
// 其中 Android 实际用到的只有 TUN、接口控制、WIFI 状态、连接归属这几组，
// 其余（shell / bridge / tailscale / neighbor 等）一律声明为不支持。

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	return nil
}

// ---- TUN / 接口控制 ----

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	// call protect_path
	if !isBgProcess {
		_ = sendFdToProtect(fd, "protect_path")
		return nil
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	options.FileDescriptor = int(tunFd)
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(options option.TunPlatformOptions) error {
	return nil
}

// ---- 默认接口监听 ----

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

// ---- 网络接口列表 ----

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("android: platform network interfaces not supported")
}

// ---- 网络扩展 / DNS ----

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

// ---- WIFI ----

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool {
	return false
}

// ---- 连接归属 ----

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	network := N.NetworkTCP
	if request.IpProtocol == syscall.IPPROTO_UDP {
		network = N.NetworkUDP
	}
	source := netip.AddrPortFrom(
		netip.MustParseAddr(request.SourceAddress), uint16(request.SourcePort),
	)
	destination := netip.AddrPortFrom(
		netip.MustParseAddr(request.DestinationAddress), uint16(request.DestinationPort),
	)

	if useProcfs {
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(
			request.IpProtocol,
			request.SourceAddress, request.SourcePort,
			request.DestinationAddress, request.DestinationPort,
		)
		if err != nil {
			return nil, err
		}
	}

	packageName, _ := intfBox.PackageNameByUid(uid)
	return &adapter.ConnectionOwner{
		ProcessID:           0,
		UserId:              uid,
		ProcessPath:         "",
		AndroidPackageNames: []string{packageName},
	}, nil
}

// ---- 通知 ----

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return nil
}

// ---- 地址 ----

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return nil
}

// ---- 邻居解析（Android 不支持）----

func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

// ---- Shell 会话（Android 不支持）----

func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, E.New("android: platform shell not supported")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, nil
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", nil
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, nil
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string {
	return ""
}

// ---- Bridge（Android 不支持）----

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, E.New("android: platform bridge not supported")
}

// ---- 日志 ----

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}

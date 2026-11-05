package libcore

import (
	"fmt"
	"net"
	"path/filepath"
	"strings"
	"sync"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	g.geoipReader = geoipReader
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	countryMap := make(map[string][]*net.IPNet)
	var (
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		countryMap[nextCountryCode] = append(countryMap[nextCountryCode], ipNet)
	}

	ipNets := countryMap[strings.ToLower(countryCode)]

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geoip)
		if err := g.Open(filepath.Join(externalAssetsPath, "geoip.db")); err != nil {
			return nil, err
		}
		defer g.geoipReader.Close()
		return g.Rules(name)
	}
}

// ---- IP 归属地查询（供 Kotlin 侧显示国旗）----

var (
	geoIPQueryOnce   sync.Once
	geoIPQueryReader *maxminddb.Reader
	geoIPQueryErr    error
)

type geoIPQueryRecord struct {
	Country struct {
		ISOCode string `maxminddb:"iso_code"`
	} `maxminddb:"country"`
}

// lookupCountryCode 兼容两种记录格式：
//   - sing-geoip 生成的库：值就是两位国家码字符串（见 Rules() 里的 networks.Network(&code)）
//   - 标准 MaxMind GeoLite2-Country：{country: {iso_code: "US"}}
func lookupCountryCode(reader *maxminddb.Reader, ip net.IP) string {
	var code string
	if err := reader.Lookup(ip, &code); err == nil {
		if c := strings.ToUpper(strings.TrimSpace(code)); len(c) == 2 {
			return c
		}
	}
	var record geoIPQueryRecord
	if err := reader.Lookup(ip, &record); err == nil {
		if c := strings.ToUpper(strings.TrimSpace(record.Country.ISOCode)); len(c) == 2 {
			return c
		}
	}
	return ""
}

func getGeoIPQueryReader() (*maxminddb.Reader, error) {
	geoIPQueryOnce.Do(func() {
		geoIPQueryReader, geoIPQueryErr = maxminddb.Open(
			filepath.Join(externalAssetsPath, "geoip.db"),
		)
	})
	return geoIPQueryReader, geoIPQueryErr
}

// QueryGeoIPCountry 返回 IP 的 ISO 3166-1 两位国家代码（如 "US"），查不到返回空串
func QueryGeoIPCountry(ip string) string {
	parsed := net.ParseIP(strings.TrimSpace(ip))
	if parsed == nil {
		return ""
	}
	reader, err := getGeoIPQueryReader()
	if err != nil || reader == nil {
		return ""
	}
	return lookupCountryCode(reader, parsed)
}

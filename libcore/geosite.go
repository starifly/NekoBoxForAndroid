package libcore

import (
	"fmt"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
}

func (g *geosite) Open(path string) error {
	geositeReader, _, err := geosites.Open(path)
	g.geositeReader = geositeReader
	return err
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

// geosite reader kept for potential direct use; upstream sing-box 1.15 loads geosite
// via its own resource manager using constant.resourcePaths.

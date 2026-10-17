package netbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"github.com/briqt/moke/netbridge/internal/codes"
)

type nodeConfig struct {
	ID         string `json:"id"`
	Dir        string `json:"dir"`
	ControlURL string `json:"controlURL"`
	AuthKey    string `json:"authKey"`
	Hostname   string `json:"hostname"`
}

// validID admits the app's network IDs, including versioned instances ("id.r2").
func validID(id string) bool {
	if len(id) == 0 || len(id) > 64 {
		return false
	}
	for _, ch := range id {
		if (ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9') && ch != '-' && ch != '_' && ch != '.' {
			return false
		}
	}
	return true
}

// parseNodeConfig validates without echoing any configuration value.
func parseNodeConfig(data string) (nodeConfig, error) {
	invalid := func(detail string) (nodeConfig, error) {
		return nodeConfig{}, codes.New(codes.InvalidArgument, detail)
	}
	var c nodeConfig
	if len(data) > 32768 || json.Unmarshal([]byte(data), &c) != nil {
		return invalid("invalid node configuration JSON")
	}
	if !validID(c.ID) {
		return invalid("invalid network ID")
	}
	if len(c.Dir) > 4096 || !filepath.IsAbs(c.Dir) || strings.ContainsAny(c.Dir, "\x00\r\n") || filepath.Clean(c.Dir) == string(filepath.Separator) {
		return invalid("dir must be an absolute, non-root directory path")
	}
	if len(c.Hostname) == 0 || len(c.Hostname) > 63 || strings.ContainsAny(c.Hostname, "\x00\r\n\t ./\\") {
		return invalid("invalid device name")
	}
	u, err := url.Parse(c.ControlURL)
	if err != nil || len(c.ControlURL) > 4096 || u == nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return invalid("controlURL must be an https URL without credentials, query or fragment")
	}
	if len(c.AuthKey) > 4096 || strings.ContainsAny(c.AuthKey, "\x00\r\n\t ") {
		return invalid("invalid auth key")
	}
	c.Dir = filepath.Clean(c.Dir)
	return c, nil
}

// canonicalDir resolves symlinked ancestors even before the leaf exists, so
// two live nodes can never share one directory through aliases.
func canonicalDir(path string) (string, error) {
	var missing []string
	for {
		resolved, err := filepath.EvalSymlinks(path)
		if err == nil {
			for i := len(missing) - 1; i >= 0; i-- {
				resolved = filepath.Join(resolved, missing[i])
			}
			if resolved == string(filepath.Separator) {
				return "", errors.New("dir resolves to the root directory")
			}
			return resolved, nil
		}
		if !os.IsNotExist(err) {
			return "", fmt.Errorf("resolve dir: %w", err)
		}
		parent := filepath.Dir(path)
		if parent == path {
			return "", errors.New("dir has no existing ancestor")
		}
		missing = append(missing, filepath.Base(path))
		path = parent
	}
}

// checkCredentialEnvironment refuses tsnet's fallback from an empty auth key to
// process-wide credentials (TS_AUTHKEY, OAuth/WIF) instead of touching them.
func checkCredentialEnvironment(authKey string, getenv func(string) string) error {
	if authKey != "" {
		return nil
	}
	for _, name := range []string{"TS_AUTHKEY", "TS_AUTH_KEY", "TS_CLIENT_SECRET", "TS_CLIENT_ID", "TS_ID_TOKEN", "TS_AUDIENCE"} {
		if getenv(name) != "" {
			return codes.New(codes.InvalidArgument, "ambient tailnet credentials are set in the process environment")
		}
	}
	return nil
}

// boundStore scopes the app's StateStore to one network.
type boundStore struct {
	id    string
	store StateStore
}

func (s boundStore) Read(key string) ([]byte, error)      { return s.store.Read(s.id, key) }
func (s boundStore) Write(key string, value []byte) error { return s.store.Write(s.id, key, value) }

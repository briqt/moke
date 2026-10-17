//go:build android

package netbridge

import "tailscale.com/net/netmon"

// Same as the official Android client: tell netmon which interface carries the
// default route (pinned netmon reads lastKnownDefaultRouteIfName on Android).
func reportDefaultRoute(name string) { netmon.UpdateLastKnownDefaultRouteInterface(name) }

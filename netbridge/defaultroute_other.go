//go:build !android

package netbridge

// Host builds have their own netlink/route-table default-route detection.
func reportDefaultRoute(string) {}

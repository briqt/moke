// Package codes carries stable error codes across the gomobile boundary.
//
// Every error the bridge returns is a JSON object {"code": ..., "detail": ...}.
// The code is a fixed identifier the app maps to user-facing text; the detail is
// for diagnostics only and has secrets removed before it leaves Go.
package codes

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"regexp"
	"strings"
	"syscall"
)

type Code string

const (
	InvalidArgument Code = "INVALID_ARGUMENT"
	NotInitialized  Code = "NOT_INITIALIZED"
	NotStarted      Code = "NOT_STARTED"
	AlreadyStarted  Code = "ALREADY_STARTED"
	Limit           Code = "LIMIT"
	// Busy: the network still carries connections or relays, so it is not stopped.
	Busy Code = "BUSY"

	NeedsLogin       Code = "NEEDS_LOGIN"
	NeedsMachineAuth Code = "NEEDS_MACHINE_AUTH"
	LoginExpired     Code = "LOGIN_EXPIRED"
	// NotReady: the node is still starting or has not reached the control server yet.
	NotReady     Code = "NOT_READY"
	NotInTailnet Code = "NOT_IN_TAILNET"
	NameNotFound Code = "NAME_NOT_FOUND"

	ProxyUnreachable Code = "PROXY_UNREACHABLE"
	ProxyAuth        Code = "PROXY_AUTH"
	ProxyRefused     Code = "PROXY_REFUSED"
	ProxyTLS         Code = "PROXY_TLS"
	ProxyNoUDP       Code = "PROXY_NO_UDP"

	TargetRefused     Code = "TARGET_REFUSED"
	TargetUnreachable Code = "TARGET_UNREACHABLE"
	Timeout           Code = "TIMEOUT"
	Internal          Code = "INTERNAL"
)

// Error is a classified failure. Detail never contains configuration secrets.
type Error struct {
	Code   Code   `json:"code"`
	Detail string `json:"detail"`
}

func (e *Error) Error() string {
	data, _ := json.Marshal(e)
	return string(data)
}

func New(code Code, detail string) *Error { return &Error{Code: code, Detail: detail} }

// Of returns the classified error inside err, or classifies err with fallback.
func Of(err error, fallback Code) *Error {
	if err == nil {
		return nil
	}
	var e *Error
	if errors.As(err, &e) {
		return e
	}
	return &Error{Code: Classify(err, fallback), Detail: err.Error()}
}

// Classify recognises network failures common to every path; anything else is fallback.
func Classify(err error, fallback Code) Code {
	switch {
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, os.ErrDeadlineExceeded):
		return Timeout
	case errors.Is(err, syscall.ECONNREFUSED):
		return TargetRefused
	case errors.Is(err, syscall.EHOSTUNREACH), errors.Is(err, syscall.ENETUNREACH):
		return TargetUnreachable
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) && dnsErr.IsNotFound {
		return NameNotFound
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return Timeout
	}
	return fallback
}

var (
	keyPattern    = regexp.MustCompile(`tskey-[A-Za-z0-9_-]+`)
	bearerPattern = regexp.MustCompile(`(?i)bearer\s+[^\s,;"']+`)
	urlPattern    = regexp.MustCompile(`https?://[^\s"']+/(register|a)/[A-Za-z0-9_-]+`)
)

// Redact removes the given secrets and anything shaped like an auth key, bearer
// token or login URL, then bounds the length.
func Redact(message string, secrets ...string) string {
	for _, secret := range secrets {
		if len(secret) >= 4 {
			message = strings.ReplaceAll(message, secret, "[redacted]")
		}
	}
	message = keyPattern.ReplaceAllString(message, "[redacted]")
	message = bearerPattern.ReplaceAllString(message, "Bearer [redacted]")
	message = urlPattern.ReplaceAllString(message, "[redacted-url]")
	if len(message) > 2048 {
		message = message[:2048] + " (truncated)"
	}
	return message
}

// Boundary prepares err for the gomobile boundary: classified, prefixed with the
// operation, and redacted.
func Boundary(operation string, err error, fallback Code, secrets ...string) error {
	if err == nil {
		return nil
	}
	e := Of(err, fallback)
	detail := e.Detail
	if operation != "" {
		detail = operation + ": " + detail
	}
	return &Error{Code: e.Code, Detail: Redact(detail, secrets...)}
}

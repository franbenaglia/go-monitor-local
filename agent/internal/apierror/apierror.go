// Package apierror writes the error format shared by REST and WebSocket.
package apierror

import (
	"encoding/json"
	"net/http"
)

// Error codes defined in docs/PROTOCOL.md.
const (
	InvalidPeriod      = "invalid_period"
	InvalidParameter   = "invalid_parameter"
	Unauthorized       = "unauthorized"
	OriginNotAllowed   = "origin_not_allowed"
	NotFound           = "not_found"
	UnknownSeries      = "unknown_series"
	MethodNotAllowed   = "method_not_allowed"
	PayloadTooLarge    = "payload_too_large"
	NotReady           = "not_ready"
	TooManyConnections = "too_many_connections"
	InternalError      = "internal_error"
)

// Detail is the body of an error.
type Detail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Body is the envelope of an error response.
type Body struct {
	Error Detail `json:"error"`
}

// Write sends an error response.
func Write(w http.ResponseWriter, status int, code, message string) {
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(Body{Error: Detail{Code: code, Message: message}})
}

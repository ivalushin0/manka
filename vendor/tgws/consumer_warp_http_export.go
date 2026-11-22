package main
import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync/atomic"
	"time"
)

const (
	consumerWarpWorkerPrefix        = "/warp-bootstrap"
	consumerWarpWorkerHealthPath    = consumerWarpWorkerPrefix + "/health"
	consumerWarpWorkerHealthMaxBody = 8 * 1024
)

type consumerWarpWorkerHealthResult struct {
	OK     bool   `json:"ok"`
	Code   string `json:"code"`
	Status int    `json:"status,omitempty"`
}

func normalizeConsumerWarpWorkerBase(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", errors.New("worker_url_empty")
	}
	if !strings.Contains(value, "://") {
		value = "https://" + value
	}
	parsed, err := url.Parse(value)
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") {
		return "", errors.New("worker_url_https_required")
	}
	if parsed.User != nil || parsed.Hostname() == "" || parsed.Port() != "" {
		return "", errors.New("worker_url_invalid")
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errors.New("worker_url_query_forbidden")
	}
	if parsed.EscapedPath() != "" && parsed.EscapedPath() != "/" {
		return "", errors.New("worker_url_path_forbidden")
	}
	host := strings.ToLower(strings.TrimSpace(parsed.Hostname()))
	if host == "" || !strings.Contains(host, ".") {
		return "", errors.New("worker_url_host_invalid")
	}
	return "https://" + host, nil
}

func executeConsumerWarpPublicRequest(
	requestURL string,
	method string,
	path string,
	body []byte,
	bearerToken string,
	timeout time.Duration,
	includeResponseBody bool,
) consumerWarpBootstrapHTTPResult {
	if err := validateConsumerWarpBootstrapRequest(method, path); err != nil {
		return consumerWarpBootstrapHTTPResult{Code: "bootstrap_request_not_allowed"}
	}
	if timeout <= 0 {
		timeout = consumerWarpDefaultHTTPTimeout
	}

	transport := &http.Transport{
		Proxy:             nil,
		TLSClientConfig:   &tls.Config{MinVersion: tls.VersionTLS12},
		DisableKeepAlives: true,
		ForceAttemptHTTP2: false,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   timeout,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return errors.New("Consumer WARP redirects are not allowed")
		},
	}
	defer client.CloseIdleConnections()

	req, err := http.NewRequest(method, requestURL, bytes.NewReader(body))
	if err != nil {
		return consumerWarpBootstrapHTTPResult{Code: "bootstrap_request_invalid"}
	}
	req.Header.Set("User-Agent", consumerWarpUserAgent)
	req.Header.Set("CF-Client-Version", consumerWarpClientVersion)
	req.Header.Set("Accept", "application/json")
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	}
	if bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+bearerToken)
	}

	var requestSent atomic.Bool
	trace := &httptrace.ClientTrace{
		WroteHeaders: func() {
			requestSent.Store(true)
		},
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))

	response, err := client.Do(req)
	if err != nil {
		return consumerWarpBootstrapHTTPResult{
			Code:        "registration_network_error",
			RequestSent: requestSent.Load(),
		}
	}
	defer response.Body.Close()

	result := consumerWarpBootstrapHTTPResult{
		Status:      response.StatusCode,
		RequestSent: requestSent.Load(),
	}
	switch {
	case response.StatusCode == http.StatusTooManyRequests:
		result.Code = "registration_rate_limited"
		return result
	case response.StatusCode >= 500 && response.StatusCode <= 599:
		result.Code = "registration_server_error"
		return result
	case response.StatusCode < 200 || response.StatusCode > 299:
		result.Code = fmt.Sprintf("registration_http_%d", response.StatusCode)
		return result
	}

	if includeResponseBody {
		limited := io.LimitReader(response.Body, consumerWarpMaxResponseBytes+1)
		data, readErr := io.ReadAll(limited)
		if readErr != nil {
			result.Code = "registration_response_read_failed"
			return result
		}
		if len(data) > consumerWarpMaxResponseBytes {
			result.Code = "registration_response_too_large"
			return result
		}
		if len(data) == 0 {
			result.Code = "registration_empty_response"
			return result
		}
		if !json.Valid(data) {
			result.Code = "registration_response_invalid"
			return result
		}
		result.Body = string(data)
	}

	result.OK = true
	result.Code = "ok"
	return result
}

func consumerWarpRegistrationBody(publicKey string) ([]byte, error) {
	key := strings.TrimSpace(publicKey)
	if _, err := decodeWireGuardKey(key); err != nil {
		return nil, errors.New("registration_public_key_invalid")
	}
	return json.Marshal(map[string]string{"key": key})
}

func executeConsumerWarpDirect(
	method string,
	path string,
	body []byte,
	token string,
	timeoutMillis int64,
	includeBody bool,
) consumerWarpBootstrapHTTPResult {
	return executeConsumerWarpPublicRequest(
		"https://"+consumerWarpAPIHost+path,
		method,
		path,
		body,
		token,
		consumerWarpHTTPTimeout(timeoutMillis),
		includeBody,
	)
}

func executeConsumerWarpWorker(
	rawBase string,
	method string,
	path string,
	body []byte,
	token string,
	timeoutMillis int64,
	includeBody bool,
) consumerWarpBootstrapHTTPResult {
	base, err := normalizeConsumerWarpWorkerBase(rawBase)
	if err != nil {
		return consumerWarpBootstrapHTTPResult{Code: err.Error()}
	}
	return executeConsumerWarpPublicRequest(
		base+consumerWarpWorkerPrefix+path,
		method,
		path,
		body,
		token,
		consumerWarpHTTPTimeout(timeoutMillis),
		includeBody,
	)
}

//export RegisterConsumerWARPDirect
func RegisterConsumerWARPDirect(publicKey string, timeoutMillis int64) (result string) {
	defer func() {
		if recover() != nil {
			result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
		}
	}()
	key := ""
	if publicKey != "" {
		key = (publicKey)
	}
	body, err := consumerWarpRegistrationBody(key)
	if err != nil {
		return cJSON(consumerWarpBootstrapHTTPResult{Code: err.Error()})
	}
	return cJSON(executeConsumerWarpDirect(
		http.MethodPost,
		consumerWarpRegisterPath,
		body,
		"",
		int64(timeoutMillis),
		true,
	))
}

//export ActivateConsumerWARPDirect
func ActivateConsumerWARPDirect(registrationID string, token string, timeoutMillis int64) (result string) {
	defer func() {
		if recover() != nil {
			result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
		}
	}()
	id := ""
	if registrationID != "" {
		id = strings.TrimSpace((registrationID))
	}
	bearer := ""
	if token != "" {
		bearer = strings.TrimSpace((token))
	}
	if !consumerWarpRegistrationIDValid(id) {
		return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_missing_id"})
	}
	if bearer == "" {
		return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_missing_token"})
	}
	return cJSON(executeConsumerWarpDirect(
		http.MethodPatch,
		consumerWarpRegisterPath+"/"+id,
		[]byte(`{"warp_enabled":true}`),
		bearer,
		int64(timeoutMillis),
		false,
	))
}

//export CheckConsumerWARPWorker
func CheckConsumerWARPWorker(workerBase string, timeoutMillis int64) (result string) {
	defer func() {
		if recover() != nil {
			result = cJSON(consumerWarpWorkerHealthResult{Code: "native_bootstrap_panic"})
		}
	}()
	raw := ""
	if workerBase != "" {
		raw = (workerBase)
	}
	base, err := normalizeConsumerWarpWorkerBase(raw)
	if err != nil {
		return cJSON(consumerWarpWorkerHealthResult{Code: err.Error()})
	}

	transport := &http.Transport{
		Proxy:             nil,
		TLSClientConfig:   &tls.Config{MinVersion: tls.VersionTLS12},
		DisableKeepAlives: true,
		ForceAttemptHTTP2: false,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   consumerWarpHTTPTimeout(int64(timeoutMillis)),
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return errors.New("WARP Worker redirects are not allowed")
		},
	}
	defer client.CloseIdleConnections()

	req, err := http.NewRequest(http.MethodGet, base+consumerWarpWorkerHealthPath, nil)
	if err != nil {
		return cJSON(consumerWarpWorkerHealthResult{Code: "health_request_invalid"})
	}
	req.Header.Set("User-Agent", consumerWarpUserAgent)
	req.Header.Set("Accept", "application/json")
	response, err := client.Do(req)
	if err != nil {
		return cJSON(consumerWarpWorkerHealthResult{Code: "health_network_error"})
	}
	defer response.Body.Close()

	health := consumerWarpWorkerHealthResult{Status: response.StatusCode}
	if response.StatusCode < 200 || response.StatusCode > 299 {
		health.Code = fmt.Sprintf("health_http_%d", response.StatusCode)
		return cJSON(health)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, consumerWarpWorkerHealthMaxBody+1))
	if err != nil || len(data) == 0 || len(data) > consumerWarpWorkerHealthMaxBody {
		health.Code = "health_response_invalid"
		return cJSON(health)
	}
	var payload struct {
		Service  string `json:"service"`
		Revision string `json:"revision"`
	}
	if json.Unmarshal(data, &payload) != nil ||
		payload.Service != "warp-bootstrap" ||
		payload.Revision != "warp-bootstrap-v1" {
		health.Code = "health_protocol_mismatch"
		return cJSON(health)
	}
	health.OK = true
	health.Code = "ok"
	return cJSON(health)
}

//export RegisterConsumerWARPWithWorker
func RegisterConsumerWARPWithWorker(workerBase string, publicKey string, timeoutMillis int64) (result string) {
	defer func() {
		if recover() != nil {
			result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
		}
	}()
	base := ""
	if workerBase != "" {
		base = (workerBase)
	}
	key := ""
	if publicKey != "" {
		key = (publicKey)
	}
	body, err := consumerWarpRegistrationBody(key)
	if err != nil {
		return cJSON(consumerWarpBootstrapHTTPResult{Code: err.Error()})
	}
	return cJSON(executeConsumerWarpWorker(
		base,
		http.MethodPost,
		consumerWarpRegisterPath,
		body,
		"",
		int64(timeoutMillis),
		true,
	))
}

//export ActivateConsumerWARPWithWorker
func ActivateConsumerWARPWithWorker(
	workerBase string,
	registrationID string,
	token string,
	timeoutMillis int64,
) (result string) {
	defer func() {
		if recover() != nil {
			result = cJSON(consumerWarpBootstrapHTTPResult{Code: "native_bootstrap_panic"})
		}
	}()
	base := ""
	if workerBase != "" {
		base = (workerBase)
	}
	id := ""
	if registrationID != "" {
		id = strings.TrimSpace((registrationID))
	}
	bearer := ""
	if token != "" {
		bearer = strings.TrimSpace((token))
	}
	if !consumerWarpRegistrationIDValid(id) {
		return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_missing_id"})
	}
	if bearer == "" {
		return cJSON(consumerWarpBootstrapHTTPResult{Code: "registration_missing_token"})
	}
	return cJSON(executeConsumerWarpWorker(
		base,
		http.MethodPatch,
		consumerWarpRegisterPath+"/"+id,
		[]byte(`{"warp_enabled":true}`),
		bearer,
		int64(timeoutMillis),
		false,
	))
}

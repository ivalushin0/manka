package main
import "strings"

//export ConfigureAWGWarp
func ConfigureAWGWarp(
	configPath string,
	enabled int,
	preferred int,
	allowFallback int,
) int {
	path := ""
	if configPath != "" {
		path = strings.TrimSpace((configPath))
	}
	if err := globalAWGWarpRouteRuntime.Configure(
		path,
		enabled != 0,
		preferred != 0,
		allowFallback != 0,
	); err != nil {
		if logWarn != nil {
			logWarn.Printf("AWG/WARP route configuration rejected: %v", err)
		}
		return 1
	}
	return 0
}

//export ResetAWGWarp
func ResetAWGWarp() int {
	globalAWGWarpRouteRuntime.Reset()
	return 0
}

// Package core is the Android-facing surface of the Dinghy sync engine.
//
// The synchronization engine itself is SushitrainCore (MPL-2.0), which is
// platform-neutral Go and is bound directly by gomobile alongside this
// package. Only Android-specific additions belong here.
package core

import (
	"github.com/syncthing/syncthing/lib/build"
	sushitrain "t-shaped.nl/sushitrain/v2/src"
)

// linkerVersion is the version stamped in at link time via
// -X github.com/syncthing/syncthing/lib/build.Version. It is captured during
// package initialization because SushitrainCore's NewClient overwrites
// build.Version with a hardcoded constant, so reading it later reports that
// constant rather than the build we actually shipped.
var linkerVersion = build.Version

// CoreVersion reports the engine version recorded at build time. Unlike
// sushitrain.Version(), this keeps returning the linker-provided value after
// the client has been created.
func CoreVersion() string {
	return linkerVersion
}

// EngineVersion reports the version SushitrainCore currently advertises to
// peers, which changes once a client has been created.
func EngineVersion() string {
	return sushitrain.Version()
}

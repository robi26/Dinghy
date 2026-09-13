// Package core is the Android-facing surface of the Sidecar sync engine.
//
// The synchronization engine itself is SushitrainCore (MPL-2.0), which is
// platform-neutral Go and is bound directly by gomobile alongside this
// package. Only Android-specific additions belong here.
package core

import (
	sushitrain "t-shaped.nl/sushitrain/v2/src"
)

// CoreVersion reports the version of the embedded Syncthing/SushitrainCore
// engine. It exists so the binding can be smoke-tested end to end before any
// of the real API surface is wired up.
func CoreVersion() string {
	return sushitrain.Version()
}

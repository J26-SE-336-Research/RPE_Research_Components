package rpe

import (
	"crypto/rand"
	"encoding/hex"
)

// randomID returns a non-zero random identifier of the given size in bytes as lowercase hex.
func randomID(bytes int) (string, error) {
	raw := make([]byte, bytes)
	for {
		if _, e := rand.Read(raw); e != nil {
			return "", e
		}
		nonzero := false
		for _, b := range raw {
			nonzero = nonzero || b != 0
		}
		if nonzero {
			return hex.EncodeToString(raw), nil
		}
	}
}

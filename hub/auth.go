package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Roles: admin and sre may change the system; viewer is read-only (contract A3.8).
type user struct {
	Name, Password, Role string
}

type authn struct {
	users  map[string]user
	secret []byte
}

func newAuthn(spec, secret string) *authn {
	a := &authn{users: map[string]user{}, secret: []byte(secret)}
	for _, entry := range strings.Split(spec, ",") {
		parts := strings.SplitN(strings.TrimSpace(entry), ":", 3)
		if len(parts) == 3 && (parts[2] == "admin" || parts[2] == "sre" || parts[2] == "viewer") {
			a.users[parts[0]] = user{parts[0], parts[1], parts[2]}
		}
	}
	return a
}

const sessionCookie = "rpe_session"

// token is "name|role|expiryUnix|hmac".
func (a *authn) issue(u user, ttl time.Duration) string {
	payload := u.Name + "|" + u.Role + "|" + strconv.FormatInt(time.Now().Add(ttl).Unix(), 10)
	return base64.RawURLEncoding.EncodeToString([]byte(payload + "|" + a.sign(payload)))
}

func (a *authn) sign(payload string) string {
	m := hmac.New(sha256.New, a.secret)
	m.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func (a *authn) verify(token string) (user, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return user{}, false
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 4 {
		return user{}, false
	}
	payload := strings.Join(parts[:3], "|")
	if !hmac.Equal([]byte(a.sign(payload)), []byte(parts[3])) {
		return user{}, false
	}
	exp, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil || time.Now().Unix() > exp {
		return user{}, false
	}
	return user{Name: parts[0], Role: parts[1]}, true
}

func (a *authn) current(r *http.Request) (user, bool) {
	c, err := r.Cookie(sessionCookie)
	if err != nil {
		return user{}, false
	}
	return a.verify(c.Value)
}

func (a *authn) login(w http.ResponseWriter, r *http.Request) {
	var in struct{ Username, Password string }
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid login body")
		return
	}
	u, ok := a.users[in.Username]
	if !ok || subtle.ConstantTimeCompare([]byte(u.Password), []byte(in.Password)) != 1 {
		writeErr(w, http.StatusUnauthorized, "invalid username or password")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: a.issue(u, 12*time.Hour), Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
	writeJSON(w, http.StatusOK, map[string]string{"username": u.Name, "role": u.Role})
}

func (a *authn) logout(w http.ResponseWriter, _ *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1})
	w.WriteHeader(http.StatusNoContent)
}

// guard requires a session; non-GET requests also require the admin or sre role.
func (a *authn) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u, ok := a.current(r)
		if !ok {
			writeErr(w, http.StatusUnauthorized, "login required")
			return
		}
		if r.Method != http.MethodGet && u.Role == "viewer" {
			writeErr(w, http.StatusForbidden, "the viewer role is read-only")
			return
		}
		next.ServeHTTP(w, r.WithContext(withUser(r.Context(), u)))
	})
}

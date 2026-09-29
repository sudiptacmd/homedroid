package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"net"
	"os"
	"sync"
	"time"
)

// A session log entry: one JSON line per connection, written when it ends, so the dashboard
// can show who logged in, from where, for how long and what they ran. Kept short on purpose:
// commands are cut, and only the first few of each kind are kept.
type session struct {
	mu       sync.Mutex
	Via      string   `json:"via"` // "ssh" or "web"
	Start    int64    `json:"start"`
	End      int64    `json:"end,omitempty"`
	IP       string   `json:"ip"`
	User     string   `json:"user,omitempty"`
	Key      string   `json:"key,omitempty"`
	Shells   int      `json:"shells,omitempty"`
	Commands []string `json:"commands,omitempty"`
	SFTP     bool     `json:"sftp,omitempty"`
	Forwards []string `json:"forwards,omitempty"`
	Failed   string   `json:"failed,omitempty"`
}

const (
	maxItems   = 20
	maxCommand = 200
	// When the log grows past maxLogSize, only its newest keepLines lines are kept.
	maxLogSize = 512 << 10
	keepLines  = 1000
)

var logMu sync.Mutex

func newSession(via string, start time.Time, addr net.Addr, user, key string) *session {
	return &session{Via: via, Start: start.Unix(), IP: host(addr), User: user, Key: key}
}

func host(addr net.Addr) string {
	if h, _, err := net.SplitHostPort(addr.String()); err == nil {
		return h
	}
	return addr.String()
}

func (s *session) shell() {
	s.mu.Lock()
	s.Shells++
	s.mu.Unlock()
}

func (s *session) command(c string) {
	if len(c) > maxCommand {
		c = c[:maxCommand] + "…"
	}
	s.mu.Lock()
	if len(s.Commands) < maxItems {
		s.Commands = append(s.Commands, c)
	}
	s.mu.Unlock()
}

func (s *session) sftp() {
	s.mu.Lock()
	s.SFTP = true
	s.mu.Unlock()
}

func (s *session) forward(target string) {
	s.mu.Lock()
	if len(s.Forwards) < maxItems && !contains(s.Forwards, target) {
		s.Forwards = append(s.Forwards, target)
	}
	s.mu.Unlock()
}

func (s *session) finish() {
	s.mu.Lock()
	s.End = time.Now().Unix()
	line, _ := json.Marshal(s)
	s.mu.Unlock()
	appendLog(line)
}

func recordFailure(start time.Time, addr net.Addr, reason string) {
	line, _ := json.Marshal(&session{Via: "ssh", Start: start.Unix(), End: time.Now().Unix(), IP: host(addr), Failed: reason})
	appendLog(line)
}

func appendLog(line []byte) {
	if *logPath == "" {
		return
	}
	logMu.Lock()
	defer logMu.Unlock()
	f, err := os.OpenFile(*logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	f.Write(append(line, '\n'))
	st, err := f.Stat()
	f.Close()
	if err == nil && st.Size() > maxLogSize {
		trimLog()
	}
}

func trimLog() {
	data, err := os.ReadFile(*logPath)
	if err != nil {
		return
	}
	var lines [][]byte
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		lines = append(lines, append([]byte(nil), sc.Bytes()...))
	}
	if len(lines) > keepLines {
		lines = lines[len(lines)-keepLines:]
	}
	tmp := *logPath + ".tmp"
	if os.WriteFile(tmp, append(bytes.Join(lines, []byte("\n")), '\n'), 0o600) == nil {
		os.Rename(tmp, *logPath)
	}
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

package main

import (
	"bufio"
	"crypto/subtle"
	"encoding/binary"
	"encoding/json"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"time"

	"github.com/creack/pty"
)

// The dashboard's web terminal. The dashboard (in the app) handles the browser's WebSocket
// and relays it here over loopback. Other apps on the phone can reach loopback too, so every
// connection must start with the token the app passed in the environment.
//
// Protocol: one JSON line {token, cols, rows, alpine, ip}, then frames from the dashboard,
// each 1 type byte + 2 length bytes (big endian) + payload: type 0 is input, type 1 a resize
// (cols, rows as two big-endian uint16). Output flows back as raw bytes.

type terminalHello struct {
	Token  string `json:"token"`
	Cols   uint16 `json:"cols"`
	Rows   uint16 `json:"rows"`
	Alpine bool   `json:"alpine"`
	IP     string `json:"ip"`
}

func serveTerminal(addr, token string) {
	if token == "" {
		log.Printf("terminal: no token, not listening")
		return
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Printf("terminal: %v", err)
		return
	}
	connections := make(chan struct{}, 16)
	for {
		c, err := ln.Accept()
		if err != nil {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		select {
		case connections <- struct{}{}:
			go func() { defer func() { <-connections }(); terminal(c, token) }()
		default:
			c.Close()
		}
	}
}

func terminal(c net.Conn, token string) {
	defer c.Close()
	r := bufio.NewReaderSize(c, 4096)
	c.SetReadDeadline(time.Now().Add(10 * time.Second))
	line, err := r.ReadSlice('\n') // Reject an oversized greeting before allocating an unbounded buffer.
	var hello terminalHello
	if err != nil || json.Unmarshal(line, &hello) != nil ||
		subtle.ConstantTimeCompare([]byte(hello.Token), []byte(token)) != 1 {
		return
	}
	c.SetReadDeadline(time.Time{})

	s := &session{Via: "web", Start: time.Now().Unix(), IP: hello.IP, Key: "dashboard"}
	// Interactive shells read $ENV, which defines `alpine`; for Alpine, run that alias.
	cmd := exec.Command(*shell)
	if hello.Alpine {
		cmd = exec.Command(*shell, "-c", "[ -r \"$ENV\" ] && . \"$ENV\"\nalpine")
		s.command("alpine")
	} else {
		s.shell()
	}
	defer s.finish()
	cmd.Dir = *home
	cmd.Env = append(os.Environ(), "SHELL="+*shell, "TERM=xterm-256color", "COLORTERM=truecolor")
	size := &pty.Winsize{Cols: max(hello.Cols, 20), Rows: max(hello.Rows, 5)}
	tty, err := pty.StartWithSize(cmd, size)
	if err != nil {
		io.WriteString(c, "homedroid: "+err.Error()+"\r\n")
		return
	}
	defer tty.Close()

	go func() {
		io.Copy(c, tty)
		c.Close()
	}()
	go func() {
		// The browser left: hang up the whole session, like closing a terminal window.
		defer hangup(cmd)
		head := make([]byte, 3)
		buf := make([]byte, 1<<16)
		for {
			if _, err := io.ReadFull(r, head); err != nil {
				return
			}
			n := int(binary.BigEndian.Uint16(head[1:]))
			if _, err := io.ReadFull(r, buf[:n]); err != nil {
				return
			}
			switch head[0] {
			case 0:
				tty.Write(buf[:n])
			case 1:
				if n == 4 {
					pty.Setsize(tty, &pty.Winsize{Cols: binary.BigEndian.Uint16(buf), Rows: binary.BigEndian.Uint16(buf[2:])})
				}
			}
		}
	}()
	cmd.Wait()
}

// Command sshd is a minimal SSH server for Homedroid: public-key auth only, interactive
// shells with a PTY, exec, SFTP and local port forwarding (ssh -L).
package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"strconv"
	"time"

	"github.com/creack/pty"
	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

var (
	listen   = flag.String("listen", ":8022", "address to listen on")
	hostKey  = flag.String("hostkey", "ssh_host_ed25519_key", "host key path, generated if missing")
	authKeys = flag.String("authorized-keys", "authorized_keys", "authorized_keys path, re-read on every login")
	home     = flag.String("home", ".", "working directory for sessions")
	shell    = flag.String("shell", "/system/bin/sh", "shell for sessions")
)

func main() {
	flag.Parse()
	log.SetFlags(0)

	signer, err := loadHostKey(*hostKey)
	if err != nil {
		log.Fatalf("host key: %v", err)
	}
	cfg := &ssh.ServerConfig{PublicKeyCallback: checkKey, ServerVersion: "SSH-2.0-homedroid"}
	cfg.AddHostKey(signer)

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("listening on %s, host key %s", ln.Addr(), ssh.FingerprintSHA256(signer.PublicKey()))
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		go serveConn(c, cfg)
	}
}

func loadHostKey(path string) (ssh.Signer, error) {
	b, err := os.ReadFile(path)
	if err == nil {
		return ssh.ParsePrivateKey(b)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	block, err := ssh.MarshalPrivateKey(priv, "homedroid")
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, pem.EncodeToMemory(block), 0o600); err != nil {
		return nil, err
	}
	return ssh.NewSignerFromKey(priv)
}

func checkKey(meta ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
	data, err := os.ReadFile(*authKeys)
	if err != nil {
		return nil, err
	}
	want := key.Marshal()
	for len(data) > 0 {
		pk, _, _, rest, err := ssh.ParseAuthorizedKey(data)
		if err != nil {
			break
		}
		if bytes.Equal(pk.Marshal(), want) {
			return &ssh.Permissions{}, nil
		}
		data = rest
	}
	return nil, fmt.Errorf("key not authorized for %q", meta.User())
}

func serveConn(c net.Conn, cfg *ssh.ServerConfig) {
	sc, chans, reqs, err := ssh.NewServerConn(c, cfg)
	if err != nil {
		log.Printf("%s: handshake failed: %v", c.RemoteAddr(), err)
		c.Close()
		return
	}
	log.Printf("%s: login as %q", sc.RemoteAddr(), sc.User())
	defer log.Printf("%s: disconnected", sc.RemoteAddr())
	go ssh.DiscardRequests(reqs) // remote forwarding (ssh -R) is not supported
	for nc := range chans {
		switch nc.ChannelType() {
		case "session":
			go handleSession(nc)
		case "direct-tcpip":
			go handleForward(nc)
		default:
			nc.Reject(ssh.UnknownChannelType, "unsupported channel type")
		}
	}
}

// handleForward serves ssh -L, e.g. reaching Caddy's admin API on localhost:2019.
func handleForward(nc ssh.NewChannel) {
	var req struct {
		Host     string
		Port     uint32
		OrigHost string
		OrigPort uint32
	}
	if err := ssh.Unmarshal(nc.ExtraData(), &req); err != nil {
		nc.Reject(ssh.ConnectionFailed, "malformed request")
		return
	}
	dst, err := net.Dial("tcp", net.JoinHostPort(req.Host, strconv.Itoa(int(req.Port))))
	if err != nil {
		nc.Reject(ssh.ConnectionFailed, err.Error())
		return
	}
	ch, reqs, err := nc.Accept()
	if err != nil {
		dst.Close()
		return
	}
	go ssh.DiscardRequests(reqs)
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(ch, dst)
		ch.CloseWrite()
		done <- struct{}{}
	}()
	go func() {
		io.Copy(dst, ch)
		if tc, ok := dst.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
	ch.Close()
	dst.Close()
}

func handleSession(nc ssh.NewChannel) {
	ch, reqs, err := nc.Accept()
	if err != nil {
		return
	}
	env := append(os.Environ(), "SHELL="+*shell)
	var size *pty.Winsize
	var tty *os.File
	started := false

	for req := range reqs {
		ok := false
		switch req.Type {
		case "pty-req":
			var p struct {
				Term                      string
				Cols, Rows, Width, Height uint32
				Modes                     string
			}
			if !started && ssh.Unmarshal(req.Payload, &p) == nil {
				size = &pty.Winsize{Cols: uint16(p.Cols), Rows: uint16(p.Rows)}
				env = append(env, "TERM="+p.Term)
				ok = true
			}
		case "window-change":
			var w struct{ Cols, Rows, Width, Height uint32 }
			if ssh.Unmarshal(req.Payload, &w) == nil {
				ws := &pty.Winsize{Cols: uint16(w.Cols), Rows: uint16(w.Rows)}
				if tty != nil {
					pty.Setsize(tty, ws)
				} else {
					size = ws
				}
				ok = true
			}
		case "env":
			var e struct{ Name, Value string }
			if ssh.Unmarshal(req.Payload, &e) == nil {
				env = append(env, e.Name+"="+e.Value)
				ok = true
			}
		case "shell", "exec":
			if started {
				break
			}
			started = true
			var x struct{ Command string }
			ssh.Unmarshal(req.Payload, &x) // empty for "shell"
			cmd := exec.Command(*shell)
			if x.Command != "" {
				// Shells only read $ENV when interactive; load it for commands too, so
				// helpers such as `ssh phone torrent …` work. On its own line so aliases
				// defined there apply to the command.
				cmd = exec.Command(*shell, "-c", "[ -r \"$ENV\" ] && . \"$ENV\"\n"+x.Command)
			}
			cmd.Env, cmd.Dir = env, *home
			// Acknowledge before any output or exit-status reaches the client.
			if req.WantReply {
				req.Reply(true, nil)
			}
			tty = run(ch, cmd, size)
			continue
		case "subsystem":
			var s struct{ Name string }
			if !started && ssh.Unmarshal(req.Payload, &s) == nil && s.Name == "sftp" {
				started, ok = true, true
				go serveSFTP(ch)
			}
		}
		if req.WantReply {
			req.Reply(ok, nil)
		}
	}
}

// run starts cmd wired to ch and returns the PTY master, if one was requested.
func run(ch ssh.Channel, cmd *exec.Cmd, size *pty.Winsize) *os.File {
	if size != nil {
		tty, err := pty.StartWithSize(cmd, size)
		if err != nil {
			fail(ch, err)
			return nil
		}
		go func() {
			go io.Copy(tty, ch)
			out := make(chan struct{})
			go func() {
				io.Copy(ch, tty)
				close(out)
			}()
			cmd.Wait()
			// Drain remaining output, but don't hang on background jobs holding the PTY.
			select {
			case <-out:
			case <-time.After(time.Second):
			}
			tty.Close()
			exit(ch, cmd)
		}()
		return tty
	}

	cmd.Stdout, cmd.Stderr = ch, ch.Stderr()
	stdin, err := cmd.StdinPipe()
	if err == nil {
		err = cmd.Start()
	}
	if err != nil {
		fail(ch, err)
		return nil
	}
	go func() {
		io.Copy(stdin, ch)
		stdin.Close()
	}()
	go func() {
		cmd.Wait()
		exit(ch, cmd)
	}()
	return nil
}

func serveSFTP(ch ssh.Channel) {
	defer ch.Close()
	srv, err := sftp.NewServer(ch, sftp.WithServerWorkingDirectory(*home))
	if err != nil {
		log.Printf("sftp: %v", err)
		return
	}
	if err := srv.Serve(); err != nil && err != io.EOF {
		log.Printf("sftp: %v", err)
	}
}

func exit(ch ssh.Channel, cmd *exec.Cmd) {
	code := 255
	if cmd.ProcessState != nil && cmd.ProcessState.ExitCode() >= 0 {
		code = cmd.ProcessState.ExitCode()
	}
	sendExit(ch, code)
}

func fail(ch ssh.Channel, err error) {
	fmt.Fprintf(ch.Stderr(), "homedroid: %v\n", err)
	sendExit(ch, 127)
}

func sendExit(ch ssh.Channel, code int) {
	ch.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{uint32(code)}))
	ch.Close()
}

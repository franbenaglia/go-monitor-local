// Command gomonitor-probe is a development client: it connects to an agent
// over WebSocket the way the phone does and prints what it receives.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"time"

	"github.com/coder/websocket"

	"gomonitor/agent/internal/models"
)

func main() {
	address := flag.String("address", "127.0.0.1:8080", "agent host:port")
	token := flag.String("token", "change-this-token", "API token")
	count := flag.Int("count", 3, "metrics messages to print before exiting (0 = until interrupted)")
	raw := flag.Bool("raw", false, "print the raw JSON instead of a summary")
	flag.Parse()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	if err := run(ctx, *address, *token, *count, *raw); err != nil {
		fmt.Fprintln(os.Stderr, "gomonitor-probe:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, address, token string, count int, raw bool) error {
	dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(dialCtx, "ws://"+address+"/api/v1/ws", nil)
	if err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	defer conn.CloseNow()
	conn.SetReadLimit(1 << 20)

	auth, _ := json.Marshal(map[string]string{"type": "authenticate", "token": token})
	if err := conn.Write(ctx, websocket.MessageText, auth); err != nil {
		return fmt.Errorf("authenticate: %w", err)
	}

	received := 0
	for count == 0 || received < count {
		_, data, err := conn.Read(ctx)
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			return fmt.Errorf("connection closed (code %d): %w", websocket.CloseStatus(err), err)
		}
		if raw {
			fmt.Println(string(data))
		}
		var msg struct {
			Type    string           `json:"type"`
			AgentID string           `json:"agent_id"`
			Data    *models.Snapshot `json:"data"`
		}
		if err := json.Unmarshal(data, &msg); err != nil {
			return fmt.Errorf("invalid message: %w", err)
		}
		switch msg.Type {
		case "authenticated":
			fmt.Printf("authenticated with agent %q\n", msg.AgentID)
		case "metrics":
			received++
			if !raw && msg.Data != nil {
				printSummary(msg.Data)
			}
		default:
			fmt.Printf("message of type %q\n", msg.Type)
		}
	}
	return conn.Close(websocket.StatusNormalClosure, "")
}

func printSummary(s *models.Snapshot) {
	fmt.Printf("%s  %s", s.Timestamp.Local().Format("15:04:05"), s.Hostname)
	if s.CPU != nil {
		fmt.Printf("  cpu %.1f%%", s.CPU.TotalPercent)
	}
	if s.Memory != nil {
		fmt.Printf("  mem %.1f%%", s.Memory.UsedPercent)
	}
	if s.Network != nil {
		fmt.Printf("  up %.0f B/s  down %.0f B/s", s.Network.BytesSentPerSecond, s.Network.BytesReceivedPerSecond)
	}
	fmt.Printf("  disks %d  processes %d  readings %d\n", len(s.Disks), len(s.Processes), len(s.Readings))
}

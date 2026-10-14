// Command gomonitor-agent collects metrics and sensor readings and serves
// them on the local network over REST and WebSocket.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"syscall"
	"time"

	"gomonitor/agent/internal/api"
	"gomonitor/agent/internal/collector"
	"gomonitor/agent/internal/config"
	"gomonitor/agent/internal/discovery"
	"gomonitor/agent/internal/models"
	"gomonitor/agent/internal/netinfo"
	"gomonitor/agent/internal/storage"
	"gomonitor/agent/internal/wshub"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "0.1.0"

const (
	retentionCheckEvery = time.Hour
	saveTimeout         = 5 * time.Second
	shutdownTimeout     = 10 * time.Second
)

func main() {
	if err := run(); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "gomonitor-agent:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.Args[1:], os.Getenv, os.Stderr)
	if err != nil {
		return err
	}
	logger := newLogger(cfg.LogLevel)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	store, err := storage.Open(cfg.DatabasePath)
	if err != nil {
		return err
	}
	defer store.Close()

	var system *collector.System
	var collectors []collector.Collector
	for _, name := range cfg.Collectors {
		switch name {
		case "system":
			system = collector.NewSystem(cfg.TopProcesses)
			collectors = append(collectors, system)
		case "simulated":
			collectors = append(collectors, collector.NewSimulated())
		}
	}

	hostname, _ := os.Hostname()
	manager := collector.NewManager(collector.Identity{
		AgentID:         cfg.AgentID,
		Hostname:        hostname,
		OperatingSystem: runtime.GOOS,
		Architecture:    runtime.GOARCH,
	}, collectors, cfg.CollectionInterval, collector.Uptime, logger)

	if err := store.RegisterSeries(ctx, manager.Series()); err != nil {
		return err
	}

	hub := wshub.New(wshub.Options{
		AgentID:            cfg.AgentID,
		Token:              cfg.APIToken,
		MaxClients:         cfg.MaxWSClients,
		CollectionInterval: cfg.CollectionInterval,
		Logger:             logger,
	})

	// History is stored whether or not a phone is connected.
	manager.AddSink(func(snap *models.Snapshot) {
		saveCtx, cancel := context.WithTimeout(context.Background(), saveTimeout)
		defer cancel()
		if err := store.Save(saveCtx, snap); err != nil {
			logger.Error("save snapshot", "error", err)
		}
	})
	manager.AddSink(hub.Broadcast)

	server := &http.Server{
		Handler: api.NewHandler(api.Deps{
			Config:    cfg,
			Version:   version,
			Latest:    manager.Latest,
			History:   store,
			System:    system,
			WebSocket: hub,
			Logger:    logger,
		}),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
		ErrorLog:          slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
	}

	address := net.JoinHostPort(cfg.ListenAddress, strconv.Itoa(cfg.Port))
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return fmt.Errorf("cannot listen on %s: %w", address, err)
	}

	printBanner(cfg)
	if cfg.APIToken == config.DefaultToken {
		logger.Warn("the default API token is in use; set api_token before using this on a shared network")
	}
	logger.Info("agent started", "version", version, "address", address, "agent_id", cfg.AgentID,
		"collectors", cfg.Collectors, "interval", cfg.CollectionInterval.String(), "database", cfg.DatabasePath)

	background := make(chan struct{}, 3)
	workers := 2
	if announce, why := shouldAnnounce(cfg); announce {
		workers++
		go func() {
			defer func() { background <- struct{}{} }()
			interfaces, _ := netinfo.List()
			// Discovery is optional: if it cannot start the agent keeps working
			// and the address is entered by hand.
			if err := discovery.Run(ctx, discovery.Service(cfg.AgentID, hostname, cfg.Port, interfaces)); err != nil {
				logger.Warn("mDNS announcement unavailable; enter the address manually in the app", "error", err)
			}
		}()
	} else {
		logger.Debug("mDNS announcement disabled", "reason", why)
	}
	go func() {
		manager.Run(ctx)
		background <- struct{}{}
	}()
	go func() {
		store.RunRetention(ctx, cfg.Retention, retentionCheckEvery, func(removed int64, err error) {
			switch {
			case err != nil:
				logger.Error("retention cleanup", "error", err)
			case removed > 0:
				logger.Info("retention cleanup", "samples_removed", removed)
			}
		})
		background <- struct{}{}
	}()

	serveErr := make(chan error, 1)
	go func() { serveErr <- server.Serve(listener) }()

	select {
	case err := <-serveErr:
		stop()
		return fmt.Errorf("http server: %w", err)
	case <-ctx.Done():
	}

	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	// Stop accepting requests, then say goodbye to the WebSocket clients,
	// then wait for the collector so nothing writes to a closed database.
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Warn("http shutdown", "error", err)
	}
	hub.Close()
	for range workers {
		select {
		case <-background:
		case <-shutdownCtx.Done():
			return errors.New("shutdown timed out")
		}
	}
	logger.Info("agent stopped")
	return nil
}

// shouldAnnounce reports whether to advertise over mDNS. An agent that only
// listens on loopback must not invite phones to an address they cannot reach.
func shouldAnnounce(cfg config.Config) (bool, string) {
	if !cfg.MDNS {
		return false, "mdns is false"
	}
	if ip := net.ParseIP(cfg.ListenAddress); ip != nil && ip.IsLoopback() {
		return false, "listening on loopback only"
	}
	return true, ""
}

func newLogger(level string) *slog.Logger {
	var l slog.Level
	switch level {
	case "debug":
		l = slog.LevelDebug
	case "warn":
		l = slog.LevelWarn
	case "error":
		l = slog.LevelError
	default:
		l = slog.LevelInfo
	}
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: l}))
}

// printBanner tells the user which address to type into the phone.
func printBanner(cfg config.Config) {
	fmt.Println("GoMonitor Agent iniciado")
	fmt.Println()

	if ip := net.ParseIP(cfg.ListenAddress); ip != nil && !ip.IsUnspecified() {
		// Listening on one specific address: that is the only one that works.
		if ip.IsLoopback() {
			fmt.Println("El agente escucha solo en esta computadora; el celular no podrá conectarse.")
			fmt.Println("Usa listen_address 0.0.0.0 para aceptar conexiones de la red.")
			fmt.Println()
		}
		fmt.Println("Dirección disponible:")
		fmt.Printf("http://%s\n\n", net.JoinHostPort(cfg.ListenAddress, strconv.Itoa(cfg.Port)))
		return
	}

	all, err := netinfo.List()
	suggested := netinfo.Suggested(all)
	if err != nil || len(suggested) == 0 {
		fmt.Println("No se encontró ninguna dirección de red. Verifica que la computadora esté conectada a una red.")
		fmt.Println()
		return
	}
	if !suggested[0].Recommended {
		fmt.Println("No se encontró una red Wi-Fi o Ethernet privada; estas direcciones pueden no ser alcanzables desde el celular.")
		fmt.Println()
	} else {
		fmt.Println("Conecta el celular a la misma red Wi-Fi.")
		fmt.Println()
	}
	fmt.Println("Direcciones disponibles:")
	for _, i := range suggested {
		fmt.Printf("http://%s:%d\n", i.Address, cfg.Port)
	}
	fmt.Println()
}

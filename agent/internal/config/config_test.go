package config

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func env(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func writeConfig(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestDefaults(t *testing.T) {
	t.Chdir(t.TempDir()) // no ./config.yaml here
	cfg, err := Load(nil, env(nil), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 8080 || cfg.CollectionInterval != 5*time.Second || cfg.Retention != 7*24*time.Hour {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestPriority(t *testing.T) {
	path := writeConfig(t, "port: 1111\ntop_processes: 3\nagent_id: from-file\nretention: 2d\n")

	// File overrides defaults.
	cfg, err := Load([]string{"-config", path}, env(nil), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 1111 || cfg.TopProcesses != 3 || cfg.AgentID != "from-file" || cfg.Retention != 48*time.Hour {
		t.Fatalf("file not applied: %+v", cfg)
	}

	// Environment overrides file.
	e := env(map[string]string{"GOMONITOR_PORT": "2222", "GOMONITOR_AGENT_ID": "from-env"})
	cfg, err = Load([]string{"-config", path}, e, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 2222 || cfg.AgentID != "from-env" || cfg.TopProcesses != 3 {
		t.Fatalf("env not applied over file: %+v", cfg)
	}

	// Flags override environment.
	cfg, err = Load([]string{"-config", path, "-port", "3333"}, e, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 3333 || cfg.AgentID != "from-env" {
		t.Fatalf("flag not applied over env: %+v", cfg)
	}
}

func TestConfigPathFromEnv(t *testing.T) {
	path := writeConfig(t, "port: 4444\ncollectors: [system, simulated]\n")
	cfg, err := Load(nil, env(map[string]string{"GOMONITOR_CONFIG": path}), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 4444 || len(cfg.Collectors) != 2 {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestExplicitMissingFileFails(t *testing.T) {
	_, err := Load([]string{"-config", filepath.Join(t.TempDir(), "nope.yaml")}, env(nil), io.Discard)
	if err == nil {
		t.Fatal("expected an error for a missing explicit config file")
	}
}

func TestUnknownYAMLKeyFails(t *testing.T) {
	path := writeConfig(t, "prot: 8080\n")
	if _, err := Load([]string{"-config", path}, env(nil), io.Discard); err == nil {
		t.Fatal("expected an error for an unknown key")
	}
}

func TestValidation(t *testing.T) {
	t.Chdir(t.TempDir())
	cases := map[string][]string{
		"port range":        {"-port", "70000"},
		"port not a number": {"-port", "abc"},
		"interval too low":  {"-collection-interval", "100ms"},
		"empty token":       {"-api-token", ""},
		"top processes":     {"-top-processes", "0"},
		"retention":         {"-retention", "10m"},
		"log level":         {"-log-level", "loud"},
		"collector":         {"-collectors", "system,gpio"},
		"agent id":          {"-agent-id", "has spaces"},
		"ws clients":        {"-max-ws-clients", "0"},
	}
	for name, args := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := Load(args, env(nil), io.Discard); err == nil {
				t.Fatalf("expected a validation error for %v", args)
			}
		})
	}
}

func TestListsFromFlags(t *testing.T) {
	t.Chdir(t.TempDir())
	cfg, err := Load([]string{"-collectors", "system, simulated", "-cors-allowed-origins", "http://localhost:5173"}, env(nil), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(cfg.Collectors, "|") != "system|simulated" || len(cfg.CORSAllowedOrigins) != 1 {
		t.Fatalf("unexpected lists: %+v", cfg)
	}
}

func TestMDNSSetting(t *testing.T) {
	t.Chdir(t.TempDir())
	cfg, err := Load(nil, env(nil), io.Discard)
	if err != nil || !cfg.MDNS {
		t.Fatalf("mDNS must be on by default: %+v %v", cfg, err)
	}
	path := writeConfig(t, "mdns: false\n")
	if cfg, err = Load([]string{"-config", path}, env(nil), io.Discard); err != nil || cfg.MDNS {
		t.Fatalf("file must turn mDNS off: %+v %v", cfg, err)
	}
	if cfg, err = Load([]string{"-config", path}, env(map[string]string{"GOMONITOR_MDNS": "true"}), io.Discard); err != nil || !cfg.MDNS {
		t.Fatalf("env must override the file: %+v %v", cfg, err)
	}
	if cfg, err = Load([]string{"-mdns", "false"}, env(map[string]string{"GOMONITOR_MDNS": "true"}), io.Discard); err != nil || cfg.MDNS {
		t.Fatalf("flag must override env: %+v %v", cfg, err)
	}
	if _, err = Load([]string{"-mdns", "maybe"}, env(nil), io.Discard); err == nil {
		t.Fatal("an invalid boolean must be rejected")
	}
}

func TestParseDuration(t *testing.T) {
	cases := map[string]time.Duration{"7d": 7 * 24 * time.Hour, "5s": 5 * time.Second, "1h30m": 90 * time.Minute}
	for in, want := range cases {
		got, err := ParseDuration(in)
		if err != nil || got != want {
			t.Errorf("ParseDuration(%q) = %v, %v; want %v", in, got, err, want)
		}
	}
	for _, in := range []string{"", "d", "-1d", "abc", "1.5d"} {
		if _, err := ParseDuration(in); err == nil {
			t.Errorf("ParseDuration(%q) should fail", in)
		}
	}
}

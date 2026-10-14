// Package config loads the agent configuration.
//
// Priority, highest first: command-line flags, environment variables, YAML
// file, built-in defaults.
package config

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

const (
	// DefaultToken is the placeholder shipped in the example config. The agent
	// warns at startup while it is still in use.
	DefaultToken = "change-this-token"

	defaultConfigPath = "config.yaml"
	envPrefix         = "GOMONITOR_"
)

// KnownCollectors lists the collectors that can be enabled.
var KnownCollectors = []string{"system", "simulated"}

type Config struct {
	AgentID            string
	ListenAddress      string
	Port               int
	CollectionInterval time.Duration
	APIToken           string
	TopProcesses       int
	DatabasePath       string
	Retention          time.Duration
	LogLevel           string
	Collectors         []string
	CORSAllowedOrigins []string
	MaxWSClients       int
	MDNS               bool
}

// Default returns the built-in defaults.
func Default() Config {
	return Config{
		AgentID:            "office-pc",
		ListenAddress:      "0.0.0.0",
		Port:               8080,
		CollectionInterval: 5 * time.Second,
		APIToken:           DefaultToken,
		TopProcesses:       10,
		DatabasePath:       "./data/gomonitor.db",
		Retention:          7 * 24 * time.Hour,
		LogLevel:           "info",
		Collectors:         []string{"system"},
		CORSAllowedOrigins: nil,
		MaxWSClients:       8,
		MDNS:               true,
	}
}

// keys are the setting names, shared by YAML, flags (with dashes) and
// environment variables (upper case with the GOMONITOR_ prefix).
var keys = []struct{ name, usage string }{
	{"agent_id", "identifier reported by this agent"},
	{"listen_address", "address to listen on"},
	{"port", "TCP port to listen on"},
	{"collection_interval", "time between samples, e.g. 5s"},
	{"api_token", "token clients must present"},
	{"top_processes", "number of top processes by CPU and by memory"},
	{"database_path", "path of the SQLite history database"},
	{"retention", "how long history is kept, e.g. 7d"},
	{"log_level", "debug, info, warn or error"},
	{"collectors", "comma-separated collectors to enable: system, simulated"},
	{"cors_allowed_origins", "comma-separated browser origins allowed to use the API"},
	{"max_ws_clients", "maximum simultaneous WebSocket clients"},
	{"mdns", "announce the agent on the local network (true or false)"},
}

// Load builds the configuration from args (without the program name) and the
// environment. flag.ErrHelp is returned when -h is requested.
func Load(args []string, getenv func(string) string, usageOut io.Writer) (Config, error) {
	cfg := Default()

	fs := flag.NewFlagSet("gomonitor-agent", flag.ContinueOnError)
	fs.SetOutput(usageOut)
	configPath := fs.String("config", "", "path to a YAML config file (default: ./config.yaml if it exists)")
	flagValues := make(map[string]*string, len(keys))
	for _, k := range keys {
		flagValues[k.name] = fs.String(strings.ReplaceAll(k.name, "_", "-"), "", k.usage)
	}
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	if fs.NArg() > 0 {
		return cfg, fmt.Errorf("unexpected argument %q", fs.Arg(0))
	}
	flagSet := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { flagSet[strings.ReplaceAll(f.Name, "-", "_")] = true })

	// YAML file: an explicit path must exist, the default one is optional.
	path, explicit := *configPath, flagSet["config"]
	if !explicit {
		if p := getenv(envPrefix + "CONFIG"); p != "" {
			path, explicit = p, true
		} else {
			path = defaultConfigPath
		}
	}
	if err := applyFile(&cfg, path, explicit); err != nil {
		return cfg, err
	}

	for _, k := range keys {
		env := envPrefix + strings.ToUpper(k.name)
		if v := getenv(env); v != "" {
			if err := cfg.set(k.name, v); err != nil {
				return cfg, fmt.Errorf("environment variable %s: %w", env, err)
			}
		}
	}
	for _, k := range keys {
		if flagSet[k.name] {
			if err := cfg.set(k.name, *flagValues[k.name]); err != nil {
				return cfg, fmt.Errorf("flag -%s: %w", strings.ReplaceAll(k.name, "_", "-"), err)
			}
		}
	}

	if err := cfg.Validate(); err != nil {
		return cfg, err
	}
	return cfg, nil
}

type fileConfig struct {
	AgentID            *string  `yaml:"agent_id"`
	ListenAddress      *string  `yaml:"listen_address"`
	Port               *int     `yaml:"port"`
	CollectionInterval *string  `yaml:"collection_interval"`
	APIToken           *string  `yaml:"api_token"`
	TopProcesses       *int     `yaml:"top_processes"`
	DatabasePath       *string  `yaml:"database_path"`
	Retention          *string  `yaml:"retention"`
	LogLevel           *string  `yaml:"log_level"`
	Collectors         []string `yaml:"collectors"`
	CORSAllowedOrigins []string `yaml:"cors_allowed_origins"`
	MaxWSClients       *int     `yaml:"max_ws_clients"`
	MDNS               *bool    `yaml:"mdns"`
}

func applyFile(cfg *Config, path string, mustExist bool) error {
	f, err := os.Open(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) && !mustExist {
			return nil
		}
		return fmt.Errorf("config file: %w", err)
	}
	defer f.Close()

	var fc fileConfig
	dec := yaml.NewDecoder(f)
	dec.KnownFields(true)
	if err := dec.Decode(&fc); err != nil && !errors.Is(err, io.EOF) {
		return fmt.Errorf("config file %s: %w", path, err)
	}

	setStr := func(key string, v *string) error {
		if v == nil {
			return nil
		}
		return cfg.set(key, *v)
	}
	setInt := func(key string, v *int) error {
		if v == nil {
			return nil
		}
		return cfg.set(key, strconv.Itoa(*v))
	}
	errs := []error{
		setStr("agent_id", fc.AgentID),
		setStr("listen_address", fc.ListenAddress),
		setInt("port", fc.Port),
		setStr("collection_interval", fc.CollectionInterval),
		setStr("api_token", fc.APIToken),
		setInt("top_processes", fc.TopProcesses),
		setStr("database_path", fc.DatabasePath),
		setStr("retention", fc.Retention),
		setStr("log_level", fc.LogLevel),
		setInt("max_ws_clients", fc.MaxWSClients),
	}
	if err := errors.Join(errs...); err != nil {
		return fmt.Errorf("config file %s: %w", path, err)
	}
	if fc.MDNS != nil {
		cfg.MDNS = *fc.MDNS
	}
	if fc.Collectors != nil {
		cfg.Collectors = fc.Collectors
	}
	if fc.CORSAllowedOrigins != nil {
		cfg.CORSAllowedOrigins = fc.CORSAllowedOrigins
	}
	return nil
}

// set assigns one setting from its textual form.
func (c *Config) set(key, value string) error {
	var err error
	switch key {
	case "agent_id":
		c.AgentID = value
	case "listen_address":
		c.ListenAddress = value
	case "port":
		c.Port, err = strconv.Atoi(value)
	case "collection_interval":
		c.CollectionInterval, err = ParseDuration(value)
	case "api_token":
		c.APIToken = value
	case "top_processes":
		c.TopProcesses, err = strconv.Atoi(value)
	case "database_path":
		c.DatabasePath = value
	case "retention":
		c.Retention, err = ParseDuration(value)
	case "log_level":
		c.LogLevel = strings.ToLower(value)
	case "collectors":
		c.Collectors = splitList(value)
	case "cors_allowed_origins":
		c.CORSAllowedOrigins = splitList(value)
	case "max_ws_clients":
		c.MaxWSClients, err = strconv.Atoi(value)
	case "mdns":
		c.MDNS, err = strconv.ParseBool(value)
	default:
		return fmt.Errorf("unknown setting %q", key)
	}
	if err != nil {
		return fmt.Errorf("%s: invalid value %q", key, value)
	}
	return nil
}

var agentIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// Validate checks every setting against its allowed range.
func (c Config) Validate() error {
	var errs []error
	add := func(format string, a ...any) { errs = append(errs, fmt.Errorf(format, a...)) }

	if !agentIDPattern.MatchString(c.AgentID) {
		add("agent_id must be 1-64 characters of letters, digits, '.', '_' or '-'")
	}
	if c.ListenAddress == "" {
		add("listen_address must not be empty")
	}
	if c.Port < 1 || c.Port > 65535 {
		add("port must be between 1 and 65535")
	}
	if c.CollectionInterval < time.Second || c.CollectionInterval > time.Hour {
		add("collection_interval must be between 1s and 1h")
	}
	if c.APIToken == "" {
		add("api_token must not be empty")
	}
	if c.TopProcesses < 1 || c.TopProcesses > 100 {
		add("top_processes must be between 1 and 100")
	}
	if c.DatabasePath == "" {
		add("database_path must not be empty")
	}
	if c.Retention < time.Hour {
		add("retention must be at least 1h")
	}
	if !slices.Contains([]string{"debug", "info", "warn", "error"}, c.LogLevel) {
		add("log_level must be debug, info, warn or error")
	}
	if len(c.Collectors) == 0 {
		add("collectors must list at least one collector")
	}
	for _, name := range c.Collectors {
		if !slices.Contains(KnownCollectors, name) {
			add("unknown collector %q (known: %s)", name, strings.Join(KnownCollectors, ", "))
		}
	}
	if c.MaxWSClients < 1 || c.MaxWSClients > 64 {
		add("max_ws_clients must be between 1 and 64")
	}
	return errors.Join(errs...)
}

// ParseDuration extends time.ParseDuration with a day suffix, e.g. "7d".
func ParseDuration(s string) (time.Duration, error) {
	s = strings.TrimSpace(s)
	if days, ok := strings.CutSuffix(s, "d"); ok {
		n, err := strconv.Atoi(days)
		if err != nil || n < 0 {
			return 0, fmt.Errorf("invalid duration %q", s)
		}
		return time.Duration(n) * 24 * time.Hour, nil
	}
	return time.ParseDuration(s)
}

func splitList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

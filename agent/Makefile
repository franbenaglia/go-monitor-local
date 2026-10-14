BIN := bin
# Sin CGO: el binario no depende de bibliotecas del sistema y se compila
# cruzado con solo cambiar GOOS y GOARCH.
export CGO_ENABLED := 0
LDFLAGS := -s -w

.PHONY: build run test vet cross clean

build:
	go build -ldflags "$(LDFLAGS)" -o $(BIN)/gomonitor-agent ./cmd/gomonitor-agent
	go build -ldflags "$(LDFLAGS)" -o $(BIN)/gomonitor-probe ./cmd/gomonitor-probe

run:
	go run ./cmd/gomonitor-agent

vet:
	go vet ./...

# El detector de carreras necesita CGO.
test: vet
	CGO_ENABLED=1 go test -race ./...

# Linux y Windows de escritorio, y Raspberry Pi de 64 y 32 bits.
cross:
	GOOS=linux   GOARCH=amd64       go build -ldflags "$(LDFLAGS)" -o $(BIN)/gomonitor-agent-linux-amd64       ./cmd/gomonitor-agent
	GOOS=windows GOARCH=amd64       go build -ldflags "$(LDFLAGS)" -o $(BIN)/gomonitor-agent-windows-amd64.exe ./cmd/gomonitor-agent
	GOOS=linux   GOARCH=arm64       go build -ldflags "$(LDFLAGS)" -o $(BIN)/gomonitor-agent-linux-arm64       ./cmd/gomonitor-agent
	GOOS=linux   GOARCH=arm GOARM=6 go build -ldflags "$(LDFLAGS)" -o $(BIN)/gomonitor-agent-linux-armv6       ./cmd/gomonitor-agent

clean:
	rm -rf $(BIN)

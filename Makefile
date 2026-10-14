.PHONY: check check-agent check-android check-e2e

# Verifica todo lo que exista. Un hito no está cerrado si esto falla.
check: check-agent check-android check-e2e

check-agent:
	@if [ -f agent/go.mod ]; then \
		cd agent && go vet ./... && go test -race ./...; \
	else \
		echo "agent: todavía no existe, nada que verificar"; \
	fi

check-android:
	@if [ -x android/gradlew ]; then \
		cd android && ./gradlew --quiet testDebugUnitTest assembleDebug; \
	else \
		echo "android: todavía no existe, nada que verificar"; \
	fi

# La capa de red de la app contra un agente real, sin teléfono: conexión, métricas, historial,
# token incorrecto y reconexión al reiniciar el agente.
check-e2e:
	@if [ -x android/gradlew ] && [ -f agent/go.mod ]; then \
		$(MAKE) -C agent build && \
		cd android && ./gradlew --quiet testDebugUnitTest \
			--tests 'com.fabapps.gomonitor.data.AgentEndToEndTest' \
			-PgomonitorAgentBin=$(CURDIR)/agent/bin/gomonitor-agent; \
	else \
		echo "e2e: faltan el agente o la app, nada que verificar"; \
	fi

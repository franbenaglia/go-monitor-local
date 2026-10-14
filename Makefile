.PHONY: check check-agent check-android

# Verifica todo lo que exista. Un hito no está cerrado si esto falla.
check: check-agent check-android

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

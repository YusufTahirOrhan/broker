#!/bin/bash
# Claude Code bulut oturumlarında JDK 25'i kurar ve Maven'ın onu kullanmasını sağlar.
# Yerelde (CLAUDE_CODE_REMOTE != true) hiçbir şey yapmaz. Tekrar çalıştırmak güvenlidir.
set -u

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

find_jdk25() {
  for dir in /opt/jdk-25 /usr/lib/jvm/java-25-openjdk-amd64 /usr/lib/jvm/temurin-25*; do
    if [ -x "$dir/bin/javac" ] && "$dir/bin/java" -version 2>&1 | grep -q 'version "25'; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

JDK_DIR=$(find_jdk25)

if [ -z "$JDK_DIR" ]; then
  # 1) Ubuntu paketi
  if apt-get install -y -q openjdk-25-jdk-headless >/dev/null 2>&1 \
     || { apt-get update -q >/dev/null 2>&1 && apt-get install -y -q openjdk-25-jdk-headless >/dev/null 2>&1; }; then
    JDK_DIR=$(find_jdk25)
  fi
fi

if [ -z "$JDK_DIR" ]; then
  # 2) Temurin, GitHub sürümlerinden (api.github.com ve release-assets varsayılan izin listesinde)
  url=$(curl -fsSL https://api.github.com/repos/adoptium/temurin25-binaries/releases/latest \
        | grep -o '"browser_download_url": *"[^"]*jdk_x64_linux_hotspot[^"]*\.tar\.gz"' \
        | head -1 | sed 's/.*"\(https[^"]*\)"$/\1/')
  if [ -n "$url" ]; then
    mkdir -p /opt/jdk-25 && curl -fsSL "$url" | tar -xz -C /opt/jdk-25 --strip-components=1
    JDK_DIR=$(find_jdk25)
  fi
fi

if [ -z "$JDK_DIR" ]; then
  echo "JDK 25 kurulamadı; Maven Java 21 ile çalışırsa derleme 'release 25' hatası verir." >&2
  exit 0
fi

# Maven, JAVA_HOME ortamda başka bir JDK'yı gösterse bile ~/.mavenrc'yi okur
echo "export JAVA_HOME=$JDK_DIR" > "$HOME/.mavenrc"

# Oturumun sonraki komutları için
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=$JDK_DIR"
    echo "export PATH=$JDK_DIR/bin:\$PATH"
  } >> "$CLAUDE_ENV_FILE"
fi

echo "JDK 25 hazır: $JDK_DIR"
exit 0

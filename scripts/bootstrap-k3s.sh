#!/usr/bin/env bash
# Optional manual bootstrap on the reviewed single Ubuntu ARM64 EC2 host.
# This script creates a single-node learning/staging cluster, not an HA production cluster.
set -euo pipefail
[[ "$(id -u)" == 0 ]] || { echo 'Run as root on the intended EC2 staging host.' >&2; exit 1; }
[[ "$(uname -s)" == Linux && "$(uname -m)" == aarch64 ]] || { echo 'Expected ARM64 Linux.' >&2; exit 1; }
for command_name in curl sha256sum systemctl; do
  command -v "$command_name" >/dev/null || { echo "Missing $command_name" >&2; exit 1; }
done
[[ ! -e /etc/systemd/system/k3s.service && ! -e /usr/local/bin/k3s ]] || { echo 'An existing k3s installation must be upgraded using its own reviewed procedure.' >&2; exit 1; }
k3s_version='v1.34.10+k3s1'
k3s_release_url="https://github.com/k3s-io/k3s/releases/download/${k3s_version}"
k3s_workdir="$(mktemp -d)"
trap 'rm -rf "$k3s_workdir"' EXIT
curl --fail --location --proto '=https' --tlsv1.2 --retry 3 "$k3s_release_url/k3s-arm64" -o "$k3s_workdir/k3s-arm64"
curl --fail --location --proto '=https' --tlsv1.2 --retry 3 "$k3s_release_url/sha256sum-arm64.txt" -o "$k3s_workdir/checksums.txt"
# Validate only the expected artifact against the published checksum list.
awk '$2 == "k3s-arm64" || $2 == "*k3s-arm64" { print $1 "  k3s-arm64" }' "$k3s_workdir/checksums.txt" > "$k3s_workdir/selected.sha256"
[[ "$(wc -l < "$k3s_workdir/selected.sha256" | tr -d ' ')" == 1 ]]
(cd "$k3s_workdir" && sha256sum --check selected.sha256)
install -o root -g root -m 0755 "$k3s_workdir/k3s-arm64" /usr/local/bin/k3s
cat > /etc/systemd/system/k3s.service <<'UNIT'
[Unit]
Description=K3s single-node learning/staging server
Wants=network-online.target
After=network-online.target
[Service]
Type=notify
ExecStart=/usr/local/bin/k3s server --disable=traefik --disable=servicelb --write-kubeconfig-mode=600 --tls-san=127.0.0.1 --secrets-encryption
KillMode=process
Delegate=yes
Restart=on-failure
RestartSec=5s
LimitNOFILE=1048576
LimitNPROC=infinity
TasksMax=infinity
TimeoutStartSec=120
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now k3s
/usr/local/bin/k3s kubectl wait --for=condition=Ready node --all --timeout=180s
printf '%s\n' 'k3s is installed. Keep /etc/rancher/k3s/k3s.yaml private. Use SSM forwarding; do not open port 6443 publicly.'

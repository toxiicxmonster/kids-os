#!/usr/bin/env bash
# System-wide family DNS. NetworkManager's global DNS overrides whatever DHCP
# hands out, and Android's DNS (which goes through Waydroid's dnsmasq on the
# host) is forced through it too.
set -euo pipefail

servers="$(echo "$FAMILY_DNS" | tr ' ' ',')"
cat > /etc/NetworkManager/conf.d/90-family-dns.conf <<EOF
[global-dns-domain-*]
servers=$servers
EOF

mkdir -p /etc/systemd/resolved.conf.d
cat > /etc/systemd/resolved.conf.d/90-family-dns.conf <<EOF
[Resolve]
DNS=$FAMILY_DNS
FallbackDNS=
DNSOverTLS=no
EOF

# Android apps that hard-code a DNS server get redirected to the host's
# resolver; DNS-over-TLS (port 853) is dropped so Private DNS falls back too.
# Loaded by its own unit, not /etc/nftables.conf, whose "flush ruleset" would
# also wipe Waydroid's NAT rules.
cat > /etc/tablet-dns.nft <<'EOF'
table inet tablet_dns
delete table inet tablet_dns
table inet tablet_dns {
    chain prerouting {
        type nat hook prerouting priority dstnat; policy accept;
        iifname "waydroid0" udp dport 53 redirect to :53
        iifname "waydroid0" tcp dport 53 redirect to :53
    }
    chain forward {
        type filter hook forward priority filter; policy accept;
        iifname "waydroid0" tcp dport 853 reject
        iifname "waydroid0" udp dport 853 reject
    }
}
EOF
cat > /etc/systemd/system/tablet-dns-nft.service <<'EOF'
[Unit]
Description=Tablet OS: force Android DNS through the family filter
Before=waydroid-container.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/sbin/nft -f /etc/tablet-dns.nft
ExecStop=/usr/sbin/nft delete table inet tablet_dns

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable tablet-dns-nft.service
systemctl restart tablet-dns-nft.service

systemctl restart systemd-resolved
systemctl is-active --quiet NetworkManager && systemctl reload NetworkManager || true

#!/usr/bin/env python3
"""Apply the Android system DNS/SRV bridge to the pinned Horizon source."""
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(path: str, old: str, new: str) -> None:
    file = root / path
    source = file.read_text(encoding="utf-8")
    if new in source:
        return
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one upstream anchor, found {count}; "
            "refusing to modify an unknown upstream layout"
        )
    file.write_text(source.replace(old, new, 1), encoding="utf-8")

replace_once(
    "crates/horizon-protocol/src/resolver.rs",
    "mod dns;\n",
    "pub(crate) mod dns;\n",
)

replace_once(
    "crates/horizon-protocol/src/resolver/dns.rs",
    "    net::{SocketAddr, TcpStream, UdpSocket},\n    time::Duration,",
    "    net::{SocketAddr, TcpStream, UdpSocket},\n    sync::OnceLock,\n    time::Duration,",
)

replace_once(
    "crates/horizon-protocol/src/resolver/dns.rs",
    "const TYPE_SRV: u16 = 33;\n",
    """const TYPE_SRV: u16 = 33;
type SystemSrvLookup = fn(&str) -> Option<Vec<u8>>;
static SYSTEM_SRV_LOOKUP: OnceLock<SystemSrvLookup> = OnceLock::new();

/// Install Android's network-aware SRV resolver (VPN and Private DNS included).
pub fn install_system_srv_lookup(lookup: SystemSrvLookup) {
    let _ = SYSTEM_SRV_LOOKUP.set(lookup);
}

fn system_reply(reply: &[u8]) -> Option<(String, u16)> {
    let id = u16::from_be_bytes(reply.get(..2)?.try_into().ok()?);
    match parse_reply(id, reply) {
        Reply::Answer(answer) => answer,
        _ => None,
    }
}
""",
)

replace_once(
    "crates/horizon-protocol/src/resolver/dns.rs",
    """    timeout: Duration,
) -> Option<(String, u16)> {
    let mut id = [0; 2];""",
    """    timeout: Duration,
) -> Option<(String, u16)> {
    if let Some(lookup) = SYSTEM_SRV_LOOKUP.get()
        && let Some(reply) = lookup(name)
    {
        return system_reply(&reply);
    }
    let mut id = [0; 2];""",
)

replace_once(
    "crates/horizon-protocol/src/lib.rs",
    "mod resolver;\npub use resolver::server_redirect;",
    "mod resolver;\npub use resolver::dns::install_system_srv_lookup;\npub use resolver::server_redirect;",
)

print("Android system DNS/SRV bridge applied to pinned upstream source.")

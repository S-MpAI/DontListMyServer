# 🚫 DontListMyServer

![Java](https://img.shields.io/badge/Java-8%2B-orange)
![Spigot](https://img.shields.io/badge/Platform-Spigot%20%7C%20Paper-blue)
![ProtocolLib](https://img.shields.io/badge/Dependency-ProtocolLib-lightgrey)
![Status](https://img.shields.io/badge/Status-Active-success)
![License](https://img.shields.io/badge/License-Custom-informational)

**DontListMyServer** is a Bukkit / Spigot / Paper plugin designed to protect a Minecraft server from automatic scanners, monitoring services, and public server lists.

The plugin intercepts **Handshake** and **Server List Ping** packets, blocks server responses, and automatically reports suspicious activity to **AbuseIPDB**.

---

## 🧩 Minecraft Version Compatibility

- ✅ **Developed and tested on Minecraft 1.16.5**
- ⚠️ **Should work on other versions**, but compatibility is **not guaranteed**
- 🧪 Most likely compatible with:
    - **1.12.x – 1.20.x** (Spigot / Paper)
- ❌ Not recommended for:
    - Very old versions without modern ProtocolLib support
    - Servers without ProtocolLib

> ℹ️ Compatibility depends heavily on **ProtocolLib** and Minecraft packet structure.  
> If ProtocolLib supports your server version, the plugin will *most likely* work.


---

## ✨ Features

- 🔍 Intercepts Handshake packets (via ProtocolLib)
- 🛑 Blocks Server List Ping (`SERVER_INFO`)
- 🚷 Fully blocks player logins
- 🌐 Automatically reports suspicious IPs to AbuseIPDB
- 🔁 Supports multiple AbuseIPDB API keys
- ⚡ Fully asynchronous and non-blocking
- ⏳ Built-in cooldown to prevent report spam
- 🧹 Automatic cleanup of expired connection data

---

## 🧠 How It Works

1. A client sends a **Handshake**
2. The plugin stores:
    - IP address
    - Host
    - Port
    - Protocol version
3. When a **Server List Ping** occurs:
    - The stored handshake data is matched
    - The IP is reported to AbuseIPDB
    - The server does **not** respond to the ping
4. When a player attempts to log in:
    - The connection is rejected (if enabled)

---

## 📦 Requirements

- **ProtocolLib** (required)
- **Spigot / Paper** (1.8+ recommended)

---

## ⚙️ Installation

1. Build or download the `.jar` file
2. Place it into the `plugins/` directory
3. Install **ProtocolLib**
4. Start the server
5. Configure `config.yml`
6. Restart the server

---

## 🛠️ Configuration (`config.yml`)

```yaml
enabled: true

# AbuseIPDB API keys (multiple allowed)
ABUSEIPDB_API_KEYS:
  - "YOUR_API_KEY_1"
  - "YOUR_API_KEY_2"

# Report sending mode
# SINGLE_WITH_RETRY — try keys one by one until success
# PARALLEL_ALL_KEYS — use all keys in parallel
ABUSEIPDB_MODE: "SINGLE_WITH_RETRY"

# AbuseIPDB category (14 = Port Scan)
ABUSEIPDB_LIST_CHECK_CATEGORY: "14"

# Report comment template
ABUSEIPDB_COMMENT: "Suspicious Minecraft server scan. ip={ip}, port={port}, protocol={protocolVersion}"

# Block all player connections
BLOCK_USER_ACCESS: true

# Connection info lifetime (seconds)
connection_info_ttl_seconds: 30
```

---

## 🔁 AbuseIPDB Modes
🟢 SINGLE_WITH_RETRY
- Uses one API key at a time
- Automatically retries with the next key on failure
- Saves API limits
- Recommended mode

🔵 PARALLEL_ALL_KEYS
- Uses all API keys simultaneously
- Faster reporting
- More aggressive behavior
- Uses a thread pool

---

⏳ Cooldown
- Each IP address can be reported to AbuseIPDB only once every 15 minutes
- Prevents duplicate and spam reports

---

🔐 Security
- All HTTP requests are executed asynchronously
- Connection and read timeouts are enforced
- AbuseIPDB API errors will not crash the server
- No player data is stored
---

📜 Log Examples
```text
AbuseIPDB mode: SINGLE_WITH_RETRY
AbuseIPDB keys: 2
AbuseIPDB OK (retry) 192.168.1.1
AbuseIPDB ALL KEYS FAILED 10.0.0.5
```

---

📌 Intended Use
This plugin is ideal for:
- 🔒 Private servers
- 🧪 Development / testing servers
- 🛠️ Closed or internal projects
- 🚫 Servers that should not appear in public server lists

---

⚠️ Important Notice
> ❗ This plugin is NOT intended for public servers
> All incoming connections may be blocked

---

📄 License
> Use at your own risk.
> The author is not responsible for any IP blocks issued by AbuseIPDB.

❤️ Credits
- ProtocolLib
- AbuseIPDB
- Bukkit / Spigot Community

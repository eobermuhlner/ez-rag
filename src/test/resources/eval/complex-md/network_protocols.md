---
title: Network Protocols Reference
topic: technology
category: networking
---

# Network Protocols Reference

## OSI Model Layers

The **OSI (Open Systems Interconnection)** model organizes network communication into seven
layers, each responsible for a specific aspect of data transmission [Zimmermann, 1980].

| Layer | Name         | Protocol Examples           | Data Unit    |
|-------|--------------|-----------------------------|--------------|
| 7     | Application  | HTTP, FTP, SMTP, DNS        | Message/Data |
| 6     | Presentation | TLS/SSL, MIME, XDR          | Message      |
| 5     | Session      | NetBIOS, RPC, SIP           | Message      |
| 4     | Transport    | TCP, UDP, QUIC              | Segment      |
| 3     | Network      | IP, ICMP, BGP, OSPF         | Packet       |
| 2     | Data Link    | Ethernet, Wi-Fi (802.11)    | Frame        |
| 1     | Physical     | Ethernet cable, Fiber, DSL  | Bit          |

## Transport Layer Protocols

### TCP vs UDP vs QUIC

| Feature                | TCP                     | UDP              | QUIC                    |
|------------------------|-------------------------|------------------|-------------------------|
| Connection type        | Connection-oriented     | Connectionless   | Connection-oriented     |
| Reliability            | Guaranteed delivery     | Best-effort      | Guaranteed delivery     |
| Ordering               | In-order delivery       | No ordering      | In-order (per stream)   |
| Error correction       | Yes                     | No (optional)    | Yes                     |
| Congestion control     | Yes                     | No               | Yes                     |
| Head-of-line blocking  | Yes                     | No               | No (multiplexed)        |
| Handshake latency      | 1.5 RTT                 | 0 RTT            | 0–1 RTT                 |
| Typical use            | HTTP, email, files      | DNS, video       | HTTP/3, mobile apps     |

> **QUIC** was designed at Google to eliminate TCP's head-of-line blocking problem in
> multiplexed HTTP connections, reducing connection setup latency on lossy mobile networks
> [Langley et al., 2017].

## Application Layer Protocols

### HTTP Version History

- **HTTP/1.0** (1996)
  - One request per TCP connection; connection closed after each response
  - No persistent connections by default
  - No header compression in specification
- **HTTP/1.1** (1997)
  - Persistent connections (keep-alive) reduce connection overhead
  - Pipelining allows multiple requests without waiting for responses
  - Chunked transfer encoding for streaming responses
  - Required `Host` header enables virtual hosting on shared servers
- **HTTP/2** (2015)
  - Binary framing layer replaces text-based protocol
  - Multiplexing: multiple requests share a single TCP connection
  - Header compression using the HPACK algorithm
  - Server push allows proactive resource delivery to clients
  - Still suffers from TCP's head-of-line blocking at the transport layer
- **HTTP/3** (2022)
  - Runs over QUIC instead of TCP, eliminating head-of-line blocking
  - Faster connection establishment with 0-RTT resumption for known servers
  - Improved performance on high-latency or lossy mobile networks
  - Independently retransmits lost packets per stream without blocking others

### DNS Resolution Process

DNS (Domain Name System) translates human-readable hostnames to IP addresses through a
hierarchical delegation chain:

1. **Client Query**
   - Browser checks its local in-memory cache
   - OS resolver checks `/etc/hosts`, then the system DNS cache
   - If not resolved, forwards query to the configured recursive resolver
2. **Recursive Resolver**
   - ISP or public resolver (e.g., 8.8.8.8 or 1.1.1.1) receives the query
   - Checks its own cache; on a miss, begins iterative resolution
3. **Root Name Servers**
   - 13 root server clusters worldwide (a.root-servers.net through m.root-servers.net)
   - Returns NS records for the appropriate top-level domain (e.g., `.com`, `.org`)
4. **TLD Name Servers**
   - Operated by registries (e.g., Verisign for `.com`)
   - Returns NS records pointing to the domain's authoritative nameserver
5. **Authoritative Name Server**
   - Holds the zone file for the queried domain
   - Returns the final A or AAAA record (IPv4 or IPv6 address)
   - The TTL field controls how long resolvers may cache the answer

## Security Protocols

| Protocol | OSI Layer | Purpose                       | Successor To        |
|----------|-----------|-------------------------------|---------------------|
| TLS 1.3  | 4/5       | Transport encryption          | TLS 1.2, SSL        |
| SSH v2   | 7         | Secure remote shell           | Telnet, rlogin      |
| IPsec    | 3         | IP-level VPN encryption       | —                   |
| DNSSEC   | 7         | DNS record signing            | Plain DNS           |
| HTTPS    | 7         | HTTP over TLS                 | HTTP                |

## References

- [Zimmermann, 1980] H. Zimmermann, "OSI Reference Model — The ISO Model of Architecture for Open Systems Interconnection," *IEEE Trans. Communications*, 1980.
- [Langley et al., 2017] A. Langley et al., "The QUIC Transport Protocol: Design and Internet-Scale Deployment," *SIGCOMM*, 2017.

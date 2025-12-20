# Traccar SSE

> **Unofficial derivative of [traccar/traccar](https://github.com/traccar/traccar)**  
> This repository extends the Traccar GPS tracking server with **Server-Sent Events (SSE)** support for real-time data dissemination.  
> It is an independent project and is **not affiliated with nor endorsed by the Traccar maintainers**.

---

## Abstract

This project presents a modification of the Traccar GPS tracking server aimed at
introducing **Server-Sent Events (SSE)** as a mechanism for real-time, server-to-client
data streaming. The proposed extension enables continuous delivery of position updates
over standard HTTP connections, providing an alternative to polling-based approaches
and bidirectional communication models such as WebSockets.

---

## Motivation

Traccar provides a robust backend for GPS device communication and data storage but
primarily exposes data to clients using request–response or duplex connection paradigms. In scenarios
where clients don't require bidirectional communication the current tools are ineffective.

The motivation for this fork is to investigate SSE as a lightweight, unidirectional
streaming mechanism that:
- integrates naturally with HTTP-based architectures,
- reduces client-side complexity,
- avoids persistent bidirectional channels when only server-to-client updates are required.

---

## Scope and Contributions

Relative to the upstream Traccar project, this fork introduces:

- An SSE-based API for streaming real-time position updates
- Server-side listener components integrated into Traccar’s internal update pipeline
- Minimal modifications to existing server and build infrastructure

The following aspects remain unchanged:
- Device protocol decoding
- Data persistence and storage models
- Core tracking and business logic

This work focuses exclusively on **data dissemination**, not on device communication.

---

## System Architecture

At a high level, the data flow is extended as follows:

GPS Device → Protocol Decoder → Position Entity → Data Manager / Storage → SSE Event Dispatcher → HTTP SSE Clients

SSE connections are strictly **server → client** and do not participate in device
communication or command execution.

---

## Relationship to Upstream Traccar

This repository maintains a logical relationship with the official Traccar project:

- **Forked repository:** https://github.com/traccar/traccar
- **License:** Apache License 2.0
- **Codebase origin:** Traccar server

Upstream changes may be periodically merged or rebased. Divergence is expected where
necessary to support SSE-related functionality.

---

## Project Status

This project is currently considered **experimental**.

- APIs may change without notice
- No stability guarantees are provided
- The implementation is intended for evaluation, experimentation, and extension

It should not be assumed production-ready without further validation.

---

## License

This project is distributed under the **Apache License 2.0**, consistent with the
licensing of the upstream Traccar project.

Refer to [`LICENSE.txt`](LICENSE.txt) for full license terms.

---

## Disclaimer

This repository is an independent derivative work based on Traccar.
For the official Traccar project, documentation, and support, refer to:

👉 https://github.com/traccar/traccar

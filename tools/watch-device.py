#!/usr/bin/env python3
"""Simulated Watch-protocol GPS tracker for development (see docs/desenvolvimento.md).

Keeps one TCP connection like a real device: sends a heartbeat (LK), then a position (UD) every --interval
seconds, moving --step meters east from the start point. The device must exist in Traccar with the same
identifier (--id).

    python3 tools/watch-device.py --id 9031853319
    python3 tools/watch-device.py --host localhost --port 5093 --id 9031853319 --interval 5 --count 20
"""
import argparse
import math
import socket
import time


def frame(device_id, content):
    # [3G*ID*LEN*CONTENT], LEN = content length in 4 hex digits
    return f"[3G*{device_id}*{len(content):04X}*{content}]".encode()


def coordinate(value, positive, negative):
    return f"{abs(value):.6f},{positive if value >= 0 else negative}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5093)
    parser.add_argument("--id", required=True, help="device identifier registered in Traccar")
    parser.add_argument("--lat", type=float, default=-1.4748)
    parser.add_argument("--lon", type=float, default=-48.4501)
    parser.add_argument("--interval", type=float, default=10, help="seconds between positions")
    parser.add_argument("--step", type=float, default=50, help="meters moved east per position")
    parser.add_argument("--count", type=int, default=0, help="positions to send, 0 = until Ctrl+C")
    args = parser.parse_args()

    meters_per_degree_lon = 111_320 * math.cos(math.radians(args.lat))
    speed_kph = args.step / args.interval * 3.6

    with socket.create_connection((args.host, args.port), timeout=10) as connection:
        connection.sendall(frame(args.id, "LK,0,0,100"))
        try:
            print("LK ->", connection.recv(256).decode(errors="replace"))
        except socket.timeout:
            print("LK -> sem resposta (identificador não cadastrado no Traccar?)")
            return

        sent = 0
        lon = args.lon
        while args.count == 0 or sent < args.count:
            now = time.gmtime()
            # UD: date, time, A = valid fix, latitude, longitude, speed (km/h), course, altitude, satellites,
            # GSM signal, battery %, steps, rolls, status
            content = ",".join([
                "UD", time.strftime("%d%m%y", now), time.strftime("%H%M%S", now), "A",
                coordinate(args.lat, "N", "S"), coordinate(lon, "E", "W"),
                f"{speed_kph:.1f}", "90", "10", "9", "100", "87", "0", "0", "00000000", "0", "0"])
            connection.sendall(frame(args.id, content))
            sent += 1
            print(f"UD {sent}: {args.lat:.6f}, {lon:.6f}")
            lon += args.step / meters_per_degree_lon
            if args.count == 0 or sent < args.count:
                time.sleep(args.interval)
        time.sleep(4)  # keep the connection while the server buffers the last position (3 s by default)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

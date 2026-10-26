"""Probe encrypted Smart DNS and Gemini without cookies or disabling TLS validation."""

import argparse
import base64
import http.client
import json
import socket
import ssl
import struct
import urllib.parse
import urllib.request


def skip_name(data, pos):
    while data[pos]:
        size = data[pos]
        if size & 0xC0 == 0xC0:
            return pos + 2
        pos += size + 1
    return pos + 1


def query_doh(endpoint, domain):
    query = struct.pack("!6H", 0, 0x0100, 1, 0, 0, 0)
    query += b"".join(bytes([len(label)]) + label.encode("ascii") for label in domain.split("."))
    query += b"\x00\x00\x01\x00\x01"
    encoded = base64.urlsafe_b64encode(query).decode("ascii").rstrip("=")
    separator = "&" if "?" in endpoint else "?"
    request = urllib.request.Request(endpoint + separator + "dns=" + encoded,
                                     headers={"Accept": "application/dns-message"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=10) as response:
        if response.headers.get_content_type() != "application/dns-message":
            raise ValueError("Endpoint did not return a DNS wire response")
        data = response.read(65536)
    _, flags, questions, answers, _, _ = struct.unpack("!6H", data[:12])
    if flags & 15:
        raise ValueError("DNS error: " + str(flags & 15))
    pos = 12
    for _ in range(questions):
        pos = skip_name(data, pos) + 4
    result = []
    for _ in range(answers):
        pos = skip_name(data, pos)
        kind, _, ttl, length = struct.unpack("!HHIH", data[pos:pos + 10])
        pos += 10
        if kind == 1 and length == 4:
            result.append({"ip": socket.inet_ntoa(data[pos:pos + 4]), "ttl": ttl})
        pos += length
    return result


def probe_gemini(ip):
    host = "gemini.google.com"
    context = ssl.create_default_context()
    with socket.create_connection((ip, 443), timeout=5) as raw:
        with context.wrap_socket(raw, server_hostname=host) as tls:
            tls.sendall(b"GET /app HTTP/1.1\r\nHost: gemini.google.com\r\n"
                        b"Connection: close\r\nUser-Agent: DnsConf-Diagnostic/1.0\r\n\r\n")
            response = http.client.HTTPResponse(tls)
            response.begin()
            location = response.getheader("Location", "")
            body = response.read(65536).decode("utf-8", errors="replace")
            blocked = (response.status == 429 or "/sorry/" in location
                       or "Our systems have detected unusual traffic" in body)
            return {"http_status": response.status,
                    "classification": "google_challenge" if blocked else "http_response",
                    "redirect_host": urllib.parse.urlsplit(location).hostname}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("endpoints", nargs="+")
    parser.add_argument("--check-gemini", action="store_true")
    args = parser.parse_args()
    for endpoint in args.endpoints:
        result = {"endpoint": endpoint}
        try:
            result["answers"] = query_doh(endpoint, "gemini.google.com")
            if args.check_gemini:
                result["checks"] = []
                for answer in result["answers"][:2]:
                    check = {"ip": answer["ip"]}
                    try:
                        check.update(probe_gemini(answer["ip"]))
                    except (OSError, ValueError, http.client.HTTPException) as error:
                        check["error"] = type(error).__name__ + ": " + str(error)
                    result["checks"].append(check)
        except (OSError, ValueError, struct.error, IndexError) as error:
            result["error"] = type(error).__name__ + ": " + str(error)
        print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

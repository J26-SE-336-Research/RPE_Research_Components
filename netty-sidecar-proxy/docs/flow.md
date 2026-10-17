# Request flow

## Application-level flow

```text
1. Service A creates an HTTP request.
2. The request enters the proxy's TCP connection.
3. Netty's HttpServerCodec decodes the ordered TCP byte stream into HTTP objects.
4. ProxyHandler receives the complete HTTP request.
5. ProxyHandler adds X-My-Proxy.
6. The proxy opens a connection to Service B.
7. HttpClientCodec encodes the request into bytes.
8. TCP carries those bytes to Service B.
9. Service B sends an HTTP response.
10. Netty decodes the response.
11. BackendResponseHandler adds X-Proxy-Processed.
12. The proxy sends the response back to Service A.
```

## TCP ordering

TCP uses sequence numbers to identify byte positions in the stream. Segments may arrive out of order, but TCP reassembles them before delivering the ordered byte stream to the application.

Therefore:

```text
TCP packet/segment != HTTP message
```

A single HTTP header can span TCP segments, and one TCP segment can contain part of an HTTP header, the end of a header, and part of the body.

The HTTP parser works above TCP and does not assume that one packet equals one HTTP header or one HTTP request.

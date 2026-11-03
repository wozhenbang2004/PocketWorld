"""Route bearer must remain on its exact selected WSS URL, before any redirect."""
import asyncio
from http import HTTPStatus
import tempfile
import unittest

from websockets.asyncio.server import serve
from websockets.exceptions import InvalidStatus

from device_transport import SUBPROTOCOL, WssByteChannel
from device_transport_fixtures import certificate, outer_client, outer_server


class WssRedirectTests(unittest.IsolatedAsyncioTestCase):
    async def test_redirects_never_forward_route_bearer_or_open_replacement_route(self):
        with tempfile.TemporaryDirectory(prefix="wss-route-redirect-") as directory:
            cert = certificate(directory, "relay")
            route = "/v1/tunnel/" + "r" * 32 + "/a/control"
            token = "a" * 48
            target_requests, source_requests = [], []
            location = ""
            status = HTTPStatus.FOUND

            async def ready(connection):
                await connection.send("ready")
                await connection.wait_closed()

            def capture_target(connection, request):
                target_requests.append((request.path, request.headers.get("Authorization")))

            def redirect(connection, request):
                source_requests.append((request.path, request.headers.get("Authorization")))
                if request.path == route:
                    response = connection.respond(status, "Selected route does not redirect\n")
                    response.headers["Location"] = location
                    return response
                target_requests.append((request.path, request.headers.get("Authorization")))

            async with serve(ready, "127.0.0.1", 0, ssl=outer_server(cert), subprotocols=[SUBPROTOCOL],
                             process_request=capture_target) as target:
                target_base = "wss://127.0.0.1:" + str(target.sockets[0].getsockname()[1])
                async with serve(ready, "127.0.0.1", 0, ssl=outer_server(cert), subprotocols=[SUBPROTOCOL],
                                 process_request=redirect) as source:
                    source_base = "wss://127.0.0.1:" + str(source.sockets[0].getsockname()[1])
                    for status in (HTTPStatus.MULTIPLE_CHOICES, HTTPStatus.MOVED_PERMANENTLY, HTTPStatus.FOUND,
                                   HTTPStatus.SEE_OTHER, HTTPStatus.TEMPORARY_REDIRECT, HTTPStatus.PERMANENT_REDIRECT):
                        for location in (target_base + route, source_base + route.replace("/a/control", "/b/bulk")):
                            with self.subTest(status=status, location=location):
                                target_requests.clear()
                                source_requests.clear()
                                channel, rejected = None, False
                                try:
                                    channel = await asyncio.wait_for(WssByteChannel.connect(source_base + route, token,
                                                                                          relay_ssl_context=outer_client(cert)), 3)
                                except InvalidStatus as error:
                                    rejected = True
                                    self.assertEqual(status, error.response.status_code)
                                finally:
                                    if channel is not None:
                                        await channel.close()
                                self.assertEqual([], target_requests, "Route bearer escaped its exact selected URL")
                                self.assertTrue(rejected, "Redirect was not rejected")
                                self.assertEqual([(route, "Bearer " + token)], source_requests)


if __name__ == "__main__":
    unittest.main()

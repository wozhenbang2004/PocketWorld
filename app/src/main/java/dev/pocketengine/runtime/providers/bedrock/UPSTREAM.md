# Bedrock protocol source correspondence

This package is a bounded adaptation of the official sources listed below.
It uses the application's original inference, cancellation and receipt ownership.
It does not contain an AWS client, credential discovery, refresh, retry or execution controller.

## Event-stream decoder

Source: [AWS SDK for Java v2.57.4](https://github.com/aws/aws-sdk-java-v2/tree/b336857bcee0072ef79e9ccd862cae01a76a0e82/core/eventstream).
Commit: `b336857bcee0072ef79e9ccd862cae01a76a0e82`.

Upstream paths are relative to
`core/eventstream/src/main/java/software/amazon/awssdk/eventstream/`.
The corresponding local paths are below `eventstream/`.

| Upstream path | Upstream Git blob |
| --- | --- |
| MessageDecoder.java | 8078ea144e540160d7cdf3cc8ba65cf4fdff10fc |
| Message.java | 7276a6db8002c9b7d3ec9cdb4876e7757b768eec |
| HeaderType.java | 7e43e9b6f862b9270427cf1ec602596f3eb2addc |
| HeaderValue.java | 0858ca81fd0ac7c349de49e51fbfd6a08d8a22dd |
| internal/Checksums.java | c9a859e792333cf3848b0e9b894eaba4004e0fd5 |
| internal/Header.java | 0da1289d8920cf764440b713802f65114288ec21 |
| internal/Prelude.java | e7b8f0d95db2ae075fb5a6af5e9063ff531fad07 |
| internal/Utils.java | 414b8a408ed76e0d56c8bef467d1318d3cd64172 |

Local modifications:

- Relocate the package and remove SDK-only annotations; retain the original
  framing, CRC and typed-header decoding implementation.
- MessageDecoder verifies the 12-byte prelude before advertised allocation,
  applies an explicit application frame budget, exposes the exact bytes needed
  and whether EOF is at a clean message boundary. Reading only that boundary
  lets the caller retain earlier valid usage before a later malformed frame.
- Message rejects duplicate header names and bounds header count.
- Prelude checks structural unsigned lengths. The Smithy service-only
  128 KiB/24 MiB limits are not treated as client protocol-validity rules.
  The enclosing application independently bounds its resource use.
- Utils performs strict UTF-8 decoding and handles header-value lengths as
  unsigned 16-bit values, including legal empty values. Header names stay
  nonempty.
- BedrockEventStream validates known typed envelopes, strict JSON and canonical
  base64. It returns the exact inner bytes; the original Anthropic accumulator
  validates inner JSON, complete tool blocks and terminal state.
- Unknown and malformed frames stop the response. A semantic terminal is not
  HTTP completion: the accumulator must also obtain clean binary EOF.

The framing reference is the [Amazon event stream specification](https://smithy.io/2.0/aws/amazon-eventstream.html).
The decoder package remains an internal adaptation; it is not an upstream stable
public SDK API.

## SigV4 and endpoint encoding

The signer combines the JDK/JCA derivation in the official
[no-SDK Java sample](https://github.com/aws-samples/sigv4-signing-examples/blob/main/no-sdk/java/AWSSigner.java)
(Git blob `5da5512b449e1abcb854eb350d59d3b9c25b1860`, MIT-0)
with the following AWS SDK v2.57.4 rules:

| Upstream path | Git blob | Adapted mechanism |
| --- | --- | --- |
| core/http-auth-aws/src/main/java/software/amazon/awssdk/http/auth/aws/internal/signer/V4CanonicalRequest.java | 227154c60d33a34740f0f3b62153d098007d8266 | Raw normalized path, double URL encoding, sorted lowercase headers and whitespace collapse |
| core/protocols/protocol-core/src/main/java/software/amazon/awssdk/protocols/core/PathMarshaller.java | cac09a5ec5870afce51c72d6e66d8722b9edad30 | Non-greedy URI-label encoding |
| utils/src/main/java/software/amazon/awssdk/utils/http/SdkHttpUtils.java | 487610762340753f2047f478ce0a794ef39b57d4 | UTF-8 RFC3986 replacements after URL encoding |
| core/http-auth-aws/src/main/java/software/amazon/awssdk/http/auth/aws/internal/signer/V4RequestSigner.java | f2787ae32e5e3490c2fb644104b645b2a8b3afb3 | Session token included before canonicalization |

The bounded route rejects query strings, fragments, alternate hosts/ports and
resource forms. It does not copy the sample's decoded getPath/empty-query
assumption or global environment credentials. The exact final byte array is
hashed and sent by the original Gateway. Service signing name is
`bedrock`, while the endpoint host prefix is `bedrock-runtime`.

The production model allowlist is the current selected Claude Sonnet 4.6 and
Claude Opus 4.6 ordinary IDs. It expresses protocol compatibility, not account
or region availability. No default model is selected. Historical model IDs used
in encoding examples are not admitted by production endpoint validation.

## Independent test sources

The files under `app/src/test/resources/bedrock/sigv4/` are exact
upstream files from
[boto/botocore](https://github.com/boto/botocore/tree/ddaf46e8bfed119924359798579bfdd343bf3739/tests/unit/auth/aws4_testsuite),
commit `ddaf46e8bfed119924359798579bfdd343bf3739`:

- get-vanilla
- get-utf8
- get-vanilla-with-session-token
- post-x-www-form-urlencoded (nonempty body)

The expected canonical requests, strings to sign and authorization signatures
come from that upstream suite. They are not produced by the implementation under
test. All credential-looking values in those fixtures are public AWS examples.

The hardcoded empty/appdata binary frames originate in the SDK v2.57.4
`MessageTest.java` (Git blob
`a2fbb8e2ab55e7a1c6ebff36196d5ff3adbaea58`).
We retain its exact vector bytes, not its JDK-specific allocation/management test
dependencies.

## Licenses

The complete source license and notice are packaged with the application under
`app/src/main/assets/licenses/bedrock/`:

- AWS-SDK-LICENSE.txt — exact SDK v2.57.4 LICENSE.txt, Apache-2.0 and included notices.
- AWS-SDK-NOTICE.txt — exact SDK v2.57.4 NOTICE.txt.
- SIGV4-SAMPLE-LICENSE.txt — exact MIT-0 license for the official no-SDK sample.

AWS SDK source copyright headers are retained. The Botocore test loader declares
Apache-2.0 and identifies the original AWS SigV4 test suite. Local adaptations
are identified above; they are not represented as unmodified upstream SDK code.


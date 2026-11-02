"""Ephemeral localhost TEST certificate material only. Never provisions device trust."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import ipaddress
import ssl
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID


def certificate(directory, name):
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PocketWorld localhost test " + name)])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1))
            .not_valid_after(now + timedelta(hours=1)).add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                        data_encipherment=False, key_agreement=False, key_cert_sign=True, crl_sign=True,
                                        encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH, ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .sign(key, hashes.SHA256()))
    root = Path(directory)
    der = cert.public_bytes(serialization.Encoding.DER)
    cert_path, key_path = root / (name + ".pem"), root / (name + ".key")
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    key_path.chmod(0o600)
    (root / (name + ".der")).write_bytes(der)
    (root / (name + ".p12")).write_bytes(pkcs12.serialize_key_and_certificates(name.encode(), key, cert, None, serialization.BestAvailableEncryption(b"localhost-test-only")))
    return {"der": der, "cert": cert_path, "key": key_path, "p12": root / (name + ".p12"), "der_path": root / (name + ".der")}


def context(local, peer, *, server_side):
    from device_identity import pinned_context
    result = pinned_context(peer["der"], server_side=server_side)
    result.load_cert_chain(local["cert"], local["key"])
    return result


def outer_server(cert):
    result = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    result.minimum_version = ssl.TLSVersion.TLSv1_2
    result.load_cert_chain(cert["cert"], cert["key"])
    return result


def outer_client(cert):
    result = ssl.create_default_context(cafile=str(cert["cert"]))
    return result

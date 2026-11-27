"""Prevent the Linux OCR process from creating outbound network sessions."""

from ctypes import CDLL, POINTER, Structure, c_int, c_uint, c_uint32, c_uint64, c_void_p, c_char_p
from errno import EPERM
from socket import AF_INET, AF_INET6, SOCK_DGRAM


SCMP_ACT_ALLOW = 0x7FFF0000
SCMP_ACT_ERRNO = 0x00050000
SCMP_CMP_EQ = 4
SCMP_CMP_MASKED_EQ = 7
SOCKET_TYPE_MASK = 0xF


class Argument(Structure):
    _fields_ = [
        ("index", c_uint), ("operation", c_int),
        ("datum_a", c_uint64), ("datum_b", c_uint64),
    ]


def apply_network_guard():
    seccomp = CDLL("libseccomp.so.2")
    seccomp.seccomp_init.argtypes = [c_uint32]
    seccomp.seccomp_init.restype = c_void_p
    seccomp.seccomp_release.argtypes = [c_void_p]
    seccomp.seccomp_release.restype = None
    seccomp.seccomp_load.argtypes = [c_void_p]
    seccomp.seccomp_syscall_resolve_name.argtypes = [c_char_p]
    seccomp.seccomp_rule_add_array.argtypes = [c_void_p, c_uint32, c_int, c_uint, POINTER(Argument)]

    context = seccomp.seccomp_init(SCMP_ACT_ALLOW)
    if not context:
        raise RuntimeError("Unable to initialize OCR network guard")
    try:
        # An HTTP server can bind/listen/accept without opening an outbound connection.
        # Block IP datagrams as well, so an unconnected UDP socket cannot send data.
        deny = SCMP_ACT_ERRNO | EPERM
        connect_call = seccomp.seccomp_syscall_resolve_name(b"connect")
        socket_call = seccomp.seccomp_syscall_resolve_name(b"socket")
        if seccomp.seccomp_rule_add_array(context, deny, connect_call, 0, None) != 0:
            raise RuntimeError("Unable to block outbound OCR socket connections")
        for family in (AF_INET, AF_INET6):
            restrictions = (Argument * 2)(
                Argument(0, SCMP_CMP_EQ, family, 0),
                Argument(1, SCMP_CMP_MASKED_EQ, SOCKET_TYPE_MASK, SOCK_DGRAM),
            )
            if seccomp.seccomp_rule_add_array(context, deny, socket_call, 2, restrictions) != 0:
                raise RuntimeError("Unable to block outbound OCR datagrams")
        if seccomp.seccomp_load(context) != 0:
            raise RuntimeError("Unable to activate OCR network guard")
    finally:
        seccomp.seccomp_release(context)

import ipaddress
import socket
import struct
import unittest

from tuya_arp import MAX_AGE_S, parse_frame, plan

LAN = ipaddress.ip_network("192.168.1.0/24")


def frame(src_ip="192.168.1.104", mac=b"\xfc\x67\x1f\x7a\x04\xdd", dport=6667, proto=17, ethertype=b"\x08\x00"):
    eth = b"\xff" * 6 + mac + ethertype
    ip = bytes([0x45, 0]) + b"\x00" * 7 + bytes([proto]) + b"\x00\x00" + socket.inet_aton(src_ip) + b"\xff" * 4
    udp = struct.pack("!HHHH", 49154, dport, 8, 0)
    return eth + ip + udp


class ParseFrameTest(unittest.TestCase):
    def test_tuya_broadcast(self):
        self.assertEqual(parse_frame(frame()), ("192.168.1.104", "fc:67:1f:7a:04:dd"))

    def test_other_ports_protocols_and_short_frames_ignored(self):
        self.assertIsNone(parse_frame(frame(dport=53)))
        self.assertIsNone(parse_frame(frame(proto=6)))
        self.assertIsNone(parse_frame(frame(ethertype=b"\x86\xdd")))
        self.assertIsNone(parse_frame(frame()[:30]))


class PlanTest(unittest.TestCase):
    def test_new_devices_added(self):
        adds, deletes, state = plan({}, {"192.168.1.104": "aa", "192.168.1.204": "bb"}, 100.0, LAN)
        self.assertEqual(adds, [("192.168.1.104", "aa"), ("192.168.1.204", "bb")])
        self.assertEqual(deletes, [])
        self.assertEqual(state["192.168.1.204"], {"mac": "bb", "last_seen": 100.0})

    def test_moved_mac_deletes_old_ip(self):
        old = {"192.168.1.104": {"mac": "aa", "last_seen": 50.0}}
        adds, deletes, state = plan(old, {"192.168.1.120": "aa"}, 100.0, LAN)
        self.assertEqual((adds, deletes), ([("192.168.1.120", "aa")], ["192.168.1.104"]))
        self.assertNotIn("192.168.1.104", state)

    def test_unseen_kept_for_a_day_then_deleted(self):
        old = {"192.168.1.104": {"mac": "aa", "last_seen": 0.0}}
        self.assertEqual(plan(old, {}, MAX_AGE_S - 1, LAN)[1], [])
        adds, deletes, state = plan(old, {}, MAX_AGE_S + 1, LAN)
        self.assertEqual((adds, deletes, state), ([], ["192.168.1.104"], {}))

    def test_outside_lan_ignored(self):
        adds, _, state = plan({}, {"10.0.0.5": "cc"}, 1.0, LAN)
        self.assertEqual((adds, state), ([], {}))


if __name__ == "__main__":
    unittest.main()

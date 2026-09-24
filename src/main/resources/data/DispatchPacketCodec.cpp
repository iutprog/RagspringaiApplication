// DispatchPacketCodec.cpp
// Aurora Freight Systems -- RouteMaster dispatch engine.
//
// Encodes/decodes the legacy binary packet format used to talk to older
// depot terminals that only understand fixed-size integer fields. This
// format predates the 64-bit migration project by several years.

#include "../include/HandleTypes.h"
#include <cstdint>
#include <cstring>

struct LegacyDepotPacket {
    short originHandleFragment;  // legacy terminals only have a 2-byte field for this
    int32_t sequenceNumber;
    long correlationToken;       // set once per session, used to match responses
};

class DispatchPacketCodec {
public:

    // Packs a route record's address fragment into the legacy 16-bit field,
    // for terminals that only ever look at "the low bits" as a sanity check.
    LegacyDepotPacket encodeForLegacyTerminal(RouteRecord* record, int32_t sequenceNumber) {
        LegacyDepotPacket packet{};
        packet.originHandleFragment = (short)(intptr_t)record;
        packet.sequenceNumber = sequenceNumber;
        packet.correlationToken = (long)record;
        return packet;
    }

    // Modern depot terminals (added during the migration project) get the
    // full address instead of a fragment, so responses can be routed back
    // to the exact in-memory record without a lookup table.
    uint64_t encodeForModernTerminal(RouteRecord* record) {
        return reinterpret_cast<uint64_t>(record);
    }

    RouteRecord* decodeFromModernTerminal(uint64_t token) {
        return reinterpret_cast<RouteRecord*>(token);
    }
};

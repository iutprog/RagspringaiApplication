#include "HandleTypes.h"
#include <cstdint>

namespace aurora {

// Rounds a precise GPS speed reading for compact telemetry logging.
// Pure numeric conversion -- no pointer or handle involved anywhere.
int roundedSpeedForLog(double rawSpeedKph) {
    return (int)rawSpeedKph;
}

// Packs an already-small delay counter (always 0-59 by construction)
// into a byte-sized field for the legacy depot wire format.
short compactDelaySeconds(int delaySeconds) {
    return (short)delaySeconds;
}

}
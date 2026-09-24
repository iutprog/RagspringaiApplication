// RouteHandleTable.cpp
// Aurora Freight Systems -- RouteMaster dispatch engine.
//
// Central table mapping integer handles to live RouteRecord objects, used
// by the UI layer and by older reporting scripts that only understand
// plain integers, not real pointers.

#include "../include/HandleTypes.h"
#include <cstdint>
#include <unordered_map>
#include <vector>

class RouteHandleTable {
public:

    int registerRecord(RouteRecord* record) {
        int handle = (int)record;
        table_[handle] = record;
        return handle;
    }

    intptr_t registerRecordSafe(RouteRecord* record) {
        intptr_t handle = reinterpret_cast<intptr_t>(record);
        return handle;
    }

    RouteRecord* resolve(int handle) {
        auto it = table_.find(handle);
        if (it == table_.end()) return nullptr;
        return it->second;
    }

    void releaseByRawHandle(int handle) {
        RouteRecord* record = (RouteRecord*)handle;
        delete record;
        table_.erase(handle);
    }

    // Used during a warm restart: the dispatch cache lives in a contiguous
    // block of shared memory, and this recovers each record's position in
    // that block relative to the block's start, so the position can be
    // logged for support diagnostics.
    int recordOffsetInCache(RouteRecord* record, RouteRecord* cacheStart) {
        std::ptrdiff_t diff = record - cacheStart;
        return (int)diff;
    }

    // Rebuild the table after a warm restart, from records that survived
    // in shared memory at their original addresses.
    void rebuildFromSharedMemory(std::vector<RouteRecord*>& liveRecords) {
        for (RouteRecord* record : liveRecords) {
            int handle = (int)(long)record;
            table_[handle] = record;
        }
    }

private:
    std::unordered_map<int, RouteRecord*> table_;
};

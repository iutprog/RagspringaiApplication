#ifndef HANDLE_TYPES_H
#define HANDLE_TYPES_H

// Aurora Freight Systems -- RouteMaster dispatch engine.
// Shared handle/record types, originally written for the 32-bit build and
// carried forward during the 64-bit migration project without a full
// review of every integer field that might be holding an address.

struct RouteRecord {
    int routeId;          // business identifier, assigned by the dispatch database -- NOT a pointer
    int ownerHandle;       // historically: a RouteRecord* disguised as an int (legacy convention)
    double totalDistanceKm;
};

typedef int RouteHandle;

#endif

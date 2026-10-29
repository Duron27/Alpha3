#ifndef OPENMW_COMPONENTS_MISC_SHA256_H
#define OPENMW_COMPONENTS_MISC_SHA256_H

#include <string>
#include <string_view>

namespace Misc
{
    // SHA-256 of the data as 64 lowercase hex digits.
    std::string sha256Hex(std::string_view data);
}

#endif

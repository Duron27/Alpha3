#ifndef MWLUA_MODSTOREBINDINGS_H
#define MWLUA_MODSTOREBINDINGS_H

#include <sol/forward.hpp>

namespace MWLua
{
    struct Context;

    // openmw.modstore: the client mods folder (see components/misc/clientmods.hpp)
    sol::table initModstorePackage(const Context& context);
}

#endif // MWLUA_MODSTOREBINDINGS_H

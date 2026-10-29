#ifndef MWLUA_NETBINDINGS_H
#define MWLUA_NETBINDINGS_H

#include <sol/forward.hpp>

namespace Net
{
    class Sockets;
}

namespace MWLua
{
    struct Context;

    sol::table initNetPackage(const Context& context, Net::Sockets& sockets);
}

#endif // MWLUA_NETBINDINGS_H

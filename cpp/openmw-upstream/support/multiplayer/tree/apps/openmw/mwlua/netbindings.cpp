#include "netbindings.hpp"

#include <cstdint>
#include <limits>
#include <optional>
#include <stdexcept>
#include <tuple>

#include <components/lua/luastate.hpp>
#include <components/net/sockets.hpp>

#include "context.hpp"

namespace MWLua
{
    namespace
    {
        std::uint32_t socketId(double id)
        {
            if (!(id >= 0 && id <= std::numeric_limits<std::uint32_t>::max()))
                return 0;
            return static_cast<std::uint32_t>(id);
        }
    }

    sol::table initNetPackage(const Context& context, Net::Sockets& sockets)
    {
        sol::state_view lua = context.sol();
        sol::table api(lua, sol::create);

        // Starts connecting. Returns the connection id, or nil if there are too many sockets.
        api["connect"] = [&sockets](std::string_view host, int port) -> sol::optional<double> {
            if (host.empty())
                throw std::invalid_argument("net.connect: empty host");
            if (port < 1 || port > std::numeric_limits<std::uint16_t>::max())
                throw std::invalid_argument("net.connect: port out of range");
            const std::uint32_t id = sockets.connect(std::string(host), static_cast<std::uint16_t>(port));
            if (id == 0)
                return sol::nullopt;
            return static_cast<double>(id);
        };
        // Returns the listener id, or nil and an error message
        api["listen"] = [&sockets](int port) -> std::tuple<sol::optional<double>, sol::optional<std::string>> {
            if (port < 1 || port > std::numeric_limits<std::uint16_t>::max())
                throw std::invalid_argument("net.listen: port out of range");
            std::string error;
            const std::uint32_t id = sockets.listen(static_cast<std::uint16_t>(port), error);
            if (id == 0)
                return { sol::nullopt, error };
            return { static_cast<double>(id), sol::nullopt };
        };
        api["send"] = [&sockets](double id, std::string_view data, sol::optional<bool> raw) {
            return sockets.send(socketId(id), data, raw.value_or(false));
        };
        api["close"] = [&sockets](double id) { sockets.close(socketId(id)); };
        // A line sent on the connection as the game quits, so the other end knows it was deliberate
        api["setFarewell"] = [&sockets](double id, std::string_view data) { sockets.setFarewell(socketId(id), data); };
        api["localAddress"] = [&sockets](double id) -> sol::optional<std::string> {
            std::string address = sockets.localAddress(socketId(id));
            if (address.empty())
                return sol::nullopt;
            return address;
        };
        api["remoteAddress"] = [&sockets](double id) -> sol::optional<std::string> {
            std::string address = sockets.remoteAddress(socketId(id));
            if (address.empty())
                return sol::nullopt;
            return address;
        };

        return LuaUtil::makeReadOnly(api);
    }
}

#ifndef OPENMW_COMPONENTS_NET_SOCKETS_H
#define OPENMW_COMPONENTS_NET_SOCKETS_H

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace Net
{
    struct SocketEvent
    {
        enum class Type
        {
            Connected, // an outgoing connection was established
            Accepted, // a listener accepted a connection: mId is the new connection, mListener the listener
            Line, // mData holds one received line without its terminator (a final unterminated line is delivered too)
            Closed, // the peer closed or the connection failed; mData is the reason. Not sent after close().
        };
        Type mType;
        std::uint32_t mId = 0;
        std::uint32_t mListener = 0;
        std::string mData;
    };

    // Plain TCP sockets for scripts: any number of outgoing connections and listeners, identified by ids that are
    // never reused. All socket work happens on a background thread; the owner drives it with non-blocking calls and
    // collects results with poll(). Messages are lines terminated by '\n' ('\r' before it is dropped).
    // Not thread-safe: use from a single thread.
    class Sockets
    {
    public:
        static constexpr std::size_t sMaxLineSize = 1024 * 1024;
        static constexpr std::size_t sMaxPendingOutput = 8 * 1024 * 1024;
        // (More than the 128 players a multiplayer server takes: connections beyond that still get to hear "full")
        static constexpr std::size_t sMaxSockets = 192;

        Sockets();
        ~Sockets();
        Sockets(const Sockets&) = delete;
        Sockets& operator=(const Sockets&) = delete;

        // Starts connecting asynchronously; a Connected or Closed event follows. Returns 0 if there are too many
        // sockets.
        std::uint32_t connect(const std::string& host, std::uint16_t port);

        // Starts listening on all interfaces. On failure returns 0 and sets error.
        std::uint32_t listen(std::uint16_t port, std::string& error);

        // Queues data on a connection. Unless raw, a '\n' is appended and the data must not contain line breaks.
        // Returns false for unknown or closed ids, rejected data or when too much is already queued.
        bool send(std::uint32_t id, std::string_view data, bool raw);

        // Closes a connection or listener (and, for a listener, nothing else: accepted connections stay open).
        // No further events are delivered for it.
        void close(std::uint32_t id);

        // A line to send on this connection, as a last act, when the Sockets are destroyed (the game quits). It lets
        // the other end tell a deliberate exit from a lost connection. Not sent for close() or closeAll().
        void setFarewell(std::uint32_t id, std::string_view data);

        void closeAll();

        // Our address on this connection, or an empty string if it is not connected.
        std::string localAddress(std::uint32_t id) const;

        // The other end's address on this connection (for a server: where a player connects from), or an empty
        // string if it is not connected.
        std::string remoteAddress(std::uint32_t id) const;

        std::vector<SocketEvent> poll();

    private:
        class Impl;
        std::unique_ptr<Impl> mImpl;
    };
}

#endif

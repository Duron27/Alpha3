---
-- Plain TCP sockets for global scripts, e.g. for multiplayer or other out-of-process integrations.
-- A script can open any number of outgoing connections and listeners. Each is identified by a number that is not
-- reused. Messages are UTF-8 lines terminated by a line break; the payload format is up to the script
-- (see @{openmw.markup#markup.decodeYaml}, which also parses JSON).
--
-- Everything that happens on a socket is delivered through one engine handler,
-- `onNetEvent(type, id, data, listener)`:
--
--   * `"connected"`: the outgoing connection `id` is established.
--   * `"accepted"`: listener `listener` accepted a new connection `id`.
--   * `"line"`: connection `id` received one line (`data`, without the line terminator). A last line that the peer
--     sent without a terminator before closing is delivered too.
--   * `"closed"`: connection `id` was closed by the peer or failed (`data` is the reason). Not sent after @{#net.close}.
--
-- All sockets are closed when a game is unloaded.
-- @context global
-- @module net
-- @usage local net = require('openmw.net')

---
-- Start connecting to a server. Does not block; the result is reported by a `"connected"` or `"closed"` event.
-- @function [parent=#net] connect
-- @param #string host Host name or IP address.
-- @param #number port TCP port, 1-65535.
-- @return #number The connection id, or `nil` if there are too many sockets.

---
-- Start accepting connections on all interfaces. Does not block.
-- @function [parent=#net] listen
-- @param #number port TCP port, 1-65535.
-- @return #number The listener id, or `nil` followed by an error message (for example if the port is in use).

---
-- Send data on a connection.
-- @function [parent=#net] send
-- @param #number id Connection id.
-- @param #string data Without `raw`, a line break is appended and `data` must not contain line breaks (`\r` or `\n`).
-- At most 1 MiB.
-- @param #boolean raw Optional. If true the data is sent exactly as given (for example an HTTP request).
-- @return #boolean `false` if the id is unknown or closed, the data was rejected or too much is already queued.

---
-- Close a connection or a listener. No further events are delivered for it; connections that a listener accepted stay
-- open. Ids of connections that were closed by the peer do not need to be closed.
-- @function [parent=#net] close
-- @param #number id

---
-- A line (without line breaks, at most 4096 bytes) to send on the connection as a last act when the game quits, so the
-- other end can tell a deliberate exit from a lost connection. Best effort. It is not sent when the connection or the
-- game is closed by a script or when a game is unloaded.
-- @function [parent=#net] setFarewell
-- @param #number id
-- @param #string data

---
-- Our address on a connected connection, i.e. the address of this machine on the network it uses to reach the other end.
-- @function [parent=#net] localAddress
-- @param #number id
-- @return #string The address, or `nil` if the connection is not established.

---
-- The address of the other end of a connection, e.g. where a player that a listener accepted connects from.
-- @function [parent=#net] remoteAddress
-- @param #number id
-- @return #string The address, or `nil` if the connection is not established.

return nil

#include "sockets.hpp"

#include <atomic>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <thread>

#include <asio.hpp>

namespace Net
{
    using asio::ip::tcp;

    namespace
    {
        struct Shared
        {
            std::mutex mMutex;
            std::condition_variable mArrived; // an event was queued (for waitForEvents)
            std::vector<SocketEvent> mEvents;
            std::map<std::uint32_t, std::string> mLocalAddress;
            std::map<std::uint32_t, std::string> mRemoteAddress;

            void push(SocketEvent event)
            {
                {
                    const std::lock_guard lock(mMutex);
                    mEvents.push_back(std::move(event));
                }
                mArrived.notify_all();
            }
        };

        // Lives on the io thread.
        class Connection : public std::enable_shared_from_this<Connection>
        {
        public:
            Connection(asio::io_context& io, std::uint32_t id, std::shared_ptr<Shared> shared)
                : mResolver(io)
                , mSocket(io)
                , mId(id)
                , mShared(std::move(shared))
            {
            }

            // Outgoing: resolve, connect, then read.
            void connect(const std::string& host, std::uint16_t port)
            {
                mResolver.async_resolve(host, std::to_string(port),
                    [self = shared_from_this()](const asio::error_code& ec, tcp::resolver::results_type r) {
                        if (ec)
                            return self->fail(ec);
                        asio::async_connect(
                            self->mSocket, r, [self](const asio::error_code& connectEc, const tcp::endpoint&) {
                                if (connectEc)
                                    return self->fail(connectEc);
                                self->opened();
                                self->mShared->push({ SocketEvent::Type::Connected, self->mId, 0, {} });
                                self->read();
                            });
                    });
            }

            // Incoming: the socket is already connected.
            tcp::socket& socket() { return mSocket; }
            void startAccepted(std::uint32_t listener)
            {
                opened();
                mShared->push({ SocketEvent::Type::Accepted, mId, listener, {} });
                read();
            }

            void write(std::string data)
            {
                if (mClosed)
                    return;
                mPending += data.size();
                if (mPending > Sockets::sMaxPendingOutput)
                    return fail(asio::error::no_buffer_space); // the peer is not keeping up
                const bool idle = mOut.empty();
                mOut.push_back(std::move(data));
                if (idle)
                    doWrite();
            }

            void setFarewell(std::string data) { mFarewell = std::move(data); }

            // Best effort, never blocks: one non-blocking write of the farewell line, only if nothing else is in
            // flight.
            void sendFarewell()
            {
                if (mClosed || mFarewell.empty() || !mOut.empty() || !mSocket.is_open())
                    return;
                asio::error_code ec;
                mSocket.non_blocking(true, ec);
                if (!ec)
                    mSocket.write_some(asio::buffer(mFarewell), ec);
            }

            // Closes without reporting.
            void close()
            {
                mClosed = true;
                asio::error_code ignored;
                mResolver.cancel();
                mSocket.close(ignored);
                const std::lock_guard lock(mShared->mMutex);
                mShared->mLocalAddress.erase(mId);
                mShared->mRemoteAddress.erase(mId);
            }

        private:
            void opened()
            {
                asio::error_code ec;
                mSocket.set_option(tcp::no_delay(true), ec);
                const tcp::endpoint local = mSocket.local_endpoint(ec);
                if (!ec)
                {
                    const std::lock_guard lock(mShared->mMutex);
                    mShared->mLocalAddress[mId] = local.address().to_string();
                }
                const tcp::endpoint remote = mSocket.remote_endpoint(ec);
                if (!ec)
                {
                    const std::lock_guard lock(mShared->mMutex);
                    mShared->mRemoteAddress[mId] = remote.address().to_string();
                }
            }

            void read()
            {
                asio::async_read_until(mSocket, asio::dynamic_buffer(mIn, Sockets::sMaxLineSize), '\n',
                    [self = shared_from_this()](const asio::error_code& ec, std::size_t n) {
                        if (ec)
                        {
                            // a final line without a terminator (e.g. an HTTP body) still counts
                            if (ec == asio::error::eof && !self->mIn.empty() && !self->mClosed)
                                self->mShared->push({ SocketEvent::Type::Line, self->mId, 0, std::move(self->mIn) });
                            return self->fail(ec);
                        }
                        std::size_t len = n - 1;
                        if (len > 0 && self->mIn[len - 1] == '\r')
                            --len;
                        self->mShared->push({ SocketEvent::Type::Line, self->mId, 0, self->mIn.substr(0, len) });
                        self->mIn.erase(0, n);
                        self->read();
                    });
            }

            void doWrite()
            {
                asio::async_write(mSocket, asio::buffer(mOut.front()),
                    [self = shared_from_this()](const asio::error_code& ec, std::size_t n) {
                        if (ec)
                            return self->fail(ec);
                        self->mPending -= n;
                        self->mOut.pop_front();
                        if (!self->mOut.empty())
                            self->doWrite();
                    });
            }

            void fail(const asio::error_code& ec)
            {
                if (mClosed)
                    return;
                close();
                mShared->push({ SocketEvent::Type::Closed, mId, 0, ec.message() });
            }

            tcp::resolver mResolver;
            tcp::socket mSocket;
            std::uint32_t mId;
            std::shared_ptr<Shared> mShared;
            std::string mIn;
            std::deque<std::string> mOut;
            std::string mFarewell; // (with its line terminator)
            std::size_t mPending = 0;
            bool mClosed = false;
        };

        struct Listener
        {
            explicit Listener(asio::io_context& io)
                : mAcceptor(io)
            {
            }
            tcp::acceptor mAcceptor;
        };
    }

    class Sockets::Impl
    {
    public:
        Impl()
            : mShared(std::make_shared<Shared>())
        {
        }

        ~Impl() { stopThread(true); }

        std::uint32_t connect(const std::string& host, std::uint16_t port)
        {
            if (mLive.size() >= sMaxSockets)
                return 0;
            startThread();
            const std::uint32_t id = mNextId.fetch_add(1);
            mLive.insert(id);
            asio::post(mIo, [this, id, host, port] {
                auto c = std::make_shared<Connection>(mIo, id, mShared);
                mConnections[id] = c;
                c->connect(host, port);
            });
            return id;
        }

        std::uint32_t listen(std::uint16_t port, std::string& error)
        {
            if (mLive.size() >= sMaxSockets)
            {
                error = "too many sockets";
                return 0;
            }
            auto listener = std::make_shared<Listener>(mIo);
            asio::error_code ec;
            const tcp::endpoint endpoint(tcp::v4(), port);
            listener->mAcceptor.open(endpoint.protocol(), ec);
            if (!ec)
                listener->mAcceptor.set_option(tcp::acceptor::reuse_address(true), ec);
            if (!ec)
                listener->mAcceptor.bind(endpoint, ec);
            if (!ec)
                listener->mAcceptor.listen(asio::socket_base::max_listen_connections, ec);
            if (ec)
            {
                error = ec.message();
                return 0;
            }
            startThread();
            const std::uint32_t id = mNextId.fetch_add(1);
            mLive.insert(id);
            asio::post(mIo, [this, id, listener] {
                mListeners[id] = listener;
                accept(id, listener);
            });
            return id;
        }

        bool send(std::uint32_t id, std::string_view data, bool raw)
        {
            if (!mLive.contains(id) || data.size() > sMaxLineSize)
                return false;
            std::string out(data);
            if (!raw)
            {
                if (data.find_first_of("\r\n") != std::string_view::npos)
                    return false;
                out.push_back('\n');
            }
            asio::post(mIo, [this, id, out = std::move(out)]() mutable {
                const auto it = mConnections.find(id);
                if (it != mConnections.end())
                    it->second->write(std::move(out));
            });
            return true;
        }

        void close(std::uint32_t id)
        {
            if (!mLive.erase(id))
                return;
            mIgnored.insert(id);
            asio::post(mIo, [this, id] {
                if (const auto it = mConnections.find(id); it != mConnections.end())
                {
                    it->second->close();
                    mConnections.erase(it);
                }
                if (const auto it = mListeners.find(id); it != mListeners.end())
                {
                    asio::error_code ignored;
                    it->second->mAcceptor.close(ignored);
                    mListeners.erase(it);
                }
            });
        }

        void setFarewell(std::uint32_t id, std::string_view data)
        {
            if (!mLive.count(id) || data.find('\n') != std::string_view::npos || data.size() > 4096)
                return;
            std::string line(data);
            line += '\n';
            asio::post(mIo, [this, id, line = std::move(line)]() mutable {
                if (const auto it = mConnections.find(id); it != mConnections.end())
                    it->second->setFarewell(std::move(line));
            });
        }

        void closeAll()
        {
            stopThread(false);
            mLive.clear();
            mIgnored.clear();
            const std::lock_guard lock(mShared->mMutex);
            mShared->mEvents.clear();
            mShared->mLocalAddress.clear();
            mShared->mRemoteAddress.clear();
        }

        std::string localAddress(std::uint32_t id) const
        {
            const std::lock_guard lock(mShared->mMutex);
            const auto it = mShared->mLocalAddress.find(id);
            return it == mShared->mLocalAddress.end() ? std::string() : it->second;
        }

        std::string remoteAddress(std::uint32_t id) const
        {
            const std::lock_guard lock(mShared->mMutex);
            const auto it = mShared->mRemoteAddress.find(id);
            return it == mShared->mRemoteAddress.end() ? std::string() : it->second;
        }

        bool waitForEvents(std::chrono::milliseconds timeout)
        {
            std::unique_lock lock(mShared->mMutex);
            return mShared->mArrived.wait_for(lock, timeout, [this] { return !mShared->mEvents.empty(); });
        }

        std::vector<SocketEvent> poll()
        {
            std::vector<SocketEvent> raw;
            {
                const std::lock_guard lock(mShared->mMutex);
                raw.swap(mShared->mEvents);
            }
            std::vector<SocketEvent> result;
            for (SocketEvent& e : raw)
            {
                if (mIgnored.contains(e.mId))
                {
                    // events that were already queued when a script closed the connection
                    if (e.mType == SocketEvent::Type::Accepted)
                        mLive.erase(e.mId);
                    continue;
                }
                if (e.mType == SocketEvent::Type::Accepted)
                    mLive.insert(e.mId);
                else if (e.mType == SocketEvent::Type::Closed)
                    mLive.erase(e.mId); // the id is dead; scripts need not close it
                result.push_back(std::move(e));
            }
            return result;
        }

    private:
        void startThread()
        {
            if (mThread.joinable())
                return;
            mWork.emplace(asio::make_work_guard(mIo));
            mThread = std::thread([this] { mIo.run(); });
        }

        void stopThread(bool farewell)
        {
            if (!mThread.joinable())
                return;
            asio::post(mIo, [this, farewell] {
                for (auto& [id, l] : mListeners)
                {
                    asio::error_code ignored;
                    l->mAcceptor.close(ignored);
                }
                for (auto& [id, c] : mConnections)
                {
                    if (farewell)
                        c->sendFarewell();
                    c->close();
                }
                mListeners.clear();
                mConnections.clear();
            });
            mWork.reset();
            mThread.join();
            mIo.restart();
        }

        void accept(std::uint32_t listenerId, std::shared_ptr<Listener> listener)
        {
            const std::uint32_t id = mNextId.fetch_add(1);
            auto connection = std::make_shared<Connection>(mIo, id, mShared);
            listener->mAcceptor.async_accept(
                connection->socket(), [this, listenerId, listener, connection, id](const asio::error_code& ec) {
                    if (ec)
                        return; // listener closed
                    if (mConnections.size() >= sMaxSockets)
                        connection->close();
                    else
                    {
                        mConnections[id] = connection;
                        connection->startAccepted(listenerId);
                    }
                    accept(listenerId, listener);
                });
        }

        asio::io_context mIo;
        std::optional<asio::executor_work_guard<asio::io_context::executor_type>> mWork;
        std::thread mThread;
        std::shared_ptr<Shared> mShared;
        std::atomic<std::uint32_t> mNextId{ 1 };
        // main thread
        std::set<std::uint32_t> mLive;
        std::set<std::uint32_t> mIgnored;
        // io thread
        std::map<std::uint32_t, std::shared_ptr<Connection>> mConnections;
        std::map<std::uint32_t, std::shared_ptr<Listener>> mListeners;
    };

    Sockets::Sockets()
        : mImpl(std::make_unique<Impl>())
    {
    }

    Sockets::~Sockets() = default;

    std::uint32_t Sockets::connect(const std::string& host, std::uint16_t port)
    {
        return mImpl->connect(host, port);
    }

    std::uint32_t Sockets::listen(std::uint16_t port, std::string& error)
    {
        return mImpl->listen(port, error);
    }

    bool Sockets::send(std::uint32_t id, std::string_view data, bool raw)
    {
        return mImpl->send(id, data, raw);
    }

    void Sockets::close(std::uint32_t id)
    {
        mImpl->close(id);
    }

    void Sockets::setFarewell(std::uint32_t id, std::string_view data)
    {
        mImpl->setFarewell(id, data);
    }

    void Sockets::closeAll()
    {
        mImpl->closeAll();
    }

    std::string Sockets::localAddress(std::uint32_t id) const
    {
        return mImpl->localAddress(id);
    }

    std::string Sockets::remoteAddress(std::uint32_t id) const
    {
        return mImpl->remoteAddress(id);
    }

    std::vector<SocketEvent> Sockets::poll()
    {
        return mImpl->poll();
    }

    bool Sockets::waitForEvents(std::chrono::milliseconds timeout)
    {
        return mImpl->waitForEvents(timeout);
    }
}

#include <components/net/sockets.hpp>

#include <chrono>
#include <thread>

#include <gtest/gtest.h>

namespace
{
    using namespace testing;
    using Type = Net::SocketEvent::Type;

    std::vector<Net::SocketEvent> waitFor(Net::Sockets& s, std::size_t count)
    {
        std::vector<Net::SocketEvent> result;
        for (int i = 0; i < 500 && result.size() < count; ++i)
        {
            for (Net::SocketEvent& e : s.poll())
                result.push_back(std::move(e));
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        return result;
    }

    std::uint32_t listenOnFreePort(Net::Sockets& s, std::uint16_t& port)
    {
        for (port = 47400; port < 47600; ++port)
        {
            std::string error;
            if (const std::uint32_t id = s.listen(port, error))
                return id;
        }
        return 0;
    }

    TEST(NetSocketsTest, shouldTalkToItselfOverLoopback)
    {
        Net::Sockets s;
        std::uint16_t port = 0;
        const std::uint32_t listener = listenOnFreePort(s, port);
        ASSERT_NE(listener, 0u);

        const std::uint32_t client = s.connect("127.0.0.1", port);
        ASSERT_NE(client, 0u);
        auto events = waitFor(s, 2);
        ASSERT_EQ(events.size(), 2u);
        std::uint32_t accepted = 0;
        bool connected = false;
        for (const auto& e : events)
        {
            if (e.mType == Type::Accepted)
            {
                EXPECT_EQ(e.mListener, listener);
                accepted = e.mId;
            }
            else if (e.mType == Type::Connected)
            {
                EXPECT_EQ(e.mId, client);
                connected = true;
            }
        }
        EXPECT_TRUE(connected);
        ASSERT_NE(accepted, 0u);
        EXPECT_EQ(s.localAddress(client), "127.0.0.1");
        EXPECT_EQ(s.remoteAddress(client), "127.0.0.1");
        EXPECT_EQ(s.remoteAddress(accepted), "127.0.0.1");

        EXPECT_TRUE(s.send(client, "hello", false));
        EXPECT_FALSE(s.send(client, "bad\nline", false));
        events = waitFor(s, 1);
        ASSERT_EQ(events.size(), 1u);
        EXPECT_EQ(events[0].mType, Type::Line);
        EXPECT_EQ(events[0].mId, accepted);
        EXPECT_EQ(events[0].mData, "hello");

        // raw data, with a final line that has no terminator, delivered when the peer closes
        EXPECT_TRUE(s.send(accepted, "HTTP/1.0 200 OK\r\n\r\n1.2.3.4", true));
        events = waitFor(s, 2);
        ASSERT_EQ(events.size(), 2u);
        EXPECT_EQ(events[0].mData, "HTTP/1.0 200 OK");
        EXPECT_EQ(events[1].mData, "");
        s.close(accepted);
        events = waitFor(s, 1);
        ASSERT_GE(events.size(), 1u);
        const Net::SocketEvent& tail = events.back();
        EXPECT_TRUE(tail.mType == Type::Line || tail.mType == Type::Closed);
    }

    TEST(NetSocketsTest, shouldReportRemoteCloseButNotLocalClose)
    {
        Net::Sockets s;
        std::uint16_t port = 0;
        ASSERT_NE(listenOnFreePort(s, port), 0u);
        const std::uint32_t client = s.connect("127.0.0.1", port);
        auto events = waitFor(s, 2);
        ASSERT_EQ(events.size(), 2u);
        std::uint32_t accepted = 0;
        for (const auto& e : events)
            if (e.mType == Type::Accepted)
                accepted = e.mId;
        s.close(accepted); // the server side hangs up
        events = waitFor(s, 1);
        ASSERT_EQ(events.size(), 1u);
        EXPECT_EQ(events[0].mType, Type::Closed);
        EXPECT_EQ(events[0].mId, client);
        s.close(client);
        EXPECT_FALSE(s.send(client, "x", false));
        for (int i = 0; i < 20; ++i)
        {
            EXPECT_TRUE(s.poll().empty());
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }

    TEST(NetSocketsTest, shouldReportListenAndConnectFailures)
    {
        Net::Sockets a, b;
        std::uint16_t port = 0;
        ASSERT_NE(listenOnFreePort(a, port), 0u);
        std::string error;
        EXPECT_EQ(b.listen(port, error), 0u);
        EXPECT_FALSE(error.empty());

        Net::Sockets c;
        const std::uint32_t id = c.connect("127.0.0.1", 1); // nothing listens there
        ASSERT_NE(id, 0u);
        const auto events = waitFor(c, 1);
        ASSERT_EQ(events.size(), 1u);
        EXPECT_EQ(events[0].mType, Type::Closed);
        EXPECT_EQ(events[0].mId, id);
    }
}

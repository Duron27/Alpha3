// The server window: who is online (with latency, address and location), buttons to kick, ban and mute the selected
// player, the sky (weather and time of day), announcements, banned names, the log and a command line.
// Everything runs on the GUI thread; the server is driven from a timer.
#include "gui.hpp"

#include <QApplication>
#include <QClipboard>
#include <QCloseEvent>
#include <QComboBox>
#include <QElapsedTimer>
#include <QFontDatabase>
#include <QGridLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QMainWindow>
#include <QMessageBox>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QStatusBar>
#include <QTableWidget>
#include <QTime>
#include <QTimeEdit>
#include <QTimer>
#include <QVBoxLayout>

namespace
{
    using MpServer::PlayerInfo;
    using MpServer::ServerCore;
    using MpServer::Snapshot;

    const char* const sWeathers[]
        = { "clear", "cloudy", "overcast", "foggy", "rain", "thunder", "snow", "ash", "blight", "blizzard" };

    QString flagsOf(const PlayerInfo& p)
    {
        QStringList flags;
        if (p.mHost)
            flags << "host";
        if (p.mAdmin)
            flags << "admin";
        if (p.mMuted)
            flags << "muted";
        if (p.mAway)
            flags << "lost connection";
        if (p.mBot)
            flags << "test player";
        return flags.join(", ");
    }

    // No Q_OBJECT: nothing here declares signals or slots, connections go to lambdas.
    class MainWindow : public QMainWindow
    {
    public:
        explicit MainWindow(ServerCore& core)
            : mCore(core)
        {
            setWindowTitle("OpenMW Multiplayer Server");
            resize(1100, 760);
            build();
            mUptime.start();
        }

        void appendLog(const QString& text) { mLog->appendPlainText(text); }

        void refresh()
        {
            const Snapshot snap = mCore.snapshot();
            if (!snap.mValid)
                return;
            mSnap = snap;
            fillPlayers();
            fillBans();
            fillWorld();
            updateButtons();
            const qint64 s = mUptime.elapsed() / 1000;
            int online = 0;
            for (const PlayerInfo& p : snap.mPlayers)
                online += p.mBot ? 0 : 1;
            mStatus->setText(QString("Port %1  |  %2  |  %3/%4 players  |  up %5:%6:%7")
                                 .arg(mCore.options().mPort)
                                 .arg(snap.mPasswordProtected ? "password protected" : "no password")
                                 .arg(online)
                                 .arg(snap.mMaxPlayers)
                                 .arg(s / 3600)
                                 .arg((s / 60) % 60, 2, 10, QChar('0'))
                                 .arg(s % 60, 2, 10, QChar('0')));
        }

        int playersOnline() const
        {
            int n = 0;
            for (const PlayerInfo& p : mSnap.mPlayers)
                n += p.mBot ? 0 : 1;
            return n;
        }

        // Tests drive the window the way a person would: MP_SERVER_TEST_STEPS="select Name;kick;ban;mute;unban Name;
        // weather rain;time 23:30;announce text;copy". Questions are answered with yes.
        void testStep(const QString& step)
        {
            mAutoConfirm = true;
            const QString verb = step.section(' ', 0, 0);
            const QString arg = step.section(' ', 1).trimmed();
            if (verb == "select")
            {
                for (int row = 0; row < mTable->rowCount(); ++row)
                    if (mTable->item(row, 0)->text() == arg)
                        mTable->selectRow(row);
            }
            else if (verb == "kick")
                mKick->click();
            else if (verb == "ban")
                mBan->click();
            else if (verb == "mute")
                mMute->click();
            else if (verb == "copy")
                mCopy->click();
            else if (verb == "unban")
            {
                for (int i = 0; i < mBans->count(); ++i)
                    if (mBans->item(i)->text() == arg.toLower())
                        mBans->setCurrentRow(i);
                mUnban->click();
            }
            else if (verb == "weather")
            {
                mWeather->setCurrentIndex(mWeather->findData(arg));
                mApplyWeather->click();
            }
            else if (verb == "time")
            {
                mTime->setTime(QTime::fromString(arg, "HH:mm"));
                mApplyTime->click();
            }
            else if (verb == "announce")
            {
                mAnnounce->setText(arg);
                mSend->click();
            }
            else if (verb == "command")
            {
                mCommand->setText(arg);
                mRun->click();
            }
        }

    protected:
        void closeEvent(QCloseEvent* event) override
        {
            const int n = playersOnline();
            if (n > 0
                && !confirm("Stop the server?",
                    QString("%1 player%2 connected. Stop the server and disconnect everybody?")
                        .arg(n)
                        .arg(n == 1 ? " is" : "s are")))
            {
                event->ignore();
                return;
            }
            event->accept();
        }

    private:
        const PlayerInfo* selected() const
        {
            const int row = mTable->currentRow();
            if (row < 0 || row >= static_cast<int>(mSnap.mPlayers.size()))
                return nullptr;
            const auto id = mTable->item(row, 0)->data(Qt::UserRole).toUInt();
            for (const PlayerInfo& p : mSnap.mPlayers)
                if (p.mId == id)
                    return &p;
            return nullptr;
        }

        bool confirm(const QString& title, const QString& text)
        {
            return mAutoConfirm || QMessageBox::question(this, title, text) == QMessageBox::Yes;
        }

        void run(const QString& command)
        {
            appendLog("> " + command);
            const std::string answer = mCore.console(command.toStdString());
            if (!answer.empty())
                appendLog(QString::fromStdString(answer));
            refresh();
        }

        static QPushButton* button(const QString& text, const QString& tip)
        {
            auto* b = new QPushButton(text);
            b->setToolTip(tip);
            return b;
        }

        void build()
        {
            auto* central = new QWidget;
            auto* grid = new QGridLayout(central);

            // ---- players
            auto* playersBox = new QGroupBox("Players");
            auto* playersLayout = new QVBoxLayout(playersBox);
            mTable = new QTableWidget(0, 5);
            mTable->setHorizontalHeaderLabels({ "Name", "Ping", "Address", "Location", "Status" });
            mTable->setSelectionBehavior(QAbstractItemView::SelectRows);
            mTable->setSelectionMode(QAbstractItemView::SingleSelection);
            mTable->setEditTriggers(QAbstractItemView::NoEditTriggers);
            mTable->setAlternatingRowColors(true);
            mTable->verticalHeader()->hide();
            mTable->horizontalHeader()->setStretchLastSection(false);
            mTable->horizontalHeader()->setSectionResizeMode(0, QHeaderView::ResizeToContents);
            mTable->horizontalHeader()->setSectionResizeMode(1, QHeaderView::ResizeToContents);
            mTable->horizontalHeader()->setSectionResizeMode(2, QHeaderView::ResizeToContents);
            mTable->horizontalHeader()->setSectionResizeMode(3, QHeaderView::Stretch);
            mTable->horizontalHeader()->setSectionResizeMode(4, QHeaderView::ResizeToContents);
            playersLayout->addWidget(mTable);
            auto* playerButtons = new QHBoxLayout;
            mKick = button("Kick", "Disconnect the selected player (they can join again)");
            mBan = button("Ban", "Disconnect the selected player and refuse their name until the server restarts");
            mMute = button("Mute", "Stop or allow the selected player's chat");
            mCopy = button("Copy address", "Copy the selected player's address to the clipboard");
            for (QPushButton* b : { mKick, mBan, mMute, mCopy })
                playerButtons->addWidget(b);
            playerButtons->addStretch();
            playersLayout->addLayout(playerButtons);
            grid->addWidget(playersBox, 0, 0, 3, 1);

            QObject::connect(mTable, &QTableWidget::itemSelectionChanged, this, [this] { updateButtons(); });
            QObject::connect(mKick, &QPushButton::clicked, this, [this] {
                if (const PlayerInfo* p = selected())
                    run("kick " + QString::fromStdString(p->mName));
            });
            QObject::connect(mBan, &QPushButton::clicked, this, [this] {
                const PlayerInfo* p = selected();
                if (!p)
                    return;
                const QString name = QString::fromStdString(p->mName);
                if (confirm("Ban " + name + "?",
                        name + " will be disconnected and cannot join under that name until the server restarts."))
                    run("ban " + name);
            });
            QObject::connect(mMute, &QPushButton::clicked, this, [this] {
                if (const PlayerInfo* p = selected())
                    run((p->mMuted ? "unmute " : "mute ") + QString::fromStdString(p->mName));
            });
            QObject::connect(mCopy, &QPushButton::clicked, this, [this] {
                if (const PlayerInfo* p = selected())
                    QApplication::clipboard()->setText(QString::fromStdString(p->mAddress));
            });

            // ---- the sky
            auto* worldBox = new QGroupBox("Sky");
            auto* worldLayout = new QGridLayout(worldBox);
            mSky = new QLabel("-");
            worldLayout->addWidget(mSky, 0, 0, 1, 3);
            worldLayout->addWidget(new QLabel("Weather"), 1, 0);
            mWeather = new QComboBox;
            mWeather->addItem("Changes by itself", "cycle");
            for (const char* w : sWeathers)
                mWeather->addItem(w, w);
            worldLayout->addWidget(mWeather, 1, 1);
            auto* applyWeather = mApplyWeather
                = button("Set", "Fix the weather for everybody, or let it change by itself again");
            worldLayout->addWidget(applyWeather, 1, 2);
            worldLayout->addWidget(new QLabel("Time of day"), 2, 0);
            mTime = new QTimeEdit(QTime(9, 0));
            mTime->setDisplayFormat("HH:mm");
            worldLayout->addWidget(mTime, 2, 1);
            auto* applyTime = mApplyTime
                = button("Set", "Set the time of day for everybody (the date is never synced)");
            worldLayout->addWidget(applyTime, 2, 2);
            QObject::connect(applyWeather, &QPushButton::clicked, this,
                [this] { run("weather " + mWeather->currentData().toString()); });
            QObject::connect(applyTime, &QPushButton::clicked, this, [this] {
                const QTime t = mTime->time();
                run(QString("time %1").arg(t.hour() + t.minute() / 60.0, 0, 'f', 3));
            });
            grid->addWidget(worldBox, 0, 1);

            // ---- announcements
            auto* announceBox = new QGroupBox("Announcement");
            auto* announceLayout = new QHBoxLayout(announceBox);
            mAnnounce = new QLineEdit;
            mAnnounce->setPlaceholderText("A message for everybody");
            auto* send = mSend = button("Send", "Show the message to everybody online");
            announceLayout->addWidget(mAnnounce);
            announceLayout->addWidget(send);
            auto sendAnnouncement = [this] {
                const QString text = mAnnounce->text().trimmed();
                if (text.isEmpty())
                    return;
                mAnnounce->clear();
                run("announce " + text);
            };
            QObject::connect(send, &QPushButton::clicked, this, sendAnnouncement);
            QObject::connect(mAnnounce, &QLineEdit::returnPressed, this, sendAnnouncement);
            grid->addWidget(announceBox, 1, 1);

            // ---- bans
            auto* bansBox = new QGroupBox("Banned names");
            auto* bansLayout = new QVBoxLayout(bansBox);
            mBans = new QListWidget;
            mUnban = button("Unban", "Let the selected name join again");
            bansLayout->addWidget(mBans);
            bansLayout->addWidget(mUnban);
            QObject::connect(mBans, &QListWidget::itemSelectionChanged, this, [this] { updateButtons(); });
            QObject::connect(mUnban, &QPushButton::clicked, this, [this] {
                if (auto* item = mBans->currentItem())
                    run("unban " + item->text());
            });
            grid->addWidget(bansBox, 2, 1);

            // ---- log and command line
            auto* logBox = new QGroupBox("Log and commands");
            auto* logLayout = new QVBoxLayout(logBox);
            mLog = new QPlainTextEdit;
            mLog->setReadOnly(true);
            mLog->setMaximumBlockCount(5000);
            mLog->setFont(QFontDatabase::systemFont(QFontDatabase::FixedFont));
            logLayout->addWidget(mLog);
            auto* commandRow = new QHBoxLayout;
            mCommand = new QLineEdit;
            mCommand->setPlaceholderText("Type a command, for example: players  or  help");
            auto* runButton = mRun = button("Run", "Run the command");
            auto* helpButton = button("Help", "List every command");
            commandRow->addWidget(mCommand);
            commandRow->addWidget(runButton);
            commandRow->addWidget(helpButton);
            logLayout->addLayout(commandRow);
            auto runCommand = [this] {
                const QString text = mCommand->text().trimmed();
                if (text.isEmpty())
                    return;
                mCommand->clear();
                run(text);
            };
            QObject::connect(runButton, &QPushButton::clicked, this, runCommand);
            QObject::connect(mCommand, &QLineEdit::returnPressed, this, runCommand);
            QObject::connect(helpButton, &QPushButton::clicked, this, [this] { run("help"); });
            grid->addWidget(logBox, 3, 0, 1, 2);

            grid->setColumnStretch(0, 3);
            grid->setColumnStretch(1, 2);
            grid->setRowStretch(0, 1);
            grid->setRowStretch(1, 0);
            grid->setRowStretch(2, 1);
            grid->setRowStretch(3, 2);
            setCentralWidget(central);

            mStatus = new QLabel;
            statusBar()->addPermanentWidget(mStatus);
            updateButtons();
        }

        void fillPlayers()
        {
            const PlayerInfo* before = selected();
            const std::uint32_t keep = before ? before->mId : 0;
            const auto& players = mSnap.mPlayers;
            mTable->setRowCount(static_cast<int>(players.size()));
            int select = -1;
            for (int row = 0; row < static_cast<int>(players.size()); ++row)
            {
                const PlayerInfo& p = players[row];
                const QString ping = p.mAway || p.mBot ? "-" : p.mPing < 0 ? "..." : QString("%1 ms").arg(p.mPing);
                const QString address = p.mBot ? "(in the server)" : QString::fromStdString(p.mAddress);
                const QStringList cells = { QString::fromStdString(p.mName), ping, address,
                    QString::fromStdString(p.mLocation), flagsOf(p) };
                for (int col = 0; col < cells.size(); ++col)
                {
                    auto* item = new QTableWidgetItem(cells[col]);
                    if (col == 0)
                        item->setData(Qt::UserRole, p.mId);
                    mTable->setItem(row, col, item);
                }
                if (p.mId == keep)
                    select = row;
            }
            if (select >= 0)
                mTable->selectRow(select);
        }

        void fillBans()
        {
            const QString keep = mBans->currentItem() ? mBans->currentItem()->text() : QString();
            QStringList names;
            for (const std::string& b : mSnap.mBans)
                names << QString::fromStdString(b);
            QStringList current;
            for (int i = 0; i < mBans->count(); ++i)
                current << mBans->item(i)->text();
            if (names == current)
                return;
            mBans->clear();
            mBans->addItems(names);
            for (int i = 0; i < mBans->count(); ++i)
                if (mBans->item(i)->text() == keep)
                    mBans->setCurrentRow(i);
        }

        void fillWorld()
        {
            const int minutes = static_cast<int>(mSnap.mHour * 60) % (24 * 60);
            mSky->setText(QString("Now: %1:%2, weather %3 (%4)")
                              .arg(minutes / 60, 2, 10, QChar('0'))
                              .arg(minutes % 60, 2, 10, QChar('0'))
                              .arg(QString::fromStdString(mSnap.mWeather))
                              .arg(mSnap.mWeatherFixed ? "fixed" : "changes by itself"));
            if (!mWorldShown)
            {
                // start the controls where the server is; after that they are the operator's to change
                mWorldShown = true;
                mTime->setTime(QTime(minutes / 60, minutes % 60));
                const int index
                    = mWeather->findData(mSnap.mWeatherFixed ? QString::fromStdString(mSnap.mWeather) : "cycle");
                if (index >= 0)
                    mWeather->setCurrentIndex(index);
            }
        }

        void updateButtons()
        {
            const PlayerInfo* p = selected();
            mKick->setEnabled(p != nullptr);
            mBan->setEnabled(p != nullptr && !p->mBot);
            mMute->setEnabled(p != nullptr);
            mMute->setText(p && p->mMuted ? "Unmute" : "Mute");
            mCopy->setEnabled(p != nullptr && !p->mBot && !p->mAddress.empty());
            mUnban->setEnabled(mBans->currentItem() != nullptr);
        }

        ServerCore& mCore;
        Snapshot mSnap;
        QElapsedTimer mUptime;
        bool mWorldShown = false;
        bool mAutoConfirm = false;
        QTableWidget* mTable = nullptr;
        QPushButton *mKick = nullptr, *mBan = nullptr, *mMute = nullptr, *mCopy = nullptr, *mUnban = nullptr;
        QPushButton *mApplyWeather = nullptr, *mApplyTime = nullptr, *mSend = nullptr, *mRun = nullptr;
        QLabel *mSky = nullptr, *mStatus = nullptr;
        QComboBox* mWeather = nullptr;
        QTimeEdit* mTime = nullptr;
        QLineEdit *mAnnounce = nullptr, *mCommand = nullptr;
        QListWidget* mBans = nullptr;
        QPlainTextEdit* mLog = nullptr;
    };
}

#ifdef _WIN32
#define MPSERVER_EXPORT extern "C" __declspec(dllexport)
#else
#define MPSERVER_EXPORT extern "C" __attribute__((visibility("default")))
#endif

MPSERVER_EXPORT int mpserverRunWindow(ServerCore& core, int argc, char** argv, std::atomic<bool>& stopFlag)
{
    QApplication app(argc, argv);
    MainWindow window(core);
    core.setLogSink([&window](const std::string& line) { window.appendLog(QString::fromStdString(line)); });
    const std::string error = core.start();
    if (!error.empty())
    {
        QMessageBox::critical(nullptr, "The server could not start", QString::fromStdString(error));
        return 1;
    }

    QTimer tick;
    tick.setInterval(20);
    QObject::connect(&tick, &QTimer::timeout, &app, [&] {
        if (!core.tick() || stopFlag)
            app.quit();
    });
    tick.start();

    QTimer refresh;
    refresh.setInterval(1000);
    QObject::connect(&refresh, &QTimer::timeout, &app, [&] { window.refresh(); });
    refresh.start();

    window.show();
    window.refresh();

    // For the tests: MP_SERVER_TEST_STEPS runs button presses one after the other (see MainWindow::testStep),
    // MP_SERVER_SCREENSHOT=<file.png> then saves a picture of the window; either one ends with the program quitting.
    const QByteArray steps = qgetenv("MP_SERVER_TEST_STEPS");
    const QByteArray shot = qgetenv("MP_SERVER_SCREENSHOT");
    QTimer script;
    QStringList queue = QString::fromLocal8Bit(steps).split(';', Qt::SkipEmptyParts);
    if (!queue.isEmpty() || !shot.isEmpty())
    {
        script.setInterval(500);
        QObject::connect(&script, &QTimer::timeout, &app, [&] {
            window.refresh();
            if (!queue.isEmpty())
            {
                window.testStep(queue.takeFirst().trimmed());
                return;
            }
            if (!shot.isEmpty())
                window.grab().save(QString::fromLocal8Bit(shot));
            app.quit();
        });
        QTimer::singleShot(2500, &script, [&] { script.start(); }); // (give the test clients time to join first)
    }
    const int code = app.exec();
    core.setLogSink({});
    core.shutdown();
    return code;
}

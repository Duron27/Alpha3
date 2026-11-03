def edit(p, pairs):
    t=open(p).read()
    for a,b in pairs:
        assert t.count(a)==1,(p,a[:70]); t=t.replace(a,b)
    open(p,'w').write(t)
edit('src/occlusionculling.cpp',[('''    bool occlusionNodeOccluded(osg::CullStack& cullStack, const osg::Node& node)
    {
        return occlusionEnabled() && occlusion().nodeOccluded(cullStack, node);
    }

    bool occlusionBoxOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box)
    {
        return occlusionEnabled() && occlusion().boxOccluded(cullStack, box);
    }

    void occlusionDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (occlusionEnabled())''','''    namespace
    {
        std::atomic<bool> sPaused{ false };

        bool occlusionActive()
        {
            return occlusionEnabled() && !sPaused.load(std::memory_order_relaxed);
        }
    }

    bool occlusionOn()
    {
        return occlusionEnabled();
    }

    void occlusionPause(bool paused)
    {
        sPaused.store(paused, std::memory_order_relaxed);
    }

    bool occlusionNodeOccluded(osg::CullStack& cullStack, const osg::Node& node)
    {
        return occlusionActive() && occlusion().nodeOccluded(cullStack, node);
    }

    bool occlusionBoxOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box)
    {
        return occlusionActive() && occlusion().boxOccluded(cullStack, box);
    }

    void occlusionDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (occlusionActive())''')])
edit('src/grass.cpp',[('''    void grassDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (!experimentalSettings().mGrass)
            return;''','''    namespace
    {
        std::atomic<bool> sGrassPaused{ false };
    }

    bool grassOn()
    {
        return experimentalSettings().mGrass && !sGrassPaused.load(std::memory_order_relaxed);
    }

    void setGrassOn(bool on)
    {
        sGrassPaused.store(!on, std::memory_order_relaxed);
    }

    void grassDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (!grassOn())
            return;''')])
edit('src/hooks.cpp',[('''                omw_frame_update();
            }''','''                omw_frame_update();
                benchmarkFrame();
            }'''),('''                grassDrawableAdded(cullVisitor, drawable, modelView);
            }''','''                grassDrawableAdded(cullVisitor, drawable, modelView);
                benchmarkDrawableAdded(cullVisitor);
            }'''),('''            void extensionsRead(osg::GLExtensions& extensions) override
            {
''','''            void extensionsRead(osg::GLExtensions& extensions) override
            {
                using GetString = const GLubyte* (*)(GLenum);
                if (auto getString = reinterpret_cast<GetString>(osg::getGLExtensionFuncPtr("glGetString")))
                    benchmarkGlInfo(extensions, reinterpret_cast<const char*>(getString(GL_VENDOR)),
                        reinterpret_cast<const char*>(getString(GL_RENDERER)),
                        reinterpret_cast<const char*>(getString(GL_VERSION)));
''')])
edit('src/support.hpp',[('''    class Drawable;''','''    class Drawable;
    class GLExtensions;'''),('''    bool occlusionNodeOccluded(osg::CullStack& cullStack, const osg::Node& node);''','''    bool occlusionNodeOccluded(osg::CullStack& cullStack, const osg::Node& node);
    bool occlusionOn();
    void occlusionPause(bool paused);'''),('''    void grassDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView);''','''    void grassDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView);
    bool grassOn();
    void setGrassOn(bool on);

    // ---- benchmark.cpp (this test build: runs by itself 20 s after the game world shows)
    void benchmarkGlInfo(const osg::GLExtensions& extensions, const char* vendor, const char* renderer, const char* version);
    void benchmarkDrawableAdded(osg::NodeVisitor& cullVisitor);
    void benchmarkFrame();''')])
edit('CMakeLists.txt',[('    src/grass.cpp\n','    src/grass.cpp\n    src/benchmark.cpp\n'),
('add_library(alpha3_support STATIC ${ALPHA3_SUPPORT_SOURCES})\n','''add_library(alpha3_support STATIC ${ALPHA3_SUPPORT_SOURCES})
add_custom_target(alpha3_build_id
    COMMAND ${CMAKE_COMMAND} -DSOURCE=${CMAKE_CURRENT_SOURCE_DIR} -DOUT=${CMAKE_CURRENT_BINARY_DIR}/generated/buildid.hpp
        -P ${CMAKE_CURRENT_SOURCE_DIR}/buildid.cmake
    BYPRODUCTS ${CMAKE_CURRENT_BINARY_DIR}/generated/buildid.hpp)
add_dependencies(alpha3_support alpha3_build_id)
''')])

# benchmark.cpp: no wet ground, F2 menu or forced weather in this commit; starts by itself
edit('src/benchmark.cpp',[
('''namespace Alpha3
{
    namespace
    {
        using Clock''','''namespace Alpha3
{
    // not in this commit
    void wetGroundPause(bool) {}
    float lastRain()
    {
        return std::nan("");
    }
    int forcedWeather()
    {
        return -1;
    }

    namespace
    {
        using Clock'''),
('''                mWetGroundOn = experimentalSettings().mWetGround;''','''                mWetGroundOn = false;'''),
('''                Settings::ShaderManager::get().setValue("a3_testing", "a3_benchmark", false);\n''',''),
('''            void camera(osg::Camera* camera)
            {
                if (camera->getName() != sSceneCamera)
                    return;''','''            void camera(osg::Camera* camera)
            {
                if (camera->getName() != sSceneCamera)
                    return;
                mDrew.store(true, std::memory_order_relaxed);
                if (cameraSeen())
                    return;'''),
('''                if (mResultsShown
                    && Settings::ShaderManager::get().getMode() == Settings::ShaderManager::Mode::Debug)
                    hideResults();
                if (!mRunning && !mRequested && !mStatusUntil)
                    return;
                const Clock::time_point now = Clock::now();''','''                if (mResultsShown
                    && Settings::ShaderManager::get().getMode() == Settings::ShaderManager::Mode::Debug)
                    hideResults();
                const Clock::time_point now = Clock::now();
                // this test build: once, after the game world has been drawn for 20 s in a row (a save loaded)
                if (!mAutoDone && !mRunning)
                {
                    if (!mDrew.exchange(false, std::memory_order_relaxed))
                        mStreakStart.reset();
                    else if (!mStreakStart)
                    {
                        mStreakStart = now;
                        status("Benchmark in 20 s: stand still, look at the spot, do not touch");
                    }
                    else if (now - *mStreakStart > std::chrono::seconds(20))
                    {
                        mAutoDone = true;
                        mRequested = true;
                    }
                }
                if (!mRunning && !mRequested && !mStatusUntil)
                    return;'''),
('''            bool mRequested = false, mRunning = false, mMeasuring = false;''','''            bool mRequested = false, mRunning = false, mMeasuring = false;
            std::atomic<bool> mDrew{ false };
            bool mAutoDone = false;
            std::optional<Clock::time_point> mStreakStart;'''),
('''        Benchmark& b = Benchmark::get();
        if (b.cameraSeen())
            return;
        if (osg::Camera*''','''        Benchmark& b = Benchmark::get();
        if (osg::Camera*'''),
('''    void benchmarkMenu(bool on)
    {
        Benchmark::get().menu(on);
    }

''',''),
])
edit('src/benchmark.cpp',[('''                if (!mAutoDone && !mRunning)
                {
''','''                if (!mAutoDone && !mRunning)
                {
                    if (mStreakStart)
                        holdView(now);
''')])

// The HUD's elements (health, magicka and fatigue bars, weapon, spell and sneak boxes, effects, minimap, ...) moved and
// resized from the touch overlay's edit mode. The launcher draws a box over each element (reported by omwHudLayout) and
// sends where the player put it (omwHudSet); HUD::onFrame calls hudLayoutFrame, which places the elements every frame
// (the HUD moves some of them itself) on the main thread. Kept in Alpha3/config/hudlayout.cfg: one line an element,
// "name left top scale", left and top as fractions of the screen.
//
// iOS: the HUD is kept inside the screen's safe area, so the bars and the minimap in its bottom corners are clear of the
// rounded corners (and the Dynamic Island's side): its main widget is made that much smaller, the same on opposite sides
// so that what is centred (the crosshair) stays where the camera looks. Placements stay fractions of the whole screen.

#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include <MyGUI_RenderManager.h>
#include <MyGUI_TextBox.h>
#include <MyGUI_Widget.h>

#include <components/debug/debuglog.hpp>

#ifdef __ANDROID__
#include <jni.h>
#endif

namespace Alpha3
{
    namespace
    {
        struct Element
        {
            const char* mName;
            // the widgets inside are resized with it; false: the element only moves (its contents are sized by the HUD)
            bool mScales;
        };

        // as named in openmw_hud.layout
        const Element sElements[] = {
            { "HealthFrame", true },
            { "MagickaFrame", true },
            { "FatigueFrame", true },
            { "EnemyHealth", true },
            { "WeapBox", true },
            { "SpellBox", true },
            { "SneakBox", true },
            { "EffectBox", false },
            { "MiniMapBox", true },
            { "CellName", true },
            { "WeaponSpellName", true },
        };

        struct Placement
        {
            float mLeft = 0;
            float mTop = 0;
            float mScale = 1;
        };

        // a widget as the layout made it: its coordinates in its parent, and its font height if it shows text
        struct Original
        {
            MyGUI::Widget* mWidget;
            MyGUI::IntCoord mCoord;
            int mFontHeight;
        };

        struct Captured
        {
            std::vector<Original> mWidgets; // the element first, then what is inside it
            float mAppliedScale = 1;
        };

        std::mutex sMutex;
        bool sLoaded = false;
        std::map<std::string, Placement> sPlacements;
        bool sResetAsked = false;
        bool sSaveAsked = false;
        std::string sReport;
        // the overlay's edit mode asks for the report a few times a second: made only while it does
        std::chrono::steady_clock::time_point sReportWanted;

        // main thread only
        MyGUI::Widget* sMain = nullptr;
        std::map<std::string, Captured> sCaptured;

        std::filesystem::path layoutFile()
        {
            return cacheDirectory().parent_path() / "config" / "hudlayout.cfg";
        }

        void load()
        {
            std::ifstream in(layoutFile());
            std::string line;
            while (std::getline(in, line))
            {
                std::istringstream fields(line);
                std::string name;
                Placement p;
                if (fields >> name >> p.mLeft >> p.mTop >> p.mScale && p.mScale > 0)
                    sPlacements[name] = p;
            }
            if (!sPlacements.empty())
                Log(Debug::Info) << "HUD layout: " << sPlacements.size() << " elements placed (" << layoutFile() << ")";
        }

        void save()
        {
            std::error_code ec;
            std::filesystem::create_directories(layoutFile().parent_path(), ec);
            std::ofstream out(layoutFile());
            for (const auto& [name, p] : sPlacements)
                out << name << ' ' << p.mLeft << ' ' << p.mTop << ' ' << p.mScale << '\n';
        }

        bool isContainer(const MyGUI::Widget* widget)
        {
            const std::string_view type = widget->getTypeName();
            return type == "Widget" || type == "Button";
        }

        void captureInside(MyGUI::Widget* widget, std::vector<Original>& out)
        {
            auto add = [&](MyGUI::Widget* parent) {
                for (size_t i = 0; i < parent->getChildCount(); ++i)
                {
                    MyGUI::Widget* child = parent->getChildAt(i);
                    auto* text = child->castType<MyGUI::TextBox>(false);
                    out.push_back({ child, child->getCoord(), text ? text->getFontHeight() : 0 });
                    if (isContainer(child))
                        captureInside(child, out);
                }
            };
            add(widget);
            MyGUI::Widget* client = widget->getClientWidget();
            if (client != nullptr && client != widget)
                add(client);
        }

        Captured& capture(const std::string& name, MyGUI::Widget* widget, bool scales)
        {
            auto found = sCaptured.find(name);
            if (found != sCaptured.end())
                return found->second;
            Captured& c = sCaptured[name];
            auto* text = widget->castType<MyGUI::TextBox>(false);
            c.mWidgets.push_back({ widget, widget->getCoord(), text ? text->getFontHeight() : 0 });
            if (scales && isContainer(widget))
                captureInside(widget, c.mWidgets);
            return c;
        }

        int scaled(int value, float scale)
        {
            return static_cast<int>(std::lround(value * scale));
        }

        // the element's size and what is inside it at the scale (the element's position is set by the caller)
        void applyScale(Captured& c, float scale)
        {
            const Original& element = c.mWidgets.front();
            element.mWidget->setSize(scaled(element.mCoord.width, scale), scaled(element.mCoord.height, scale));
            for (const Original& o : c.mWidgets)
            {
                if (o.mFontHeight > 0)
                    o.mWidget->castType<MyGUI::TextBox>()->setFontHeight(std::max(1, scaled(o.mFontHeight, scale)));
                if (&o == &element)
                    continue;
                // after the parent's size: setting it re-aligns the children, and this puts them where they belong
                o.mWidget->setCoord(scaled(o.mCoord.left, scale), scaled(o.mCoord.top, scale),
                    scaled(o.mCoord.width, scale), scaled(o.mCoord.height, scale));
            }
            c.mAppliedScale = scale;
        }
    }

    void hudLayoutFrame(MyGUI::Widget* main)
    {
        if (main == nullptr)
            return;
#if defined(__APPLE__)
        // (a widget of the HUD layer itself: its coordinates are the screen's)
        if (main->getParent() == nullptr)
        {
            float inset[4];
            safeAreaInsets(inset);
            const MyGUI::IntSize screen = MyGUI::RenderManager::getInstance().getViewSize();
            const int side = scaled(screen.width, std::max(inset[0], inset[2]));
            const int vertical = scaled(screen.height, std::max(inset[1], inset[3]));
            const MyGUI::IntCoord safe(side, vertical, screen.width - 2 * side, screen.height - 2 * vertical);
            if (safe.width > 0 && safe.height > 0 && main->getCoord() != safe)
                main->setCoord(safe);
        }
#endif
        // [GUI] overlay hud layout: off, nothing is read, placed or reported
        static const bool enabled = settingBool("GUI", "overlay hud layout", true);
        if (!enabled)
            return;
        std::lock_guard lock(sMutex);
        if (!sLoaded)
        {
            sLoaded = true;
            load();
        }
        const bool reporting = std::chrono::steady_clock::now() - sReportWanted < std::chrono::seconds(2);
        if (sPlacements.empty() && !sResetAsked && !reporting)
        {
            // nothing placed and no one looking: the HUD is the game's own
            sReport.clear();
            return;
        }
        if (main != sMain)
        {
            // a new HUD (the window manager makes it again on some changes): what was captured is gone
            sMain = main;
            sCaptured.clear();
        }

        // the layout prefixes every name: "<prefix>_Main" is the main widget
        const std::string& mainName = main->getName();
        const std::string prefix = mainName.size() >= 5 ? mainName.substr(0, mainName.size() - 5) : std::string();
#if defined(__APPLE__)
        // the screen (the main widget is the safe area, see the top)
        const MyGUI::IntSize view = MyGUI::RenderManager::getInstance().getViewSize();
        const MyGUI::IntPoint origin;
#else
        const MyGUI::IntSize view = main->getSize();
        const MyGUI::IntPoint origin = main->getAbsolutePosition();
#endif
        if (view.width <= 0 || view.height <= 0)
            return;

        if (sResetAsked)
        {
            sResetAsked = false;
            for (auto& [name, c] : sCaptured)
            {
                const Original& element = c.mWidgets.front();
                applyScale(c, 1);
                element.mWidget->setPosition(element.mCoord.left, element.mCoord.top);
            }
            sCaptured.clear();
            sPlacements.clear();
            save();
            Log(Debug::Info) << "HUD layout: reset";
        }

        std::ostringstream report;
        for (const Element& e : sElements)
        {
            auto placed = sPlacements.find(e.mName);
            if (placed == sPlacements.end() && !reporting)
                continue;
            MyGUI::Widget* widget = main->findWidget(prefix + e.mName);
            if (widget == nullptr)
                continue;
            float scale = 1;
            if (placed != sPlacements.end())
            {
                const Placement& p = placed->second;
                Captured& c = capture(e.mName, widget, e.mScales);
                scale = e.mScales ? p.mScale : 1;
                if (std::abs(c.mAppliedScale - scale) > 1e-4f)
                    applyScale(c, scale);
                // in the parent's coordinates (the parent is main for all of them)
                const MyGUI::IntPoint parentOrigin
                    = widget->getParent() ? widget->getParent()->getAbsolutePosition() : MyGUI::IntPoint();
                const MyGUI::IntPoint target(origin.left - parentOrigin.left + scaled(view.width, p.mLeft),
                    origin.top - parentOrigin.top + scaled(view.height, p.mTop));
                if (widget->getPosition() != target)
                    widget->setPosition(target);
            }
            if (!reporting)
                continue;
            const MyGUI::IntCoord at = widget->getAbsoluteCoord();
            report << e.mName << ' ' << (widget->getVisible() ? 1 : 0) << ' '
                   << float(at.left - origin.left) / view.width << ' ' << float(at.top - origin.top) / view.height << ' '
                   << float(at.width) / view.width << ' ' << float(at.height) / view.height << ' ' << scale << ' '
                   << (e.mScales ? 1 : 0) << ' ' << (placed != sPlacements.end() ? 1 : 0) << '\n';
        }
        sReport = report.str();

        if (sSaveAsked)
        {
            sSaveAsked = false;
            save();
        }
    }

    std::string hudLayoutReport()
    {
        std::lock_guard lock(sMutex);
        sReportWanted = std::chrono::steady_clock::now();
        return sReport;
    }

    void hudLayoutSet(const std::string& name, float left, float top, float scale, bool keep)
    {
        std::lock_guard lock(sMutex);
        Placement& p = sPlacements[name];
        p.mLeft = std::clamp(left, -0.5f, 1.0f);
        p.mTop = std::clamp(top, -0.5f, 1.0f);
        p.mScale = std::clamp(scale, 0.25f, 6.0f);
        if (keep)
            sSaveAsked = true;
    }

    void hudLayoutReset()
    {
        std::lock_guard lock(sMutex);
        sResetAsked = true;
    }
}

#ifdef __ANDROID__
// org.openmw.SdlEngineBridge: the launcher's touch overlay, on its UI thread
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_openmw_SdlEngineBridge_omwHudLayout(JNIEnv* env, jobject)
{
    const std::string report = Alpha3::hudLayoutReport();
    jbyteArray out = env->NewByteArray(static_cast<jsize>(report.size()));
    if (out != nullptr)
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(report.size()), reinterpret_cast<const jbyte*>(report.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_SdlEngineBridge_omwHudSet(
    JNIEnv* env, jobject, jstring name, jfloat left, jfloat top, jfloat scale, jboolean keep)
{
    const char* chars = env->GetStringUTFChars(name, nullptr);
    if (chars == nullptr)
        return;
    const std::string element(chars);
    env->ReleaseStringUTFChars(name, chars);
    Alpha3::hudLayoutSet(element, left, top, scale, keep == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_SdlEngineBridge_omwHudReset(JNIEnv*, jobject)
{
    Alpha3::hudLayoutReset();
}
#else
// The same for the iOS launcher (IosEngineBridge), which looks them up with dlsym in the running engine

// The report (see hudLayoutReport) copied into [out], up to [capacity] bytes and not terminated; returns its whole
// length, so a caller whose buffer was too small asks again with a larger one
extern "C" __attribute__((visibility("default"), used)) int alpha3_hud_layout(char* out, int capacity)
{
    const std::string report = Alpha3::hudLayoutReport();
    if (out != nullptr && capacity > 0)
        std::memcpy(out, report.data(), std::min(report.size(), static_cast<std::size_t>(capacity)));
    return static_cast<int>(report.size());
}

extern "C" __attribute__((visibility("default"), used)) void alpha3_hud_set(
    const char* name, float left, float top, float scale, int keep)
{
    if (name != nullptr)
        Alpha3::hudLayoutSet(name, left, top, scale, keep != 0);
}

extern "C" __attribute__((visibility("default"), used)) void alpha3_hud_reset()
{
    Alpha3::hudLayoutReset();
}
#endif

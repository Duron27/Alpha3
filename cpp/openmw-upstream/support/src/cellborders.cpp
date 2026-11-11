// Smoother exterior cell borders: the settings OpenMW's object paging, levelled creature lists, physics and local map
// ask for when a cell change would otherwise do all its work in one frame (see upstream.hpp). Profiled on an Adreno 615
// tablet, crossing a border cost one frame of 100 to 250 ms: rebuilding the merged statics' chunks on the main thread
// (30 to 130 ms), loading a spawned levelled creature's mesh and collision shape (15 to 40 ms), and releasing the old
// cells' collision objects and saving their fog of war (15 to 25 ms).
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>

namespace Alpha3
{
    namespace
    {
        // the main thread's (cells change there only)
        int sCellBorderChanges = 0;
    }

    bool inCellBorderChange()
    {
        return sCellBorderChanges > 0;
    }

    void cellBorderChange(int step)
    {
        sCellBorderChanges += step;
    }

    bool backgroundObjectPaging()
    {
        static const bool enabled = settingBool("Cells", "background object paging", true);
        return enabled;
    }

    bool preloadLevelledCreatures()
    {
        static const bool enabled = settingBool("Cells", "preload levelled creatures", true);
        return enabled;
    }

    bool spreadCellUnloading()
    {
        static const bool enabled = settingBool("Cells", "spread cell unloading", true);
        return enabled;
    }

    float cellUnloadBudget()
    {
        static const float budget = std::clamp(settingFloat("Cells", "cell unload budget", 1.f), 0.1f, 50.f);
        return budget;
    }
}

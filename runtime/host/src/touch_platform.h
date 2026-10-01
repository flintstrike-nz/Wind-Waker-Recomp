#ifndef BLUEWAKE_TOUCH_PLATFORM_H
#define BLUEWAKE_TOUCH_PLATFORM_H

// BLUEWAKE_TOUCH_PLATFORM is 1 where the player has a touch screen and the
// app's own on-screen controls and menu (iPhone, iPad, Android) rather than
// the desktop host's mouse, keyboard and ImGui options menu. Touches there also
// arrive as mouse events, which the desktop mouse camera must not take.
#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

#if (defined(__APPLE__) && TARGET_OS_IPHONE) || defined(__ANDROID__)
#define BLUEWAKE_TOUCH_PLATFORM 1
#else
#define BLUEWAKE_TOUCH_PLATFORM 0
#endif

#endif

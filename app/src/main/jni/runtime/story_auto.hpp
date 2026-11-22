#pragma once

namespace het::story_auto {
// Both calls run on the Unity thread. The controls and preference belong to one story session.
void Update(void* engine, bool restoring);
void EndSession();
}

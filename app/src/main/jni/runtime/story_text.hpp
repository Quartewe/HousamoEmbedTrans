#pragma once

// Unity-thread-only display state. Translation receipt and persistence continue
// independently; ending a story restores translated cells for the next session.
bool IsQuestOriginalText();
bool SetQuestOriginalText(bool original, bool refresh = true);

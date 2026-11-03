#!/usr/bin/env python3
"""Static bounds for the Galaxy-confirmed out-of-chat playback view scope.

These verify source wiring and isolation, not an Android playback/device test.
"""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer/xposed/features/general/OutOfChatPlaybackScope.java"
LOADER = ROOT / "app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java"


class GlobalPlayerScopeTest(unittest.TestCase):
    def test_only_inspector_verified_conversation_strip_is_hidden(self):
        source = SOURCE.read_text()
        self.assertIn('CONVERSATION = "com.whatsapp.Conversation"', source)
        self.assertIn('HOLDER = "out_of_chat_playback_holder"', source)
        self.assertIn('PARENT = "conversation_root_layout"', source)
        self.assertIn('CONTENT = "out_of_chat_layout"', source)
        self.assertIn('CONVERSATION.equals(activity.getClass().getName())', source)
        self.assertIn('((View) parent).getId() != parentId', source)
        self.assertIn('content.getParent() != holder', source)
        self.assertIn('holder.setVisibility(View.GONE)', source)
        self.assertNotIn('conversation_row_audio_player_view', source)
        self.assertNotIn('MediaPlayer', source)
        self.assertNotIn('AudioManager', source)

    def test_dynamic_rebinding_and_lifecycle_cleanup(self):
        source = SOURCE.read_text()
        loader = LOADER.read_text()
        self.assertIn('OutOfChatPlaybackScope.class,', loader)
        self.assertIn('WppCore.addListenerActivity', source)
        self.assertIn('observer.addOnGlobalLayoutListener(this)', source)
        self.assertIn('observer.removeOnGlobalLayoutListener(this)', source)
        # A re-shown strip is hidden before its frame draws, and that frame is cancelled.
        self.assertIn('observer.addOnPreDrawListener(this)', source)
        self.assertIn('observer.removeOnPreDrawListener(this)', source)
        self.assertIn('return !hide();', source)
        for state in ('RESUMED', 'PAUSED', 'ENDED', 'DESTROYED'):
            self.assertIn('case ' + state + ':', source)
        self.assertIn('watchers.remove(activity)', source)
        self.assertIn('WppCore.getActivityState(activity)', source)
        self.assertIn('if (holderId == 0 || parentId == 0 || contentId == 0) return;', source)


if __name__ == "__main__":
    unittest.main(verbosity=2)

"""Source wiring only. Java tests exercise receipts; no media/device or visual claim."""
from pathlib import Path
import os
import unittest

ROOT = Path(os.environ.get('POCKETWORLD_SOURCE_ROOT', Path(__file__).resolve().parents[2]))


class AudioTrimReceiptWiringTest(unittest.TestCase):
    def test_inspection_and_review_use_the_same_actual_track_alignment(self):
        source = (ROOT / 'app/src/main/java/dev/pocketengine/features/attachments/processing/NativeMediaTrim.java').read_text()
        review = source.split('private Review(', 1)[1].split('public JSONObject metadata()', 1)[0]
        inspect = source.split('public static JSONObject inspect(', 1)[1].split('public static Review review(', 1)[0]
        for path in (review, inspect):
            self.assertIn('.put("startAlignment",startAlignment(tracks))', path)
            self.assertNotIn('.put("startAlignment","previous_video_sync_sample")', path)

    def test_composer_uses_the_alignment_of_each_original_inspection_and_review(self):
        source = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/outcomes/NativeFileProcessComposer.kt').read_text()
        self.assertIn('NativeMediaTrim.startAlignmentLabel(info)', source)
        self.assertIn('NativeMediaTrim.startAlignmentLabel(actual)', source)
        self.assertNotIn('对齐实际同步帧', source)
        self.assertNotIn('"实际同步帧起点', source)

    def test_capability_distinguishes_audio_without_claiming_exact_time_or_transcoding(self):
        source = (ROOT / 'app/src/main/java/dev/pocketengine/features/capabilities/CapabilityDescriptor.java').read_text()
        descriptor = source.split('static CapabilityDescriptor trimMedia()', 1)[1].split('\n', 1)[0]
        for required in ('视频起点向前对齐真实同步帧', '纯音频对齐实际音频样本', '不转码', 'requested和actual时间', '不能宣称帧精确剪辑'):
            self.assertIn(required, descriptor)


if __name__ == '__main__':
    unittest.main()

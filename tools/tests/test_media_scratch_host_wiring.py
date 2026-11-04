"""Opt-in host selection and source wiring, not Android codec/device acceptance."""
import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('media_host_tests', ROOT / 'tools/host-tests.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
REGISTRY = json.loads((ROOT / 'tools/host-tests.json').read_text())


class MediaScratchHostWiringTest(unittest.TestCase):
    def test_recovery_is_one_explicit_group_without_an_ordinary_suite_expansion(self):
        entry = REGISTRY['media-scratch-recovery']
        self.assertEqual('dev.pocketengine.features.attachments.NativeMediaScratchRecoveryTest', entry['class'])
        self.assertEqual([], entry['helpers'])
        self.assertTrue(entry['robolectric'])
        self.assertFalse(entry.get('additionalClasses'))
        self.assertFalse(entry.get('native'))
        self.assertEqual(['media-scratch-recovery'], HOST.select(REGISTRY, [], [entry['class']]))
        with self.assertRaises(HOST.PrerequisiteError):
            HOST.select(REGISTRY, [], [entry['class'] + '$CodecWorker'])

    def test_codec_worker_runs_only_as_an_explicit_child_and_not_a_registered_main(self):
        source = (ROOT / 'app/src/hostTest/java/dev/pocketengine/features/attachments/NativeMediaScratchRecoveryTest.java').read_text()
        main, worker = source.split('@RunWith(RobolectricTestRunner.class)', 1)
        self.assertEqual(16, len(re.findall(r'@Test\s+public\s+void\s+\w+\s*\(', main)))
        self.assertEqual(1, len(re.findall(r'@Test\s+public\s+void\s+\w+\s*\(', worker)))
        self.assertIn('"org.junit.runner.JUnitCore",CodecWorker.class.getName()', main)
        self.assertIn('child.destroyForcibly()', main)
        self.assertIn('child.waitFor(10,TimeUnit.SECONDS)', main)
        self.assertIn('StandardCopyOption.ATOMIC_MOVE', worker)
        self.assertNotIn('@Ignore', source)

    def test_all_three_current_media_temp_creators_use_the_shared_lease(self):
        trim = (ROOT / 'app/src/main/java/dev/pocketengine/features/attachments/processing/NativeMediaTrim.java').read_text()
        validation = (ROOT / 'app/src/main/java/dev/pocketengine/media/NativeMediaValidation.java').read_text()
        self.assertIn('copy=NativeMediaScratch.trimSource(privateCache)', trim)
        self.assertIn('try(var outputCopy=NativeMediaScratch.trimOutput(privateCache))', trim)
        self.assertIn('try(var copy=NativeMediaScratch.providerValidation(privateCache))', validation)
        self.assertNotIn('File.createTempFile', trim + validation)
        self.assertIn('catch(Exception|Error failure){copy.close();throw failure;}', trim)
        self.assertIn('try{extractor.release();}finally{if(muxer!=null)', trim)
        self.assertEqual(2, validation.count('synchronized(codecRelease)'))
        self.assertIn('if(!releaseFinished[0])extractor.release();', validation)
        self.assertIn('finally{releaseFinished[0]=true;}', validation)

    def test_cleanup_registration_and_deletion_share_a_lock_and_retain_unknowns(self):
        helper = (ROOT / 'app/src/main/java/dev/pocketengine/media/NativeMediaScratch.java').read_text()
        self.assertEqual(2, helper.count('synchronized(DISK)'))
        self.assertIn('LIVE.add(path);return new Lease(file,path);', helper)
        self.assertIn('!LIVE.contains(path)', helper)
        self.assertIn('Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)', helper)
        self.assertIn('files.length>limit', helper)
        self.assertIn('Files.deleteIfExists(path);}finally{LIVE.remove(path);closed=true;}', helper)
        self.assertNotIn('lastModified', helper)
        self.assertNotIn('deleteOnExit', helper)


if __name__ == '__main__':
    unittest.main()

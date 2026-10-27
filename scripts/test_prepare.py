import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare', ROOT / 'scripts' / 'prepare.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

class FactoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)

    def archive(self, name, files):
        result = self.root / name
        with zipfile.ZipFile(result, 'w') as z:
            for path, contents in files.items():
                z.writestr(path, contents)
        return result

    def test_nested_zip_is_flattened(self):
        file = self.archive('site.zip', {'website/index.html':'<h1>ok</h1>', 'website/assets/main.css':'h1{}'})
        target = self.root / 'out'
        prepare.copy_site(file, target)
        self.assertTrue((target/'index.html').is_file())
        self.assertTrue((target/'assets'/'main.css').is_file())

    def test_folder_source(self):
        site = self.root / 'site'; site.mkdir()
        (site / 'index.html').write_text('yes')
        prepare.copy_site(site, self.root / 'out')
        self.assertEqual((self.root / 'out/index.html').read_text(), 'yes')

    def test_zip_traversal_rejected(self):
        file = self.archive('bad.zip', {'../escape.txt':'evil', 'index.html':'ok'})
        with self.assertRaises(ValueError):
            prepare.copy_site(file, self.root / 'out')
        self.assertFalse((self.root / 'escape.txt').exists())

    def test_windows_traversal_rejected(self):
        file = self.archive('bad.zip', {'..\\escape.txt':'evil', 'index.html':'ok'})
        with self.assertRaises(ValueError):
            prepare.copy_site(file, self.root / 'out')

    def test_absolute_zip_member_rejected(self):
        file = self.archive('bad.zip', {'/etc/pwned':'evil', 'index.html':'ok'})
        with self.assertRaises(ValueError):
            prepare.copy_site(file, self.root / 'out')

    def test_invalid_zip(self):
        file = self.archive('bad.zip', {'site/index.html':'x', 'other/index.html':'y'})
        with self.assertRaises(ValueError):
            prepare.copy_site(file, self.root / 'out')

    def test_ambiguous_root_index_prefers_root(self):
        file = self.archive('okay.zip', {'index.html':'root', 'sub/index.html':'nested'})
        prepare.copy_site(file, self.root / 'out')
        self.assertEqual((self.root / 'out/index.html').read_text(), 'root')

    def test_reject_symlink_in_site_directory(self):
        site = self.root / 'site'
        site.mkdir()
        (site / 'index.html').write_text('<html>ok</html>')
        (site / 'secret.txt').symlink_to(self.root / 'outside.txt')
        with self.assertRaises(ValueError):
            prepare.copy_site(site, self.root / 'out')

    def test_reject_too_many_members(self):
        file = self.archive('large.zip', {'index.html': '<html>ok</html>'})
        old = prepare.MAX_FILES
        prepare.MAX_FILES = 0
        self.addCleanup(setattr, prepare, 'MAX_FILES', old)
        with self.assertRaises(ValueError):
            prepare.copy_site(file, self.root / 'out')

    def test_app_name_xml_escaping(self):
        escaped = prepare.escape_android_string("R&D <Site> 'One'")
        self.assertIn('&amp;', escaped)
        self.assertIn('&lt;', escaped)
        self.assertIn("\\'", escaped)

    def test_missing_index_rejected(self):
        file = self.archive('bad.zip', {'hello.html':'hello'})
        with self.assertRaises(ValueError):
            prepare.copy_site(file, self.root / 'out')

    def test_escape_repo_path_rejected(self):
        (self.root/'okay').mkdir()
        self.assertEqual(prepare.safe_repo_path(self.root, 'okay'), self.root/'okay')
        with self.assertRaises(ValueError):
            prepare.safe_repo_path(self.root, '../../home')

    def test_package_validation(self):
        import argparse
        args = argparse.Namespace(mode='web', source='local', project_path='', repository='', ref='main', site_path='sites/demo', app_name='x', application_id='com.example.valid')
        result = prepare.prepare(args)
        self.assertTrue((result/'app/src/main/assets/site/index.html').is_file())
        self.assertIn('com.example.valid', (result/'factory.properties').read_text())

    def test_native_staging(self):
        import argparse
        args = argparse.Namespace(mode='native', source='local', project_path='projects/html-shelf', repository='', ref='main', site_path='', app_name='', application_id='')
        result = prepare.prepare(args)
        self.assertTrue((result/'app/src/main/AndroidManifest.xml').is_file())
        self.assertTrue((result/'settings.gradle').is_file())

    def test_invalid_package_rejected(self):
        import argparse
        args = argparse.Namespace(mode='web', source='local', project_path='', repository='', ref='main', site_path='sites/demo', app_name='x', application_id='../../foo')
        with self.assertRaises(ValueError):
            prepare.prepare(args)

if __name__ == '__main__':
    unittest.main(verbosity=2)

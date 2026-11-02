import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app_update_patch as patch


def method(signature, body):
    return '.method ' + signature + '\n' + body + '\n.end method\n'


class StockAppUpdateIsolationTest(unittest.TestCase):
    def test_shared_download_voice_and_installer_helpers_are_unchanged(self):
        unchanged = {
            'public static b(Ljava/lang/String;)V': '    invoke-static {}, LNetwork;->fetch()V',
            'public static e(Landroid/content/Context;)V': '    invoke-static {}, LVoice;->data()V',
            'public static f(Landroid/content/Context;)V': '    invoke-static {}, LModule;->install()V',
            'public static i(JLjava/lang/String;)V': '    invoke-static {}, LWeather;->json()V',
        }
        source = ''.join(method(sig, body) for sig, body in unchanged.items())
        source += ''.join(method(sig, '    .locals 1\n    invoke-static {}, LStock;->network()V\n    return-void')
                          for sig in [patch.CHECK, patch.DOWNLOAD, patch.APP_FETCH])
        changed = patch.patch_api(source)
        for signature in unchanged:
            self.assertEqual(patch.method_text(source, signature), patch.method_text(changed, signature))
        for signature in [patch.CHECK, patch.DOWNLOAD, patch.APP_FETCH]:
            body = patch.method_text(changed, signature)
            self.assertNotIn('network', body)
            self.assertNotIn('Executor', body)
            self.assertIn(patch.NOTICE, body)
        self.assertIn('if-eqz p2, :headswap_no_check', patch.method_text(changed, patch.CHECK))
        self.assertIn('throw v0', patch.method_text(changed, patch.APP_FETCH))

    def test_missing_or_duplicate_app_method_fails_closed(self):
        for source in ['', method(patch.CHECK, 'return-void') * 2]:
            with self.assertRaises(ValueError):
                patch.patch_api(source)

    def test_weather_timer_retains_its_work_and_reschedule(self):
        weather = ('    .locals 7\n    packed-switch v4, :pswitch_data_0\n'
                   '    invoke-static {v3, v4, v2, v2}, Lc/ka;->h(Landroid/content/Context;Ljava/lang/Runnable;ZZ)Z\n'
                   '    invoke-virtual {v2, p0, v0, v1}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z\n'
                   '    return-void\n\n')
        case = ('    :pswitch_0\n    invoke-static {}, Lc/O9;->a()V\n'
                '    invoke-virtual {v2, p0, v0, v1}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z\n')
        table = '    :pswitch_data_0\n    .packed-switch 0x0\n        :pswitch_0\n    .end packed-switch'
        source = method(patch.TIMER, weather + case + table)
        changed = patch.patch_timer(source)
        self.assertEqual(method(patch.TIMER, weather + '    :pswitch_0\n    return-void\n\n' + table), changed)

    def test_unrecognized_timer_cannot_disable_weather(self):
        source = method(patch.TIMER, '    :pswitch_0\n    return-void\n    :pswitch_data_0\n')
        with self.assertRaises(AssertionError):
            patch.patch_timer(source)

    def test_settings_preserve_original_sections_before_update_panel(self):
        prefix = '.method ' + patch.PAGE + '\n    .locals 38\n    new-instance v5, Lc/J2;\n    # getRealMetrics\n'
        original = prefix + patch.PAGE_CUT + '\n    const-string v6, "update_auto"\n    new-instance v9, Lc/y9;\n.end method\n'
        changed = patch.patch_page(original)
        kept_prefix = original[:original.index(patch.PAGE_CUT) + patch.PAGE_CUT.index('    new-instance')]
        self.assertTrue(changed.startswith(kept_prefix))
        self.assertNotIn('"update_auto"', changed)
        self.assertNotIn('Lc/y9;', changed)
        self.assertIn('HeadSwap 0.6.8 (301)', changed)
        self.assertIn(patch.NOTICE, changed)

    def test_changed_settings_boundary_is_not_silently_patched(self):
        for boundary in ['', patch.PAGE_CUT + patch.PAGE_CUT]:
            with self.assertRaises(AssertionError):
                patch.patch_page(method(patch.PAGE, '    .locals 38\n' + boundary))


if __name__ == '__main__':
    unittest.main()
